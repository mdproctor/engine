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

class SwarmIntegrationTest {

  private ObservationRegistry observationRegistry;
  private SignalRegistry signalRegistry;
  private RuleRegistry ruleRegistry;
  private ActivityTracker activityTracker;
  private StigmergyCoordinator coordinator;
  private RoleTracker roleTracker;
  private TeamDetector teamDetector;
  private SwarmProgressTracker progressTracker;
  private UUID caseId;
  private StigmergyConfig config;
  private SwarmConfig swarmConfig;

  @BeforeEach
  void setUp() {
    observationRegistry = new ObservationRegistry();
    signalRegistry = new SignalRegistry();
    ruleRegistry = new RuleRegistry();
    activityTracker = new ActivityTracker();
    coordinator = new StigmergyCoordinator(signalRegistry, observationRegistry, activityTracker);
    roleTracker = new RoleTracker(observationRegistry, signalRegistry, ruleRegistry, coordinator);
    teamDetector = new TeamDetector(observationRegistry, signalRegistry, roleTracker, coordinator);
    progressTracker = new SwarmProgressTracker(signalRegistry, roleTracker, coordinator);

    caseId = UUID.randomUUID();
    swarmConfig = new SwarmConfig(null, 0.5, 2, 20, 1, null, 0.3, 2, 0.05);
    config =
        new StigmergyConfig(
            new StigmergyDefaults(null, 0.01, null, null, null, null, null, null, null),
            new CoordinationConfig(2, 10.0, 0.6),
            swarmConfig);
  }

  @Test
  void fullSwarmLifecycle() {
    coordinator.initializeCase(caseId, List.of("monitor-1", "monitor-2", "controller-1"), config);
    for (String id : List.of("monitor-1", "monitor-2", "controller-1")) {
      coordinator.agentJoined(caseId, id, "b-" + id);
      coordinator.agentActivated(caseId, id);
    }

    observationRegistry.registerObserver(
        caseId,
        "monitor-1",
        "b-monitor-1",
        new TestObserver("temp-obs", Set.of("tempReading", "pressure")),
        20);
    observationRegistry.registerObserver(
        caseId,
        "monitor-2",
        "b-monitor-2",
        new TestObserver("pressure-obs", Set.of("tempReading", "pressure")),
        20);
    observationRegistry.registerObserver(
        caseId,
        "controller-1",
        "b-controller-1",
        new TestObserver("cooling-obs", Set.of("coolingAction")),
        20);

    signalRegistry.deposit(caseId, "overheating", 0.8, Duration.ofMinutes(5), "monitor-1", 100);
    signalRegistry.deposit(caseId, "overheating", 0.9, Duration.ofMinutes(5), "monitor-2", 100);

    roleTracker.accumulate(caseId);
    var roleEvents = roleTracker.detect(caseId, swarmConfig);
    var teamEvents = teamDetector.detect(caseId, swarmConfig);
    var progressEvents = progressTracker.evaluate(caseId, config);

    var roles = roleTracker.getDetectedRoles(caseId);
    assertEquals(1, roles.size(), "monitors should cluster into one role");
    assertTrue(roles.get(0).memberAgents().containsAll(Set.of("monitor-1", "monitor-2")));

    var teams = teamDetector.getDetectedTeams(caseId);
    assertFalse(teams.isEmpty(), "monitors should form a team via shared interests+signals");

    var progress = progressTracker.getProgress(caseId);
    assertTrue(
        progress.consensusScore() > 0, "consensus should be non-zero (overheating has 2 sources)");

    assertFalse(roleEvents.isEmpty());
    assertTrue(roleEvents.stream().anyMatch(e -> e.type() == CaseHubEventType.SWARM_ROLE_EMERGED));
  }

  @Test
  void metricsSpaceExposesAllData() {
    coordinator.initializeCase(caseId, List.of("a1", "a2"), config);
    for (String id : List.of("a1", "a2")) {
      coordinator.agentJoined(caseId, id, "b-" + id);
      coordinator.agentActivated(caseId, id);
    }

    observationRegistry.registerObserver(
        caseId, "a1", "b-a1", new TestObserver("obs", Set.of("key1")), 20);

    roleTracker.accumulate(caseId);
    progressTracker.evaluate(caseId, config);

    var metrics =
        new DefaultMetricsSpace(
            caseId, "a1", activityTracker, roleTracker, teamDetector, progressTracker);

    assertNotNull(metrics.myFingerprint());
    assertFalse(metrics.myFingerprint().perception().isEmpty());
    assertNotNull(metrics.swarmProgress());
    assertNotNull(metrics.detectedRoles());
    assertNotNull(metrics.detectedTeams());
    assertNotNull(metrics.activityRates());
    assertNotNull(metrics.budgetUsage());
  }

  @Test
  void evictionCleansAllTrackers() {
    coordinator.initializeCase(caseId, List.of("a1"), config);
    coordinator.agentJoined(caseId, "a1", "b-a1");
    coordinator.agentActivated(caseId, "a1");
    roleTracker.accumulate(caseId);
    roleTracker.detect(caseId, swarmConfig);
    teamDetector.detect(caseId, swarmConfig);
    progressTracker.evaluate(caseId, config);

    roleTracker.evictByCase(caseId);
    teamDetector.evictByCase(caseId);
    progressTracker.evictByCase(caseId);
    coordinator.evictByCase(caseId);

    assertTrue(roleTracker.getDetectedRoles(caseId).isEmpty());
    assertTrue(teamDetector.getDetectedTeams(caseId).isEmpty());
    assertEquals(SwarmProgress.EMPTY, progressTracker.getProgress(caseId));
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
