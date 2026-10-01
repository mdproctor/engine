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
package io.casehub.engine.internal.stigmergy;

import io.casehub.api.engine.MetricsSpace;
import io.casehub.api.model.stigmergy.BehavioralFingerprint;
import io.casehub.api.model.stigmergy.DetectedRole;
import io.casehub.api.model.stigmergy.DetectedTeam;
import io.casehub.api.model.stigmergy.SwarmProgress;
import io.casehub.engine.common.internal.convergence.ActivityTracker;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class DefaultMetricsSpace implements MetricsSpace {

  private static final Duration DEFAULT_RATE_WINDOW = Duration.ofSeconds(30);

  private final UUID caseId;
  private final String agentId;
  private final ActivityTracker activityTracker;
  private final RoleTracker roleTracker;
  private final TeamDetector teamDetector;
  private final SwarmProgressTracker progressTracker;

  public DefaultMetricsSpace(
      UUID caseId,
      String agentId,
      ActivityTracker activityTracker,
      RoleTracker roleTracker,
      TeamDetector teamDetector,
      SwarmProgressTracker progressTracker) {
    this.caseId = caseId;
    this.agentId = agentId;
    this.activityTracker = activityTracker;
    this.roleTracker = roleTracker;
    this.teamDetector = teamDetector;
    this.progressTracker = progressTracker;
  }

  @Override
  public Map<String, Double> activityRates() {
    var state = activityTracker.getState(caseId);
    var now = Instant.now();
    return Map.of(
        "dispatches", state.dispatchRate(DEFAULT_RATE_WINDOW, now),
        "signalDeposits", state.signalDepositRate(DEFAULT_RATE_WINDOW, now),
        "contextMutations", state.contextMutationRate(DEFAULT_RATE_WINDOW, now),
        "evaluationCycles", state.evaluationRate(DEFAULT_RATE_WINDOW, now));
  }

  @Override
  public Map<String, Long> budgetUsage() {
    var state = activityTracker.getState(caseId);
    return Map.of(
        "dispatches", state.totalDispatches(),
        "signalDeposits", state.totalSignalDeposits(),
        "contextMutations", state.totalContextMutations(),
        "evaluationCycles", state.totalEvaluationCycles());
  }

  @Override
  public BehavioralFingerprint myFingerprint() {
    return roleTracker.getFingerprint(caseId, agentId);
  }

  @Override
  public SwarmProgress swarmProgress() {
    return progressTracker.getProgress(caseId);
  }

  @Override
  public List<DetectedRole> detectedRoles() {
    return roleTracker.getDetectedRoles(caseId);
  }

  @Override
  public List<DetectedTeam> detectedTeams() {
    return teamDetector.getDetectedTeams(caseId);
  }
}
