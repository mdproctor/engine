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

import io.casehub.api.model.stigmergy.*;
import io.casehub.engine.common.internal.convergence.ActivityTracker;
import io.casehub.engine.common.internal.observation.ObservationRegistry;
import io.casehub.engine.common.internal.signal.SignalRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StigmergyCoordinatorTest {

  private StigmergyCoordinator coordinator;
  private SignalRegistry signalRegistry;
  private ObservationRegistry observationRegistry;
  private ActivityTracker activityTracker;
  private UUID caseId;
  private StigmergyConfig config;

  @BeforeEach
  void setUp() {
    signalRegistry = new SignalRegistry();
    observationRegistry = new ObservationRegistry();
    activityTracker = new ActivityTracker();
    coordinator = new StigmergyCoordinator(signalRegistry, observationRegistry, activityTracker);
    caseId = UUID.randomUUID();
    config = new StigmergyConfig(null, new CoordinationConfig(2, 10.0, 0.6), null);
  }

  @Test
  void notStigmergyCaseBeforeInit() {
    assertFalse(coordinator.isStigmergyCase(caseId));
  }

  @Test
  void isStigmergyCaseAfterInit() {
    coordinator.initializeCase(caseId, List.of("agent-1"), config);
    assertTrue(coordinator.isStigmergyCase(caseId));
  }

  @Test
  void agentJoinedTransitionsToJoining() {
    coordinator.initializeCase(caseId, List.of("agent-1"), config);
    coordinator.agentJoined(caseId, "agent-1", "binding-1");
    var agents = coordinator.activeAgents(caseId);
    assertTrue(agents.isEmpty());
    var agent = coordinator.getAgent(caseId, "agent-1");
    assertNotNull(agent);
    assertEquals(AgentLifecycleState.JOINING, agent.state());
  }

  @Test
  void agentActivatedTransitionsToActive() {
    coordinator.initializeCase(caseId, List.of("agent-1"), config);
    coordinator.agentJoined(caseId, "agent-1", "binding-1");
    coordinator.agentActivated(caseId, "agent-1");
    var agents = coordinator.activeAgents(caseId);
    assertEquals(1, agents.size());
    assertEquals(AgentLifecycleState.ACTIVE, agents.get(0).state());
  }

  @Test
  void agentDepartedTransitionsToDeparted() {
    coordinator.initializeCase(caseId, List.of("agent-1"), config);
    coordinator.agentJoined(caseId, "agent-1", "binding-1");
    coordinator.agentActivated(caseId, "agent-1");
    coordinator.agentDeparted(caseId, "agent-1");
    var agents = coordinator.activeAgents(caseId);
    assertTrue(agents.isEmpty());
    var agent = coordinator.getAgent(caseId, "agent-1");
    assertEquals(AgentLifecycleState.DEPARTED, agent.state());
  }

  @Test
  void allActiveReturnsTrueWhenAllPastJoining() {
    coordinator.initializeCase(caseId, List.of("a1", "a2"), config);
    coordinator.agentJoined(caseId, "a1", "b1");
    coordinator.agentJoined(caseId, "a2", "b2");
    assertFalse(coordinator.allActive(caseId));
    coordinator.agentActivated(caseId, "a1");
    assertFalse(coordinator.allActive(caseId));
    coordinator.agentActivated(caseId, "a2");
    assertTrue(coordinator.allActive(caseId));
  }

  @Test
  void evictByCaseClearsAll() {
    coordinator.initializeCase(caseId, List.of("agent-1"), config);
    coordinator.agentJoined(caseId, "agent-1", "binding-1");
    coordinator.evictByCase(caseId);
    assertFalse(coordinator.isStigmergyCase(caseId));
  }

  @Test
  void resetClearsAll() {
    coordinator.initializeCase(caseId, List.of("agent-1"), config);
    coordinator.reset();
    assertFalse(coordinator.isStigmergyCase(caseId));
  }

  @Test
  void detectsSignalConsensus() {
    coordinator.initializeCase(caseId, List.of("a1", "a2"), config);
    coordinator.agentJoined(caseId, "a1", "b1");
    coordinator.agentJoined(caseId, "a2", "b2");
    coordinator.agentActivated(caseId, "a1");
    coordinator.agentActivated(caseId, "a2");
    signalRegistry.deposit(caseId, "trail", 0.8, Duration.ofMinutes(5), "a1", 100);
    signalRegistry.deposit(caseId, "trail", 0.9, Duration.ofMinutes(5), "a2", 100);
    var events = coordinator.detectPatterns(caseId, config);
    assertEquals(1, events.size());
    assertEquals(
        StigmergyCoordinator.CoordinationEvent.Type.SIGNAL_CONSENSUS, events.get(0).type());
  }

  @Test
  void consensusDeduplicates() {
    coordinator.initializeCase(caseId, List.of("a1", "a2"), config);
    signalRegistry.deposit(caseId, "trail", 0.8, Duration.ofMinutes(5), "a1", 100);
    signalRegistry.deposit(caseId, "trail", 0.9, Duration.ofMinutes(5), "a2", 100);
    var first = coordinator.detectPatterns(caseId, config);
    assertEquals(1, first.size());
    var second = coordinator.detectPatterns(caseId, config);
    assertTrue(second.isEmpty());
  }
}
