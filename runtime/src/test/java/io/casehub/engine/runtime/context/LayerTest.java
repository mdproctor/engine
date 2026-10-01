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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.context.ContextLayer;
import io.casehub.api.context.ReadOnlyLayerException;
import io.casehub.api.context.ReadableLayer;
import io.casehub.api.context.WritableLayer;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LayerTest {

  @Test
  void layerName_returnsConstructedName() {
    WritableLayer p = new WritableLayerImpl(ContextLayer.WORKING);
    assertEquals("working", p.layerName());
  }

  @Test
  void isReadOnly_falseByDefault() {
    assertFalse(new WritableLayerImpl("custom").isReadOnly());
  }

  @Test
  void setAndGet_roundTrip() {
    WritableLayer p = new WritableLayerImpl(ContextLayer.WORKING);
    p.set("score", 42);
    assertEquals(42, p.get("score"));
  }

  @Test
  void getVersion_incrementsOnWrite() {
    WritableLayer p = new WritableLayerImpl(ContextLayer.WORKING);
    long before = p.getVersion();
    p.set("key", "val");
    assertEquals(before + 1, p.getVersion());
  }

  @Test
  void getVersion_noIncrementOnNoOp() {
    WritableLayer p = new WritableLayerImpl(ContextLayer.WORKING);
    p.set("key", "val");
    long before = p.getVersion();
    p.set("key", "val"); // same value
    assertEquals(before, p.getVersion());
  }

  @Test
  void clear_removesAllKeys() {
    WritableLayer p = new WritableLayerImpl(ContextLayer.WORKING);
    p.set("a", 1).set("b", 2);
    p.clear();
    assertTrue(p.isEmpty());
  }

  @Test
  void asJsonNode_representsData() {
    WritableLayer p = new WritableLayerImpl(ContextLayer.WORKING);
    p.set("result", "done");
    assertEquals("done", p.asJsonNode().get("result").asText());
  }

  @Test
  void applyAndDiff_returnsNonEmptyOnChange() {
    WritableLayer p = new WritableLayerImpl(ContextLayer.WORKING);
    p.set("score", 10);
    var diff = p.applyAndDiff("score", 20);
    assertTrue(diff.isPresent());
  }

  @Test
  void applyAndDiff_returnsEmptyOnNoChange() {
    WritableLayer p = new WritableLayerImpl(ContextLayer.WORKING);
    p.set("score", 10);
    var diff = p.applyAndDiff("score", 10);
    assertTrue(diff.isEmpty());
  }

  @Test
  void deepCopy_isDetached() {
    WritableLayerImpl original = new WritableLayerImpl(ContextLayer.WORKING);
    original.set("key", "value");
    WritableLayerImpl copy = original.deepCopy();
    copy.set("key", "modified");
    assertEquals("value", original.get("key")); // original unchanged
  }

  @Test
  void merge_copiesFromOtherLayer() {
    WritableLayerImpl p1 = new WritableLayerImpl(ContextLayer.WORKING);
    p1.set("a", 1);
    WritableLayerImpl p2 = new WritableLayerImpl(ContextLayer.WORKING);
    p2.set("b", 2);
    p1.merge(p2);
    assertEquals(1, p1.getAs("a", Integer.class));
    assertEquals(2, p1.getAs("b", Integer.class));
  }

  @Test
  void frozen_throwsOnSet() {
    WritableLayerImpl p = new WritableLayerImpl(ContextLayer.SEMANTIC);
    p.freeze();
    assertThrows(ReadOnlyLayerException.class, () -> p.set("key", "val"));
  }

  @Test
  void frozen_throwsOnSetAll() {
    WritableLayerImpl p = new WritableLayerImpl(ContextLayer.SEMANTIC);
    p.freeze();
    assertThrows(ReadOnlyLayerException.class, () -> p.setAll(Map.of("k", "v")));
  }

  @Test
  void frozen_throwsOnClear() {
    WritableLayerImpl p = new WritableLayerImpl(ContextLayer.SEMANTIC);
    p.freeze();
    assertThrows(ReadOnlyLayerException.class, p::clear);
  }

  @Test
  void frozen_allowsGet() {
    WritableLayerImpl p = new WritableLayerImpl(ContextLayer.SEMANTIC);
    p.set("key", "val");
    p.freeze();
    assertEquals("val", p.get("key")); // reads still work
  }

  @Test
  void frozen_isReadOnlyTrue() {
    WritableLayerImpl p = new WritableLayerImpl(ContextLayer.SEMANTIC);
    assertFalse(p.isReadOnly());
    p.freeze();
    assertTrue(p.isReadOnly());
  }

  @Test
  void notFrozen_isReadOnlyFalse() {
    assertFalse(new WritableLayerImpl(ContextLayer.WORKING).isReadOnly());
  }

  @Test
  void snapshot_isDetached() {
    WritableLayerImpl p = new WritableLayerImpl(ContextLayer.WORKING);
    p.set("key", "original");
    ReadableLayer snap = p.snapshot();
    p.set("key", "modified");
    assertEquals("original", snap.get("key")); // snapshot is unaffected
  }

  @Test
  void snapshot_preservesLayerName() {
    WritableLayerImpl p = new WritableLayerImpl(ContextLayer.SEMANTIC);
    ReadableLayer snap = p.snapshot();
    assertEquals(ContextLayer.SEMANTIC, snap.layerName());
  }

  @Test
  void constructor_deepCopiesInitialSubMaps() {
    Map<String, Object> initial = Map.of("pr", Map.of("headSha", "abc123"));
    WritableLayerImpl p = new WritableLayerImpl(ContextLayer.WORKING, initial);

    assertDoesNotThrow(() -> p.setPath("pr.headSha", "def456"));
    assertEquals("def456", p.getPath("pr.headSha"));
  }

  @Test
  void deepCopy_deepCopiesListsContainingMaps() {
    WritableLayerImpl p = new WritableLayerImpl(ContextLayer.WORKING);
    java.util.List<java.util.Map<String, Object>> workers = new java.util.ArrayList<>();
    java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
    entry.put("name", "extractor");
    entry.put("runs", 1);
    workers.add(entry);
    p.set("workers", workers);

    WritableLayerImpl copy = p.deepCopy();
    // Mutate original entry
    ((java.util.Map<String, Object>) ((java.util.List<?>) p.get("workers")).get(0)).put("runs", 99);
    // Copy should be unaffected
    assertEquals(
        1,
        ((Number)
                ((java.util.Map<?, ?>) ((java.util.List<?>) copy.get("workers")).get(0))
                    .get("runs"))
            .intValue());
  }
}
