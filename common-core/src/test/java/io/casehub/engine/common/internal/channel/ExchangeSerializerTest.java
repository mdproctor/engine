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
package io.casehub.engine.common.internal.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.context.ContextBridge;
import io.casehub.api.spi.DataRefResolver;
import io.casehub.engine.common.internal.context.BridgeResolver;
import io.casehub.engine.common.internal.context.DataRefRegistry;
import io.casehub.worker.api.Exchange;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import java.lang.annotation.Annotation;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExchangeSerializerTest {

  public record TestPojo(String name, int value) {}

  private ExchangeSerializer serializer;

  @BeforeEach
  void setUp() {
    BridgeResolver bridgeResolver =
        new BridgeResolver(emptyBridges(), new DataRefRegistry(emptyResolvers()));
    serializer = new ExchangeSerializer(bridgeResolver, new ObjectMapper());
  }

  @Test
  void roundTripWithMapBody() {
    Exchange<Map> original = Exchange.of(Map.of("key", "value"));

    Map<String, Object> metadata = serializer.toMetadata(original, Map.class);
    Exchange<Map> restored = serializer.fromMetadata(metadata, Map.class);

    assertThat(restored.body()).isEqualTo(Map.of("key", "value"));
  }

  @Test
  void roundTripWithPojoBody() {
    Exchange<TestPojo> original = Exchange.of(new TestPojo("alice", 42));

    Map<String, Object> metadata = serializer.toMetadata(original, TestPojo.class);
    Exchange<TestPojo> restored = serializer.fromMetadata(metadata, TestPojo.class);

    assertThat(restored.body()).isEqualTo(new TestPojo("alice", 42));
  }

  @Test
  void nullBodyPreserved() {
    Exchange<String> original = Exchange.of(null, Map.of("signal", "ping"));

    Map<String, Object> metadata = serializer.toMetadata(original, String.class);
    Exchange<String> restored = serializer.fromMetadata(metadata, String.class);

    assertThat(restored.body()).isNull();
  }

  @Test
  void headersPreservedAcrossRoundTrip() {
    Exchange<String> original =
        Exchange.of("data", Map.of("correlationId", "abc-123", "source", "system-a"));

    Map<String, Object> metadata = serializer.toMetadata(original, String.class);
    Exchange<String> restored = serializer.fromMetadata(metadata, String.class);

    assertThat(restored.headers())
        .containsEntry("correlationId", "abc-123")
        .containsEntry("source", "system-a");
  }

  @Test
  void propertiesExcludedFromSerialization() {
    Exchange<String> original =
        new Exchange<>("data", Map.of("header", "kept"), Map.of("loopCount", 3));

    Map<String, Object> metadata = serializer.toMetadata(original, String.class);

    assertThat(metadata).doesNotContainKey("exchangeProperties");
  }

  @Test
  void propertiesEmptyAfterDeserialization() {
    Exchange<String> original =
        new Exchange<>("data", Map.of("header", "kept"), Map.of("loopCount", 3));

    Map<String, Object> metadata = serializer.toMetadata(original, String.class);
    Exchange<String> restored = serializer.fromMetadata(metadata, String.class);

    assertThat(restored.properties()).isEmpty();
  }

  @Test
  void bodyTypeNameStoredInMetadata() {
    Exchange<TestPojo> exchange = Exchange.of(new TestPojo("test", 1));

    Map<String, Object> metadata = serializer.toMetadata(exchange, TestPojo.class);

    assertThat(metadata.get("exchangeBodyType")).isEqualTo(TestPojo.class.getName());
  }

  @Test
  void emptyHeadersHandled() {
    Exchange<String> original = Exchange.of("data");

    Map<String, Object> metadata = serializer.toMetadata(original, String.class);
    Exchange<String> restored = serializer.fromMetadata(metadata, String.class);

    assertThat(restored.headers()).isEmpty();
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static Instance<ContextBridge<?>> emptyBridges() {
    return new Instance<>() {
      @Override
      public Instance<ContextBridge<?>> select(Annotation... q) {
        return this;
      }

      @Override
      public <U extends ContextBridge<?>> Instance<U> select(Class<U> s, Annotation... q) {
        return null;
      }

      @Override
      public <U extends ContextBridge<?>> Instance<U> select(TypeLiteral<U> s, Annotation... q) {
        return null;
      }

      @Override
      public boolean isUnsatisfied() {
        return true;
      }

      @Override
      public boolean isAmbiguous() {
        return false;
      }

      @Override
      public boolean isResolvable() {
        return false;
      }

      @Override
      public ContextBridge<?> get() {
        throw new UnsupportedOperationException();
      }

      @Override
      public void destroy(ContextBridge<?> i) {}

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
        return Stream.empty();
      }

      @Override
      public Iterator<ContextBridge<?>> iterator() {
        return List.<ContextBridge<?>>of().iterator();
      }
    };
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static Instance<DataRefResolver> emptyResolvers() {
    return new Instance<>() {
      @Override
      public Instance<DataRefResolver> select(Annotation... q) {
        return this;
      }

      @Override
      public <U extends DataRefResolver> Instance<U> select(Class<U> s, Annotation... q) {
        return null;
      }

      @Override
      public <U extends DataRefResolver> Instance<U> select(TypeLiteral<U> s, Annotation... q) {
        return null;
      }

      @Override
      public boolean isUnsatisfied() {
        return true;
      }

      @Override
      public boolean isAmbiguous() {
        return false;
      }

      @Override
      public boolean isResolvable() {
        return false;
      }

      @Override
      public DataRefResolver get() {
        throw new UnsupportedOperationException();
      }

      @Override
      public void destroy(DataRefResolver i) {}

      @Override
      public Handle<DataRefResolver> getHandle() {
        return null;
      }

      @Override
      public Iterable<Handle<DataRefResolver>> handles() {
        return null;
      }

      @Override
      public Stream<DataRefResolver> stream() {
        return Stream.empty();
      }

      @Override
      public Iterator<DataRefResolver> iterator() {
        return List.<DataRefResolver>of().iterator();
      }
    };
  }
}
