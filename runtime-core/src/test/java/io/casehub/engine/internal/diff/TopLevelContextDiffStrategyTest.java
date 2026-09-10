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

/** Pure unit tests — no CDI, no Quarkus. TopLevelContextDiffStrategy is a stateless utility. */
class TopLevelContextDiffStrategyTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final TopLevelContextDiffStrategy strategy = new TopLevelContextDiffStrategy();

  // ---- Added keys -----------------------------------------------------------

  @Test
  void addedKey_hasAfterOnly_noBeforeField() {
    ObjectNode after = MAPPER.createObjectNode();
    after.put("status", "done");

    JsonNode diff = strategy.compute(MAPPER.createObjectNode(), after);

    assertThat(diff.has("status")).isTrue();
    assertThat(diff.get("status").get("after").asText()).isEqualTo("done");
    assertThat(diff.get("status").has("before")).isFalse();
  }

  @Test
  void addedObjectKey_capturesFullValue() {
    ObjectNode afterDoc = MAPPER.createObjectNode();
    afterDoc.put("id", "doc-1");
    afterDoc.put("title", "Test");
    ObjectNode after = MAPPER.createObjectNode();
    after.set("doc", afterDoc);

    JsonNode diff = strategy.compute(MAPPER.createObjectNode(), after);

    assertThat(diff.get("doc").get("after").get("id").asText()).isEqualTo("doc-1");
    assertThat(diff.get("doc").has("before")).isFalse();
  }

  // ---- Updated keys ---------------------------------------------------------

  @Test
  void updatedKey_hasBeforeAndAfter() {
    ObjectNode before = MAPPER.createObjectNode();
    before.put("status", "processing");
    ObjectNode after = MAPPER.createObjectNode();
    after.put("status", "done");

    JsonNode diff = strategy.compute(before, after);

    assertThat(diff.get("status").get("before").asText()).isEqualTo("processing");
    assertThat(diff.get("status").get("after").asText()).isEqualTo("done");
  }

  @Test
  void updatedNumericKey_capturesNumericValues() {
    ObjectNode before = MAPPER.createObjectNode();
    before.put("score", 10);
    ObjectNode after = MAPPER.createObjectNode();
    after.put("score", 99);

    JsonNode diff = strategy.compute(before, after);

    assertThat(diff.get("score").get("before").asInt()).isEqualTo(10);
    assertThat(diff.get("score").get("after").asInt()).isEqualTo(99);
  }

  // ---- Removed keys ---------------------------------------------------------

  @Test
  void removedKey_hasBeforeOnly_noAfterField() {
    ObjectNode before = MAPPER.createObjectNode();
    before.put("tempKey", "old-value");

    JsonNode diff = strategy.compute(before, MAPPER.createObjectNode());

    assertThat(diff.has("tempKey")).isTrue();
    assertThat(diff.get("tempKey").get("before").asText()).isEqualTo("old-value");
    assertThat(diff.get("tempKey").has("after")).isFalse();
  }

  @Test
  void nullValueWritten_treatedAsRemoval_noAfterField() {
    ObjectNode before = MAPPER.createObjectNode();
    before.put("tempKey", "old-value");
    ObjectNode after = MAPPER.createObjectNode();
    after.putNull("tempKey"); // worker explicitly sets null

    JsonNode diff = strategy.compute(before, after);

    assertThat(diff.has("tempKey")).isTrue();
    assertThat(diff.get("tempKey").get("before").asText()).isEqualTo("old-value");
    assertThat(diff.get("tempKey").has("after")).isFalse();
  }

  // ---- Unchanged keys -------------------------------------------------------

  @Test
  void unchangedKey_isOmittedFromDiff() {
    ObjectNode before = MAPPER.createObjectNode();
    before.put("status", "done");
    ObjectNode after = MAPPER.createObjectNode();
    after.put("status", "done");

    JsonNode diff = strategy.compute(before, after);

    assertThat(diff.has("status")).isFalse();
  }

  @Test
  void mixedContext_onlyChangedKeysAppear() {
    ObjectNode before = MAPPER.createObjectNode();
    before.put("status", "processing");
    before.put("unchanged", "same");

    ObjectNode after = MAPPER.createObjectNode();
    after.put("status", "done");
    after.put("unchanged", "same");

    JsonNode diff = strategy.compute(before, after);

    assertThat(diff.has("status")).isTrue();
    assertThat(diff.has("unchanged")).isFalse();
  }

  // ---- No changes -----------------------------------------------------------

  @Test
  void noChanges_returnsEmptyObject() {
    ObjectNode both = MAPPER.createObjectNode();
    both.put("status", "done");
    both.put("result", "ok");

    JsonNode diff = strategy.compute(both, both);

    assertThat(diff.size()).isZero();
    assertThat(diff.isObject()).isTrue();
  }

  @Test
  void bothEmpty_returnsEmptyObject() {
    JsonNode diff = strategy.compute(MAPPER.createObjectNode(), MAPPER.createObjectNode());

    assertThat(diff.size()).isZero();
  }

  // ---- Multiple simultaneous changes ----------------------------------------

  @Test
  void multipleChanges_allCaptured() {
    ObjectNode before = MAPPER.createObjectNode();
    before.put("status", "processing");
    before.put("toRemove", "bye");

    ObjectNode after = MAPPER.createObjectNode();
    after.put("status", "done");
    after.put("newKey", "hello");

    JsonNode diff = strategy.compute(before, after);

    // updated
    assertThat(diff.get("status").get("before").asText()).isEqualTo("processing");
    assertThat(diff.get("status").get("after").asText()).isEqualTo("done");
    // added
    assertThat(diff.get("newKey").get("after").asText()).isEqualTo("hello");
    assertThat(diff.get("newKey").has("before")).isFalse();
    // removed
    assertThat(diff.get("toRemove").get("before").asText()).isEqualTo("bye");
    assertThat(diff.get("toRemove").has("after")).isFalse();
  }
}
