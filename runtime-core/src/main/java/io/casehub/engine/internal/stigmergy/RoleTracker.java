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
import io.casehub.api.model.stigmergy.BehavioralFingerprint;
import io.casehub.api.model.stigmergy.DetectedRole;
import io.casehub.api.model.stigmergy.RoleDomainWeights;
import io.casehub.api.model.stigmergy.SwarmConfig;
import io.casehub.api.spi.observation.RuleAction;
import io.casehub.engine.common.internal.observation.ObservationRegistry;
import io.casehub.engine.common.internal.observation.RuleRegistry;
import io.casehub.engine.common.internal.signal.SignalRegistry;
import io.casehub.engine.common.spi.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@ApplicationScoped
public class RoleTracker implements Resettable {

  private final ObservationRegistry observationRegistry;
  private final SignalRegistry signalRegistry;
  private final RuleRegistry ruleRegistry;
  private final StigmergyCoordinator coordinator;
  private volatile SwarmProvisioner swarmProvisioner;

  private final ConcurrentHashMap<UUID, CaseRoleState> cases = new ConcurrentHashMap<>();

  @Inject
  public RoleTracker(
      ObservationRegistry observationRegistry,
      SignalRegistry signalRegistry,
      RuleRegistry ruleRegistry,
      StigmergyCoordinator coordinator) {
    this.observationRegistry = observationRegistry;
    this.signalRegistry = signalRegistry;
    this.ruleRegistry = ruleRegistry;
    this.coordinator = coordinator;
  }

  public void setSwarmProvisioner(SwarmProvisioner swarmProvisioner) {
    this.swarmProvisioner = swarmProvisioner;
  }

  public void accumulate(UUID caseId) {
    var state = cases.computeIfAbsent(caseId, k -> new CaseRoleState());
    state.cycleCount++;
    state.agentsInDelay.clear();
    var agents = coordinator.activeAgents(caseId);
    for (var agent : agents) {
      if (swarmProvisioner != null
          && swarmProvisioner.isInIntegrationDelay(caseId, agent.agentId())) {
        accumulateAgent(caseId, agent.agentId(), state);
        swarmProvisioner.decrementIntegrationDelay(caseId, agent.agentId());
        state.agentsInDelay.add(agent.agentId());
      } else {
        accumulateAgent(caseId, agent.agentId(), state);
      }
    }
  }

  private void accumulateAgent(UUID caseId, String agentId, CaseRoleState state) {
    var agentState = state.agentAccumulators.computeIfAbsent(agentId, k -> new AgentAccumulator());

    var firings = ruleRegistry.getFirings(caseId, agentId);
    Map<String, Integer> cycleFirings = new HashMap<>();
    Map<String, Integer> cycleWriteKeys = new HashMap<>();
    for (var firing : firings) {
      cycleFirings.merge(firing.ruleId(), 1, Integer::sum);
      for (var action : firing.executedActions()) {
        if (action instanceof RuleAction.WriteContext wc) {
          cycleWriteKeys.merge(wc.key(), 1, Integer::sum);
          agentState.allEffectKeys.add(wc.key());
        }
      }
    }
    agentState.decisionWindow.addLast(cycleFirings);
    agentState.effectWindow.addLast(cycleWriteKeys);

    int window = 20;
    while (agentState.decisionWindow.size() > window) agentState.decisionWindow.removeFirst();
    while (agentState.effectWindow.size() > window) agentState.effectWindow.removeFirst();
  }

  public BehavioralFingerprint getFingerprint(UUID caseId, String agentId) {
    Map<String, Double> perception = extractPerception(caseId, agentId);
    Map<String, Double> communication = extractCommunication(caseId, agentId);
    var state = cases.get(caseId);
    Map<String, Double> decision = Map.of();
    Map<String, Double> effect = Map.of();
    if (state != null) {
      var acc = state.agentAccumulators.get(agentId);
      if (acc != null) {
        decision = normalizeWindow(acc.decisionWindow);
        effect = normalizeWindow(acc.effectWindow);
      }
    }
    return new BehavioralFingerprint(perception, communication, decision, effect);
  }

