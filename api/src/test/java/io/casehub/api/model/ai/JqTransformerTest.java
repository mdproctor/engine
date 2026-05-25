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
package io.casehub.api.model.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class JqTransformerTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void extractsFields() throws Exception {
    JqTransformer transformer = new JqTransformer("{ documentId: .documentId, status: .status }");
    JsonNode input =
        mapper.readTree(
            "{\"documentId\": \"abc\", \"status\": \"active\", \"extra\": \"ignored\"}");

    JsonNode result = transformer.apply(input);

    assertEquals("abc", result.get("documentId").asText());
    assertEquals("active", result.get("status").asText());
    assertNull(result.get("extra"));
  }

  @Test
  void identityFilter() throws Exception {
    JqTransformer transformer = new JqTransformer(".");
    JsonNode input = mapper.readTree("{\"key\": \"value\"}");

    JsonNode result = transformer.apply(input);

    assertEquals(input, result);
  }

  @Test
  void invalidJqThrowsOnConstruction() {
    assertThrows(
        IllegalArgumentException.class, () -> new JqTransformer("this is not valid jq !!!"));
  }

  @Test
  void emptyResult_throwsAgentException() {
    JqTransformer transformer = new JqTransformer("empty");
    JsonNode input = mapper.createObjectNode().put("key", "value");

    assertThrows(AgentException.class, () -> transformer.apply(input));
  }

  @Test
  void runtimeJqError_throwsAgentException() {
    JqTransformer transformer = new JqTransformer(".foo | .bar");
    JsonNode input = mapper.createObjectNode().put("foo", "not-an-object");

    assertThrows(AgentException.class, () -> transformer.apply(input));
  }

  @Test
  void multipleResults_returnsFirst() {
    JqTransformer transformer = new JqTransformer(".[]");
    JsonNode input = mapper.createArrayNode().add("first").add("second").add("third");

    JsonNode result = transformer.apply(input);

    assertEquals("first", result.asText());
  }
}
