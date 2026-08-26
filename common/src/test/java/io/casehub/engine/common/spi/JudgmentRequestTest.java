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
package io.casehub.engine.common.spi;

import static org.junit.jupiter.api.Assertions.*;

import io.casehub.api.model.JudgmentTarget;
import io.casehub.api.spi.RiskDecision;
import io.casehub.worker.api.PlannedAction;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JudgmentRequestTest {

  @Test
  void bindingPayloadRequestCarriesAllFields() {
    var target = JudgmentTarget.forHuman().prompt("Review").build();
    var payload =
        new JudgmentPayload.BindingPayload(
            Map.of("key", "value"),
            null,
            null,
            Set.of("group-a"),
            Set.of(),
            null,
            null,
            "Review Title",
            "case-scope",
            List.of(),
            Map.of());
    var request =
        new JudgmentRequest(UUID.randomUUID(), "tenant-1", "review-binding", target, payload);

    assertNotNull(request);
    assertEquals("tenant-1", request.tenancyId());
    assertEquals("review-binding", request.bindingName());
    assertInstanceOf(JudgmentPayload.BindingPayload.class, request.payload());
    var bp = (JudgmentPayload.BindingPayload) request.payload();
    assertEquals(Map.of("key", "value"), bp.inputData());
    assertEquals("Review Title", bp.resolvedTitle());
  }

  @Test
  void gatePayloadRequestCarriesPlannedAction() {
    var target = JudgmentTarget.forHuman().prompt("Approve action").build();
    var action = PlannedAction.of("Cancel subscription", "sub.cancel", Map.of());
    var gateRequired =
        new RiskDecision.GateRequired("Review needed", true, null, null, null, null, null);
    var payload =
        new JudgmentPayload.GatePayload(
            1L, action, gateRequired, Set.of("approvers"), null, Map.of("output", "value"));
    var request = new JudgmentRequest(UUID.randomUUID(), "tenant-1", "__gate__", target, payload);

    assertInstanceOf(JudgmentPayload.GatePayload.class, request.payload());
    var gp = (JudgmentPayload.GatePayload) request.payload();
    assertEquals("sub.cancel", gp.plannedAction().actionType());
    assertEquals(1L, gp.gateId());
    assertEquals(Map.of("output", "value"), gp.deferredOutput());
  }

  @Test
  void bindingPayloadDefensiveCopies() {
    var mutableInput = new java.util.HashMap<>(Map.of("k", (Object) "v"));
    var payload =
        new JudgmentPayload.BindingPayload(
            mutableInput, null, null, null, null, null, null, null, null, List.of(), Map.of());
    mutableInput.put("k2", "v2");

    assertFalse(payload.inputData().containsKey("k2"));
  }

  @Test
  void gatePayloadRejectsNullPlannedAction() {
    assertThrows(
        NullPointerException.class,
        () -> new JudgmentPayload.GatePayload(1L, null, null, null, null, null));
  }

  @Test
  void requestRejectsNullCaseId() {
    var target = JudgmentTarget.forAny().prompt("test").build();
    var payload =
        new JudgmentPayload.BindingPayload(
            Map.of(), null, null, null, null, null, null, null, null, List.of(), Map.of());
    assertThrows(
        NullPointerException.class, () -> new JudgmentRequest(null, "t", "b", target, payload));
  }

  @Test
  void pendingJudgmentPreservesFields() {
    var payload =
        new JudgmentPayload.BindingPayload(
            Map.of(), null, null, null, null, null, null, null, null, List.of(), Map.of());
    var pending =
        new PendingJudgment(
            42L,
            "review-binding",
            payload,
            "worker-1",
            "idem-key",
            Map.of("out", "val"),
            java.time.Instant.now());

    assertEquals(42L, pending.judgmentId());
    assertEquals("review-binding", pending.bindingName());
    assertEquals("worker-1", pending.workerId());
    assertEquals("idem-key", pending.idempotency());
  }
}
