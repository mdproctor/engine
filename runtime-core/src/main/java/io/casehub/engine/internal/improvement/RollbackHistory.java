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

import io.casehub.engine.common.spi.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@ApplicationScoped
public class RollbackHistory implements Resettable {

  public record RollbackRecord(
      UUID improvementCaseId, String category, String target, Instant rolledBackAt) {}

  private final ConcurrentHashMap<UUID, List<RollbackRecord>> history = new ConcurrentHashMap<>();

  public void record(UUID caseId, UUID improvementCaseId, String category, String target) {
    history
        .computeIfAbsent(caseId, k -> new CopyOnWriteArrayList<>())
        .add(new RollbackRecord(improvementCaseId, category, target, Instant.now()));
  }

  public boolean wasRecentlyRolledBack(
      UUID caseId, String category, String target, Duration window) {
    var records = history.get(caseId);
    if (records == null) return false;

    Instant cutoff = Instant.now().minus(window);
    return records.stream()
        .anyMatch(
            r ->
                r.category().equals(category)
                    && r.target().equals(target)
                    && r.rolledBackAt().isAfter(cutoff));
  }

  @Override
  public void reset() {
    history.clear();
  }
}
