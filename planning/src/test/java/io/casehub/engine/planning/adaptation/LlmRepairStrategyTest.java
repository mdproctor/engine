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
package io.casehub.engine.planning.adaptation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.TaskStatus;
import io.casehub.engine.plan.adaptation.AdaptationCause;
import io.casehub.engine.plan.adaptation.AdaptationContext;
import io.casehub.engine.plan.adaptation.RepairStrategy;
import io.casehub.engine.plan.adaptation.RevisionContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LlmRepairStrategyTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void idReturnsLlmRepair() {
    var strategy = new LlmRepairStrategy(Optional.empty());
    assertEquals("llm-repair", strategy.id());
  }

  @Test
  void implementsRepairStrategy() {
    assertTrue(RepairStrategy.class.isAssignableFrom(LlmRepairStrategy.class));
  }

  @Test
  void throwsWhenNoChatModelProvider() {
    var strategy = new LlmRepairStrategy(Optional.empty());

    ObjectNode context = MAPPER.createObjectNode();
    var definition = mock(CaseDefinition.class);

    var adaptCtx =
        new AdaptationContext(
            UUID.randomUUID(),
            "t1",
            "c1",
            "c1",
            List.of(),
            List.of(),
            List.of(),
            context,
            definition,
            TaskStatus.FAULTED,
            "step-a",
            0);

    var cause = new AdaptationCause.StepFailed("step-a", "Knowledge failure");
    var revisionCtx = new RevisionContext(adaptCtx, cause, List.of(), List.of());

    assertThrows(UnsupportedOperationException.class, () -> strategy.revise(revisionCtx));
  }

  @Test
  void extractCritiqueReturnsCritiqueWhenPresent() {
    ObjectNode context = MAPPER.createObjectNode();
    ObjectNode diagnostics = MAPPER.createObjectNode();
    ObjectNode bindingDiag = MAPPER.createObjectNode();
    bindingDiag.put("critique", "Agent lacked domain knowledge");
    diagnostics.set("step-a", bindingDiag);
    context.set("_diagnostics", diagnostics);

    assertEquals(
        "Agent lacked domain knowledge", LlmRepairStrategy.extractCritique(context, "step-a"));
  }

  @Test
  void extractCritiqueReturnsNullWhenMissing() {
    ObjectNode context = MAPPER.createObjectNode();
    assertNull(LlmRepairStrategy.extractCritique(context, "step-a"));
    assertNull(LlmRepairStrategy.extractCritique(null, "step-a"));
  }

  @Test
  void extractCritiqueReturnsNullForUnknownBinding() {
    ObjectNode context = MAPPER.createObjectNode();
    ObjectNode diagnostics = MAPPER.createObjectNode();
    context.set("_diagnostics", diagnostics);

    assertNull(LlmRepairStrategy.extractCritique(context, "unknown"));
  }
}
