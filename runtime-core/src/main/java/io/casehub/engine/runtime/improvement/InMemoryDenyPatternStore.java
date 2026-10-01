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
package io.casehub.engine.internal.improvement;

import io.casehub.engine.common.spi.DenyPatternStore;
import io.casehub.engine.common.spi.Resettable;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@DefaultBean
@ApplicationScoped
public class InMemoryDenyPatternStore implements DenyPatternStore, Resettable {

  private final ConcurrentHashMap<UUID, Set<String>> patterns = new ConcurrentHashMap<>();

  @Override
  public void save(UUID caseId, String pattern, String tenancyId) {
    patterns.computeIfAbsent(caseId, k -> ConcurrentHashMap.newKeySet()).add(pattern);
  }

  @Override
  public void remove(UUID caseId, String pattern, String tenancyId) {
    var casePatterns = patterns.get(caseId);
    if (casePatterns != null) {
      casePatterns.remove(pattern);
    }
  }

  @Override
  public Set<String> findAll(UUID caseId, String tenancyId) {
    return Set.copyOf(patterns.getOrDefault(caseId, Set.of()));
  }

  @Override
  public void reset() {
    patterns.clear();
  }
}
