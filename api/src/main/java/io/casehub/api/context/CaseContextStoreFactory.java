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

import io.casehub.platform.api.routing.NamedStrategy;
import java.util.UUID;

/**
 * Factory that creates {@link CaseContextStore} instances per layer per case. Resolved per case
 * definition via {@code StrategyResolver} (same {@link NamedStrategy} pattern as routing
 * strategies).
 */
public interface CaseContextStoreFactory extends NamedStrategy {

  /** Creates an empty store for a new case. */
  CaseContextStore createStore(String layerName, UUID caseId);

  /**
   * Loads a store for an existing case. For persistent stores, the returned store is pre-populated
   * with the persisted state. For volatile stores, returns an empty store (same as createStore).
   */
  default CaseContextStore loadStore(String layerName, UUID caseId) {
    return createStore(layerName, caseId);
  }

  /**
   * Whether stores produced by this factory survive JVM restarts. When true, recovery uses
   * loadStore() directly — no EventLog replay. When false (default), recovery replays EventLog to
   * reconstruct state.
   */
  default boolean isDurable() {
    return false;
  }
}
