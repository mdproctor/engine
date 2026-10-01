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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.model.stigmergy.IntegrationPolicy;
import io.casehub.api.model.stigmergy.ProvisionBudget;
import io.casehub.api.model.stigmergy.StigmergyConfig;
import io.casehub.api.model.stigmergy.SwarmConfig;
import io.casehub.api.spi.ProvisionResult;
import io.casehub.api.spi.WorkerProvisioner;
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

class IntegrationDelayTest {

  private RoleTracker roleTracker;
  private SwarmProvisioner swarmProvisioner;
  private StigmergyCoordinator coordinator;
  private ObservationRegistry observationRegistry;
  private SignalRegistry signalRegistry;
  private ActivityTracker activityTracker;
  private RecordingProvisioner workerProvisioner;
  private UUID caseId;

  @BeforeEach
  void setUp() {
    observationRegistry = new ObservationRegistry();
    signalRegistry = new SignalRegistry();
    var ruleRegistry = new RuleRegistry();
    activityTracker = new ActivityTracker();
    coordinator = new StigmergyCoordinator(signalRegistry, observationRegistry, activityTracker);
    roleTracker = new RoleTracker(observationRegistry, signalRegistry, ruleRegistry, coordinator);
    var teamDetector =
        new TeamDetector(observationRegistry, signalRegistry, roleTracker, coordinator);
    var progressTracker = new SwarmProgressTracker(signalRegistry, roleTracker, coordinator);
    workerProvisioner = new RecordingProvisioner();
    swarmProvisioner =
        new SwarmProvisioner(
            workerProvisioner,
            coordinator,
            signalRegistry,
            activityTracker,
            roleTracker,
            teamDetector,
            progressTracker,
            q -> Integer.MAX_VALUE,
            null);
    roleTracker.setSwarmProvisioner(swarmProvisioner);
    caseId = UUID.randomUUID();
  }

  @Test
  void agentInIntegrationDelayExcludedFromRoleDetection() {
    var budget = new ProvisionBudget(50, 10, 0, null, null);
    var policy = new IntegrationPolicy(0.7, 5, 0.5);
    var swarmConfig = new SwarmConfig(20, 0.5, 2, 20, 1, null, null, null, null, budget, policy);
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

    signalRegistry.deposit(caseId, "swarm:need-capacity", 0.8, Duration.ofMinutes(5), "a1", 100);
    signalRegistry.deposit(caseId, "swarm:need-capacity", 0.8, Duration.ofMinutes(5), "a2", 100);
    swarmProvisioner.evaluateAndProvision(caseId, swarmConfig, config);

    String provisionedId = workerProvisioner.lastProvisionedId();
    assertNotNull(provisionedId);
    assertTrue(swarmProvisioner.isInIntegrationDelay(caseId, provisionedId));

    observationRegistry.registerObserver(
        caseId, provisionedId, "b-" + provisionedId, new TestObserver("obs", Set.of("temp")), 20);

    roleTracker.accumulate(caseId);
    roleTracker.detect(caseId, swarmConfig);

    var detectedRoles = roleTracker.getDetectedRoles(caseId);
    for (var role : detectedRoles) {
      assertFalse(
          role.memberAgents().contains(provisionedId),
          "Agent in integration delay should not appear in role clusters");
    }
  }

  @Test
  void integrationDelayDecrementsEachCycle() {
    var budget = new ProvisionBudget(50, 10, 0, null, null);
    var policy = new IntegrationPolicy(0.7, 2, 0.5);
    var swarmConfig =
        new SwarmConfig(20, null, null, null, null, null, null, null, null, budget, policy);
    var config = new StigmergyConfig(null, null, swarmConfig);
    coordinator.initializeCase(caseId, List.of("a1", "a2"), config);
    for (String id : List.of("a1", "a2")) {
      coordinator.agentJoined(caseId, id, "b-" + id);
      coordinator.agentActivated(caseId, id);
    }

    signalRegistry.deposit(caseId, "swarm:need-capacity", 0.8, Duration.ofMinutes(5), "a1", 100);
    signalRegistry.deposit(caseId, "swarm:need-capacity", 0.8, Duration.ofMinutes(5), "a2", 100);
    swarmProvisioner.evaluateAndProvision(caseId, swarmConfig, config);

    String provisionedId = workerProvisioner.lastProvisionedId();
    assertTrue(swarmProvisioner.isInIntegrationDelay(caseId, provisionedId));

    roleTracker.accumulate(caseId);
    assertTrue(swarmProvisioner.isInIntegrationDelay(caseId, provisionedId));

    roleTracker.accumulate(caseId);
    assertFalse(swarmProvisioner.isInIntegrationDelay(caseId, provisionedId));
  }

  static class RecordingProvisioner implements WorkerProvisioner {
    private int count;
    private String lastId;

    @Override
    public ProvisionResult provision(
        Set<String> capabilities, io.casehub.api.model.ProvisionContext context) {
      count++;
      lastId = "swarm-provisioned-" + count;
      return new ProvisionResult(null, lastId);
    }

    @Override
    public void terminate(String workerId, String tenancyId) {}

    @Override
    public Set<String> getCapabilities() {
      return Set.of("*");
    }

    String lastProvisionedId() {
      return lastId;
    }
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
