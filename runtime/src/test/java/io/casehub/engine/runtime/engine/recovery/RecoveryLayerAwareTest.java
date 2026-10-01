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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.context.ContextLayer;
import io.casehub.engine.internal.context.CaseContextImpl;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecoveryLayerAwareTest {

  @Test
  void fromLayerDocument_reconstructsWorkingLayer() {
    CaseContextImpl original = new CaseContextImpl();
    original.set("result", "done");
    original.set("score", 42);

    var doc = original.asJsonNode();
    CaseContextImpl recovered = CaseContextImpl.fromLayerDocument(doc);

    assertEquals("done", recovered.get("result"));
    assertEquals(42, recovered.getAs("score", Integer.class));
  }

  @Test
  void fromLayerDocument_reconstructsSemanticLayer() {
    CaseContextImpl original = new CaseContextImpl();
    original.writableLayer(ContextLayer.SEMANTIC).set("threshold", 0.8);
    original.freezeLayer(ContextLayer.SEMANTIC);

    var doc = original.asJsonNode();
    CaseContextImpl recovered = CaseContextImpl.fromLayerDocument(doc);

    assertEquals(0.8, recovered.layer(ContextLayer.SEMANTIC).getAs("threshold", Double.class));
  }

  @Test
  void fromLayerDocument_reconstructsEpisodicLayer() {
    CaseContextImpl original = new CaseContextImpl();
    original.writableLayer(ContextLayer.EPISODIC).set("milestones", List.of("data-ready"));

    var doc = original.asJsonNode();
    CaseContextImpl recovered = CaseContextImpl.fromLayerDocument(doc);

    var milestones = recovered.layer(ContextLayer.EPISODIC).getList("milestones", String.class);
    assertTrue(milestones.contains("data-ready"));
  }

  @Test
  void fromLayerDocument_nullPayload_returnsEmptyContext() {
    CaseContextImpl ctx = CaseContextImpl.fromLayerDocument(null);
    assertTrue(ctx.isEmpty()); // working layer is empty
  }

  @Test
  void fromLayerDocument_presentsWorkingKeysThroughFlatApi() {
    CaseContextImpl original = new CaseContextImpl();
    original.set("result", "done");

    CaseContextImpl recovered = CaseContextImpl.fromLayerDocument(original.asJsonNode());
    // Flat API should work — delegates to working layer
    assertEquals("done", recovered.get("result"));
    assertEquals("done", recovered.getString("result"));
  }
}
