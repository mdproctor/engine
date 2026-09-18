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
import org.junit.jupiter.api.Test;

class StigmergyIntegrationTest {

  @Test
  void fullLifecycle() {
    var signalRegistry = new SignalRegistry();
    var observationRegistry = new ObservationRegistry();
    var activityTracker = new ActivityTracker();
    var coordinator =
        new StigmergyCoordinator(signalRegistry, observationRegistry, activityTracker);
    var caseId = UUID.randomUUID();
    var config = new StigmergyConfig(null, new CoordinationConfig(2, 10.0, 0.6));

    coordinator.initializeCase(caseId, List.of("temp-monitor", "pressure-monitor"), config);
    assertTrue(coordinator.isStigmergyCase(caseId));

    coordinator.agentJoined(caseId, "temp-monitor", "run-temp-monitor");
    coordinator.agentJoined(caseId, "pressure-monitor", "run-pressure-monitor");
    assertFalse(coordinator.allActive(caseId));

    coordinator.agentActivated(caseId, "temp-monitor");
    coordinator.agentActivated(caseId, "pressure-monitor");
    assertTrue(coordinator.allActive(caseId));
    assertEquals(2, coordinator.activeCount(caseId));

    signalRegistry.deposit(caseId, "overheating", 0.8, Duration.ofMinutes(5), "temp-monitor", 100);
    signalRegistry.deposit(
        caseId, "overheating", 0.9, Duration.ofMinutes(5), "pressure-monitor", 100);

    var patterns = coordinator.detectPatterns(caseId, config);
    assertTrue(
        patterns.stream()
            .anyMatch(
                e -> e.type() == StigmergyCoordinator.CoordinationEvent.Type.SIGNAL_CONSENSUS));

    coordinator.agentDeparted(caseId, "temp-monitor");
    assertEquals(1, coordinator.activeCount(caseId));
    assertEquals(
        AgentLifecycleState.DEPARTED, coordinator.getAgent(caseId, "temp-monitor").state());

    coordinator.evictByCase(caseId);
    assertFalse(coordinator.isStigmergyCase(caseId));
  }
}
