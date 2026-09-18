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
package io.casehub.engine.common.internal.signal;

import static org.junit.jupiter.api.Assertions.*;

import io.casehub.api.model.signal.Signal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SignalRegistryConsensusTest {

  private SignalRegistry registry;
  private UUID caseId;

  @BeforeEach
  void setUp() {
    registry = new SignalRegistry();
    caseId = UUID.randomUUID();
  }

  @Test
  void returnsEmptyWhenNoSignals() {
    Map<String, Signal> result = registry.consensusSignals(caseId, 2, 0.01);
    assertTrue(result.isEmpty());
  }

  @Test
  void returnsEmptyWhenNoSignalMeetsThreshold() {
    registry.deposit(caseId, "lonely", 0.8, Duration.ofMinutes(5), "agent-1", 100);
    Map<String, Signal> result = registry.consensusSignals(caseId, 2, 0.01);
    assertTrue(result.isEmpty());
  }

  @Test
  void returnsSignalWhenSourceCountMeetsThreshold() {
    registry.deposit(caseId, "consensus-signal", 0.8, Duration.ofMinutes(5), "agent-1", 100);
    registry.deposit(caseId, "consensus-signal", 0.9, Duration.ofMinutes(5), "agent-2", 100);
    Map<String, Signal> result = registry.consensusSignals(caseId, 2, 0.01);
    assertEquals(1, result.size());
    assertTrue(result.containsKey("consensus-signal"));
    assertEquals(2, result.get("consensus-signal").sources().size());
  }

  @Test
  void excludesExpiredSignals() {
    Instant past = Instant.now().minusSeconds(600);
    registry.deposit(caseId, "old", 0.5, Duration.ofSeconds(1), "agent-1", 100, past);
    registry.deposit(caseId, "old", 0.5, Duration.ofSeconds(1), "agent-2", 100, past);
    Map<String, Signal> result = registry.consensusSignals(caseId, 2, 0.01);
    assertTrue(result.isEmpty());
  }
}
