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

import io.casehub.api.model.improvement.ImprovementBudget;
import io.casehub.api.model.improvement.ImprovementConfig;
import io.casehub.api.model.improvement.ImprovementRequest;
import io.casehub.api.model.stigmergy.CategoryDescriptor;
import io.casehub.api.model.stigmergy.StageDescriptor;
import io.casehub.api.spi.improvement.ImprovementCategoryProvider;
import io.casehub.api.spi.improvement.ImprovementProposalSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImprovementGoalFormationStrategyTest {

  private ImprovementGoalFormationStrategy strategy;
  private ImprovementBudgetEnforcer budgetEnforcer;
  private ImprovementProposalSourceRegistry proposalSourceRegistry;
  private ImprovementCategoryRegistry categoryRegistry;
  private ConflictStrategyRegistry conflictStrategyRegistry;
  private DenyPatternProviderRegistry denyPatternProviderRegistry;
  private UUID caseId;

  @BeforeEach
  void setUp() {
    budgetEnforcer = new ImprovementBudgetEnforcer(new InMemoryDenyPatternStore());
    proposalSourceRegistry = new ImprovementProposalSourceRegistry();
    categoryRegistry = new ImprovementCategoryRegistry();
    conflictStrategyRegistry = new ConflictStrategyRegistry();
    denyPatternProviderRegistry = new DenyPatternProviderRegistry();

    categoryRegistry.registerProvider(new CodeEvolutionCategoryProvider());

    strategy =
        new ImprovementGoalFormationStrategy(
            budgetEnforcer,
            proposalSourceRegistry,
            categoryRegistry,
            new ImprovementCategoryTracker(),
            new RollbackHistory(),
            conflictStrategyRegistry,
            denyPatternProviderRegistry);
    caseId = UUID.randomUUID();
  }

  @Test
  void noProposalWhenNoSources() {
    var config = new ImprovementConfig(null, null, null, null, null);
    var proposal = strategy.proposeImprovements(caseId, "test-tenant", config);
    assertThat(proposal).isNull();
  }

  @Test
  void collectsFromSourceAndProposesGoal() {
    var request =
        new ImprovementRequest(
            "operational", "dependency-update", "hibernate-core", 20, Map.of(), "code-evolution");
    proposalSourceRegistry.register(staticSource("s1", "code-evolution", List.of(request)));

    var config = new ImprovementConfig(null, null, null, null, null);
    var proposal = strategy.proposeImprovements(caseId, "test-tenant", config);

    assertThat(proposal).isNotNull();
    assertThat(proposal.goals()).hasSize(1);
    assertThat(proposal.goals().get(0).name()).contains("self_improvement");
    assertThat(proposal.goals().get(0).attributes())
        .containsEntry("improvement.category", "dependency-update");
  }

  @Test
  void collectsFromMultipleSourcesAndFilters() {
    var codeRequest =
        new ImprovementRequest(
            "operational", "dependency-update", "lodash", 5, Map.of(), "code-evolution");
    var tradingRequest =
        new ImprovementRequest(
            "operational", "parameter-tuning", "sharpe-ratio", 3, Map.of(), "trading");

    proposalSourceRegistry.register(staticSource("s1", "code-evolution", List.of(codeRequest)));
    proposalSourceRegistry.register(staticSource("s2", "trading", List.of(tradingRequest)));

    categoryRegistry.registerProvider(
        new ImprovementCategoryProvider() {
          @Override
          public String domainId() {
            return "trading";
          }

          @Override
          public List<CategoryDescriptor> categories() {
            return List.of(
                new CategoryDescriptor(
                    "parameter-tuning", "Parameter Tuning", "Tune params", "trading"));
          }

          @Override
          public List<StageDescriptor> stages() {
            return List.of();
          }
        });

    var config = new ImprovementConfig(null, null, null, null, null);
    var proposal = strategy.proposeImprovements(caseId, "test-tenant", config);

    assertThat(proposal).isNotNull();
    assertThat(proposal.goals()).hasSize(2);
  }

  @Test
  void disabledCategoryFiltersProposal() {
    var request =
        new ImprovementRequest(
            "operational", "lint-fix", "checkstyle", 10, Map.of(), "code-evolution");
    proposalSourceRegistry.register(staticSource("s1", "code-evolution", List.of(request)));

    var config = new ImprovementConfig(null, null, List.of("dependency-update"), null, null);
    var proposal = strategy.proposeImprovements(caseId, "test-tenant", config);

    assertThat(proposal).isNull();
  }

  @Test
  void budgetDenialPreventsProposal() {
    var budget = new ImprovementBudget(0, null, null, null, null, null, null);
    var config = new ImprovementConfig(null, null, null, budget, null);

    budgetEnforcer.recordStart(
        UUID.randomUUID(),
        new ImprovementRequest("operational", "lint-fix", "checkstyle", 10, Map.of(), null));

    var request =
        new ImprovementRequest(
            "operational", "lint-fix", "checkstyle", 10, Map.of(), "code-evolution");
    proposalSourceRegistry.register(staticSource("s1", "code-evolution", List.of(request)));

    var proposal = strategy.proposeImprovements(caseId, "test-tenant", config);

    assertThat(proposal).isNull();
  }

  @Test
  void denyPatternProviderFiltersByDomain() {
    var request =
        new ImprovementRequest(
            "operational",
            "dependency-update",
            "target",
            5,
            CodeEvolutionMetadata.encode(null, List.of("src/ImprovementBudgetEnforcer.java")),
            "code-evolution");
    proposalSourceRegistry.register(staticSource("s1", "code-evolution", List.of(request)));

    var denyProvider =
        new CodeEvolutionDenyPatternProvider(
            new CodeEvolutionDenyPatternProviderTest.InMemoryDenyPatternStore());
    denyPatternProviderRegistry.register(denyProvider);

    var config = new ImprovementConfig(null, null, null, null, null);
    var proposal = strategy.proposeImprovements(caseId, "test-tenant", config);

    assertThat(proposal).isNull();
  }

  @Test
  void strategyIdIsSelfImprovement() {
    assertThat(strategy.id()).isEqualTo("self-improvement");
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
