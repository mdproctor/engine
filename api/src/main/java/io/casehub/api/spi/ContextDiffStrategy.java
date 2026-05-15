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
package io.casehub.api.spi;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * SPI: computes the diff between CaseContext state before and after a worker execution.
 *
 * <p>The result is stored as {@code contextChanges} in the {@code WORKER_EXECUTION_COMPLETED}
 * EventLog metadata. Return {@code null} to omit {@code contextChanges} entirely.
 *
 * <p>Select a built-in implementation via:
 *
 * <pre>casehub.engine.diff-strategy=none|top-level|json-patch</pre>
 *
 * <ul>
 *   <li>{@code none} (default) — returns null; no diff overhead
 *   <li>{@code top-level} — per-key before/after object
 *   <li>{@code json-patch} — RFC 6902 patch array
 * </ul>
 *
 * <p>A consumer {@code @ApplicationScoped} implementation of this interface takes priority over the
 * config-selected built-in.
 */
public interface ContextDiffStrategy {

  /**
   * Computes the diff between the CaseContext before and after a worker execution.
   *
   * @param before CaseContext state snapshotted before {@code setAll(output)} was called
   * @param after CaseContext state after {@code setAll(output)} was called
   * @return diff node to store as {@code contextChanges}, or {@code null} to omit the field
   */
  JsonNode compute(JsonNode before, JsonNode after);
}
