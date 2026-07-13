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
package io.casehub.api.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public abstract class CaseContextStoreContractTest {

  protected abstract CaseContextStore createStore();

  private CaseContextStore store;

  @BeforeEach
  void setUp() {
    store = createStore();
  }

  @Test
  void putAndGet() {
    assertNull(store.put("k", "v1"));
    assertEquals("v1", store.get("k"));
  }

  @Test
  void putReturnsPrevious() {
    store.put("k", "v1");
    assertEquals("v1", store.put("k", "v2"));
    assertEquals("v2", store.get("k"));
  }

  @Test
  void getMissing() {
    assertNull(store.get("absent"));
  }

  @Test
  void remove() {
    store.put("k", "v");
    assertEquals("v", store.remove("k"));
    assertNull(store.get("k"));
    assertFalse(store.containsKey("k"));
  }

  @Test
  void removeAbsent() {
    assertNull(store.remove("absent"));
  }

  @Test
  void containsKey() {
    assertFalse(store.containsKey("k"));
    store.put("k", "v");
    assertTrue(store.containsKey("k"));
  }

  @Test
  void keySet() {
    store.put("a", 1);
    store.put("b", 2);
    assertEquals(Set.of("a", "b"), store.keySet());
  }

  @Test
  void snapshotIsImmutable() {
    store.put("a", 1);
    store.put("b", 2);
    Map<String, Object> snap = store.snapshot();
    assertEquals(Map.of("a", 1, "b", 2), snap);
    assertThrows(UnsupportedOperationException.class, () -> snap.put("c", 3));
  }

  @Test
  void snapshotIsDetached() {
    store.put("a", 1);
    Map<String, Object> snap = store.snapshot();
    store.put("a", 2);
    assertEquals(1, snap.get("a"));
  }

  @Test
  void clear() {
    store.put("a", 1);
    store.put("b", 2);
    store.clear();
    assertTrue(store.isEmpty());
    assertEquals(0, store.size());
  }

  @Test
  void putAll() {
    store.putAll(Map.of("a", 1, "b", 2));
    assertEquals(1, store.get("a"));
    assertEquals(2, store.get("b"));
  }

  @Test
  void sizeAndIsEmpty() {
    assertTrue(store.isEmpty());
    assertEquals(0, store.size());
    store.put("k", "v");
    assertFalse(store.isEmpty());
    assertEquals(1, store.size());
  }

  @Test
  void closeIsIdempotent() throws Exception {
    store.close();
    store.close();
  }

  @Test
  void defaultsNoExternalChangeNotification() {
    assertFalse(store.supportsExternalChangeNotification());
  }

  @Test
  void defaultOnExternalChangeReturnsNoop() {
    Subscription sub = store.onExternalChange(e -> {});
    assertNotNull(sub);
    sub.cancel();
  }

  @Test
  void putNullValue() {
    store.put("k", null);
    assertNull(store.get("k"));
    assertTrue(store.containsKey("k"));
  }

  @Test
  void keySetIsDetached() {
    store.put("a", 1);
    Set<String> keys = store.keySet();
    store.put("b", 2);
    assertFalse(keys.contains("b"));
  }

  @Test
  void putAllOverwritesExisting() {
    store.put("a", 1);
    store.putAll(Map.of("a", 99, "b", 2));
    assertEquals(99, store.get("a"));
    assertEquals(2, store.get("b"));
  }
}
