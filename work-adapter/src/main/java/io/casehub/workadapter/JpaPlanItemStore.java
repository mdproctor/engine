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
package io.casehub.workadapter;

import io.casehub.engine.common.internal.model.PlanItemRecord;
import io.casehub.engine.common.internal.model.PlanItemStatus;
import io.casehub.engine.common.spi.PlanItemStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Blocking JPA {@link PlanItemStore} for use in the work-adapter context.
 *
 * <p>Uses the same blocking persistence unit as casehub-work, so writes participate in the same JTA
 * transaction as {@link io.casehub.work.runtime.service.WorkItemService}. This is the key atomicity
 * guarantee: {@code planItemStore.updateStatus()} and {@code workItemService.create()} either both
 * commit or both roll back.
 *
 * <p>This bean is {@code @ApplicationScoped} and therefore takes priority over {@link
 * io.casehub.blackboard.store.NoOpPlanItemStore} which is {@code @DefaultBean}.
 */
@ApplicationScoped
public class JpaPlanItemStore implements PlanItemStore {

  @Inject EntityManager em;

  @Override
  public void save(
      UUID caseId,
      String planItemId,
      String bindingName,
      PlanItemStatus status,
      Instant createdAt) {
    WorkAdapterPlanItemEntity e = new WorkAdapterPlanItemEntity();
    e.caseId = caseId;
    e.planItemId = planItemId;
    e.bindingName = bindingName;
    e.status = status;
    e.createdAt = createdAt;
    em.persist(e);
  }

  @Override
  public void updateStatus(String planItemId, PlanItemStatus status) {
    // Flush pending inserts so the bulk UPDATE can see entities persisted earlier in this
    // transaction but not yet written to the DB row store.
    em.flush();
    em.createQuery(
            "UPDATE WorkAdapterPlanItemEntity e SET e.status = :status WHERE e.planItemId = :planItemId")
        .setParameter("status", status)
        .setParameter("planItemId", planItemId)
        .executeUpdate();
    // Bulk DML bypasses the first-level cache, leaving cached entities stale. Clear so any
    // subsequent find re-reads from the database with the updated status.
    em.clear();
  }

  @Override
  public List<PlanItemRecord> findByCaseId(UUID caseId) {
    return em
        .createQuery(
            "SELECT e FROM WorkAdapterPlanItemEntity e WHERE e.caseId = :caseId",
            WorkAdapterPlanItemEntity.class)
        .setParameter("caseId", caseId)
        .getResultList()
        .stream()
        .map(e -> new PlanItemRecord(e.caseId, e.planItemId, e.bindingName, e.status, e.createdAt))
        .collect(Collectors.toList());
  }
}
