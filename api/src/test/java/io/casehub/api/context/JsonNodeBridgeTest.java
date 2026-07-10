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

class JsonNodeBridgeTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final JsonNodeBridge bridge = new JsonNodeBridge();

  @Test
  void initialiseReturnsNarrowedInputDirectly() {
    var node = MAPPER.valueToTree(Map.of("a", 1));
    assertThat(bridge.initialise(null, node)).isSameAs(node);
  }

  @Test
  void serialiseIsIdentity() {
    var node = MAPPER.valueToTree(Map.of("key", "value"));
    assertThat(bridge.serialise(node)).isSameAs(node);
  }

  @Test
  void deserialiseIsIdentity() {
    var node = MAPPER.valueToTree(Map.of("key", "value"));
    assertThat(bridge.deserialise(node)).isSameAs(node);
  }

  @Test
  void contextTypeIsJsonNode() {
    assertThat(bridge.contextType()).isEqualTo(com.fasterxml.jackson.databind.JsonNode.class);
  }

  @Test
  void isNotLiveView() {
    assertThat(bridge.isLiveView()).isFalse();
  }

  @Test
  void extractOutputReturnsNull() {
    var node = MAPPER.valueToTree(Map.of());
    assertThat(bridge.extractOutput(node)).isNull();
  }
}
