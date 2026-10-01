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

import io.casehub.api.model.improvement.ImprovementConfig;
import io.casehub.api.model.stigmergy.CircuitBreakerState;
import io.casehub.api.model.stigmergy.TickTrace;
import io.casehub.api.model.stigmergy.TickTrace.GateResult;
import io.casehub.api.model.stigmergy.TickTrace.GateResult.GateVerdict;
import io.casehub.api.model.stigmergy.TickTrace.TickTrigger;
import io.casehub.api.spi.routing.GoalFormationService;
import io.casehub.engine.common.spi.Resettable;
import io.casehub.engine.common.spi.event.TickEvaluatedEvent;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class EvolutionTicker implements Resettable {

  private final ImprovementGoalFormationStrategy goalFormation;
  private final ImprovementCircuitBreaker circuitBreaker;
  private final HealthScoreTracker healthTracker;
  private final RegressionDetector regressionDetector;
  private final GoalFormationService goalFormationService;
  private final TickTraceBuffer traceBuffer;
  private final Event<TickEvaluatedEvent> tickEvaluatedEvent;

  @Inject
  public EvolutionTicker(
      ImprovementGoalFormationStrategy goalFormation,
      ImprovementCircuitBreaker circuitBreaker,
      HealthScoreTracker healthTracker,
      RegressionDetector regressionDetector,
      GoalFormationService goalFormationService,
      TickTraceBuffer traceBuffer,
      Event<TickEvaluatedEvent> tickEvaluatedEvent) {
    this.goalFormation = goalFormation;
    this.circuitBreaker = circuitBreaker;
    this.healthTracker = healthTracker;
    this.regressionDetector = regressionDetector;
    this.goalFormationService = goalFormationService;
    this.traceBuffer = traceBuffer;
    this.tickEvaluatedEvent = tickEvaluatedEvent;
  }

  public TickTrace tick(UUID caseId, String tenancyId, ImprovementConfig config) {
    return tick(caseId, tenancyId, config, TickTrigger.EVENT_DRIVEN);
  }

  public TickTrace tick(
      UUID caseId, String tenancyId, ImprovementConfig config, TickTrigger trigger) {
    var gates = new ArrayList<GateResult>();

    if (!config.effectiveEvolutionEnabled()) {
      gates.add(new GateResult("evolution_enabled", GateVerdict.BLOCKED, "evolution disabled"));
      return recordTrace(
          caseId, trigger, gates, new TickTrace.TickOutcome.NoProposal("evolution disabled"));
    }
    gates.add(new GateResult("evolution_enabled", GateVerdict.PASSED, null));

    healthTracker.refresh(caseId, tenancyId, config.effectiveHealthPolicy());
    gates.add(new GateResult("health_refresh", GateVerdict.PASSED, null));

    regressionDetector.checkActiveMonitors(caseId, healthTracker, config.effectiveRollbackPolicy());
    gates.add(new GateResult("regression_monitor", GateVerdict.PASSED, null));

    circuitBreaker.evaluate(caseId, tenancyId, healthTracker, config.effectiveHealthPolicy());
    gates.add(new GateResult("circuit_breaker_evaluate", GateVerdict.PASSED, null));

    if (circuitBreaker.state(caseId) == CircuitBreakerState.OPEN) {
      gates.add(
          new GateResult("circuit_breaker_check", GateVerdict.BLOCKED, "circuit breaker OPEN"));
      return recordTrace(
          caseId, trigger, gates, new TickTrace.TickOutcome.NoProposal("circuit breaker OPEN"));
    }
    gates.add(new GateResult("circuit_breaker_check", GateVerdict.PASSED, null));

    var proposal = goalFormation.proposeImprovements(caseId, tenancyId, config);
    if (proposal == null || proposal.goals().isEmpty()) {
      return recordTrace(
          caseId,
          trigger,
          gates,
          new TickTrace.TickOutcome.NoProposal("no consensus or all filtered"));
    }

    goalFormationService.propose("improvement-system", tenancyId, proposal);

    return recordTrace(
        caseId,
        trigger,
        gates,
        new TickTrace.TickOutcome.ProposalGenerated(proposal.goals().size(), null));
  }

  private TickTrace recordTrace(
      UUID caseId, TickTrigger trigger, List<GateResult> gates, TickTrace.TickOutcome outcome) {
    var trace = new TickTrace(caseId, Instant.now(), trigger, List.copyOf(gates), outcome);
    traceBuffer.record(trace);

    boolean notable =
        outcome instanceof TickTrace.TickOutcome.ProposalGenerated
            || gates.stream().anyMatch(g -> g.verdict() == GateVerdict.BLOCKED);
    if (notable) {
      tickEvaluatedEvent.fireAsync(new TickEvaluatedEvent(caseId, trace));
    }

    return trace;
  }

  @Override
  public void reset() {}
}
