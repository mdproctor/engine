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

import io.casehub.api.model.stigmergy.WatchPattern;
import io.casehub.engine.common.spi.Resettable;
import io.casehub.engine.common.spi.WatchPatternStore;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@DefaultBean
@ApplicationScoped
public class InMemoryWatchPatternStore implements WatchPatternStore, Resettable {

  private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<WatchPattern>> patterns =
      new ConcurrentHashMap<>();

  @Override
  public void save(UUID caseId, WatchPattern pattern, String tenancyId) {
    patterns.computeIfAbsent(caseId, k -> new CopyOnWriteArrayList<>()).add(pattern);
  }

  @Override
  public void remove(UUID caseId, String patternId, String tenancyId) {
    var casePatterns = patterns.get(caseId);
    if (casePatterns != null) {
      casePatterns.removeIf(p -> p.id().equals(patternId));
    }
  }

  @Override
  public List<WatchPattern> findActive(UUID caseId, String tenancyId) {
    var casePatterns = patterns.get(caseId);
    return casePatterns != null ? List.copyOf(casePatterns) : List.of();
  }

  @Override
  public void reset() {
    patterns.clear();
  }
}
