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

import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Pluggable storage backend for a single context layer.
 *
 * <p>Implementations handle where key-value pairs are stored (in-memory, Redis, database).
 * CaseContextImpl adds versioning, CAS, change listeners, and layer management on top. Store
 * implementations do not need to understand those higher-level semantics.
 */
public interface CaseContextStore extends AutoCloseable {

  Object get(String key);

  /** Stores the value and returns the previous value, or null. */
  Object put(String key, Object value);

  Object remove(String key);

  boolean containsKey(String key);

  Set<String> keySet();

  /** Returns an immutable snapshot of all entries. */
  Map<String, Object> snapshot();

  void clear();

  /**
   * Stores all entries from the map. Default iterates and calls put(). Persistent stores may
   * override with a batch implementation (e.g. Redis MSET, single database transaction).
   */
  default void putAll(Map<String, Object> entries) {
    entries.forEach(this::put);
  }

  int size();

  boolean isEmpty();

  @Override
  default void close() {}

  /** Returns true if this store can detect writes from external sources. */
  default boolean supportsExternalChangeNotification() {
    return false;
  }

  /**
   * Registers a listener for external changes. Only called when
   * supportsExternalChangeNotification() returns true.
   *
   * <p><b>Contract:</b> fires ONLY for changes NOT made through this store instance's
   * put/remove/clear methods. Self-echoing stores (e.g. Redis pub/sub where the writer's own
   * subscription receives the write) must filter their own echoes — the implementation strategy
   * (client-ID filtering, write-ID dedup, sequence comparison) is a store concern.
   */
  default Subscription onExternalChange(Consumer<ContextChangeEvent> listener) {
    return Subscription.NOOP;
  }
}
