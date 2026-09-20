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

import io.casehub.api.model.stigmergy.ImprovementConfig;
import io.casehub.api.spi.routing.GoalFormationService;
import io.casehub.engine.common.spi.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

@ApplicationScoped
public class EvolutionTicker implements Resettable {

  private final ImprovementGoalFormationStrategy goalFormation;
  private final ImprovementCircuitBreaker circuitBreaker;
  private final HealthScoreTracker healthTracker;
  private final RegressionDetector regressionDetector;
  private final GoalFormationService goalFormationService;

  public EvolutionTicker(
      ImprovementGoalFormationStrategy goalFormation,
      ImprovementCircuitBreaker circuitBreaker,
      HealthScoreTracker healthTracker,
      RegressionDetector regressionDetector,
      GoalFormationService goalFormationService) {
    this.goalFormation = goalFormation;
    this.circuitBreaker = circuitBreaker;
    this.healthTracker = healthTracker;
    this.regressionDetector = regressionDetector;
    this.goalFormationService = goalFormationService;
  }

  public void tick(UUID caseId, String tenancyId, ImprovementConfig config) {
    if (!config.effectiveEvolutionEnabled()) {
      return;
    }

    healthTracker.refresh(caseId, config.effectiveHealthPolicy());

    regressionDetector.checkActiveMonitors(caseId, healthTracker, config.effectiveRollbackPolicy());

    circuitBreaker.evaluate(caseId, healthTracker, config.effectiveHealthPolicy());

    if (circuitBreaker.state(caseId) == ImprovementCircuitBreaker.CircuitBreakerState.OPEN) {
      return;
    }

    var proposal = goalFormation.proposeImprovements(caseId, config);
    if (proposal == null || proposal.goals().isEmpty()) {
      return;
    }

    goalFormationService.propose("improvement-system", tenancyId, proposal);
  }

  @Override
  public void reset() {}
}
