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
package io.casehub.engine.runtime.context;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.casehub.api.context.CaseContextStore;
import io.casehub.api.context.CaseContextStoreFactory;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CaseContextImplLoadFromStoreTest {

  @Test
  void loadFromStoreCallsLoadStoreNotCreateStore() {
    AtomicInteger loadCalls = new AtomicInteger();
    AtomicInteger createCalls = new AtomicInteger();

    CaseContextStoreFactory factory =
        new CaseContextStoreFactory() {
          @Override
          public String id() {
            return "tracking";
          }

          @Override
          public CaseContextStore createStore(String layerName, UUID caseId) {
            createCalls.incrementAndGet();
            return new InMemoryCaseContextStore();
          }

          @Override
          public CaseContextStore loadStore(String layerName, UUID caseId) {
            loadCalls.incrementAndGet();
            InMemoryCaseContextStore store = new InMemoryCaseContextStore();
            store.put("loaded", true);
            return store;
          }

          @Override
          public boolean isDurable() {
            return true;
          }
        };

    UUID caseId = UUID.randomUUID();
    CaseContextImpl ctx = CaseContextImpl.loadFromStore(factory, caseId);

    assertEquals(3, loadCalls.get(), "loadStore called for working + semantic + episodic");
    assertEquals(0, createCalls.get(), "createStore must not be called");
    assertEquals(true, ctx.get("loaded"));
  }

  @Test
  void loadFromStoreUsesFactoryForNewLayers() {
    CaseContextStoreFactory factory =
        new CaseContextStoreFactory() {
          @Override
          public String id() {
            return "test";
          }

          @Override
          public CaseContextStore createStore(String layerName, UUID caseId) {
            InMemoryCaseContextStore store = new InMemoryCaseContextStore();
            store.put("created-by", "factory");
            return store;
          }

          @Override
          public boolean isDurable() {
            return true;
          }
        };

    UUID caseId = UUID.randomUUID();
    CaseContextImpl ctx = CaseContextImpl.loadFromStore(factory, caseId);

    var customLayer = ctx.writableLayer("custom");
    assertEquals("factory", customLayer.get("created-by"));
  }
}
