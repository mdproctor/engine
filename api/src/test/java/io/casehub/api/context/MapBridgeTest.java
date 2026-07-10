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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MapBridgeTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final MapBridge bridge = new MapBridge();

  @Test
  void initialiseReturnsNarrowedInputAsMap() {
    var narrowed = MAPPER.valueToTree(Map.of("a", 1, "b", "two"));
    Map<String, Object> result = bridge.initialise(null, narrowed);
    assertThat(result).containsEntry("a", 1).containsEntry("b", "two").hasSize(2);
  }

  @Test
  void serialiseAndDeserialiseRoundTrip() {
    Map<String, Object> input = Map.of("key", "value", "num", 42);
    var serialised = bridge.serialise(input);
    Map<String, Object> deserialised = bridge.deserialise(serialised);
    assertThat(deserialised).containsEntry("key", "value");
    assertThat(deserialised.get("num")).isEqualTo(42);
  }

  @Test
  void contextTypeIsMap() {
    assertThat(bridge.contextType()).isEqualTo(Map.class);
  }

  @Test
  void isNotLiveView() {
    assertThat(bridge.isLiveView()).isFalse();
  }

  @Test
  void extractOutputReturnsNull() {
    assertThat(bridge.extractOutput(Map.of())).isNull();
  }

  @Test
  void initialiseWithEmptyObjectReturnsEmptyMap() {
    var empty = MAPPER.valueToTree(Map.of());
    assertThat(bridge.initialise(null, empty)).isEmpty();
  }

  @Test
  void serialisePreservesNestedStructure() {
    Map<String, Object> nested = Map.of("outer", Map.of("inner", "value"));
    var serialised = bridge.serialise(nested);
    Map<String, Object> result = bridge.deserialise(serialised);
    assertThat(result).containsKey("outer");
  }
}
