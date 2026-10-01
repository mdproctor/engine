/*
 * Copyright 2026-Present The Case Hub Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.casehub.engine.runtime.improvement;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.improvement.ImprovementConfig;
import io.casehub.api.model.improvement.ImprovementRequest;
import io.casehub.api.model.stigmergy.CategoryDescriptor;
import io.casehub.api.model.stigmergy.HealthScoreSnapshot;
import io.casehub.api.model.stigmergy.RegressionVerdict;
import io.casehub.api.model.stigmergy.StageDescriptor;
import io.casehub.api.spi.improvement.ImprovementCategoryProvider;
import io.casehub.api.spi.improvement.ImprovementProposalSource;
import io.casehub.api.spi.improvement.RegressionEvaluator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MultiDomainEvolutionTest {

  private ImprovementCategoryRegistry categoryRegistry;
  private ImprovementProposalSourceRegistry proposalSourceRegistry;
  private RegressionEvaluatorRegistry regressionEvaluatorRegistry;
  private ConflictStrategyRegistry conflictStrategyRegistry;
  private DenyPatternProviderRegistry denyPatternProviderRegistry;
  private ImprovementGoalFormationStrategy coordinator;
  private ImprovementBudgetEnforcer budgetEnforcer;
  private final UUID caseId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    categoryRegistry = new ImprovementCategoryRegistry();
    proposalSourceRegistry = new ImprovementProposalSourceRegistry();
    regressionEvaluatorRegistry = new RegressionEvaluatorRegistry();
    conflictStrategyRegistry = new ConflictStrategyRegistry();
    denyPatternProviderRegistry = new DenyPatternProviderRegistry();
    budgetEnforcer = new ImprovementBudgetEnforcer(new InMemoryDenyPatternStore());

    categoryRegistry.registerProvider(new CodeEvolutionCategoryProvider());
    categoryRegistry.registerProvider(tradingCategoryProvider());

    coordinator =
        new ImprovementGoalFormationStrategy(
            budgetEnforcer,
            proposalSourceRegistry,
            categoryRegistry,
            new ImprovementCategoryTracker(),
            new RollbackHistory(),
            conflictStrategyRegistry,
            denyPatternProviderRegistry);
  }

  @Test
  void categoriesFromBothDomainsDiscovered() {
    assertThat(categoryRegistry.allCategories()).hasSize(6);
    assertThat(categoryRegistry.domainForCategory("dependency-update")).hasValue("code-evolution");
    assertThat(categoryRegistry.domainForCategory("parameter-tuning")).hasValue("trading");
  }

  @Test
  void proposalsFromBothSourcesCollected() {
    var codeRequest =
        new ImprovementRequest(
            "operational", "dependency-update", "lodash", 5, Map.of(), "code-evolution");
    var tradingRequest =
        new ImprovementRequest("operational", "parameter-tuning", "sharpe", 3, Map.of(), "trading");

    proposalSourceRegistry.register(
        staticSource("code-src", "code-evolution", List.of(codeRequest)));
    proposalSourceRegistry.register(
        staticSource("trading-src", "trading", List.of(tradingRequest)));

    var config = new ImprovementConfig(null, null, null, null, null);
    var proposal = coordinator.proposeImprovements(caseId, "t1", config);

    assertThat(proposal).isNotNull();
    assertThat(proposal.goals()).hasSize(2);
  }

  @Test
  void domainSpecificDenyAppliesPerDomain() {
    var codeRequest =
        new ImprovementRequest(
            "operational",
            "dependency-update",
            "target",
            5,
            CodeEvolutionMetadata.encode(null, List.of("src/ImprovementBudgetEnforcer.java")),
            "code-evolution");
    var tradingRequest =
        new ImprovementRequest("operational", "parameter-tuning", "sharpe", 3, Map.of(), "trading");

    proposalSourceRegistry.register(
        staticSource("code-src", "code-evolution", List.of(codeRequest)));
    proposalSourceRegistry.register(
        staticSource("trading-src", "trading", List.of(tradingRequest)));

    denyPatternProviderRegistry.register(
        new CodeEvolutionDenyPatternProvider(
            new CodeEvolutionDenyPatternProviderTest.InMemoryDenyPatternStore()));

    var config = new ImprovementConfig(null, null, null, null, null);
    var proposal = coordinator.proposeImprovements(caseId, "t1", config);

    assertThat(proposal).isNotNull();
    assertThat(proposal.goals()).hasSize(1);
    assertThat(proposal.goals().get(0).attributes())
        .containsEntry("improvement.category", "parameter-tuning");
  }

  @Test
  void domainSpecificConflictAppliesPerDomain() {
    var existingRequest =
        new ImprovementRequest(
            "operational",
            "dependency-update",
            "existing",
            50,
            CodeEvolutionMetadata.encode(null, List.of("src/Foo.java")),
            "code-evolution");
    budgetEnforcer.recordStart(UUID.randomUUID(), existingRequest);

    var conflictingCodeRequest =
        new ImprovementRequest(
            "operational",
            "dependency-update",
            "new",
            50,
            CodeEvolutionMetadata.encode(null, List.of("src/Foo.java")),
            "code-evolution");
    var tradingRequest =
        new ImprovementRequest("operational", "parameter-tuning", "sharpe", 3, Map.of(), "trading");

    proposalSourceRegistry.register(
        staticSource("code-src", "code-evolution", List.of(conflictingCodeRequest)));
    proposalSourceRegistry.register(
        staticSource("trading-src", "trading", List.of(tradingRequest)));

    conflictStrategyRegistry.register(new FilePathConflictStrategy());

    var config = new ImprovementConfig(null, null, null, null, null);
    var proposal = coordinator.proposeImprovements(caseId, "t1", config);

    assertThat(proposal).isNotNull();
    assertThat(proposal.goals()).hasSize(1);
    assertThat(proposal.goals().get(0).attributes())
        .containsEntry("improvement.category", "parameter-tuning");
  }

  @Test
  void regressionEvaluatorFilteredByDomain() {
    var codeEvaluator = new HealthScoreDeltaRegressionEvaluator(new ConfidenceScorer());
    var tradingEvaluator = tradingRegressionEvaluator();

    regressionEvaluatorRegistry.register(codeEvaluator);
    regressionEvaluatorRegistry.register(tradingEvaluator);

    var baseline = new HealthScoreSnapshot(0.8, Instant.now(), Map.of("a", 0.8));
    var current = new HealthScoreSnapshot(0.5, Instant.now(), Map.of("a", 0.5));

    var codeEvaluators =
        regressionEvaluatorRegistry.all().stream()
            .filter(e -> e.domainId().equals("code-evolution"))
            .toList();
    var tradingEvaluators =
        regressionEvaluatorRegistry.all().stream()
            .filter(e -> e.domainId().equals("trading"))
            .toList();

    assertThat(codeEvaluators).hasSize(1);
    assertThat(tradingEvaluators).hasSize(1);

    var codeVerdict = codeEvaluators.get(0).evaluate(caseId, baseline, current, "dep");
    assertThat(codeVerdict).isInstanceOf(RegressionVerdict.Detected.class);

    var tradingVerdict = tradingEvaluators.get(0).evaluate(caseId, baseline, current, "param");
    assertThat(tradingVerdict).isInstanceOf(RegressionVerdict.Detected.class);
    assertThat(((RegressionVerdict.Detected) tradingVerdict).reason()).contains("sharpe");
  }

  private ImprovementCategoryProvider tradingCategoryProvider() {
    return new ImprovementCategoryProvider() {
      @Override
      public String domainId() {
        return "trading";
      }

      @Override
      public List<CategoryDescriptor> categories() {
        return List.of(
            new CategoryDescriptor(
                "parameter-tuning", "Parameter Tuning", "Tune trading parameters", "trading"));
      }

      @Override
      public List<StageDescriptor> stages() {
        return List.of(
            new StageDescriptor("backtest", "Backtest", 0, true, "trading"),
            new StageDescriptor("deploy", "Deploy", 1, false, "trading"));
      }
    };
  }

  private RegressionEvaluator tradingRegressionEvaluator() {
    return new RegressionEvaluator() {
      @Override
      public String evaluatorId() {
        return "sharpe-delta";
      }

      @Override
      public String domainId() {
        return "trading";
      }

      @Override
      public RegressionVerdict evaluate(
          UUID caseId, HealthScoreSnapshot baseline, HealthScoreSnapshot current, String category) {
        if (current.score() < baseline.score()) {
          return new RegressionVerdict.Detected(0.9, "sharpe ratio dropped");
        }
        return new RegressionVerdict.NoRegression();
      }
    };
  }

  private ImprovementProposalSource staticSource(
      String sourceId, String domainId, List<ImprovementRequest> proposals) {
    return new ImprovementProposalSource() {
      @Override
      public String sourceId() {
        return sourceId;
      }

      @Override
      public String domainId() {
        return domainId;
      }

      @Override
      public List<ImprovementRequest> propose(
          UUID caseId, String tenancyId, ImprovementConfig config) {
        return proposals;
      }
    };
  }
}
