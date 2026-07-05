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

import io.casehub.api.context.ContextLayer;
import io.casehub.api.context.ReadOnlyLayerException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SemanticLayerTest {

  @Test
  void semanticLayer_populatedFromMap() {
    CaseContextImpl ctx = new CaseContextImpl();
    ctx.writableLayer(ContextLayer.SEMANTIC).set("threshold", 0.8).set("domain", "fraud-check");
    ctx.freezeLayer(ContextLayer.SEMANTIC);

    assertEquals(0.8, ctx.layer(ContextLayer.SEMANTIC).getAs("threshold", Double.class));
    assertEquals("fraud-check", ctx.layer(ContextLayer.SEMANTIC).getString("domain"));
  }

  @Test
  void semanticLayer_readOnlyAfterFreeze() {
    CaseContextImpl ctx = new CaseContextImpl();
    ctx.writableLayer(ContextLayer.SEMANTIC).set("key", "val");
    ctx.freezeLayer(ContextLayer.SEMANTIC);

    assertTrue(ctx.layer(ContextLayer.SEMANTIC).isReadOnly());
    // Read still works
    assertEquals("val", ctx.layer(ContextLayer.SEMANTIC).get("key"));
    // Write via writableLayer throws
    assertThrows(
        ReadOnlyLayerException.class, () -> ctx.writableLayer(ContextLayer.SEMANTIC).set("k", "v"));
  }

  @Test
  void semanticLayer_inAsJsonNode() {
    CaseContextImpl ctx = new CaseContextImpl();
    ctx.writableLayer(ContextLayer.SEMANTIC).set("domain", "fraud-check");
    ctx.freezeLayer(ContextLayer.SEMANTIC);

    var doc = ctx.asJsonNode();
    assertEquals("fraud-check", doc.get("semantic").get("domain").asText());
  }

  @Test
  void callSiteSemanticData_overridesDefinitionDefaults() {
    CaseContextImpl ctx = new CaseContextImpl();
    // Definition defaults first
    ctx.writableLayer(ContextLayer.SEMANTIC).setAll(Map.of("threshold", 0.8, "domain", "fraud"));
    // Call-site overrides second
    ctx.writableLayer(ContextLayer.SEMANTIC).setAll(Map.of("threshold", 0.9));
    ctx.freezeLayer(ContextLayer.SEMANTIC);

    assertEquals(0.9, ctx.layer(ContextLayer.SEMANTIC).getAs("threshold", Double.class));
    assertEquals("fraud", ctx.layer(ContextLayer.SEMANTIC).getString("domain")); // from definition
  }
}
