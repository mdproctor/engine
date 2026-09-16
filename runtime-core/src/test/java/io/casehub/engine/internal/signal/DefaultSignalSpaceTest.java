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
package io.casehub.engine.internal.signal;

import static org.junit.jupiter.api.Assertions.*;

import io.casehub.api.model.signal.SignalConfig;
import io.casehub.engine.common.internal.signal.SignalRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultSignalSpaceTest {

  private SignalRegistry registry;
  private DefaultSignalSpace signalSpace;
  private final UUID caseId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    registry = new SignalRegistry();
    var config = new SignalConfig(Duration.ofMinutes(5), 0.01, 100);
    signalSpace = new DefaultSignalSpace(registry, caseId, "agent-1", config);
  }

  @Test
  void depositAndPerceive() {
    signalSpace.deposit("food", 1.0);
    var signals = signalSpace.perceive();
    assertEquals(1, signals.size());
    assertTrue(signals.containsKey("food"));
    assertTrue(signals.get("food").effectiveStrength() > 0.9);
  }

  @Test
  void depositWithCustomHalfLife() {
    signalSpace.deposit("danger", 0.8, Duration.ofSeconds(30));
    var signals = signalSpace.perceive();
    assertTrue(signals.containsKey("danger"));
  }

  @Test
  void perceiveFiltersExpired() throws InterruptedException {
    registry.deposit(caseId, "old", 0.001, Duration.ofMillis(1), "agent-1", 100);
    Thread.sleep(10);
    var signals = signalSpace.perceive();
    assertFalse(signals.containsKey("old"));
  }
}
