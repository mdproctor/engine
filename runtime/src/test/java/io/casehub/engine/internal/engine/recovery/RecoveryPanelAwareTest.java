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
package io.casehub.engine.internal.engine.recovery;

import static org.junit.jupiter.api.Assertions.*;

import io.casehub.api.context.ContextPanel;
import io.casehub.engine.internal.context.CaseContextImpl;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecoveryPanelAwareTest {

  @Test
  void fromPanelDocument_reconstructsWorkingPanel() {
    CaseContextImpl original = new CaseContextImpl();
    original.set("result", "done");
    original.set("score", 42);

    var doc = original.asJsonNode();
    CaseContextImpl recovered = CaseContextImpl.fromPanelDocument(doc);

    assertEquals("done", recovered.get("result"));
    assertEquals(42, recovered.getAs("score", Integer.class));
  }

  @Test
  void fromPanelDocument_reconstructsSemanticPanel() {
    CaseContextImpl original = new CaseContextImpl();
    original.writablePanel(ContextPanel.SEMANTIC).set("threshold", 0.8);
    original.freezePanel(ContextPanel.SEMANTIC);

    var doc = original.asJsonNode();
    CaseContextImpl recovered = CaseContextImpl.fromPanelDocument(doc);

    assertEquals(0.8, recovered.panel(ContextPanel.SEMANTIC).getAs("threshold", Double.class));
  }

  @Test
  void fromPanelDocument_reconstructsEpisodicPanel() {
    CaseContextImpl original = new CaseContextImpl();
    original.writablePanel(ContextPanel.EPISODIC).set("milestones", List.of("data-ready"));

    var doc = original.asJsonNode();
    CaseContextImpl recovered = CaseContextImpl.fromPanelDocument(doc);

    var milestones = recovered.panel(ContextPanel.EPISODIC).getList("milestones", String.class);
    assertTrue(milestones.contains("data-ready"));
  }

  @Test
  void fromPanelDocument_nullPayload_returnsEmptyContext() {
    CaseContextImpl ctx = CaseContextImpl.fromPanelDocument(null);
    assertTrue(ctx.isEmpty()); // working panel is empty
  }

  @Test
  void fromPanelDocument_presentsWorkingKeysThroughFlatApi() {
    CaseContextImpl original = new CaseContextImpl();
    original.set("result", "done");

    CaseContextImpl recovered = CaseContextImpl.fromPanelDocument(original.asJsonNode());
    // Flat API should work — delegates to working panel
    assertEquals("done", recovered.get("result"));
    assertEquals("done", recovered.getString("result"));
  }
}
