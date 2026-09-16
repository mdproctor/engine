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
package io.casehub.engine.internal.executor;

import static org.assertj.core.api.Assertions.*;

import io.casehub.api.model.WorkerContext;
import io.casehub.api.model.signal.PerceivedSignal;
import io.casehub.api.model.signal.SignalConfig;
import io.casehub.engine.common.internal.signal.SignalRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultWorkerRuntimeSignalTest {

  private SignalRegistry signalRegistry;
  private DefaultWorkerRuntime runtime;
  private UUID caseId;

  @BeforeEach
  void setUp() {
    signalRegistry = new SignalRegistry();
    caseId = UUID.randomUUID();
    SignalConfig config = SignalConfig.defaults();
    runtime =
        new DefaultWorkerRuntime(
            caseId,
            "task-1",
            new WorkerContext(null, null, List.of(), List.of(), null, Map.of()),
            Map.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "test-agent",
            "test-binding",
            signalRegistry,
            config);
  }

  @Test
  void depositSignal_createsSignalInRegistry() {
    runtime.depositSignal("trail", 0.8);
    assertThat(signalRegistry.signalCount(caseId)).isEqualTo(1);
  }

  @Test
  void depositSignal_withCustomHalfLife() {
    runtime.depositSignal("trail", 0.8, Duration.ofMinutes(10));
    Map<String, PerceivedSignal> perceived = signalRegistry.perceive(caseId, 0.01);
    assertThat(perceived).containsKey("trail");
    assertThat(perceived.get("trail").effectiveStrength()).isCloseTo(0.8, within(0.01));
  }

  @Test
  void perceiveSignals_returnsActiveSignals() {
    signalRegistry.deposit(caseId, "trail", 0.8, Duration.ofMinutes(5), "other-agent", 100);
    Map<String, PerceivedSignal> perceived = runtime.perceiveSignals();
    assertThat(perceived).containsKey("trail");
    assertThat(perceived.get("trail").effectiveStrength()).isGreaterThan(0.0);
  }

  @Test
  void perceiveSignals_emptyWhenNoSignals() {
    Map<String, PerceivedSignal> perceived = runtime.perceiveSignals();
    assertThat(perceived).isEmpty();
  }

  @Test
  void depositSignal_nullRegistry_noOp() {
    DefaultWorkerRuntime runtimeNoSignals =
        new DefaultWorkerRuntime(
            caseId,
            "task-1",
            new WorkerContext(null, null, List.of(), List.of(), null, Map.of()),
            Map.of(),
            null,
            null,
            null,
            null,
            null,
            null);
    runtimeNoSignals.depositSignal("trail", 0.5);
    assertThat(runtimeNoSignals.perceiveSignals()).isEmpty();
  }
}
