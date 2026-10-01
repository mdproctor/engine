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
import io.casehub.api.model.stigmergy.IntegrationPolicy;
import io.casehub.api.model.stigmergy.ProvisionBudget;
import io.casehub.api.model.stigmergy.StigmergyConfig;
import io.casehub.api.model.stigmergy.StigmergyDefaults;
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

class SelfProvisioningIntegrationTest {

  private ObservationRegistry observationRegistry;
  private SignalRegistry signalRegistry;
  private ActivityTracker activityTracker;
  private StigmergyCoordinator coordinator;
  private RoleTracker roleTracker;
  private TeamDetector teamDetector;
  private SwarmProgressTracker progressTracker;
  private SwarmProvisioner swarmProvisioner;
  private RecordingProvisioner workerProvisioner;
  private UUID caseId;
  private SwarmConfig swarmConfig;
  private StigmergyConfig config;

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
    var budget = new ProvisionBudget(10, 5, 0, 0.01, 2);
    var policy = new IntegrationPolicy(0.7, 2, 0.5);
    swarmConfig = new SwarmConfig(10, 0.5, 2, 20, 1, null, 0.3, 2, 0.05, budget, policy);
    config =
        new StigmergyConfig(
            new StigmergyDefaults(null, 0.01, null, null, null, null, null, null, null),
            new CoordinationConfig(2, 10.0, 0.6),
            swarmConfig);
  }

  @Test
  void fullProvisioningLifecycle() {
    coordinator.initializeCase(caseId, List.of("monitor-1", "monitor-2"), config);
    for (String id : List.of("monitor-1", "monitor-2")) {
      coordinator.agentJoined(caseId, id, "b-" + id);
      coordinator.agentActivated(caseId, id);
    }
    assertEquals(2, coordinator.activeAgents(caseId).size());

    signalRegistry.deposit(
        caseId, "swarm:need-capacity", 0.8, Duration.ofMinutes(5), "monitor-1", 100);
    signalRegistry.deposit(
        caseId, "swarm:need-capacity", 0.8, Duration.ofMinutes(5), "monitor-2", 100);

    var events = swarmProvisioner.evaluateAndProvision(caseId, swarmConfig, config);
    assertTrue(
        events.stream().anyMatch(e -> e.type() == CaseHubEventType.SWARM_PROVISION_COMPLETED));
    assertEquals(3, coordinator.activeAgents(caseId).size());

    String provisionedId = workerProvisioner.lastProvisionedId();
    assertTrue(swarmProvisioner.isInIntegrationDelay(caseId, provisionedId));

    roleTracker.accumulate(caseId);
    assertTrue(swarmProvisioner.isInIntegrationDelay(caseId, provisionedId));

    roleTracker.accumulate(caseId);
    assertFalse(
        swarmProvisioner.isInIntegrationDelay(caseId, provisionedId),
        "integration delay should expire after 2 accumulate cycles");
  }

  @Test
  void bootstrapContextReflectsRichnessAxis() {
    coordinator.initializeCase(caseId, List.of("a1"), config);
    coordinator.agentJoined(caseId, "a1", "b-a1");
    coordinator.agentActivated(caseId, "a1");

    signalRegistry.deposit(caseId, "alert", 0.9, Duration.ofMinutes(5), "a1", 100);

    var lowRichness =
        new SwarmConfig(
            10,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            new IntegrationPolicy(0.1, 0, 0.5));
    var ctx = swarmProvisioner.buildBootstrapContext(caseId, lowRichness, null);
    assertTrue(ctx.activeSignals().isEmpty(), "low richness should not include signals");

    var highRichness =
        new SwarmConfig(
            10,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            new IntegrationPolicy(0.9, 0, 0.5));
    var ctx2 = swarmProvisioner.buildBootstrapContext(caseId, highRichness, null);
    assertFalse(ctx2.activeSignals().isEmpty(), "high richness should include signals");
  }

  @Test
  void evictionCleansAllProvisioningState() {
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
    public List<Observation> observe(ObservationContext ctx) {
      return List.of();
    }
  }
}
