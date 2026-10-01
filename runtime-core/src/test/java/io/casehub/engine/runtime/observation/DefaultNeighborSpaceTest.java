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
package io.casehub.engine.internal.observation;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.signal.SignalConfig;
import io.casehub.api.spi.observation.Neighbor;
import io.casehub.api.spi.observation.NeighborRelation;
import io.casehub.engine.common.internal.observation.ObservationRegistry;
import io.casehub.engine.common.internal.signal.SignalRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultNeighborSpaceTest {

  private ObservationRegistry observationRegistry;
  private SignalRegistry signalRegistry;
  private DefaultNeighborSpace neighborSpace;
  private final UUID caseId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    observationRegistry = new ObservationRegistry();
    signalRegistry = new SignalRegistry();
    neighborSpace =
        new DefaultNeighborSpace(
            caseId,
            "tenant-1",
            "self-agent",
            "self-binding",
            null,
            observationRegistry,
            signalRegistry,
            null,
            null,
            new SignalConfig(Duration.ofMinutes(5), 0.01, 100));
  }

  @Test
  void activeReturnsEmptyWhenNoOtherAgents() {
    assertThat(neighborSpace.active()).isEmpty();
  }

  @Test
  void withSharedInterestsFindsOverlap() {
    var selfObserver = ThresholdObserver.of("riskScore", ThresholdObserver.Operator.GT, 0.5);
    observationRegistry.registerObserver(caseId, "self-agent", "self-binding", selfObserver, 20);

    var otherObserver = ThresholdObserver.of("riskScore", ThresholdObserver.Operator.GT, 0.7);
    observationRegistry.registerObserver(caseId, "other-agent", "other-binding", otherObserver, 20);

    var neighbors = neighborSpace.withSharedInterests();
    assertThat(neighbors).hasSize(1);
    assertThat(neighbors.get(0).agentId()).isEqualTo("other-agent");
    assertThat(neighbors.get(0).relations()).contains(NeighborRelation.SHARED_INTEREST);
  }

  @Test
  void withSharedInterestsExcludesSelf() {
    var observer = ThresholdObserver.of("x", ThresholdObserver.Operator.GT, 0.5);
    observationRegistry.registerObserver(caseId, "self-agent", "self-binding", observer, 20);
    assertThat(neighborSpace.withSharedInterests()).isEmpty();
  }

  @Test
  void withSharedInterestsNoOverlap() {
    var selfObserver = ThresholdObserver.of("riskScore", ThresholdObserver.Operator.GT, 0.5);
    observationRegistry.registerObserver(caseId, "self-agent", "self-binding", selfObserver, 20);

    var otherObserver = ThresholdObserver.of("temperature", ThresholdObserver.Operator.GT, 0.7);
    observationRegistry.registerObserver(caseId, "other-agent", "other-binding", otherObserver, 20);

    assertThat(neighborSpace.withSharedInterests()).isEmpty();
  }

  @Test
  void withSharedSignalsFindsSharedDepositors() {
    signalRegistry.deposit(caseId, "danger", 1.0, Duration.ofMinutes(5), "self-agent", 100);
    signalRegistry.deposit(caseId, "danger", 0.8, Duration.ofMinutes(5), "other-agent", 100);
    var neighbors = neighborSpace.withSharedSignals();
    assertThat(neighbors).hasSize(1);
    assertThat(neighbors.get(0).agentId()).isEqualTo("other-agent");
    assertThat(neighbors.get(0).relations()).contains(NeighborRelation.SHARED_SIGNAL);
  }

  @Test
  void withSharedSignalsExcludesSelf() {
    signalRegistry.deposit(caseId, "food", 1.0, Duration.ofMinutes(5), "self-agent", 100);
    assertThat(neighborSpace.withSharedSignals()).isEmpty();
  }

  @Test
  void withSharedSignalsNoSharedSignal() {
    signalRegistry.deposit(caseId, "food", 1.0, Duration.ofMinutes(5), "self-agent", 100);
    signalRegistry.deposit(caseId, "danger", 0.8, Duration.ofMinutes(5), "other-agent", 100);
    assertThat(neighborSpace.withSharedSignals()).isEmpty();
  }

  @Test
  void withSharedSignalsMultipleSharedAgents() {
    signalRegistry.deposit(caseId, "food", 1.0, Duration.ofMinutes(5), "self-agent", 100);
    signalRegistry.deposit(caseId, "food", 0.8, Duration.ofMinutes(5), "agent-a", 100);
    signalRegistry.deposit(caseId, "food", 0.7, Duration.ofMinutes(5), "agent-b", 100);
    var neighbors = neighborSpace.withSharedSignals();
    assertThat(neighbors).hasSize(2);
    assertThat(neighbors)
        .extracting(Neighbor::agentId)
        .containsExactlyInAnyOrder("agent-a", "agent-b");
  }

  @Test
  void complementaryReturnsEmptyWithoutRegistries() {
    assertThat(neighborSpace.complementary()).isEmpty();
  }
}
