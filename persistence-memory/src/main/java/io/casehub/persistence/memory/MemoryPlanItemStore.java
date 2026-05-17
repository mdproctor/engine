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
package io.casehub.persistence.memory;

import io.casehub.engine.internal.model.PlanItemRecord;
import io.casehub.engine.internal.model.PlanItemStatus;
import io.casehub.engine.spi.PlanItemStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory {@link PlanItemStore} for use in engine unit tests. Activated via {@code
 * quarkus.arc.selected-alternatives} — never active in production.
 */
@Alternative
@ApplicationScoped
public class MemoryPlanItemStore implements PlanItemStore {

  private final ConcurrentHashMap<String, PlanItemRecord> records = new ConcurrentHashMap<>();

  /** Clear all records — useful for test teardown. */
  public void clear() {
    records.clear();
  }

  @Override
  public void save(
      UUID caseId,
      String planItemId,
      String bindingName,
      PlanItemStatus status,
      Instant createdAt) {
    records.put(planItemId, new PlanItemRecord(caseId, planItemId, bindingName, status, createdAt));
  }

  @Override
  public void updateStatus(String planItemId, PlanItemStatus status) {
    records.computeIfPresent(
        planItemId,
        (k, r) ->
            new PlanItemRecord(r.caseId(), r.planItemId(), r.bindingName(), status, r.createdAt()));
  }

  @Override
  public List<PlanItemRecord> findByCaseId(UUID caseId) {
    return records.values().stream()
        .filter(r -> caseId.equals(r.caseId()))
        .collect(Collectors.toList());
  }
}
