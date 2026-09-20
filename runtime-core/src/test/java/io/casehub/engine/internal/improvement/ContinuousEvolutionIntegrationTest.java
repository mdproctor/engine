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

import io.casehub.api.model.stigmergy.CapabilityAreaAssessment;
import io.casehub.api.model.stigmergy.HealthPolicy;
import io.casehub.api.model.stigmergy.ImprovementConfig;
import io.casehub.api.model.stigmergy.ImprovementOutcome;
import io.casehub.api.model.stigmergy.ImprovementRequest;
import io.casehub.api.model.stigmergy.RollbackPolicy;
import io.casehub.api.spi.improvement.CapabilityArea;
import io.casehub.api.spi.routing.GoalFormationResult;
import io.casehub.api.spi.routing.GoalFormationService;
import io.casehub.engine.common.internal.signal.SignalRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ContinuousEvolutionIntegrationTest {

  private SignalRegistry signalRegistry;
  private ImprovementSignalContext signalContext;
  private ImprovementBudgetEnforcer budgetEnforcer;
  private ImprovementCategoryTracker categoryTracker;
  private RollbackHistory rollbackHistory;
  private ConflictDetector conflictDetector;
  private ImprovementGoalFormationStrategy goalFormation;
  private CapabilityAreaRegistry areaRegistry;
  private HealthScoreTracker healthTracker;
  private ImprovementCircuitBreaker circuitBreaker;
  private ConfidenceScorer confidenceScorer;
  private RegressionDetector regressionDetector;
  private EvolutionTicker ticker;
  private AtomicInteger proposalCount;
  private UUID caseId;

  @BeforeEach
  void setUp() {
    signalRegistry = new SignalRegistry();
    signalContext = new ImprovementSignalContext();
    budgetEnforcer = new ImprovementBudgetEnforcer();
    categoryTracker = new ImprovementCategoryTracker();
    rollbackHistory = new RollbackHistory();
    conflictDetector = new ConflictDetector();
    goalFormation =
        new ImprovementGoalFormationStrategy(
            budgetEnforcer,
            signalRegistry,
            signalContext,
            categoryTracker,
            rollbackHistory,
            conflictDetector);

    areaRegistry = new CapabilityAreaRegistry();
    healthTracker = new HealthScoreTracker(areaRegistry);
    circuitBreaker = new ImprovementCircuitBreaker();
    confidenceScorer = new ConfidenceScorer();
    regressionDetector =
        new RegressionDetector(confidenceScorer, categoryTracker, rollbackHistory, healthTracker);

    proposalCount = new AtomicInteger(0);
    GoalFormationService goalService =
        (agentId, tenancyId, proposal) -> {
          proposalCount.incrementAndGet();
          return new GoalFormationResult(List.of(), List.of(), 0);
        };

    ticker =
        new EvolutionTicker(
            goalFormation, circuitBreaker, healthTracker, regressionDetector, goalService);
    caseId = UUID.randomUUID();
  }

  @Test
  void evolutionOptIn_defaultConfigDoesNothing() {
    var config = new ImprovementConfig(null, null, null, null, null);
    ticker.tick(caseId, "tenant-1", config);
    assertThat(proposalCount.get()).isEqualTo(0);
  }

  @Test
  void circuitBreakerBlocks_lowHealthPreventsProposals() {
    areaRegistry.register(area("stability", 0.3));
    var healthPolicy = new HealthPolicy(0.6, null, null, null, null, null);
    var config =
        new ImprovementConfig(
            null, null, null, null, null, true, null, null, healthPolicy, null, null);

    ticker.tick(caseId, "tenant-1", config);

    assertThat(circuitBreaker.state(caseId))
        .isEqualTo(ImprovementCircuitBreaker.CircuitBreakerState.OPEN);
    assertThat(proposalCount.get()).isEqualTo(0);
  }

  @Test
  void categorySuppression_threeFailuresSuppressesCategory() {
    areaRegistry.register(area("stability", 0.8));

    categoryTracker.recordOutcome(caseId, "lint-fix", ImprovementOutcome.OutcomeStatus.FAILED);
    categoryTracker.recordOutcome(caseId, "lint-fix", ImprovementOutcome.OutcomeStatus.FAILED);
    categoryTracker.recordOutcome(caseId, "lint-fix", ImprovementOutcome.OutcomeStatus.FAILED);

    String signalName = "improvement:quality:lint:violation";
    signalRegistry.deposit(caseId, signalName, 1.0, Duration.ofHours(1), "agent-1", 100);
    signalRegistry.deposit(caseId, signalName, 1.0, Duration.ofHours(1), "agent-2", 100);
    signalContext.register(
        caseId,
        signalName,
        new ImprovementRequest(
            "operational",
            "lint-fix",
            "checkstyle",
            "casehubio/engine",
            List.of("src/Foo.java"),
            10,
            Map.of()));

    var config =
        new ImprovementConfig(null, 2, null, null, null, true, null, null, null, null, null);
    ticker.tick(caseId, "tenant-1", config);

    assertThat(proposalCount.get()).isEqualTo(0);
  }

  @Test
  void antiOscillation_recentRollbackSuppressesReproposal() {
    areaRegistry.register(area("stability", 0.8));

    rollbackHistory.record(caseId, UUID.randomUUID(), "lint-fix", "checkstyle");

    String signalName = "improvement:quality:lint:violation";
    signalRegistry.deposit(caseId, signalName, 1.0, Duration.ofHours(1), "agent-1", 100);
    signalRegistry.deposit(caseId, signalName, 1.0, Duration.ofHours(1), "agent-2", 100);
    signalContext.register(
        caseId,
        signalName,
        new ImprovementRequest(
            "operational",
            "lint-fix",
            "checkstyle",
            "casehubio/engine",
            List.of("src/Foo.java"),
            10,
            Map.of()));

    var config =
        new ImprovementConfig(null, 2, null, null, null, true, null, null, null, null, null);
    ticker.tick(caseId, "tenant-1", config);

    assertThat(proposalCount.get()).isEqualTo(0);
  }

  @Test
  void conflictAvoidance_overlappingPathsSkipped() {
    areaRegistry.register(area("stability", 0.8));

    var existingRequest =
        new ImprovementRequest(
            "operational",
            "dependency-update",
            "hibernate",
            "casehubio/engine",
            List.of("module-a/src/Foo.java"),
            50,
            Map.of());
    budgetEnforcer.recordStart(UUID.randomUUID(), existingRequest);

    String signalName = "improvement:quality:lint:violation";
    signalRegistry.deposit(caseId, signalName, 1.0, Duration.ofHours(1), "agent-1", 100);
    signalRegistry.deposit(caseId, signalName, 1.0, Duration.ofHours(1), "agent-2", 100);
    signalContext.register(
        caseId,
        signalName,
        new ImprovementRequest(
            "operational",
            "lint-fix",
            "checkstyle",
            "casehubio/engine",
            List.of("module-a/src/Bar.java"),
            50,
            Map.of()));

    var config =
        new ImprovementConfig(null, 2, null, null, null, true, null, null, null, null, null);
    ticker.tick(caseId, "tenant-1", config);

    assertThat(proposalCount.get()).isEqualTo(0);
  }

  @Test
  void outcomeFeedbackClosesLoop() {
    categoryTracker.recordOutcome(caseId, "lint-fix", ImprovementOutcome.OutcomeStatus.MERGED);
    categoryTracker.recordOutcome(caseId, "lint-fix", ImprovementOutcome.OutcomeStatus.MERGED);

    assertThat(categoryTracker.isSuppressed(caseId, "lint-fix")).isFalse();

    categoryTracker.recordOutcome(caseId, "lint-fix", ImprovementOutcome.OutcomeStatus.FAILED);
    categoryTracker.recordOutcome(caseId, "lint-fix", ImprovementOutcome.OutcomeStatus.FAILED);
    categoryTracker.recordOutcome(caseId, "lint-fix", ImprovementOutcome.OutcomeStatus.FAILED);

    assertThat(categoryTracker.isSuppressed(caseId, "lint-fix")).isTrue();

    categoryTracker.recordOutcome(caseId, "lint-fix", ImprovementOutcome.OutcomeStatus.MERGED);

    assertThat(categoryTracker.isSuppressed(caseId, "lint-fix")).isFalse();
  }

  @Test
  void regressionDetection_healthDropPausesCategory() {
    areaRegistry.register(area("stability", 0.8));
    var healthPolicy = new HealthPolicy(null, null, null, null, null, null);
    healthTracker.refresh(caseId, healthPolicy);

    var outcome =
        new ImprovementOutcome(
            caseId,
            UUID.randomUUID(),
            "lint-fix",
            "checkstyle",
            ImprovementOutcome.OutcomeStatus.MERGED,
            null,
            null,
            null,
            null,
            Instant.now(),
            Map.of());
    regressionDetector.onOutcome(caseId, outcome);

    areaRegistry.deprecate("stability");
    areaRegistry.register(area("stability", 0.3));
    healthTracker.refresh(caseId, healthPolicy);

    var rollbackPolicy = new RollbackPolicy(null, 0.1, null, null, null, null);
    regressionDetector.checkActiveMonitors(caseId, healthTracker, rollbackPolicy);

    assertThat(categoryTracker.isSuppressed(caseId, "lint-fix")).isTrue();
  }

  private CapabilityArea area(String id, double health) {
    return new CapabilityArea() {
      public String id() {
        return id;
      }

      public String name() {
        return id;
      }

      public String description() {
        return "test";
      }

      public CapabilityAreaAssessment assess(UUID caseId) {
        return new CapabilityAreaAssessment(
            id,
            health,
            CapabilityAreaAssessment.LandscapePosition.AT_PARITY,
            0.5,
            0.3,
            1.67,
            Instant.now());
      }
    };
  }
}
