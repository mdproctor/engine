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
package io.casehub.api.model;

import java.util.Set;

public record MemoryRetrievalConfig(
    boolean enabled,
    int maxMemories,
    Set<String> domains,
    Set<String> caseScopedDomains,
    int maxCaseMemories) {

  public MemoryRetrievalConfig {
    if (maxMemories < 1) {
      throw new IllegalArgumentException("maxMemories must be >= 1");
    }
    domains = domains == null ? Set.of() : Set.copyOf(domains);
    caseScopedDomains = caseScopedDomains == null ? Set.of() : Set.copyOf(caseScopedDomains);
    if (maxCaseMemories < 0) {
      throw new IllegalArgumentException("maxCaseMemories must be >= 0");
    }
  }

  public boolean isCaseScopedRetrievalEffectivelyDisabled() {
    return !caseScopedDomains.isEmpty() && maxCaseMemories == 0;
  }

  public MemoryRetrievalConfig(boolean enabled, int maxMemories, Set<String> domains) {
    this(enabled, maxMemories, domains, Set.of(), 0);
  }

  public static MemoryRetrievalConfig defaults() {
    return new MemoryRetrievalConfig(false, 10, Set.of("experience", "reflection"), Set.of(), 0);
  }
}
