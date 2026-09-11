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
package io.casehub.engine.common.internal.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.api.context.ContextBridge;
import io.casehub.api.context.JacksonPojoBridge;
import io.casehub.api.context.MapBridge;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import java.lang.annotation.Annotation;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class BridgeResolverTest {

  record TestPojo(String value) {}

  @Test
  void resolveByType_mapClass_returnsMapBridge() {
    var resolver = new BridgeResolver(stubBridges(List.of()), noOpRegistry());
    var bridge = resolver.resolveByType(Map.class);
    assertThat(bridge).isInstanceOf(MapBridge.class);
  }

  @Test
  void resolveByType_unknownPojo_returnsJacksonBridge() {
    var resolver = new BridgeResolver(stubBridges(List.of()), noOpRegistry());
    var bridge = resolver.resolveByType(TestPojo.class);
    assertThat(bridge).isInstanceOf(JacksonPojoBridge.class);
  }

  @Test
  void resolveByTypeName_delegatesToResolveByType() {
    var resolver = new BridgeResolver(stubBridges(List.of()), noOpRegistry());
    var byType = resolver.resolveByType(Map.class);
    var byName = resolver.resolveByTypeName(Map.class.getName());
    assertThat(byType.getClass()).isEqualTo(byName.getClass());
  }

  @Test
  void resolveByTypeName_null_returnsMapBridge() {
    var resolver = new BridgeResolver(stubBridges(List.of()), noOpRegistry());
    assertThat(resolver.resolveByTypeName(null)).isInstanceOf(MapBridge.class);
  }

  @Test
  void resolveByTypeName_unknownClassName_returnsMapBridge() {
    var resolver = new BridgeResolver(stubBridges(List.of()), noOpRegistry());
    assertThat(resolver.resolveByTypeName("com.nonexistent.Foo")).isInstanceOf(MapBridge.class);
  }

  @Test
  void resolveByTypeNameStrict_throwsOnUnknownClass() {
    var resolver = new BridgeResolver(stubBridges(List.of()), noOpRegistry());
    assertThatThrownBy(() -> resolver.resolveByTypeNameStrict("com.nonexistent.FooBar"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("com.nonexistent.FooBar");
  }

  @Test
  void resolveByTypeNameStrict_returnsMapBridgeForMapClass() {
    var resolver = new BridgeResolver(stubBridges(List.of()), noOpRegistry());
    assertThat(resolver.resolveByTypeNameStrict(Map.class.getName())).isInstanceOf(MapBridge.class);
  }

  @Test
  void resolveByTypeNameStrict_throwsOnNull() {
    var resolver = new BridgeResolver(stubBridges(List.of()), noOpRegistry());
    assertThatThrownBy(() -> resolver.resolveByTypeNameStrict(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void resolveByType_cdiDiscoveredBridge_takesPriority() {
    var customBridge = new MapBridge();
    var resolver = new BridgeResolver(stubBridges(List.of(customBridge)), noOpRegistry());
    var result = resolver.resolveByType(Map.class);
    assertThat(result).isSameAs(customBridge);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static Instance<ContextBridge<?>> stubBridges(List<ContextBridge<?>> list) {
    return new Instance<>() {
      @Override
      public Instance<ContextBridge<?>> select(Annotation... qualifiers) {
        return this;
      }

      @Override
      public <U extends ContextBridge<?>> Instance<U> select(
          Class<U> subtype, Annotation... qualifiers) {
        return null;
      }

      @Override
      public <U extends ContextBridge<?>> Instance<U> select(
          TypeLiteral<U> subtype, Annotation... qualifiers) {
        return null;
      }

      @Override
      public boolean isUnsatisfied() {
        return list.isEmpty();
      }

      @Override
      public boolean isAmbiguous() {
        return false;
      }

      @Override
      public boolean isResolvable() {
        return !list.isEmpty();
      }

      @Override
      public ContextBridge<?> get() {
        return list.get(0);
      }

      @Override
      public void destroy(ContextBridge<?> instance) {}

      @Override
      public Handle<ContextBridge<?>> getHandle() {
        return null;
      }

      @Override
      public Iterable<Handle<ContextBridge<?>>> handles() {
        return null;
      }

      @Override
      public Stream<ContextBridge<?>> stream() {
        return (Stream) list.stream();
      }

      @Override
      public Iterator<ContextBridge<?>> iterator() {
        return list.iterator();
      }
    };
  }

  private static DataRefRegistry noOpRegistry() {
    return new DataRefRegistry(new DataRefRegistryTest.StubInstance<>());
  }
}
