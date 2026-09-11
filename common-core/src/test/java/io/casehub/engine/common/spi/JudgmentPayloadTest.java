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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.casehub.api.model.JudgmentTarget;
import io.casehub.api.spi.RiskDecision;
import io.casehub.worker.api.PlannedAction;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JudgmentPayloadTest {

  @Test
  void bindingPayloadFullConstructor() {
    var target = JudgmentTarget.builder().prompt("Review this").build();
    var bp =
        new JudgmentPayload.BindingPayload(
            target,
            Map.of("key", "value"),
            "MyResolution",
            Instant.now(),
            null,
            "Review Title",
            "compliance",
            Set.of("managers"),
            Set.of("user-1"),
            "PayloadType",
            List.of(),
            Map.of("user-1", 0.9));

    assertEquals(target, bp.target());
    assertEquals("MyResolution", bp.resolutionTypeName());
    assertEquals("Review Title", bp.resolvedTitle());
    assertEquals(Set.of("managers"), bp.resolvedCandidateGroups());
  }

  @Test
  void bindingPayloadCompactConstructor() {
    var target = JudgmentTarget.builder().prompt("Assess").build();
    var bp = new JudgmentPayload.BindingPayload(target, Map.of(), null, null);

    assertNull(bp.caseBudgetDeadline());
    assertNull(bp.resolvedTitle());
    assertNull(bp.resolvedScope());
    assertEquals(List.of(), bp.experiences());
    assertEquals(Map.of(), bp.candidateScores());
  }

  @Test
  void gatePayload() {
    var action = PlannedAction.of("File SAR", "sar.file", Map.of("acc", "123"));
    var gate =
        new RiskDecision.GateRequired("High risk transaction", false, null, null, null, null, null);
    var gp =
        new JudgmentPayload.GatePayload(42L, action, gate, Set.of("compliance"), "ApprovalResult");

    assertEquals(42L, gp.gateId());
    assertEquals(action, gp.plannedAction());
    assertEquals(gate, gp.gateRequired());
    assertNull(gp.deferredOutput());
  }

  @Test
  void sealedTypeExhaustiveness() {
    var target = JudgmentTarget.builder().prompt("x").build();
    JudgmentPayload payload = new JudgmentPayload.BindingPayload(target, Map.of(), null, null);
    String result =
        switch (payload) {
          case JudgmentPayload.BindingPayload bp -> "binding:" + bp.target().prompt();
          case JudgmentPayload.GatePayload gp -> "gate:" + gp.gateId();
        };
    assertEquals("binding:x", result);
  }
}
