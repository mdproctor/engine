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
import io.casehub.api.spi.improvement.CapabilityArea;
import io.casehub.api.spi.routing.GoalFormationResult;
import io.casehub.api.spi.routing.GoalFormationService;
import io.casehub.engine.common.internal.signal.SignalRegistry;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EvolutionTickerTest {

  private EvolutionTicker ticker;
  private ImprovementGoalFormationStrategy goalFormation;
  private ImprovementCircuitBreaker circuitBreaker;
  private HealthScoreTracker healthTracker;
  private RegressionDetector regressionDetector;
  private CapabilityAreaRegistry registry;
  private AtomicInteger proposalCount;
  private UUID caseId;

  @BeforeEach
  void setUp() {
    registry = new CapabilityAreaRegistry();
    healthTracker = new HealthScoreTracker(registry);
    circuitBreaker = new ImprovementCircuitBreaker();
    var scorer = new ConfidenceScorer();
    var categoryTracker = new ImprovementCategoryTracker();
    var rollbackHistory = new RollbackHistory();
    regressionDetector =
        new RegressionDetector(scorer, categoryTracker, rollbackHistory, healthTracker);

    var signalRegistry = new SignalRegistry();
    var signalContext = new ImprovementSignalContext();
    var budgetEnforcer = new ImprovementBudgetEnforcer();
    goalFormation =
        new ImprovementGoalFormationStrategy(budgetEnforcer, signalRegistry, signalContext);

    proposalCount = new AtomicInteger(0);
    GoalFormationService goalService =
        (agentId, tenancyId, proposal) -> {
          proposalCount.incrementAndGet();
          return new GoalFormationResult(java.util.List.of(), java.util.List.of(), 0);
        };

    ticker =
        new EvolutionTicker(
            goalFormation, circuitBreaker, healthTracker, regressionDetector, goalService);
    caseId = UUID.randomUUID();
  }

  @Test
  void evolutionDisabledDoesNothing() {
    var config = new ImprovementConfig(null, null, null, null, null);
    ticker.tick(caseId, "tenant-1", config);
    assertThat(proposalCount.get()).isEqualTo(0);
  }

  @Test
  void circuitBreakerOpenBlocksProposals() {
    registry.register(area("stability", 0.3));
    var healthPolicy = new HealthPolicy(0.6, null, null, null, null, null);
    healthTracker.refresh(caseId, healthPolicy);
    circuitBreaker.evaluate(caseId, healthTracker, healthPolicy);
    assertThat(circuitBreaker.state(caseId))
        .isEqualTo(ImprovementCircuitBreaker.CircuitBreakerState.OPEN);

    var config =
        new ImprovementConfig(
            null, null, null, null, null, true, null, null, healthPolicy, null, null);
    ticker.tick(caseId, "tenant-1", config);

    assertThat(proposalCount.get()).isEqualTo(0);
  }

  @Test
  void healthySystemWithNoConsensusProducesNoGoals() {
    registry.register(area("stability", 0.8));
    var config =
        new ImprovementConfig(null, null, null, null, null, true, null, null, null, null, null);
    ticker.tick(caseId, "tenant-1", config);
    assertThat(proposalCount.get()).isEqualTo(0);
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
