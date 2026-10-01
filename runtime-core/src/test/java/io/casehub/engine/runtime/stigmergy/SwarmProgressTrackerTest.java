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
import io.casehub.api.model.stigmergy.CoordinationConfig;
import io.casehub.api.model.stigmergy.StigmergyConfig;
import io.casehub.api.model.stigmergy.StigmergyDefaults;
import io.casehub.api.model.stigmergy.SwarmConfig;
import io.casehub.api.model.stigmergy.SwarmProgress;
import io.casehub.api.spi.observation.EnvironmentObserver;
import io.casehub.api.spi.observation.Observation;
import io.casehub.api.spi.observation.ObservationContext;
import io.casehub.engine.common.internal.convergence.ActivityTracker;
import io.casehub.engine.common.internal.observation.ObservationRegistry;
import io.casehub.engine.common.internal.observation.RuleRegistry;
import io.casehub.engine.common.internal.signal.SignalRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SwarmProgressTrackerTest {

  private SwarmProgressTracker progressTracker;
  private SignalRegistry signalRegistry;
  private RoleTracker roleTracker;
  private StigmergyCoordinator coordinator;
  private ObservationRegistry observationRegistry;
  private RuleRegistry ruleRegistry;
  private ActivityTracker activityTracker;
  private UUID caseId;

  @BeforeEach
  void setUp() {
    signalRegistry = new SignalRegistry();
    observationRegistry = new ObservationRegistry();
    ruleRegistry = new RuleRegistry();
    activityTracker = new ActivityTracker();
    coordinator = new StigmergyCoordinator(signalRegistry, observationRegistry, activityTracker);
    roleTracker = new RoleTracker(observationRegistry, signalRegistry, ruleRegistry, coordinator);
    progressTracker = new SwarmProgressTracker(signalRegistry, roleTracker, coordinator);
    caseId = UUID.randomUUID();
  }

  @Test
  void emptyProgressOnNewCase() {
    var progress = progressTracker.getProgress(caseId);
    assertEquals(SwarmProgress.EMPTY, progress);
  }

  @Test
  void consensusScoreReflectsSignalConsensus() {
    var config =
        new StigmergyConfig(
            new StigmergyDefaults(null, 0.01, null, null, null, null, null, null, null),
            new CoordinationConfig(2, null, null),
            new SwarmConfig(null, null, null, null, null, null, null, null, 0.05));
    coordinator.initializeCase(caseId, List.of("a1", "a2"), config);
    for (String id : List.of("a1", "a2")) {
      coordinator.agentJoined(caseId, id, "b-" + id);
      coordinator.agentActivated(caseId, id);
    }

    signalRegistry.deposit(caseId, "signal-A", 0.8, Duration.ofMinutes(5), "a1", 100);
    signalRegistry.deposit(caseId, "signal-A", 0.8, Duration.ofMinutes(5), "a2", 100);
    signalRegistry.deposit(caseId, "signal-B", 0.5, Duration.ofMinutes(5), "a1", 100);

    var events = progressTracker.evaluate(caseId, config);
    var progress = progressTracker.getProgress(caseId);

    assertEquals(0.5, progress.consensusScore(), 0.01);
    assertFalse(events.isEmpty());
    assertEquals(CaseHubEventType.SWARM_PROGRESS, events.get(0).type());
  }

  @Test
  void noEventWhenProgressUnchanged() {
    var config =
        new StigmergyConfig(
            null,
            new CoordinationConfig(2, null, null),
            new SwarmConfig(null, null, null, null, null, null, null, null, 0.5));
    coordinator.initializeCase(caseId, List.of("a1"), config);
    coordinator.agentJoined(caseId, "a1", "b-a1");
    coordinator.agentActivated(caseId, "a1");

    progressTracker.evaluate(caseId, config);
    var events = progressTracker.evaluate(caseId, config);

    assertTrue(events.isEmpty());
  }

  @Test
  void stabilityScoreReflectsRoleConsistency() {
    var swarmConfig = new SwarmConfig(null, 0.5, 2, 20, 1, null, null, null, 0.05);
    var config = new StigmergyConfig(null, new CoordinationConfig(2, null, null), swarmConfig);
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

    progressTracker.evaluate(caseId, config);

    roleTracker.accumulate(caseId);
    roleTracker.detect(caseId, swarmConfig);

    progressTracker.evaluate(caseId, config);
    var progress = progressTracker.getProgress(caseId);

    assertTrue(progress.stabilityScore() > 0, "stable roles should produce nonzero stability");
  }

  @Test
  void evictRemovesState() {
    var config =
        new StigmergyConfig(
            null,
            new CoordinationConfig(2, null, null),
            new SwarmConfig(null, null, null, null, null, null, null, null, null));
    coordinator.initializeCase(caseId, List.of("a1"), config);
    coordinator.agentJoined(caseId, "a1", "b");
    coordinator.agentActivated(caseId, "a1");

    progressTracker.evaluate(caseId, config);
    progressTracker.evictByCase(caseId);
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
