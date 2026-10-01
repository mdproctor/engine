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
package io.casehub.engine.internal.diff;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/** Pure unit tests — no CDI, no Quarkus. */
class JsonPatchContextDiffStrategyTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final JsonPatchContextDiffStrategy strategy = new JsonPatchContextDiffStrategy();

  @Test
  void addedKey_producesAddOperation() {
    ObjectNode after = MAPPER.createObjectNode();
    after.put("status", "done");

    JsonNode diff = strategy.compute(MAPPER.createObjectNode(), after);

    assertThat(diff.isArray()).isTrue();
    assertThat(findOp(diff, "add", "/status")).isNotNull();
    assertThat(findOp(diff, "add", "/status").get("value").asText()).isEqualTo("done");
  }

  @Test
  void updatedKey_producesReplaceOperation() {
    ObjectNode before = MAPPER.createObjectNode();
    before.put("status", "processing");
    ObjectNode after = MAPPER.createObjectNode();
    after.put("status", "done");

    JsonNode diff = strategy.compute(before, after);

    assertThat(findOp(diff, "replace", "/status")).isNotNull();
    assertThat(findOp(diff, "replace", "/status").get("value").asText()).isEqualTo("done");
  }

  @Test
  void removedKey_producesRemoveOperation() {
    ObjectNode before = MAPPER.createObjectNode();
    before.put("tempKey", "bye");

    JsonNode diff = strategy.compute(before, MAPPER.createObjectNode());

    assertThat(findOp(diff, "remove", "/tempKey")).isNotNull();
  }

  @Test
  void noChanges_producesEmptyArray() {
    ObjectNode both = MAPPER.createObjectNode();
    both.put("status", "done");

    JsonNode diff = strategy.compute(both, both);

    assertThat(diff.isArray()).isTrue();
    assertThat(diff.size()).isZero();
  }

  @Test
  void bothEmpty_producesEmptyArray() {
    JsonNode diff = strategy.compute(MAPPER.createObjectNode(), MAPPER.createObjectNode());

    assertThat(diff.isArray()).isTrue();
    assertThat(diff.size()).isZero();
  }

  @Test
  void nestedChange_producesPathWithSlashSeparator() {
    ObjectNode beforeDoc = MAPPER.createObjectNode();
    beforeDoc.put("status", "draft");
    ObjectNode before = MAPPER.createObjectNode();
    before.set("doc", beforeDoc);

    ObjectNode afterDoc = MAPPER.createObjectNode();
    afterDoc.put("status", "published");
    ObjectNode after = MAPPER.createObjectNode();
    after.set("doc", afterDoc);

    JsonNode diff = strategy.compute(before, after);

    // JSON Patch records /doc/status as the changed path
    assertThat(findOp(diff, "replace", "/doc/status")).isNotNull();
  }

  /** Finds the first operation matching op type and path, or null. */
  private JsonNode findOp(JsonNode patch, String op, String path) {
    for (JsonNode node : patch) {
      if (op.equals(node.path("op").asText()) && path.equals(node.path("path").asText())) {
        return node;
      }
    }
    return null;
  }
}
