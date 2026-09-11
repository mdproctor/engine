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

import io.casehub.api.context.DataRef;
import io.casehub.api.spi.DataRefResolver;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

class DataRefRegistryTest {

  @Test
  void resolves_via_matching_source() {
    DataRefResolver resolver =
        new DataRefResolver() {
          @Override
          public String id() {
            return "test-store";
          }

          @SuppressWarnings("unchecked")
          @Override
          public <T> T resolve(DataRef<T> ref) {
            return (T) ("resolved:" + ref.key());
          }
        };

    DataRefRegistry registry = new DataRefRegistry(new StubInstance<>(resolver));
    Object result = registry.resolve(DataRef.of("test-store", "key1", String.class));
    assertThat(result).isEqualTo("resolved:key1");
  }

  @Test
  void throws_on_unknown_source() {
    DataRefRegistry registry = new DataRefRegistry(new StubInstance<>());
    assertThatThrownBy(() -> registry.resolve(DataRef.of("unknown", "key", String.class)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("No DataRefResolver for source: unknown");
  }

  @SafeVarargs
  private static <T> StubInstance<T> stubOf(T... items) {
    return new StubInstance<>(items);
  }

  static class StubInstance<T> implements jakarta.enterprise.inject.Instance<T> {
    private final java.util.List<T> items;

    @SafeVarargs
    StubInstance(T... items) {
      this.items = java.util.List.of(items);
    }

    @Override
    public java.util.stream.Stream<T> stream() {
      return items.stream();
    }

    @Override
    public Instance<T> select(java.lang.annotation.Annotation... qualifiers) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <U extends T> Instance<U> select(
        Class<U> subtype, java.lang.annotation.Annotation... qualifiers) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <U extends T> Instance<U> select(
        jakarta.enterprise.util.TypeLiteral<U> subtype,
        java.lang.annotation.Annotation... qualifiers) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isUnsatisfied() {
      return items.isEmpty();
    }

    @Override
    public boolean isAmbiguous() {
      return items.size() > 1;
    }

    @Override
    public void destroy(T instance) {}

    @Override
    public Handle<T> getHandle() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Iterable<? extends Handle<T>> handles() {
      throw new UnsupportedOperationException();
    }

    @Override
    public T get() {
      return items.get(0);
    }

    @Override
    public java.util.Iterator<T> iterator() {
      return items.iterator();
    }

    @Override
    public boolean isResolvable() {
      return items.size() == 1;
    }
  }
}
