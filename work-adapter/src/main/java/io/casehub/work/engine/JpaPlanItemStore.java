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
package io.casehub.work.engine;

import io.casehub.engine.common.internal.model.PlanItemRecord;
import io.casehub.engine.common.internal.model.PlanItemSaveRequest;
import io.casehub.engine.common.internal.model.PlanItemStatus;
import io.casehub.engine.common.spi.PlanItemStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Blocking JPA PlanItemStore for use in the work-adapter context.
 *
 * <p>Uses the same blocking persistence unit as casehub-work, so writes participate in the same JTA
 * transaction as WorkItemService. This is the key atomicity guarantee: planItemStore.updateStatus()
 * and workItemService.create() either both commit or both roll back.
 */
@ApplicationScoped
public class JpaPlanItemStore implements PlanItemStore {

  @Inject EntityManager em;

  @Override
  public void save(PlanItemSaveRequest request, String tenancyId) {
    WorkAdapterPlanItemEntity e = new WorkAdapterPlanItemEntity();
    e.tenancyId = tenancyId;
    e.caseId = request.caseId();
    e.planItemId = request.planItemId();
    e.bindingName = request.bindingName();
    e.status = request.status();
    e.createdAt = request.createdAt();
    e.targetType = request.targetType();
    e.outputMappingExpression = request.outputMappingExpression();
    em.persist(e);
  }

  @Override
  public void updateStatus(String planItemId, PlanItemStatus status) {
    em.flush();
    em.createQuery(
            "UPDATE WorkAdapterPlanItemEntity e SET e.status = :status WHERE e.planItemId = :planItemId")
        .setParameter("status", status)
        .setParameter("planItemId", planItemId)
        .executeUpdate();
    em.clear();
  }

  @Override
  public List<PlanItemRecord> findByCaseId(UUID caseId, String tenancyId) {
    return em
        .createQuery(
            "SELECT e FROM WorkAdapterPlanItemEntity e WHERE e.caseId = :caseId AND e.tenancyId = :tenancyId",
            WorkAdapterPlanItemEntity.class)
        .setParameter("caseId", caseId)
        .setParameter("tenancyId", tenancyId)
        .getResultList()
        .stream()
        .map(this::toRecord)
        .collect(Collectors.toList());
  }

  @Override
  public List<PlanItemRecord> findDelegated(UUID caseId) {
    return em
        .createQuery(
            "SELECT e FROM WorkAdapterPlanItemEntity e WHERE e.caseId = :caseId AND e.status = :status",
            WorkAdapterPlanItemEntity.class)
        .setParameter("caseId", caseId)
        .setParameter("status", PlanItemStatus.DELEGATED)
        .getResultList()
        .stream()
        .map(this::toRecord)
        .collect(Collectors.toList());
  }

  @Override
  public List<PlanItemRecord> findAllDelegated() {
    return em
        .createQuery(
            "SELECT e FROM WorkAdapterPlanItemEntity e WHERE e.status = :status",
            WorkAdapterPlanItemEntity.class)
        .setParameter("status", PlanItemStatus.DELEGATED)
        .getResultList()
        .stream()
        .map(this::toRecord)
        .collect(Collectors.toList());
  }

  private PlanItemRecord toRecord(WorkAdapterPlanItemEntity e) {
    return new PlanItemRecord(
        e.caseId,
        e.planItemId,
        e.bindingName,
        e.status,
        e.createdAt,
        e.targetType,
        e.outputMappingExpression,
        e.tenancyId);
  }
}
