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
package io.casehub.persistence.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.engine.common.internal.model.PendingActionGate;
import io.casehub.worker.api.PlannedAction;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PendingActionGateMapperTest {

  @Test
  void roundTrip_fullGate() {
    PendingActionGate gate =
        new PendingActionGate(
            42L,
            "sar-drafting-agent",
            "idem-123",
            Map.of("report", "draft-v1"),
            PlannedAction.of("File SAR", "FILE_SAR", Map.of("deadline", "24h")),
            "sar-binding",
            "sar-drafting",
            String.class);

    String json = PendingActionGateMapper.toJson(gate);
    assertThat(json).isNotNull();

    PendingActionGate restored = PendingActionGateMapper.fromJson(json);
    assertThat(restored).isNotNull();
    assertThat(restored.gateId()).isEqualTo(42L);
    assertThat(restored.workerId()).isEqualTo("sar-drafting-agent");
    assertThat(restored.idempotency()).isEqualTo("idem-123");
    assertThat(restored.deferredOutput()).containsEntry("report", "draft-v1");
    assertThat(restored.plannedAction().actionType()).isEqualTo("FILE_SAR");
    assertThat(restored.plannedAction().description()).isEqualTo("File SAR");
    assertThat(restored.plannedAction().parameters()).containsEntry("deadline", "24h");
    assertThat(restored.bindingName()).isEqualTo("sar-binding");
    assertThat(restored.capabilityName()).isEqualTo("sar-drafting");
    assertThat(restored.resolutionType()).isEqualTo(String.class);
  }

  @Test
  void roundTrip_nullableFieldsAbsent() {
    PendingActionGate gate =
        new PendingActionGate(
            1L,
            "worker-a",
            "idem-1",
            Map.of(),
            PlannedAction.of("approve", "APPROVE"),
            null,
            null,
            null);

    String json = PendingActionGateMapper.toJson(gate);
    PendingActionGate restored = PendingActionGateMapper.fromJson(json);

    assertThat(restored).isNotNull();
    assertThat(restored.bindingName()).isNull();
    assertThat(restored.capabilityName()).isNull();
    assertThat(restored.resolutionType()).isNull();
  }

  @Test
  void nullInput_returnsNull() {
    assertThat(PendingActionGateMapper.toJson(null)).isNull();
    assertThat(PendingActionGateMapper.fromJson(null)).isNull();
    assertThat(PendingActionGateMapper.fromJson("")).isNull();
    assertThat(PendingActionGateMapper.fromJson("  ")).isNull();
  }
}