  private Map<String, Double> extractPerception(UUID caseId, String agentId) {
    var observers = observationRegistry.getObservers(caseId);
    var agentObservers = observers.getOrDefault(agentId, List.of());
    Map<String, Double> keys = new HashMap<>();
    for (var observer : agentObservers) {
      for (String key : observer.watchedKeys()) {
        keys.put(key, 1.0);
      }
    }
    return keys;
  }

  private Map<String, Double> extractCommunication(UUID caseId, String agentId) {
    var allSignals = signalRegistry.getAllSignals(caseId);
    Map<String, Integer> depositCounts = new HashMap<>();
    int totalDeposits = 0;
    for (var entry : allSignals.entrySet()) {
      if (entry.getValue().sources().contains(agentId)) {
        depositCounts.put(entry.getKey(), 1);
        totalDeposits++;
      }
    }
    Map<String, Double> comm = new HashMap<>();
    if (totalDeposits > 0) {
      for (var entry : depositCounts.entrySet()) {
        comm.put(entry.getKey(), (double) entry.getValue() / totalDeposits);
      }
    }
    return comm;
  }

  private Map<String, Double> normalizeWindow(Deque<Map<String, Integer>> window) {
    Map<String, Integer> totals = new HashMap<>();
    int sum = 0;
    for (var cycle : window) {
      for (var entry : cycle.entrySet()) {
        totals.merge(entry.getKey(), entry.getValue(), Integer::sum);
        sum += entry.getValue();
      }
    }
    if (sum == 0) return Map.of();
    Map<String, Double> normalized = new HashMap<>();
    int finalSum = sum;
    totals.forEach((k, v) -> normalized.put(k, (double) v / finalSum));
    return normalized;
  }

  public Set<String> effectKeys(UUID caseId, String agentId) {
    var state = cases.get(caseId);
    if (state == null) return Set.of();
    var acc = state.agentAccumulators.get(agentId);
    return acc != null ? Set.copyOf(acc.allEffectKeys) : Set.of();
  }

  public List<DetectedRole> getDetectedRoles(UUID caseId) {
    var state = cases.get(caseId);
    return state != null ? List.copyOf(state.currentRoles) : List.of();
  }

  public boolean shouldDetect(UUID caseId) {
    var state = cases.get(caseId);
    if (state == null) return false;
    if (state.dirty) return true;
    return state.cycleCount % 10 == 0;
  }

  public boolean shouldDetect(UUID caseId, SwarmConfig config) {
    var state = cases.get(caseId);
    if (state == null) return false;
    if (state.dirty) return true;
    return state.cycleCount % config.effectiveDetectionInterval() == 0;
  }

  public void markDirty(UUID caseId) {
    var state = cases.get(caseId);
    if (state != null) state.dirty = true;
  }

  public List<SwarmEvent> detect(UUID caseId, SwarmConfig config) {
    var state = cases.get(caseId);
    if (state == null) return List.of();
    state.dirty = false;

    var agents = coordinator.activeAgents(caseId);
    if (agents.size() < config.effectiveRoleMinClusterSize()) {
      if (!state.currentRoles.isEmpty()) {
        var events = dissolveAllRoles(state);
        state.currentRoles = List.of();
        state.previousRoles = List.of();
        return events;
      }
      return List.of();
    }

    Map<String, BehavioralFingerprint> fingerprints = new HashMap<>();
    for (var agent : agents) {
      if (state.agentsInDelay.contains(agent.agentId())) continue;
      fingerprints.put(agent.agentId(), getFingerprint(caseId, agent.agentId()));
    }

    var weights = config.effectiveDomainWeights();
    double threshold = config.effectiveRoleSimilarityThreshold();
    int minSize = config.effectiveRoleMinClusterSize();

    List<Set<String>> clusters = clusterAgents(fingerprints, weights, threshold, minSize);

    List<DetectedRole> newRoles = new ArrayList<>();
    for (var cluster : clusters) {
      var centroid = computeCentroid(cluster, fingerprints);
      var dominant = dominantFeatures(centroid, 3);
      String matchedRoleId = matchExistingRole(cluster, state);
      double drift = 0.0;
      int stability = 1;
      String roleId;
      if (matchedRoleId != null) {
        roleId = matchedRoleId;
        var origCentroid = state.originalCentroids.get(roleId);
        if (origCentroid != null) {
          drift = 1.0 - centroid.weightedSimilarity(origCentroid, weights);
        }
        var prev =
            state.currentRoles.stream().filter(r -> r.roleId().equals(matchedRoleId)).findFirst();
        stability = prev.map(r -> r.stabilityCount() + 1).orElse(1);
      } else {
        roleId = "role-" + state.roleCounter.incrementAndGet();
        state.originalCentroids.put(roleId, centroid);
      }
      newRoles.add(new DetectedRole(roleId, cluster, centroid, dominant, stability, drift));
    }

    List<SwarmEvent> events = computeEvolution(state, newRoles);
    state.previousRoles = state.currentRoles;
    state.currentRoles = newRoles;
    return events;
  }

