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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.spi.ContextDiffStrategy;

/**
 * Top-level key {@link ContextDiffStrategy}: records a before/after entry per changed top-level
 * key.
 *
 * <p>Output format per key:
 *
 * <ul>
 *   <li>Added: {@code { "after": value }} — no {@code before} field
 *   <li>Updated: {@code { "before": oldValue, "after": newValue }}
 *   <li>Removed: {@code { "before": oldValue }} — no {@code after} field
 *   <li>Unchanged: omitted entirely
 * </ul>
 *
 * <p>Operates on top-level keys only. Nested changes within an object value are captured as a
 * whole-object before/after, not path-by-path. Use {@link JsonPatchContextDiffStrategy} for full
 * path precision.
 *
 * <p>Active when {@code casehub.engine.diff-strategy=top-level}.
 */
class TopLevelContextDiffStrategy implements ContextDiffStrategy {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public JsonNode compute(JsonNode before, JsonNode after) {
    ObjectNode changes = MAPPER.createObjectNode();

    // Added or updated keys — present in `after`
    after
        .fieldNames()
        .forEachRemaining(
            key -> {
              JsonNode afterVal = after.get(key);
              JsonNode beforeVal = before.get(key);
              if (afterVal.equals(beforeVal)) {
                return; // unchanged — omit
              }
              ObjectNode change = MAPPER.createObjectNode();
              if (beforeVal != null && !beforeVal.isMissingNode()) {
                change.set("before", beforeVal);
              }
              // null written by worker is treated as removal — no "after" field
              if (afterVal != null && !afterVal.isNull()) {
                change.set("after", afterVal);
              }
              changes.set(key, change);
            });

    // Removed keys — present in `before` but absent in `after`
    before
        .fieldNames()
        .forEachRemaining(
            key -> {
              if (!after.has(key)) {
                ObjectNode change = MAPPER.createObjectNode();
                change.set("before", before.get(key));
                changes.set(key, change);
              }
            });

    return changes;
  }
}
