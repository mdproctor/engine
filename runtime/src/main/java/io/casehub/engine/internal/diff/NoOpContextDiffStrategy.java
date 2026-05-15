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

/**
 * No-op {@link ContextDiffStrategy}: skips diff computation entirely. {@code contextChanges} is
 * omitted from {@code WORKER_EXECUTION_COMPLETED} metadata.
 *
 * <p>Active when {@code casehub.engine.diff-strategy=none} (the default).
 */
class NoOpContextDiffStrategy implements ContextDiffStrategy {

  @Override
  public JsonNode compute(JsonNode before, JsonNode after) {
    return null;
  }
}
