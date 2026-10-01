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
import io.casehub.api.model.stigmergy.AgentState;
import io.casehub.api.model.stigmergy.DetectedTeam;
import io.casehub.api.model.stigmergy.SwarmConfig;
import io.casehub.api.spi.observation.NeighborRelation;
import io.casehub.engine.common.internal.observation.ObservationRegistry;
import io.casehub.engine.common.internal.signal.SignalRegistry;
import io.casehub.engine.common.spi.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@ApplicationScoped
public class TeamDetector implements Resettable {

  private final ObservationRegistry observationRegistry;
  private final SignalRegistry signalRegistry;
  private final RoleTracker roleTracker;
  private final StigmergyCoordinator coordinator;

  private final ConcurrentHashMap<UUID, CaseTeamState> cases = new ConcurrentHashMap<>();

  @Inject
  public TeamDetector(
      ObservationRegistry observationRegistry,
      SignalRegistry signalRegistry,
      RoleTracker roleTracker,
      StigmergyCoordinator coordinator) {
    this.observationRegistry = observationRegistry;
    this.signalRegistry = signalRegistry;
    this.roleTracker = roleTracker;
    this.coordinator = coordinator;
  }

  public List<SwarmEvent> detect(UUID caseId, SwarmConfig config) {
    var state = cases.computeIfAbsent(caseId, k -> new CaseTeamState());
    var agents = coordinator.activeAgents(caseId);
    if (agents.size() < config.effectiveTeamMinSize()) {
      if (!state.currentTeams.isEmpty()) {
        var events = dissolveAll(state);
        state.currentTeams = List.of();
        return events;
      }
      return List.of();
    }

    List<String> agentIds = agents.stream().map(AgentState::agentId).toList();
    int n = agentIds.size();

    Map<String, Set<String>> interestKeys = new HashMap<>();
    Map<String, Set<String>> signalNames = new HashMap<>();
    Map<String, Set<String>> effectKeyMap = new HashMap<>();

    for (String agentId : agentIds) {
      interestKeys.put(agentId, extractInterestKeys(caseId, agentId));
      signalNames.put(agentId, extractSignalNames(caseId, agentId));
      effectKeyMap.put(agentId, roleTracker.effectKeys(caseId, agentId));
    }

    double[][] affinity = new double[n][n];
    for (int i = 0; i < n; i++) {
      for (int j = i + 1; j < n; j++) {
        String ai = agentIds.get(i);
        String aj = agentIds.get(j);
        double sharedInterest = jaccard(interestKeys.get(ai), interestKeys.get(aj));
        double sharedSignal = jaccard(signalNames.get(ai), signalNames.get(aj));
        double comp =
            Math.max(
                jaccard(effectKeyMap.get(ai), interestKeys.get(aj)),
                jaccard(effectKeyMap.get(aj), interestKeys.get(ai)));
        affinity[i][j] = (sharedInterest + sharedSignal + comp) / 3.0;
        affinity[j][i] = affinity[i][j];
      }
    }

    double threshold = config.effectiveTeamAffinityThreshold();
    int minSize = config.effectiveTeamMinSize();
    List<Set<String>> clusters = cluster(agentIds, affinity, threshold, minSize);

    List<DetectedTeam> newTeams = new ArrayList<>();
    for (var cl : clusters) {
      String matchedTeamId = matchExisting(cl, state);
      int stability = 1;
      String teamId;
      if (matchedTeamId != null) {
        teamId = matchedTeamId;
        var prev =
            state.currentTeams.stream().filter(t -> t.teamId().equals(matchedTeamId)).findFirst();
        stability = prev.map(t -> t.stabilityCount() + 1).orElse(1);
      } else {
        teamId = "team-" + state.teamCounter.incrementAndGet();
      }
      Set<NeighborRelation> dominant =
          dominantRelations(cl, interestKeys, signalNames, effectKeyMap);
      double avg = averageAffinity(cl, agentIds, affinity);
      newTeams.add(new DetectedTeam(teamId, cl, dominant, avg, stability));
    }

    var events = computeEvolution(state, newTeams);
    state.previousTeams = state.currentTeams;
    state.currentTeams = newTeams;
    return events;
  }

  private Set<String> extractInterestKeys(UUID caseId, String agentId) {
    var observers = observationRegistry.getObservers(caseId);
    var agentObservers = observers.getOrDefault(agentId, List.of());
    Set<String> keys = new HashSet<>();
    for (var observer : agentObservers) {
      keys.addAll(observer.watchedKeys());
    }
    return keys;
  }

  private Set<String> extractSignalNames(UUID caseId, String agentId) {
    var allSignals = signalRegistry.getAllSignals(caseId);
    Set<String> names = new HashSet<>();
    for (var entry : allSignals.entrySet()) {
      if (entry.getValue().sources().contains(agentId)) {
        names.add(entry.getKey());
      }
    }
    return names;
  }

  private double jaccard(Set<String> a, Set<String> b) {
    if (a.isEmpty() && b.isEmpty()) return 0.0;
    Set<String> intersection = new HashSet<>(a);
    intersection.retainAll(b);
    Set<String> union = new HashSet<>(a);
    union.addAll(b);
    return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
  }

