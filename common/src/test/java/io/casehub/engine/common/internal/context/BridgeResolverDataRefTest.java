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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.context.ContextBridge;
import io.casehub.api.context.DataRef;
import io.casehub.api.context.MapBridge;
import io.casehub.api.spi.DataRefResolver;
import jakarta.enterprise.inject.Instance;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BridgeResolverDataRefTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private BridgeResolver bridgeResolver;

  @BeforeEach
  void setUp() {
    DataRefResolver testResolver =
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

    @SuppressWarnings("unchecked")
    Instance<ContextBridge<?>> emptyBridges = new DataRefRegistryTest.StubInstance<>();
    DataRefRegistry registry =
        new DataRefRegistry(new DataRefRegistryTest.StubInstance<>(testResolver));
    bridgeResolver = new BridgeResolver(emptyBridges, registry);
  }

  @Test
  void deserialise_resolves_dataRef() {
    var ref = DataRef.of("test-store", "key-1", String.class);
    JsonNode payload = ref.toJson(MAPPER);

    ContextBridge<?> dummyBridge = new MapBridge();
    Object result = bridgeResolver.deserialise(dummyBridge, payload);

    assertThat(result).isEqualTo("resolved:key-1");
  }

  @Test
  void deserialise_delegates_to_bridge_for_non_ref() {
    JsonNode payload = MAPPER.valueToTree(Map.of("name", "test"));
    MapBridge bridge = new MapBridge();

    Object result = bridgeResolver.deserialise(bridge, payload);

    assertThat(result).isInstanceOf(Map.class);
    assertThat(((Map<?, ?>) result).get("name")).isEqualTo("test");
  }

  @Test
  void serialise_passes_dataRef_through() {
    var ref = DataRef.of("test-store", "key-1", String.class);
    MapBridge bridge = new MapBridge();

    JsonNode result = bridgeResolver.serialise(bridge, ref);

    assertThat(DataRef.isRef(result)).isTrue();
    assertThat(result.get("$dataRef").get("key").asText()).isEqualTo("key-1");
  }

  @Test
  void initialise_passes_dataRef_through_for_deferred_resolution() {
    var ref = DataRef.of("test-store", "key-1", String.class);
    JsonNode refJson = ref.toJson(MAPPER);

    MapBridge bridge = new MapBridge();
    Object result = bridgeResolver.initialise(bridge, null, refJson);

    assertThat(result).isInstanceOf(DataRef.class);
    assertThat(((DataRef<?>) result).key()).isEqualTo("key-1");
  }

  @Test
  void serialise_delegates_to_bridge_for_non_dataRef() {
    MapBridge bridge = new MapBridge();
    Map<String, Object> data = Map.of("foo", "bar");

    JsonNode result = bridgeResolver.serialise(bridge, data);

    assertThat(result.get("foo").asText()).isEqualTo("bar");
    assertThat(DataRef.isRef(result)).isFalse();
  }
}
