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
package io.casehub.resilience.conflict;

/**
 * Strategy for resolving concurrent writes to the same CaseContext key by different workers.
 * Applied in WorkflowExecutionCompletedHandler when a key already has a value from a prior worker
 * in the same event cycle. Configured per-Binding via Binding.getConflictResolverStrategy().
 *
 * <p>Default when not configured: LAST_WRITER_WINS (incoming value always wins). See
 * casehubio/engine#45, casehubio/engine#51.
 */
public interface ConflictResolver {

  /**
   * @param key the CaseContext key in conflict
   * @param existing value already present (may be null if first write)
   * @param incoming value this worker is trying to write
   * @return the value to store
   */
  Object resolve(String key, Object existing, Object incoming);

  /** Returns a resolver where the incoming value always wins. */
  static ConflictResolver lastWriterWins() {
    return (k, e, i) -> i;
  }

  /** Returns a resolver where the first written value is kept; null existing defers to incoming. */
  static ConflictResolver firstWriterWins() {
    return (k, e, i) -> e != null ? e : i;
  }

  /**
   * Returns a resolver that throws {@link ConflictException} when a non-null value already exists.
   */
  static ConflictResolver fail() {
    return (k, e, i) -> {
      if (e != null) {
        throw new ConflictException(
            "Conflicting writes to key '" + k + "': existing=" + e + ", incoming=" + i);
      }
      return i;
    };
  }
}