  List<Set<String>> clusterAgents(
      Map<String, BehavioralFingerprint> fingerprints,
      RoleDomainWeights weights,
      double threshold,
      int minSize) {
    List<String> agentIds = new ArrayList<>(fingerprints.keySet());
    int n = agentIds.size();
    double[][] sim = new double[n][n];
    for (int i = 0; i < n; i++) {
      sim[i][i] = 1.0;
      for (int j = i + 1; j < n; j++) {
        double s =
            fingerprints
                .get(agentIds.get(i))
                .weightedSimilarity(fingerprints.get(agentIds.get(j)), weights);
        sim[i][j] = s;
        sim[j][i] = s;
      }
    }

    boolean[][] adj = new boolean[n][n];
    for (int i = 0; i < n; i++)
      for (int j = i + 1; j < n; j++)
        if (sim[i][j] >= threshold) {
          adj[i][j] = true;
          adj[j][i] = true;
        }

    boolean[] visited = new boolean[n];
    List<Set<String>> result = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      if (visited[i]) continue;
      Set<Integer> component = new LinkedHashSet<>();
      Queue<Integer> queue = new ArrayDeque<>();
      queue.add(i);
      visited[i] = true;
      while (!queue.isEmpty()) {
        int curr = queue.poll();
        component.add(curr);
        for (int j = 0; j < n; j++) {
          if (adj[curr][j] && !visited[j]) {
            visited[j] = true;
            queue.add(j);
          }
        }
      }
      if (component.size() >= minSize) {
        double avgSim = averageInternalSimilarity(component, sim);
        if (avgSim >= threshold) {
          result.add(component.stream().map(agentIds::get).collect(Collectors.toSet()));
        }
      }
    }
    return result;
  }

  private double averageInternalSimilarity(Set<Integer> component, double[][] sim) {
    if (component.size() <= 1) return 1.0;
    double sum = 0;
    int count = 0;
    var list = new ArrayList<>(component);
    for (int i = 0; i < list.size(); i++) {
      for (int j = i + 1; j < list.size(); j++) {
        sum += sim[list.get(i)][list.get(j)];
        count++;
      }
    }
    return count > 0 ? sum / count : 0.0;
  }

  private BehavioralFingerprint computeCentroid(
      Set<String> cluster, Map<String, BehavioralFingerprint> fingerprints) {
    Map<String, Double> percAcc = new HashMap<>();
    Map<String, Double> commAcc = new HashMap<>();
    Map<String, Double> decAcc = new HashMap<>();
    Map<String, Double> effAcc = new HashMap<>();
    for (String agentId : cluster) {
      var fp = fingerprints.get(agentId);
      fp.perception().forEach((k, v) -> percAcc.merge(k, v, Double::sum));
      fp.communication().forEach((k, v) -> commAcc.merge(k, v, Double::sum));
      fp.decision().forEach((k, v) -> decAcc.merge(k, v, Double::sum));
      fp.effect().forEach((k, v) -> effAcc.merge(k, v, Double::sum));
    }
    int size = cluster.size();
    return new BehavioralFingerprint(
        divideAll(percAcc, size),
        divideAll(commAcc, size),
        divideAll(decAcc, size),
        divideAll(effAcc, size));
  }

  private Map<String, Double> divideAll(Map<String, Double> map, int divisor) {
    Map<String, Double> result = new HashMap<>();
    map.forEach((k, v) -> result.put(k, v / divisor));
    return result;
  }

  private List<String> dominantFeatures(BehavioralFingerprint centroid, int topK) {
    Map<String, Double> all = new HashMap<>();
    centroid.perception().forEach((k, v) -> all.put("interest:" + k, v));
    centroid.communication().forEach((k, v) -> all.put("signal:" + k, v));
    centroid.decision().forEach((k, v) -> all.put("rule:" + k, v));
    centroid.effect().forEach((k, v) -> all.put("output:" + k, v));
    return all.entrySet().stream()
        .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
        .limit(topK)
        .map(Map.Entry::getKey)
        .toList();
  }

  private String matchExistingRole(Set<String> cluster, CaseRoleState state) {
    for (var prev : state.currentRoles) {
      long overlap = cluster.stream().filter(prev.memberAgents()::contains).count();
      if (overlap > prev.memberAgents().size() / 2.0 && overlap > cluster.size() / 2.0) {
        return prev.roleId();
      }
    }
    return null;
  }

  private List<SwarmEvent> computeEvolution(CaseRoleState state, List<DetectedRole> newRoles) {
    List<SwarmEvent> events = new ArrayList<>();
    Set<String> prevRoleIds =
        state.currentRoles.stream().map(DetectedRole::roleId).collect(Collectors.toSet());
    Set<String> newRoleIds =
        newRoles.stream().map(DetectedRole::roleId).collect(Collectors.toSet());

    for (var role : newRoles) {
      if (!prevRoleIds.contains(role.roleId())) {
        events.add(
            new SwarmEvent(
                CaseHubEventType.SWARM_ROLE_EMERGED,
                Map.of(
                    "roleId", role.roleId(),
                    "memberAgents", role.memberAgents(),
                    "dominantFeatures", role.dominantFeatures(),
                    "clusterSize", role.memberAgents().size())));
      }
    }
    for (var prev : state.currentRoles) {
      if (!newRoleIds.contains(prev.roleId())) {
        events.add(
            new SwarmEvent(
                CaseHubEventType.SWARM_ROLE_DISSOLVED,
                Map.of(
                    "roleId", prev.roleId(),
                    "previousMembers", prev.memberAgents(),
                    "lifetimeCycles", prev.stabilityCount())));
      }
    }

    Map<String, String> prevAssignment = new HashMap<>();
    for (var prev : state.currentRoles) {
      for (String agent : prev.memberAgents()) {
        prevAssignment.put(agent, prev.roleId());
      }
    }
    for (var role : newRoles) {
      for (String agent : role.memberAgents()) {
        String oldRole = prevAssignment.get(agent);
        if (oldRole != null && !oldRole.equals(role.roleId())) {
          events.add(
              new SwarmEvent(
                  CaseHubEventType.SWARM_ROLE_SHIFT,
                  Map.of(
                      "agentId", agent,
                      "fromRoleId", oldRole,
                      "toRoleId", role.roleId())));
        }
      }
    }
    return events;
  }

  private List<SwarmEvent> dissolveAllRoles(CaseRoleState state) {
    List<SwarmEvent> events = new ArrayList<>();
    for (var role : state.currentRoles) {
      events.add(
          new SwarmEvent(
              CaseHubEventType.SWARM_ROLE_DISSOLVED,
              Map.of(
                  "roleId", role.roleId(),
                  "previousMembers", role.memberAgents(),
                  "lifetimeCycles", role.stabilityCount())));
    }
    return events;
  }

  public void evictByCase(UUID caseId) {
    cases.remove(caseId);
  }

  @Override
  public void reset() {
    cases.clear();
  }

  private static class CaseRoleState {
    int cycleCount;
    volatile boolean dirty;
    final AtomicInteger roleCounter = new AtomicInteger();
    final ConcurrentHashMap<String, AgentAccumulator> agentAccumulators = new ConcurrentHashMap<>();
    List<DetectedRole> currentRoles = List.of();
    List<DetectedRole> previousRoles = List.of();
    final ConcurrentHashMap<String, BehavioralFingerprint> originalCentroids =
        new ConcurrentHashMap<>();
    final Set<String> agentsInDelay = ConcurrentHashMap.newKeySet();
  }

  private static class AgentAccumulator {
    final Deque<Map<String, Integer>> decisionWindow = new ArrayDeque<>();
    final Deque<Map<String, Integer>> effectWindow = new ArrayDeque<>();
    final Set<String> allEffectKeys = ConcurrentHashMap.newKeySet();
  }
}
