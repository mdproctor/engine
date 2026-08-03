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

import io.casehub.api.model.TaskStatus;
import io.casehub.engine.common.internal.model.PlanItemRecord;
import io.casehub.engine.common.internal.model.PlanItemSaveRequest;
import io.casehub.engine.common.spi.PlanItemStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
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
public class InMemoryPlanItemStore implements PlanItemStore {

  private final ConcurrentHashMap<String, PlanItemRecord> records = new ConcurrentHashMap<>();

  /** Clear all records — useful for test teardown. */
  public void clear() {
    records.clear();
  }

  @Override
  public void save(PlanItemSaveRequest request, String tenancyId) {
    records.put(
        request.planItemId(),
        new PlanItemRecord(
            request.caseId(),
            request.planItemId(),
            request.bindingName(),
            request.status(),
            request.createdAt(),
            null,
            request.targetType(),
            request.outputMappingExpression(),
            tenancyId,
            request.description(),
            request.executorName(),
            request.executorDescription(),
            request.planItemType(),
            request.planningStrategy(),
            request.completionSemantics(),
            request.dispatchMode(),
            request.repeatable(),
            request.parentCompoundId(),
            request.lifecycleScope(),
            request.activationContext()));
  }

  @Override
  public void updateStatus(String planItemId, TaskStatus status) {
    records.computeIfPresent(
        planItemId,
        (k, r) ->
            new PlanItemRecord(
                r.caseId(),
                r.planItemId(),
                r.bindingName(),
                status,
                r.createdAt(),
                status.isTerminal() ? java.time.Instant.now() : r.completedAt(),
                r.targetType(),
                r.outputMappingExpression(),
                r.tenancyId(),
                r.description(),
                r.executorName(),
                r.executorDescription(),
                r.planItemType(),
                r.planningStrategy(),
                r.completionSemantics(),
                r.dispatchMode(),
                r.repeatable(),
                r.parentCompoundId(),
                r.lifecycleScope(),
                r.activationContext()));
  }

  @Override
  public List<PlanItemRecord> findByCaseId(UUID caseId, String tenancyId) {
    return records.values().stream()
        .filter(r -> caseId.equals(r.caseId()) && tenancyId.equals(r.tenancyId()))
        .collect(Collectors.toList());
  }

  @Override
  public List<PlanItemRecord> findDelegatedCrossTenant(UUID caseId) {
    return records.values().stream()
        .filter(r -> caseId.equals(r.caseId()) && r.status() == TaskStatus.DELEGATED)
        .collect(Collectors.toList());
  }

  @Override
  public List<PlanItemRecord> findAllDelegated() {
    return records.values().stream()
        .filter(r -> r.status() == TaskStatus.DELEGATED)
        .collect(Collectors.toList());
  }
}
