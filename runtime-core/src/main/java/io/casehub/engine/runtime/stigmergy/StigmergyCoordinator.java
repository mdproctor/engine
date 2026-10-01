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

import io.casehub.api.model.signal.SignalDecay;
import io.casehub.api.model.stigmergy.AgentLifecycleState;
import io.casehub.api.model.stigmergy.AgentState;
import io.casehub.api.model.stigmergy.CoordinationConfig;
import io.casehub.api.model.stigmergy.StigmergyConfig;
import io.casehub.engine.common.internal.convergence.ActivityTracker;
import io.casehub.engine.common.internal.observation.ObservationRegistry;
import io.casehub.engine.common.internal.signal.SignalRegistry;
import io.casehub.engine.common.spi.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class StigmergyCoordinator implements Resettable {

  private static final Duration DEFAULT_RATE_WINDOW = Duration.ofSeconds(60);

  private final SignalRegistry signalRegistry;
  private final ObservationRegistry observationRegistry;
  private final ActivityTracker activityTracker;

  private final ConcurrentHashMap<UUID, CaseCoordinationState> cases = new ConcurrentHashMap<>();

  @Inject
  public StigmergyCoordinator(
      SignalRegistry signalRegistry,
      ObservationRegistry observationRegistry,
      ActivityTracker activityTracker) {
    this.signalRegistry = signalRegistry;
    this.observationRegistry = observationRegistry;
    this.activityTracker = activityTracker;
  }

  public void initializeCase(UUID caseId, List<String> agentIds, StigmergyConfig config) {
    cases.put(caseId, new CaseCoordinationState(config, agentIds));
  }

  public boolean isStigmergyCase(UUID caseId) {
    return cases.containsKey(caseId);
  }

  public void agentJoined(UUID caseId, String agentId, String bindingName) {
    var state = cases.get(caseId);
    if (state == null) return;
    var now = Instant.now();
    state.agents.put(
        agentId,
        new AgentState(agentId, bindingName, AgentLifecycleState.JOINING, now, null, null, now));
  }

  public void agentActivated(UUID caseId, String agentId) {
    var state = cases.get(caseId);
    if (state == null) return;
    var current = state.agents.get(agentId);
    if (current == null || current.state() != AgentLifecycleState.JOINING) return;
    var now = Instant.now();
    state.agents.put(
        agentId,
        new AgentState(
            agentId,
            current.bindingName(),
            AgentLifecycleState.ACTIVE,
            current.joinedAt(),
            now,
            null,
            now));
  }

  public void agentDeparted(UUID caseId, String agentId) {
    var state = cases.get(caseId);
    if (state == null) return;
    var current = state.agents.get(agentId);
    if (current == null || current.state() == AgentLifecycleState.DEPARTED) return;
    var now = Instant.now();
    state.agents.put(
        agentId,
        new AgentState(
            agentId,
            current.bindingName(),
            AgentLifecycleState.DEPARTED,
            current.joinedAt(),
            current.activatedAt(),
            now,
            now));
  }

  public AgentState getAgent(UUID caseId, String agentId) {
    var state = cases.get(caseId);
    return state != null ? state.agents.get(agentId) : null;
  }

  public List<AgentState> activeAgents(UUID caseId) {
    var state = cases.get(caseId);
    if (state == null) return List.of();
    return state.agents.values().stream()
        .filter(a -> a.state() == AgentLifecycleState.ACTIVE)
        .toList();
  }

  public int activeCount(UUID caseId) {
    return activeAgents(caseId).size();
  }

  public boolean allActive(UUID caseId) {
    var state = cases.get(caseId);
    if (state == null) return false;
    if (state.agents.isEmpty()) return false;
    return state.agents.values().stream().noneMatch(a -> a.state() == AgentLifecycleState.JOINING);
  }

  public List<CoordinationEvent> detectPatterns(UUID caseId, StigmergyConfig config) {
    var state = cases.get(caseId);
    if (state == null) return List.of();
    var coord =
        config != null && config.coordination() != null
            ? config.coordination()
            : new CoordinationConfig(2, 10.0, 0.6);
    List<CoordinationEvent> events = new ArrayList<>();
    detectSignalConsensus(caseId, coord, state, events);
    detectCoordinationStorm(caseId, coord, state, events);
    detectInterestConvergence(caseId, coord, state, events);
    return events;
  }

  private void detectSignalConsensus(
      UUID caseId,
      CoordinationConfig coord,
      CaseCoordinationState state,
      List<CoordinationEvent> events) {
    int threshold = coord.consensusThreshold() != null ? coord.consensusThreshold() : 2;
    double ezThreshold = 0.01;
    var consensus = signalRegistry.consensusSignals(caseId, threshold, ezThreshold);
    for (var entry : consensus.entrySet()) {
      if (state.consensusFired.add(entry.getKey())) {
        var signal = entry.getValue();
        double effective =
            SignalDecay.effectiveStrength(
                signal.strength(), signal.lastReinforced(), signal.halfLife(), Instant.now());
        events.add(
            new CoordinationEvent(
                CoordinationEvent.Type.SIGNAL_CONSENSUS,
                Map.of(
                    "signalName", signal.name(),
                    "reinforcementCount", signal.reinforcementCount(),
                    "sources", signal.sources(),
                    "effectiveStrength", effective)));
      }
    }
    state.consensusFired.retainAll(consensus.keySet());
  }

  private void detectCoordinationStorm(
      UUID caseId,
      CoordinationConfig coord,
      CaseCoordinationState state,
      List<CoordinationEvent> events) {
    var actState = activityTracker.getState(caseId);
    if (actState == null) return;
    double multiplier = coord.stormRateMultiplier() != null ? coord.stormRateMultiplier() : 10.0;
    var now = Instant.now();
    boolean storming = false;
    List<String> stormingMetrics = new ArrayList<>();
    double dispatchRate = actState.dispatchRate(DEFAULT_RATE_WINDOW, now);
    double signalRate = actState.signalDepositRate(DEFAULT_RATE_WINDOW, now);
    double mutationRate = actState.contextMutationRate(DEFAULT_RATE_WINDOW, now);
    double evalRate = actState.evaluationRate(DEFAULT_RATE_WINDOW, now);
    if (dispatchRate > 0.1 * multiplier) {
      stormingMetrics.add("dispatches");
      storming = true;
    }
    if (signalRate > 0.1 * multiplier) {
      stormingMetrics.add("signalDeposits");
      storming = true;
    }
    if (mutationRate > 0.1 * multiplier) {
      stormingMetrics.add("contextMutations");
      storming = true;
    }
    if (evalRate > 0.5 * multiplier) {
      stormingMetrics.add("evaluationCycles");
      storming = true;
    }

    if (storming && !state.stormActive) {
      state.stormActive = true;
      events.add(
          new CoordinationEvent(
              CoordinationEvent.Type.COORDINATION_STORM,
              Map.of(
                  "stormingMetrics",
                  stormingMetrics,
                  "currentRates",
                  Map.of(
                      "dispatches",
                      dispatchRate,
                      "signalDeposits",
                      signalRate,
                      "contextMutations",
                      mutationRate,
                      "evaluationCycles",
                      evalRate))));
    } else if (!storming && state.stormActive) {
      state.stormActive = false;
    }
  }

  private void detectInterestConvergence(
      UUID caseId,
      CoordinationConfig coord,
      CaseCoordinationState state,
      List<CoordinationEvent> events) {
    double threshold =
        coord.interestHotspotThreshold() != null ? coord.interestHotspotThreshold() : 0.6;
    int totalActive = activeCount(caseId);
    if (totalActive == 0) return;
    var observers = observationRegistry.getObservers(caseId);
    Map<String, Set<String>> keyToAgents = new HashMap<>();
    for (var entry : observers.entrySet()) {
      String agentId = entry.getKey();
      for (var observer : entry.getValue()) {
        for (String key : observer.watchedKeys()) {
          keyToAgents.computeIfAbsent(key, k -> new HashSet<>()).add(agentId);
        }
      }
    }
    Set<String> currentHotspots = new HashSet<>();
    for (var entry : keyToAgents.entrySet()) {
      double score = (double) entry.getValue().size() / totalActive;
      if (score >= threshold) {
        currentHotspots.add(entry.getKey());
        if (state.interestHotspotFired.add(entry.getKey())) {
          events.add(
              new CoordinationEvent(
                  CoordinationEvent.Type.INTEREST_CONVERGENCE,
                  Map.of(
                      "hotspotKey", entry.getKey(),
                      "watchingAgentCount", entry.getValue().size(),
                      "hotspotScore", score)));
        }
      }
    }
    state.interestHotspotFired.retainAll(currentHotspots);
  }

  public void evictByCase(UUID caseId) {
    cases.remove(caseId);
  }

  @Override
  public void reset() {
    cases.clear();
  }

  private static class CaseCoordinationState {
    final StigmergyConfig config;
    final List<String> declaredAgentIds;
    final ConcurrentHashMap<String, AgentState> agents = new ConcurrentHashMap<>();
    final Set<String> consensusFired = ConcurrentHashMap.newKeySet();
    volatile boolean stormActive = false;
    final Set<String> interestHotspotFired = ConcurrentHashMap.newKeySet();

    CaseCoordinationState(StigmergyConfig config, List<String> agentIds) {
      this.config = config;
      this.declaredAgentIds = List.copyOf(agentIds);
    }
  }

  public record CoordinationEvent(Type type, Map<String, Object> metadata) {
    public enum Type {
      SIGNAL_CONSENSUS,
      COORDINATION_STORM,
      INTEREST_CONVERGENCE
    }
  }
}
