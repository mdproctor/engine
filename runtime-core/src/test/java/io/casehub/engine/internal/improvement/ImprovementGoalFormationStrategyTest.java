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
package io.casehub.engine.internal.improvement;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.stigmergy.ImprovementBudget;
import io.casehub.api.model.stigmergy.ImprovementConfig;
import io.casehub.api.model.stigmergy.ImprovementRequest;
import io.casehub.engine.common.internal.signal.SignalRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImprovementGoalFormationStrategyTest {

  private ImprovementGoalFormationStrategy strategy;
  private ImprovementBudgetEnforcer budgetEnforcer;
  private ImprovementSignalContext signalContext;
  private SignalRegistry signalRegistry;
  private UUID caseId;

  @BeforeEach
  void setUp() {
    budgetEnforcer = new ImprovementBudgetEnforcer();
    signalRegistry = new SignalRegistry();
    signalContext = new ImprovementSignalContext();
    strategy = new ImprovementGoalFormationStrategy(budgetEnforcer, signalRegistry, signalContext);
    caseId = UUID.randomUUID();
  }

  @Test
  void noProposalWhenNoImprovementConsensus() {
    var config = new ImprovementConfig(null, null, null, null, null);

    var proposal = strategy.proposeImprovements(caseId, config);

    assertThat(proposal).isNull();
  }

  @Test
  void proposesGoalWhenConsensusReached() {
    var config = new ImprovementConfig(null, 2, null, null, null);
    String signalName = "improvement:dependency:staleness:major-behind";

    signalRegistry.deposit(caseId, signalName, 1.0, Duration.ofHours(1), "agent-1", 100);
    signalRegistry.deposit(caseId, signalName, 1.0, Duration.ofHours(1), "agent-2", 100);

    signalContext.register(
        caseId,
        signalName,
        new ImprovementRequest(
            "operational",
            "dependency-update",
            "hibernate-core",
            "casehubio/engine",
            List.of("pom.xml"),
            20,
            Map.of()));

    var proposal = strategy.proposeImprovements(caseId, config);

    assertThat(proposal).isNotNull();
    assertThat(proposal.goals()).hasSize(1);
    assertThat(proposal.goals().get(0).name()).contains("self_improvement");
    assertThat(proposal.goals().get(0).attributes())
        .containsEntry("improvement.type", "operational")
        .containsEntry("improvement.category", "dependency-update");
  }

  @Test
  void budgetDenialPreventsProposal() {
    var budget = new ImprovementBudget(0, null, null, null, null, null, null);
    var config = new ImprovementConfig(null, 2, null, budget, null);
    String signalName = "improvement:quality:lint:violation";

    budgetEnforcer.recordStart(
        UUID.randomUUID(),
        new ImprovementRequest(
            "operational", "lint-fix", "checkstyle", "casehubio/engine", List.of(), 10, Map.of()));

    signalRegistry.deposit(caseId, signalName, 1.0, Duration.ofHours(1), "agent-1", 100);
    signalRegistry.deposit(caseId, signalName, 1.0, Duration.ofHours(1), "agent-2", 100);

    signalContext.register(
        caseId,
        signalName,
        new ImprovementRequest(
            "operational", "lint-fix", "checkstyle", "casehubio/engine", List.of(), 10, Map.of()));

    var proposal = strategy.proposeImprovements(caseId, config);

    assertThat(proposal).isNull();
  }

  @Test
  void strategyIdIsSelfImprovement() {
    assertThat(strategy.id()).isEqualTo("self-improvement");
  }

  @Test
  void consensusWithoutContextSkipsSignal() {
    var config = new ImprovementConfig(null, 2, null, null, null);
    String signalName = "improvement:dependency:staleness:major-behind";

    signalRegistry.deposit(caseId, signalName, 1.0, Duration.ofHours(1), "agent-1", 100);
    signalRegistry.deposit(caseId, signalName, 1.0, Duration.ofHours(1), "agent-2", 100);

    var proposal = strategy.proposeImprovements(caseId, config);

    assertThat(proposal).isNull();
  }

  @Test
  void disabledCategorySkipsSignal() {
    var config = new ImprovementConfig(null, 2, List.of("dependency-update"), null, null);
    String signalName = "improvement:quality:lint:violation";

    signalRegistry.deposit(caseId, signalName, 1.0, Duration.ofHours(1), "agent-1", 100);
    signalRegistry.deposit(caseId, signalName, 1.0, Duration.ofHours(1), "agent-2", 100);

    signalContext.register(
        caseId,
        signalName,
        new ImprovementRequest(
            "operational", "lint-fix", "checkstyle", "casehubio/engine", List.of(), 10, Map.of()));

    var proposal = strategy.proposeImprovements(caseId, config);

    assertThat(proposal).isNull();
  }
}
