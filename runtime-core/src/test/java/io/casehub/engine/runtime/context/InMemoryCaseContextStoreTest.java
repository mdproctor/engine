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
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.casehub.api.context.CaseContextStore;
import io.casehub.api.context.CaseContextStoreContractTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryCaseContextStoreTest extends CaseContextStoreContractTest {

  @Override
  protected CaseContextStore createStore() {
    return new InMemoryCaseContextStore();
  }

  @Test
  void constructWithInitialData() {
    var store = new InMemoryCaseContextStore(Map.of("a", 1, "b", 2));
    assertEquals(1, store.get("a"));
    assertEquals(2, store.get("b"));
    assertEquals(2, store.size());
  }

  @Test
  void initialDataIsCopied() {
    var initial = new java.util.LinkedHashMap<String, Object>();
    initial.put("a", 1);
    var store = new InMemoryCaseContextStore(initial);
    initial.put("b", 2);
    assertFalse(store.containsKey("b"));
  }
}
