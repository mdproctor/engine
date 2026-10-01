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

import io.casehub.api.model.improvement.HealthPolicy;
import io.casehub.api.model.improvement.ImprovementConfig;
import io.casehub.api.model.stigmergy.CapabilityAreaAssessment;
import io.casehub.api.model.stigmergy.CircuitBreakerState;
import io.casehub.api.model.stigmergy.TickTrace;
import io.casehub.api.model.stigmergy.TickTrace.GateResult.GateVerdict;
import io.casehub.api.spi.improvement.CapabilityArea;
import io.casehub.api.spi.routing.GoalFormationResult;
import io.casehub.api.spi.routing.GoalFormationService;
import io.casehub.engine.common.spi.event.CircuitBreakerStateChangedEvent;
import io.casehub.engine.common.spi.event.RegressionDetectedEvent;
import io.casehub.engine.common.spi.event.TickEvaluatedEvent;
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
  private TestEvent<TickEvaluatedEvent> tickEvaluatedEvents;

  @BeforeEach
  void setUp() {
    registry = new CapabilityAreaRegistry();
    healthTracker = new HealthScoreTracker(registry);
    var cbEvents = new TestEvent<CircuitBreakerStateChangedEvent>();
    circuitBreaker = new ImprovementCircuitBreaker(cbEvents);
    var categoryTracker = new ImprovementCategoryTracker();
    var rollbackHistory = new RollbackHistory();
    var regressionEvents = new TestEvent<RegressionDetectedEvent>();
    var evaluatorReg = new RegressionEvaluatorRegistry();
    var categoryRegistryLocal = new ImprovementCategoryRegistry();
    categoryRegistryLocal.registerProvider(new CodeEvolutionCategoryProvider());
    evaluatorReg.register(new HealthScoreDeltaRegressionEvaluator(new ConfidenceScorer()));
    regressionDetector =
        new RegressionDetector(
            evaluatorReg,
            categoryRegistryLocal,
            categoryTracker,
            rollbackHistory,
            healthTracker,
            regressionEvents);

    var budgetEnforcer = new ImprovementBudgetEnforcer(new InMemoryDenyPatternStore());
    var proposalSourceRegistry = new ImprovementProposalSourceRegistry();
    goalFormation =
        new ImprovementGoalFormationStrategy(
            budgetEnforcer,
            proposalSourceRegistry,
            categoryRegistryLocal,
            categoryTracker,
            rollbackHistory,
            new ConflictStrategyRegistry(),
            new DenyPatternProviderRegistry());

    proposalCount = new AtomicInteger(0);
    GoalFormationService goalService =
        (agentId, tenancyId, proposal) -> {
          proposalCount.incrementAndGet();
          return new GoalFormationResult(java.util.List.of(), java.util.List.of(), 0);
        };

    var traceBuffer = new TickTraceBuffer();
    tickEvaluatedEvents = new TestEvent<>();
    ticker =
        new EvolutionTicker(
            goalFormation,
            circuitBreaker,
            healthTracker,
            regressionDetector,
            goalService,
            traceBuffer,
            tickEvaluatedEvents);
    caseId = UUID.randomUUID();
  }

  @Test
  void evolutionDisabledDoesNothing() {
    var config = new ImprovementConfig(null, null, null, null, null);
    var trace = ticker.tick(caseId, "tenant-1", config);
    assertThat(proposalCount.get()).isEqualTo(0);
    assertThat(trace.gates()).hasSize(1);
    assertThat(trace.gates().get(0).gateName()).isEqualTo("evolution_enabled");
    assertThat(trace.gates().get(0).verdict()).isEqualTo(GateVerdict.BLOCKED);
    assertThat(trace.outcome()).isInstanceOf(TickTrace.TickOutcome.NoProposal.class);
  }

  @Test
  void circuitBreakerOpenBlocksProposals() {
    registry.register(area("stability", 0.3));
    var healthPolicy = new HealthPolicy(0.6, null, null, null, null, null);
    healthTracker.refresh(caseId, "test-tenant", healthPolicy);
    circuitBreaker.evaluate(caseId, "test-tenant", healthTracker, healthPolicy);
    assertThat(circuitBreaker.state(caseId)).isEqualTo(CircuitBreakerState.OPEN);

    var config =
        new ImprovementConfig(
            null, null, null, null, null, true, null, null, healthPolicy, null, null);
    var trace = ticker.tick(caseId, "tenant-1", config);

    assertThat(proposalCount.get()).isEqualTo(0);
    assertThat(
            trace.gates().stream()
                .filter(g -> g.gateName().equals("circuit_breaker_check"))
                .findFirst()
                .orElseThrow()
                .verdict())
        .isEqualTo(GateVerdict.BLOCKED);
  }

  @Test
  void healthySystemWithNoConsensusProducesNoGoals() {
    registry.register(area("stability", 0.8));
    var config =
        new ImprovementConfig(null, null, null, null, null, true, null, null, null, null, null);
    var trace = ticker.tick(caseId, "tenant-1", config);
    assertThat(proposalCount.get()).isEqualTo(0);
    assertThat(trace.gates().stream().allMatch(g -> g.verdict() == GateVerdict.PASSED)).isTrue();
    assertThat(trace.outcome()).isInstanceOf(TickTrace.TickOutcome.NoProposal.class);
  }

  @Test
  void firesEventWhenGateBlocks() {
    var config = new ImprovementConfig(null, null, null, null, null);
    ticker.tick(caseId, "tenant-1", config);

    assertThat(tickEvaluatedEvents.fired()).hasSize(1);
    var event = tickEvaluatedEvents.fired().get(0);
    assertThat(event.caseId()).isEqualTo(caseId);
    assertThat(event.trace().outcome()).isInstanceOf(TickTrace.TickOutcome.NoProposal.class);
  }

  @Test
  void noEventOnNoConsensus() {
    registry.register(area("stability", 0.8));
    var config =
        new ImprovementConfig(null, null, null, null, null, true, null, null, null, null, null);
    ticker.tick(caseId, "tenant-1", config);
    assertThat(tickEvaluatedEvents.fired()).isEmpty();
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

      public CapabilityAreaAssessment assess(UUID caseId, String tenancyId) {
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
