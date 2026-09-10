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
import io.casehub.api.spi.ContextDiffStrategy;
import io.fabric8.zjsonpatch.JsonDiff;

/**
 * RFC 6902 JSON Patch {@link ContextDiffStrategy} using {@code zjsonpatch}.
 *
 * <p>Produces a JSON array of patch operations ({@code add}, {@code replace}, {@code remove}).
 * Records changes at every affected path, including nested keys — more precise than {@link
 * TopLevelContextDiffStrategy} but less readable for humans.
 *
 * <p>Active when {@code casehub.engine.diff-strategy=json-patch}.
 *
 * <p>Useful as a foundation for future replay support (issues #10–#13).
 */
class JsonPatchContextDiffStrategy implements ContextDiffStrategy {

  @Override
  public JsonNode compute(JsonNode before, JsonNode after) {
    return JsonDiff.asJson(before, after);
  }
}
