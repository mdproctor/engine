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
package io.casehub.engine.internal.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.context.ContextPanel;
import io.casehub.api.context.ReadOnlyPanelException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SemanticPanelTest {

  @Test
  void semanticPanel_populatedFromMap() {
    CaseContextImpl ctx = new CaseContextImpl();
    ctx.writablePanel(ContextPanel.SEMANTIC).set("threshold", 0.8).set("domain", "fraud-check");
    ctx.freezePanel(ContextPanel.SEMANTIC);

    assertEquals(0.8, ctx.panel(ContextPanel.SEMANTIC).getAs("threshold", Double.class));
    assertEquals("fraud-check", ctx.panel(ContextPanel.SEMANTIC).getString("domain"));
  }

  @Test
  void semanticPanel_readOnlyAfterFreeze() {
    CaseContextImpl ctx = new CaseContextImpl();
    ctx.writablePanel(ContextPanel.SEMANTIC).set("key", "val");
    ctx.freezePanel(ContextPanel.SEMANTIC);

    assertTrue(ctx.panel(ContextPanel.SEMANTIC).isReadOnly());
    // Read still works
    assertEquals("val", ctx.panel(ContextPanel.SEMANTIC).get("key"));
    // Write via writablePanel throws
    assertThrows(
        ReadOnlyPanelException.class, () -> ctx.writablePanel(ContextPanel.SEMANTIC).set("k", "v"));
  }

  @Test
  void semanticPanel_inAsJsonNode() {
    CaseContextImpl ctx = new CaseContextImpl();
    ctx.writablePanel(ContextPanel.SEMANTIC).set("domain", "fraud-check");
    ctx.freezePanel(ContextPanel.SEMANTIC);

    var doc = ctx.asJsonNode();
    assertEquals("fraud-check", doc.get("semantic").get("domain").asText());
  }

  @Test
  void callSiteSemanticData_overridesDefinitionDefaults() {
    CaseContextImpl ctx = new CaseContextImpl();
    // Definition defaults first
    ctx.writablePanel(ContextPanel.SEMANTIC).setAll(Map.of("threshold", 0.8, "domain", "fraud"));
    // Call-site overrides second
    ctx.writablePanel(ContextPanel.SEMANTIC).setAll(Map.of("threshold", 0.9));
    ctx.freezePanel(ContextPanel.SEMANTIC);

    assertEquals(0.9, ctx.panel(ContextPanel.SEMANTIC).getAs("threshold", Double.class));
    assertEquals("fraud", ctx.panel(ContextPanel.SEMANTIC).getString("domain")); // from definition
  }
}
