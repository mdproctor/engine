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
import io.casehub.api.spi.*;
import io.casehub.engine.common.internal.convergence.ActivityTracker;
import io.casehub.engine.common.internal.observation.ObservationRegistry;
import io.casehub.engine.common.internal.observation.RuleRegistry;
import io.casehub.engine.common.internal.signal.SignalRegistry;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SwarmProvisionerTest {

  private SwarmProvisioner swarmProvisioner;
  private StigmergyCoordinator coordinator;
  private SignalRegistry signalRegistry;
  private ObservationRegistry observationRegistry;
  private ActivityTracker activityTracker;
  private RoleTracker roleTracker;
  private TeamDetector teamDetector;
  private SwarmProgressTracker progressTracker;
  private RecordingWorkerProvisioner workerProvisioner;
  private UUID caseId;

  @BeforeEach
  void setUp() {
    observationRegistry = new ObservationRegistry();
    signalRegistry = new SignalRegistry();
    var ruleRegistry = new RuleRegistry();
    activityTracker = new ActivityTracker();
    coordinator = new StigmergyCoordinator(signalRegistry, observationRegistry, activityTracker);
    roleTracker = new RoleTracker(observationRegistry, signalRegistry, ruleRegistry, coordinator);
    teamDetector = new TeamDetector(observationRegistry, signalRegistry, roleTracker, coordinator);
    progressTracker = new SwarmProgressTracker(signalRegistry, roleTracker, coordinator);
    workerProvisioner = new RecordingWorkerProvisioner();
    swarmProvisioner =
        new SwarmProvisioner(
            workerProvisioner,
            coordinator,
            signalRegistry,
            activityTracker,
            roleTracker,
            teamDetector,
            progressTracker,
            new NoOpDispatchBudget());
    caseId = UUID.randomUUID();
  }

  @Test
  void budgetMaxSwarmSizeBlocksProvisioning() {
    var budget = new ProvisionBudget(50, 10, 0, null, null);
    var policy = IntegrationPolicy.BALANCED;
    var swarmConfig =
        new SwarmConfig(2, null, null, null, null, null, null, null, null, budget, policy);
    var config = new StigmergyConfig(null, null, swarmConfig);
    coordinator.initializeCase(caseId, List.of("a1", "a2"), config);
    for (String id : List.of("a1", "a2")) {
      coordinator.agentJoined(caseId, id, "b-" + id);
      coordinator.agentActivated(caseId, id);
    }

    signalRegistry.deposit(caseId, "swarm:need-capacity", 0.8, Duration.ofMinutes(5), "a1", 100);
    signalRegistry.deposit(caseId, "swarm:need-capacity", 0.8, Duration.ofMinutes(5), "a2", 100);

    var events = swarmProvisioner.evaluateAndProvision(caseId, swarmConfig, config);

    assertTrue(
        events.stream()
            .anyMatch(e -> e.type() == CaseHubEventType.SWARM_PROVISION_BUDGET_EXHAUSTED));
    assertEquals(0, workerProvisioner.provisionCount());
  }

  @Test
  void cooldownPreventsRapidProvisioning() {
    var budget = new ProvisionBudget(50, 10, 5, null, null);
    var policy = IntegrationPolicy.BALANCED;
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

    var events1 = swarmProvisioner.evaluateAndProvision(caseId, swarmConfig, config);
    assertTrue(
        events1.stream().anyMatch(e -> e.type() == CaseHubEventType.SWARM_PROVISION_COMPLETED));

    signalRegistry.deposit(caseId, "swarm:need-capacity", 0.9, Duration.ofMinutes(5), "a1", 100);
    var events2 = swarmProvisioner.evaluateAndProvision(caseId, swarmConfig, config);

    assertTrue(
        events2.stream()
            .anyMatch(e -> e.type() == CaseHubEventType.SWARM_PROVISION_BUDGET_EXHAUSTED));
    assertEquals(1, workerProvisioner.provisionCount());
  }

  @Test
  void successfulProvisionJoinsSwarm() {
    var budget = new ProvisionBudget(50, 10, 0, null, null);
    var policy = IntegrationPolicy.BALANCED;
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

    var events = swarmProvisioner.evaluateAndProvision(caseId, swarmConfig, config);

    assertTrue(
        events.stream().anyMatch(e -> e.type() == CaseHubEventType.SWARM_PROVISION_COMPLETED));
    assertEquals(3, coordinator.activeAgents(caseId).size());
    assertEquals(1, workerProvisioner.provisionCount());
  }

  @Test
  void integrationDelayExemptsFromIdleDetection() {
    var budget = new ProvisionBudget(50, 10, 0, 0.5, 1);
    var policy = new IntegrationPolicy(0.7, 5, 0.5);
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

    var deprovEvents = swarmProvisioner.evaluateDeprovisioning(caseId, swarmConfig);
    assertTrue(
        deprovEvents.stream()
            .noneMatch(
                e ->
                    e.type() == CaseHubEventType.SWARM_AGENT_TERMINATED
                        && e.metadata().get("agentId").equals(provisionedId)));
  }

  @Test
  void evictByCaseClearsState() {
    var budget = new ProvisionBudget(50, 10, 0, null, null);
    var policy = IntegrationPolicy.BALANCED;
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

    swarmProvisioner.evictByCase(caseId);

    assertFalse(swarmProvisioner.isInIntegrationDelay(caseId, "any"));
  }

  static class RecordingWorkerProvisioner implements WorkerProvisioner {
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

    int provisionCount() {
      return count;
    }

    String lastProvisionedId() {
      return lastId;
    }
  }

  static class NoOpDispatchBudget implements DispatchBudget {
    @Override
    public int availableCapacity(DispatchBudgetQuery query) {
      return Integer.MAX_VALUE;
    }
  }
}
