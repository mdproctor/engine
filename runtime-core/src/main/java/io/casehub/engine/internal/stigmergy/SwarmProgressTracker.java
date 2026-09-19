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

import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.stigmergy.StigmergyConfig;
import io.casehub.api.model.stigmergy.SwarmProgress;
import io.casehub.engine.common.internal.signal.SignalRegistry;
import io.casehub.engine.common.spi.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class SwarmProgressTracker implements Resettable {

  private final SignalRegistry signalRegistry;
  private final RoleTracker roleTracker;
  private final StigmergyCoordinator coordinator;

  private final ConcurrentHashMap<UUID, CaseProgressState> cases = new ConcurrentHashMap<>();

  @Inject
  public SwarmProgressTracker(
      SignalRegistry signalRegistry, RoleTracker roleTracker, StigmergyCoordinator coordinator) {
    this.signalRegistry = signalRegistry;
    this.roleTracker = roleTracker;
    this.coordinator = coordinator;
  }

  public List<SwarmEvent> evaluate(UUID caseId, StigmergyConfig config) {
    var state = cases.computeIfAbsent(caseId, k -> new CaseProgressState());
    var swarmConfig = config.swarm();

    double exploration = computeExplorationPace(caseId, state);
    double consensus = computeConsensusScore(caseId, config);
    double stability = computeStabilityScore(caseId, state);

    var now = Instant.now();
    var newProgress = new SwarmProgress(exploration, consensus, stability, now);

    double threshold = swarmConfig != null ? swarmConfig.effectiveProgressChangeThreshold() : 0.1;
    boolean changed =
        Math.abs(newProgress.explorationPace() - state.lastProgress.explorationPace()) > threshold
            || Math.abs(newProgress.consensusScore() - state.lastProgress.consensusScore())
                > threshold
            || Math.abs(newProgress.stabilityScore() - state.lastProgress.stabilityScore())
                > threshold;

    state.lastProgress = newProgress;
    state.evaluationCount++;

    if (changed) {
      return List.of(
          new SwarmEvent(
              CaseHubEventType.SWARM_PROGRESS,
              Map.of(
                  "explorationPace", exploration,
                  "consensusScore", consensus,
                  "stabilityScore", stability,
                  "cycle", state.evaluationCount)));
    }
    return List.of();
  }

  private double computeExplorationPace(UUID caseId, CaseProgressState state) {
    var agents = coordinator.activeAgents(caseId);
    Set<String> currentFeatures = new HashSet<>();
    for (var agent : agents) {
      currentFeatures.addAll(roleTracker.effectKeys(caseId, agent.agentId()));
      var allSignals = signalRegistry.getAllSignals(caseId);
      for (var entry : allSignals.entrySet()) {
        if (entry.getValue().sources().contains(agent.agentId())) {
          currentFeatures.add("signal:" + entry.getKey());
        }
      }
    }
    int currentCount = currentFeatures.size();
    state.explorationHistory.addLast(currentCount);
    int window = 20;
    while (state.explorationHistory.size() > window) {
      state.explorationHistory.removeFirst();
    }

    int max = state.explorationHistory.stream().mapToInt(Integer::intValue).max().orElse(1);
    return max > 0 ? (double) currentCount / max : 0.0;
  }

  private double computeConsensusScore(UUID caseId, StigmergyConfig config) {
    int consensusThreshold = 2;
    double ezThreshold = 0.01;
    if (config.coordination() != null && config.coordination().consensusThreshold() != null) {
      consensusThreshold = config.coordination().consensusThreshold();
    }
    if (config.defaults() != null && config.defaults().effectiveZeroThreshold() != null) {
      ezThreshold = config.defaults().effectiveZeroThreshold();
    }

    var consensus = signalRegistry.consensusSignals(caseId, consensusThreshold, ezThreshold);
    var allSignals = signalRegistry.perceive(caseId, ezThreshold);

    if (allSignals.isEmpty()) return 0.0;
    return (double) consensus.size() / allSignals.size();
  }

  private double computeStabilityScore(UUID caseId, CaseProgressState state) {
    var roles = roleTracker.getDetectedRoles(caseId);
    if (roles.isEmpty()) return 0.0;

    var agents = coordinator.activeAgents(caseId);
    if (agents.isEmpty()) return 0.0;

    Map<String, String> currentAssignment = new HashMap<>();
    for (var role : roles) {
      for (String agent : role.memberAgents()) {
        currentAssignment.put(agent, role.roleId());
      }
    }

    if (state.previousRoleAssignment.isEmpty()) {
      state.previousRoleAssignment = currentAssignment;
      return 0.0;
    }

    int stable = 0;
    for (var agent : agents) {
      String current = currentAssignment.get(agent.agentId());
      String previous = state.previousRoleAssignment.get(agent.agentId());
      if (current != null && current.equals(previous)) {
        stable++;
      }
    }
    state.previousRoleAssignment = currentAssignment;
    return (double) stable / agents.size();
  }

  public SwarmProgress getProgress(UUID caseId) {
    var state = cases.get(caseId);
    return state != null ? state.lastProgress : SwarmProgress.EMPTY;
  }

  public void evictByCase(UUID caseId) {
    cases.remove(caseId);
  }

  @Override
  public void reset() {
    cases.clear();
  }

  private static class CaseProgressState {
    SwarmProgress lastProgress = SwarmProgress.EMPTY;
    int evaluationCount;
    final Deque<Integer> explorationHistory = new ArrayDeque<>();
    Map<String, String> previousRoleAssignment = new HashMap<>();
  }
}
