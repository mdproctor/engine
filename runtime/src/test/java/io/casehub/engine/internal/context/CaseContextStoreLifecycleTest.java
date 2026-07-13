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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.context.CaseContextStore;
import io.casehub.api.context.CaseContextStoreFactory;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CaseContextStoreLifecycleTest {

  @Test
  void closeCallsStoreCloseOnAllLayers() {
    AtomicInteger closeCalls = new AtomicInteger(0);

    CaseContextStoreFactory factory =
        new CaseContextStoreFactory() {
          @Override
          public String id() {
            return "tracking";
          }

          @Override
          public CaseContextStore createStore(String layerName, UUID caseId) {
            return new InMemoryCaseContextStore() {
              @Override
              public void close() {
                closeCalls.incrementAndGet();
              }
            };
          }
        };

    CaseContextImpl ctx = new CaseContextImpl(factory, UUID.randomUUID());
    ctx.set("k", "v");
    assertEquals("v", ctx.get("k"));

    ctx.close();

    assertEquals(3, closeCalls.get(), "close() should be called for all 3 builtin layers");
  }

  @Test
  void closeIsSafeWhenStoreThrows() {
    AtomicInteger closeCalls = new AtomicInteger(0);

    CaseContextStoreFactory factory =
        new CaseContextStoreFactory() {
          @Override
          public String id() {
            return "throwing";
          }

          @Override
          public CaseContextStore createStore(String layerName, UUID caseId) {
            return new InMemoryCaseContextStore() {
              @Override
              public void close() {
                closeCalls.incrementAndGet();
                throw new RuntimeException("close failure for " + layerName);
              }
            };
          }
        };

    CaseContextImpl ctx = new CaseContextImpl(factory, UUID.randomUUID());
    ctx.close();

    assertEquals(3, closeCalls.get(), "close() called for all layers despite exceptions");
  }

  @Test
  void closeIsIdempotent() {
    CaseContextImpl ctx = new CaseContextImpl();
    ctx.close();
    ctx.close();
  }

  @Test
  void onDemandLayerAlsoClosed() {
    AtomicInteger closeCalls = new AtomicInteger(0);

    CaseContextStoreFactory factory =
        new CaseContextStoreFactory() {
          @Override
          public String id() {
            return "counting";
          }

          @Override
          public CaseContextStore createStore(String layerName, UUID caseId) {
            return new InMemoryCaseContextStore() {
              @Override
              public void close() {
                closeCalls.incrementAndGet();
              }
            };
          }
        };

    CaseContextImpl ctx = new CaseContextImpl(factory, UUID.randomUUID());
    ctx.writableLayer("custom-layer");

    ctx.close();

    assertEquals(4, closeCalls.get(), "3 builtin + 1 custom layer closed");
  }

  @Test
  void mutableCaseContextCloseDefaultIsNoOp() {
    io.casehub.api.context.MutableCaseContext mctx = new CaseContextImpl();
    mctx.close();
    assertTrue(true, "default close() should not throw");
  }
}
