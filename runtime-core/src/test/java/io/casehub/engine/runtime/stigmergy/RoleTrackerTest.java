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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.stigmergy.BehavioralFingerprint;
import io.casehub.api.model.stigmergy.RoleDomainWeights;
import io.casehub.api.model.stigmergy.StigmergyConfig;
import io.casehub.api.model.stigmergy.SwarmConfig;
import io.casehub.api.spi.observation.EnvironmentObserver;
import io.casehub.api.spi.observation.Observation;
import io.casehub.api.spi.observation.ObservationContext;
import io.casehub.engine.common.internal.convergence.ActivityTracker;
import io.casehub.engine.common.internal.observation.ObservationRegistry;
import io.casehub.engine.common.internal.observation.RuleRegistry;
import io.casehub.engine.common.internal.signal.SignalRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RoleTrackerTest {

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
    caseId = UUID.randomUUID();
  }

  @Test
  void cosineSimilarityIdenticalVectors() {
    var a = Map.of("x", 0.5, "y", 0.5);
    assertEquals(1.0, BehavioralFingerprint.cosineSimilarity(a, a), 0.001);
  }

  @Test
  void cosineSimilarityOrthogonalVectors() {
    var a = Map.<String, Double>of("x", 1.0);
    var b = Map.<String, Double>of("y", 1.0);
    assertEquals(0.0, BehavioralFingerprint.cosineSimilarity(a, b), 0.001);
  }

  @Test
  void cosineSimilarityBothEmpty() {
    assertEquals(1.0, BehavioralFingerprint.cosineSimilarity(Map.of(), Map.of()), 0.001);
  }

  @Test
  void cosineSimilarityOneEmpty() {
    assertEquals(0.0, BehavioralFingerprint.cosineSimilarity(Map.of(), Map.of("x", 1.0)), 0.001);
  }

  @Test
  void cosineSimilarityPartialOverlap() {
    var a = Map.of("x", 1.0, "y", 1.0);
    var b = Map.of("x", 1.0, "z", 1.0);
    double expected = 1.0 / (Math.sqrt(2) * Math.sqrt(2));
    assertEquals(expected, BehavioralFingerprint.cosineSimilarity(a, b), 0.001);
  }

  @Test
  void fingerprintExtractsPerceptionFromInterests() {
    var config =
        new StigmergyConfig(
            null, null, new SwarmConfig(null, null, null, null, null, null, null, null, null));
    coordinator.initializeCase(caseId, List.of("agent-1"), config);
    coordinator.agentJoined(caseId, "agent-1", "binding-1");
    coordinator.agentActivated(caseId, "agent-1");

    observationRegistry.registerObserver(
        caseId,
        "agent-1",
        "binding-1",
        new TestObserver("obs-1", Set.of("tempReading", "pressure")),
        20);

    roleTracker.accumulate(caseId);
    var fp = roleTracker.getFingerprint(caseId, "agent-1");

    assertEquals(1.0, fp.perception().get("tempReading"));
    assertEquals(1.0, fp.perception().get("pressure"));
    assertEquals(2, fp.perception().size());
  }

  @Test
  void fingerprintExtractsCommunicationFromSignals() {
    var config =
        new StigmergyConfig(
            null, null, new SwarmConfig(null, null, null, null, null, null, null, null, null));
    coordinator.initializeCase(caseId, List.of("agent-1"), config);
    coordinator.agentJoined(caseId, "agent-1", "binding-1");
    coordinator.agentActivated(caseId, "agent-1");

    signalRegistry.deposit(caseId, "overheating", 0.8, Duration.ofMinutes(5), "agent-1", 100);
    signalRegistry.deposit(caseId, "cooldown", 0.5, Duration.ofMinutes(5), "agent-1", 100);

    roleTracker.accumulate(caseId);
    var fp = roleTracker.getFingerprint(caseId, "agent-1");

    assertTrue(fp.communication().containsKey("overheating"));
    assertTrue(fp.communication().containsKey("cooldown"));
    assertEquals(2, fp.communication().size());
  }

  @Test
  void detectsRoleClustersFromSimilarBehavior() {
    var weights = new RoleDomainWeights(0.8, 0.1, 0.05, 0.05);
    var swarmConfig = new SwarmConfig(null, 0.5, 2, 20, 1, weights, null, null, null);
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
        caseId, "a3", "b-a3", new TestObserver("obs", Set.of("cooling", "venting")), 20);

    roleTracker.accumulate(caseId);
    var events = roleTracker.detect(caseId, swarmConfig);

    var roles = roleTracker.getDetectedRoles(caseId);
    assertEquals(1, roles.size());
    assertTrue(roles.get(0).memberAgents().containsAll(Set.of("a1", "a2")));
    assertFalse(roles.get(0).memberAgents().contains("a3"));
  }

  @Test
  void detectsRoleEmergenceEvent() {
    var swarmConfig = new SwarmConfig(null, 0.5, 2, 20, 1, null, null, null, null);
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

    roleTracker.accumulate(caseId);
    var events = roleTracker.detect(caseId, swarmConfig);

    assertTrue(events.stream().anyMatch(e -> e.type() == CaseHubEventType.SWARM_ROLE_EMERGED));
  }

  @Test
  void detectsRoleDissolutionWhenAgentsDepart() {
    var swarmConfig = new SwarmConfig(null, 0.5, 2, 20, 1, null, null, null, null);
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

    roleTracker.accumulate(caseId);
    roleTracker.detect(caseId, swarmConfig);
    assertEquals(1, roleTracker.getDetectedRoles(caseId).size());

    coordinator.agentDeparted(caseId, "a2");
    roleTracker.accumulate(caseId);
    var events = roleTracker.detect(caseId, swarmConfig);

    assertTrue(events.stream().anyMatch(e -> e.type() == CaseHubEventType.SWARM_ROLE_DISSOLVED));
    assertEquals(0, roleTracker.getDetectedRoles(caseId).size());
  }

  @Test
  void shouldDetectReturnsTrueWhenDirty() {
    var swarmConfig = new SwarmConfig(null, null, null, null, 10, null, null, null, null);
    cases(caseId);
    roleTracker.accumulate(caseId);
    assertFalse(roleTracker.shouldDetect(caseId, swarmConfig));
    roleTracker.markDirty(caseId);
    assertTrue(roleTracker.shouldDetect(caseId, swarmConfig));
  }

  @Test
  void evictByCaseRemovesTrackerState() {
    cases(caseId);
    roleTracker.accumulate(caseId);
    roleTracker.markDirty(caseId);
    assertTrue(roleTracker.shouldDetect(caseId));
    roleTracker.evictByCase(caseId);
    assertFalse(roleTracker.shouldDetect(caseId));
    assertEquals(List.of(), roleTracker.getDetectedRoles(caseId));
    assertEquals(Set.of(), roleTracker.effectKeys(caseId, "a1"));
  }

  private void cases(UUID caseId) {
    var config =
        new StigmergyConfig(
            null, null, new SwarmConfig(null, null, null, null, null, null, null, null, null));
    coordinator.initializeCase(caseId, List.of("a1"), config);
    coordinator.agentJoined(caseId, "a1", "b-a1");
    coordinator.agentActivated(caseId, "a1");
    observationRegistry.registerObserver(
        caseId, "a1", "b-a1", new TestObserver("obs", Set.of("temp")), 20);
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
