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

import static org.junit.jupiter.api.Assertions.*;

import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.stigmergy.*;
import io.casehub.api.spi.observation.*;
import io.casehub.engine.common.internal.convergence.ActivityTracker;
import io.casehub.engine.common.internal.observation.ObservationRegistry;
import io.casehub.engine.common.internal.observation.RuleRegistry;
import io.casehub.engine.common.internal.signal.SignalRegistry;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TeamDetectorTest {

  private TeamDetector teamDetector;
  private RoleTracker roleTracker;
  private ObservationRegistry observationRegistry;
  private SignalRegistry signalRegistry;
  private RuleRegistry ruleRegistry;
  private StigmergyCoordinator coordinator;
  private ActivityTracker activityTracker;
  private UUID caseId;

  @BeforeEach
  void setUp() {
    observationRegistry = new ObservationRegistry();
    signalRegistry = new SignalRegistry();
    ruleRegistry = new RuleRegistry();
    activityTracker = new ActivityTracker();
    coordinator = new StigmergyCoordinator(signalRegistry, observationRegistry, activityTracker);
    roleTracker = new RoleTracker(observationRegistry, signalRegistry, ruleRegistry, coordinator);
    teamDetector = new TeamDetector(observationRegistry, signalRegistry, roleTracker, coordinator);
    caseId = UUID.randomUUID();
  }

  @Test
  void detectsTeamFromSharedInterests() {
    var swarmConfig = new SwarmConfig(null, null, null, null, null, null, 0.3, 2, null);
    var config = new StigmergyConfig(null, null, swarmConfig);
    coordinator.initializeCase(caseId, List.of("a1", "a2", "a3"), config);
    for (String id : List.of("a1", "a2", "a3")) {
      coordinator.agentJoined(caseId, id, "b-" + id);
      coordinator.agentActivated(caseId, id);
    }

    observationRegistry.registerObserver(
        caseId, "a1", "b-a1", new TestObserver("obs", Set.of("temp", "pressure")), 20);
    observationRegistry.registerObserver(
        caseId, "a2", "b-a2", new TestObserver("obs", Set.of("temp", "pressure")), 20);
    observationRegistry.registerObserver(
        caseId, "a3", "b-a3", new TestObserver("obs", Set.of("cooling")), 20);

    var events = teamDetector.detect(caseId, swarmConfig);
    var teams = teamDetector.getDetectedTeams(caseId);

    assertEquals(1, teams.size());
    assertTrue(teams.get(0).memberAgents().containsAll(Set.of("a1", "a2")));
    assertEquals(1, events.size());
    assertEquals(CaseHubEventType.SWARM_TEAM_FORMED, events.get(0).type());
  }

  @Test
  void detectsTeamFromSharedSignals() {
    var swarmConfig = new SwarmConfig(null, null, null, null, null, null, 0.3, 2, null);
    var config = new StigmergyConfig(null, null, swarmConfig);
    coordinator.initializeCase(caseId, List.of("a1", "a2"), config);
    for (String id : List.of("a1", "a2")) {
      coordinator.agentJoined(caseId, id, "b-" + id);
      coordinator.agentActivated(caseId, id);
    }

    signalRegistry.deposit(caseId, "overheating", 0.8, Duration.ofMinutes(5), "a1", 100);
    signalRegistry.deposit(caseId, "overheating", 0.9, Duration.ofMinutes(5), "a2", 100);

    var events = teamDetector.detect(caseId, swarmConfig);
    assertFalse(teamDetector.getDetectedTeams(caseId).isEmpty());
  }

  @Test
  void noTeamWhenBelowMinSize() {
    var swarmConfig = new SwarmConfig(null, null, null, null, null, null, 0.3, 3, null);
    var config = new StigmergyConfig(null, null, swarmConfig);
    coordinator.initializeCase(caseId, List.of("a1", "a2"), config);
    for (String id : List.of("a1", "a2")) {
      coordinator.agentJoined(caseId, id, "b-" + id);
      coordinator.agentActivated(caseId, id);
    }

    observationRegistry.registerObserver(
        caseId, "a1", "b-a1", new TestObserver("obs", Set.of("temp")), 20);
    observationRegistry.registerObserver(
        caseId, "a2", "b-a2", new TestObserver("obs", Set.of("temp")), 20);

    teamDetector.detect(caseId, swarmConfig);
    assertTrue(teamDetector.getDetectedTeams(caseId).isEmpty());
  }

  @Test
  void teamDissolutionEventOnAgentDeparture() {
    var swarmConfig = new SwarmConfig(null, null, null, null, null, null, 0.3, 2, null);
    var config = new StigmergyConfig(null, null, swarmConfig);
    coordinator.initializeCase(caseId, List.of("a1", "a2"), config);
    for (String id : List.of("a1", "a2")) {
      coordinator.agentJoined(caseId, id, "b-" + id);
      coordinator.agentActivated(caseId, id);
    }
    observationRegistry.registerObserver(
        caseId, "a1", "b-a1", new TestObserver("obs", Set.of("temp")), 20);
    observationRegistry.registerObserver(
        caseId, "a2", "b-a2", new TestObserver("obs", Set.of("temp")), 20);

    teamDetector.detect(caseId, swarmConfig);
    assertFalse(teamDetector.getDetectedTeams(caseId).isEmpty());

    coordinator.agentDeparted(caseId, "a2");
    var events = teamDetector.detect(caseId, swarmConfig);

    assertTrue(events.stream().anyMatch(e -> e.type() == CaseHubEventType.SWARM_TEAM_DISSOLVED));
    assertTrue(teamDetector.getDetectedTeams(caseId).isEmpty());
  }

  @Test
  void evictRemovesState() {
    var swarmConfig = new SwarmConfig(null, null, null, null, null, null, 0.3, 2, null);
    var config = new StigmergyConfig(null, null, swarmConfig);
    coordinator.initializeCase(caseId, List.of("a1", "a2"), config);
    for (String id : List.of("a1", "a2")) {
      coordinator.agentJoined(caseId, id, "b-" + id);
      coordinator.agentActivated(caseId, id);
    }
    observationRegistry.registerObserver(
        caseId, "a1", "b-a1", new TestObserver("obs", Set.of("temp")), 20);
    observationRegistry.registerObserver(
        caseId, "a2", "b-a2", new TestObserver("obs", Set.of("temp")), 20);
    teamDetector.detect(caseId, swarmConfig);

    teamDetector.evictByCase(caseId);
    assertTrue(teamDetector.getDetectedTeams(caseId).isEmpty());
  }

  @Test
  void teamStabilityIncrementsOnConsecutiveDetection() {
    var swarmConfig = new SwarmConfig(null, null, null, null, null, null, 0.3, 2, null);
    var config = new StigmergyConfig(null, null, swarmConfig);
    coordinator.initializeCase(caseId, List.of("a1", "a2"), config);
    for (String id : List.of("a1", "a2")) {
      coordinator.agentJoined(caseId, id, "b-" + id);
      coordinator.agentActivated(caseId, id);
    }
    observationRegistry.registerObserver(
        caseId, "a1", "b-a1", new TestObserver("obs", Set.of("temp")), 20);
    observationRegistry.registerObserver(
        caseId, "a2", "b-a2", new TestObserver("obs", Set.of("temp")), 20);

    teamDetector.detect(caseId, swarmConfig);
    assertEquals(1, teamDetector.getDetectedTeams(caseId).get(0).stabilityCount());

    teamDetector.detect(caseId, swarmConfig);
    assertEquals(2, teamDetector.getDetectedTeams(caseId).get(0).stabilityCount());
  }

  static class TestObserver implements EnvironmentObserver {
    private final String type;
    private final Set<String> keys;

    TestObserver(String type, Set<String> keys) {
      this.type = type;
      this.keys = keys;
    }

    @Override
    public String observerType() {
      return type;
    }

    @Override
    public Set<String> watchedKeys() {
      return keys;
    }

    @Override
    public List<Observation> observe(ObservationContext context) {
      return List.of();
    }
  }
}
