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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.context.CaseContextStoreFactory;
import io.casehub.api.context.ContextLayer;
import io.casehub.api.context.MutableCaseContext;
import io.casehub.api.context.ReadOnlyLayerException;
import io.casehub.api.context.WritableLayer;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MutableCaseContextTest {

  @Test
  void writableLayerReturnsWritableLayer() {
    MutableCaseContext ctx = new CaseContextImpl();
    WritableLayer layer = ctx.writableLayer(ContextLayer.WORKING);
    assertNotNull(layer);
    layer.set("k", "v");
    assertEquals("v", ctx.get("k"));
  }

  @Test
  void freezeLayerPreventsWrites() {
    MutableCaseContext ctx = new CaseContextImpl();
    ctx.writableLayer(ContextLayer.SEMANTIC).set("k", "v");
    ctx.freezeLayer(ContextLayer.SEMANTIC);
    assertThrows(
        ReadOnlyLayerException.class,
        () -> ctx.writableLayer(ContextLayer.SEMANTIC).set("k2", "v2"));
  }

  @Test
  void customFactory() {
    CaseContextStoreFactory factory = InMemoryCaseContextStoreFactory.INSTANCE;
    CaseContextImpl ctx = new CaseContextImpl(factory, UUID.randomUUID());
    ctx.set("k", "v");
    assertEquals("v", ctx.get("k"));
  }

  @Test
  void onDemandLayerUsesFactory() {
    CaseContextStoreFactory factory = InMemoryCaseContextStoreFactory.INSTANCE;
    MutableCaseContext ctx = new CaseContextImpl(factory, null);
    WritableLayer custom = ctx.writableLayer("custom-layer");
    assertNotNull(custom);
    custom.set("k", "v");
    assertEquals("v", ctx.layer("custom-layer").get("k"));
  }

  @Test
  void backwardCompatibleConstructors() {
    CaseContextImpl ctx1 = new CaseContextImpl();
    assertTrue(ctx1.isEmpty());

    CaseContextImpl ctx2 = new CaseContextImpl(Map.of("k", "v"));
    assertEquals("v", ctx2.get("k"));
  }

  @Test
  void implementsMutableCaseContext() {
    CaseContextImpl ctx = new CaseContextImpl();
    assertTrue(ctx instanceof MutableCaseContext);
  }
}
