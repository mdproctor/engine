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
package io.casehub.persistence.jpa;

import io.casehub.api.model.TaskStatus;
import io.casehub.engine.common.internal.model.PlanItemRecord;
import io.casehub.engine.common.internal.model.PlanItemSaveRequest;
import io.casehub.engine.common.spi.PlanItemStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class JpaPlanItemStore extends TenantAwareRepository implements PlanItemStore {

  @Override
  @Transactional
  public void save(PlanItemSaveRequest request, String tenancyId) {
    setTenantContext(tenancyId);
    PlanItemEntity e = new PlanItemEntity();
    e.tenancyId = tenancyId;
    e.caseId = request.caseId();
    e.planItemId = request.planItemId();
    e.bindingName = request.bindingName();
    e.status = request.status();
    e.createdAt = request.createdAt();
    e.targetType = request.targetType();
    e.outputMappingExpression = request.outputMappingExpression();
    e.description = request.description();
    e.executorName = request.executorName();
    e.executorDescription = request.executorDescription();
    em.persist(e);
  }

  @Override
  @Transactional
  public void updateStatus(String planItemId, TaskStatus status) {
    setCrossTenantContext();
    em.flush();
    em.createQuery("UPDATE PlanItemEntity SET status = :status WHERE planItemId = :planItemId")
        .setParameter("status", status)
        .setParameter("planItemId", planItemId)
        .executeUpdate();
  }

  @Override
  @Transactional
  public void updateStatus(String planItemId, TaskStatus status, String tenancyId) {
    setTenantContext(tenancyId);
    em.flush();
    em.createQuery("UPDATE PlanItemEntity SET status = :status WHERE planItemId = :planItemId")
        .setParameter("status", status)
        .setParameter("planItemId", planItemId)
        .executeUpdate();
  }

  @Override
  @Transactional
  public List<PlanItemRecord> findByCaseId(UUID caseId, String tenancyId) {
    setTenantContext(tenancyId);
    return em
        .createQuery(
            "SELECT e FROM PlanItemEntity e WHERE e.caseId = :caseId AND e.tenancyId = :tenancyId",
            PlanItemEntity.class)
        .setParameter("caseId", caseId)
        .setParameter("tenancyId", tenancyId)
        .getResultList()
        .stream()
        .map(this::toRecord)
        .toList();
  }

  @Override
  @Transactional
  public List<PlanItemRecord> findDelegated(UUID caseId, String tenancyId) {
    setTenantContext(tenancyId);
    return em
        .createQuery(
            "SELECT e FROM PlanItemEntity e WHERE e.caseId = :caseId AND e.status = :status AND e.tenancyId = :tenancyId",
            PlanItemEntity.class)
        .setParameter("caseId", caseId)
        .setParameter("status", TaskStatus.DELEGATED)
        .setParameter("tenancyId", tenancyId)
        .getResultList()
        .stream()
        .map(this::toRecord)
        .toList();
  }

  @Override
  @Transactional
  public List<PlanItemRecord> findDelegatedCrossTenant(UUID caseId) {
    setCrossTenantContext();
    return em
        .createQuery(
            "SELECT e FROM PlanItemEntity e WHERE e.caseId = :caseId AND e.status = :status",
            PlanItemEntity.class)
        .setParameter("caseId", caseId)
        .setParameter("status", TaskStatus.DELEGATED)
        .getResultList()
        .stream()
        .map(this::toRecord)
        .toList();
  }

  @Override
  @Transactional
  public List<PlanItemRecord> findAllDelegated() {
    setCrossTenantContext();
    return em
        .createQuery(
            "SELECT e FROM PlanItemEntity e WHERE e.status = :status", PlanItemEntity.class)
        .setParameter("status", TaskStatus.DELEGATED)
        .getResultList()
        .stream()
        .map(this::toRecord)
        .toList();
  }

  private PlanItemRecord toRecord(PlanItemEntity e) {
    return PlanItemRecord.primitive(
        e.caseId,
        e.planItemId,
        e.bindingName,
        e.status,
        e.createdAt,
        e.targetType,
        e.outputMappingExpression,
        e.tenancyId,
        e.description,
        e.executorName,
        e.executorDescription);
  }
}
