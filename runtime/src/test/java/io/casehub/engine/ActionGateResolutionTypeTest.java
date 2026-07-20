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
package io.casehub.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.spi.RiskDecision;
import io.casehub.engine.common.internal.event.ActionGateApprovedEvent;
import io.casehub.engine.common.internal.event.ActionGateScheduleEvent;
import io.casehub.engine.common.internal.model.PendingActionGate;
import io.casehub.worker.api.PlannedAction;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActionGateResolutionTypeTest {

  record ComplianceSignOff(String approverName, String comment, boolean approved) {}

  @Test
  void gateRequired_carries_resolutionType() {
    var gate =
        new RiskDecision.GateRequired(
            "compliance review", true, null, null, null, ComplianceSignOff.class);
    assertThat(gate.resolutionType()).isEqualTo(ComplianceSignOff.class);
  }

  @Test
  void gateRequired_resolutionType_nullable() {
    var gate = new RiskDecision.GateRequired("review", true, null, null, null, null);
    assertThat(gate.resolutionType()).isNull();
  }

  @Test
  void pendingActionGate_carries_resolutionType() {
    var gate =
        new PendingActionGate(
            1L,
            "worker1",
            "idem",
            Map.of(),
            PlannedAction.of("desc", "type", Map.of()),
            "binding1",
            "cap1",
            ComplianceSignOff.class);
    assertThat(gate.resolutionType()).isEqualTo(ComplianceSignOff.class);
  }

  @Test
  void actionGateScheduleEvent_carries_resolutionTypeName() {
    var event =
        new ActionGateScheduleEvent(
            UUID.randomUUID(),
            "tenant1",
            1L,
            PlannedAction.of("desc", "type", Map.of()),
            new RiskDecision.GateRequired("reason", true, null, null, null, null),
            Set.of(),
            ComplianceSignOff.class.getName());
    assertThat(event.resolutionTypeName()).isEqualTo(ComplianceSignOff.class.getName());
  }

  @Test
  void actionGateApprovedEvent_carries_resolutionTypeName() {
    var event =
        new ActionGateApprovedEvent(
            UUID.randomUUID(), "tenant1", 1L, "{}", "approver", ComplianceSignOff.class.getName());
    assertThat(event.resolutionTypeName()).isEqualTo(ComplianceSignOff.class.getName());
  }
}