  private List<Set<String>> cluster(
      List<String> agentIds, double[][] affinity, double threshold, int minSize) {
    int n = agentIds.size();
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
          if (!visited[j] && affinity[curr][j] >= threshold) {
            visited[j] = true;
            queue.add(j);
          }
        }
      }
      if (component.size() >= minSize) {
        double avg = avgInternal(component, affinity);
        if (avg >= threshold) {
          result.add(component.stream().map(agentIds::get).collect(Collectors.toSet()));
        }
      }
    }
    return result;
  }

  private double avgInternal(Set<Integer> component, double[][] affinity) {
    if (component.size() <= 1) return 1.0;
    var list = new ArrayList<>(component);
    double sum = 0;
    int count = 0;
    for (int i = 0; i < list.size(); i++) {
      for (int j = i + 1; j < list.size(); j++) {
        sum += affinity[list.get(i)][list.get(j)];
        count++;
      }
    }
    return count > 0 ? sum / count : 0.0;
  }

  private Set<NeighborRelation> dominantRelations(
      Set<String> cluster,
      Map<String, Set<String>> interestKeys,
      Map<String, Set<String>> signalNames,
      Map<String, Set<String>> effectKeys) {
    Set<NeighborRelation> dominant = EnumSet.noneOf(NeighborRelation.class);
    for (String a : cluster) {
      for (String b : cluster) {
        if (a.equals(b)) continue;
        if (!Collections.disjoint(interestKeys.get(a), interestKeys.get(b)))
          dominant.add(NeighborRelation.SHARED_INTEREST);
        if (!Collections.disjoint(signalNames.get(a), signalNames.get(b)))
          dominant.add(NeighborRelation.SHARED_SIGNAL);
        if (!Collections.disjoint(effectKeys.get(a), interestKeys.get(b))
            || !Collections.disjoint(effectKeys.get(b), interestKeys.get(a)))
          dominant.add(NeighborRelation.COMPLEMENTARY);
      }
    }
    return dominant;
  }

  private double averageAffinity(Set<String> cluster, List<String> agentIds, double[][] affinity) {
    var indices = cluster.stream().map(agentIds::indexOf).toList();
    double sum = 0;
    int count = 0;
    for (int i = 0; i < indices.size(); i++) {
      for (int j = i + 1; j < indices.size(); j++) {
        sum += affinity[indices.get(i)][indices.get(j)];
        count++;
      }
    }
    return count > 0 ? sum / count : 0.0;
  }

  private String matchExisting(Set<String> cluster, CaseTeamState state) {
    for (var prev : state.currentTeams) {
      long overlap = cluster.stream().filter(prev.memberAgents()::contains).count();
      if (overlap > prev.memberAgents().size() / 2.0 && overlap > cluster.size() / 2.0) {
        return prev.teamId();
      }
    }
    return null;
  }

  private List<SwarmEvent> computeEvolution(CaseTeamState state, List<DetectedTeam> newTeams) {
    List<SwarmEvent> events = new ArrayList<>();
    Set<String> prevIds =
        state.currentTeams.stream().map(DetectedTeam::teamId).collect(Collectors.toSet());
    Set<String> newIds = newTeams.stream().map(DetectedTeam::teamId).collect(Collectors.toSet());

    for (var team : newTeams) {
      if (!prevIds.contains(team.teamId())) {
        events.add(
            new SwarmEvent(
                CaseHubEventType.SWARM_TEAM_FORMED,
                Map.of(
                    "teamId", team.teamId(),
                    "memberAgents", team.memberAgents(),
                    "dominantRelations", team.dominantRelations(),
                    "avgAffinity", team.avgAffinity())));
      }
    }
    for (var prev : state.currentTeams) {
      if (!newIds.contains(prev.teamId())) {
        events.add(
            new SwarmEvent(
                CaseHubEventType.SWARM_TEAM_DISSOLVED,
                Map.of(
                    "teamId", prev.teamId(),
                    "previousMembers", prev.memberAgents(),
                    "lifetimeCycles", prev.stabilityCount())));
      }
    }

    Map<String, String> prevAssignment = new HashMap<>();
    for (var prev : state.currentTeams) {
      for (String agent : prev.memberAgents()) {
        prevAssignment.put(agent, prev.teamId());
      }
    }
    for (var team : newTeams) {
      for (String agent : team.memberAgents()) {
        String old = prevAssignment.get(agent);
        if (old != null && !old.equals(team.teamId())) {
          events.add(
              new SwarmEvent(
                  CaseHubEventType.SWARM_TEAM_SHIFT,
                  Map.of(
                      "agentId",
                      agent,
                      "fromTeamId",
                      old,
                      "toTeamId",
                      team.teamId(),
                      "affinityToNewTeam",
                      team.avgAffinity())));
        }
      }
    }
    return events;
  }

  private List<SwarmEvent> dissolveAll(CaseTeamState state) {
    return state.currentTeams.stream()
        .map(
            t ->
                new SwarmEvent(
                    CaseHubEventType.SWARM_TEAM_DISSOLVED,
                    Map.of(
                        "teamId", t.teamId(),
                        "previousMembers", t.memberAgents(),
                        "lifetimeCycles", t.stabilityCount())))
        .toList();
  }

  public List<DetectedTeam> getDetectedTeams(UUID caseId) {
    var state = cases.get(caseId);
    return state != null ? List.copyOf(state.currentTeams) : List.of();
  }

  public void evictByCase(UUID caseId) {
    cases.remove(caseId);
  }

  @Override
  public void reset() {
    cases.clear();
  }

  private static class CaseTeamState {
    final AtomicInteger teamCounter = new AtomicInteger();
    List<DetectedTeam> currentTeams = List.of();
    List<DetectedTeam> previousTeams = List.of();
  }
}
