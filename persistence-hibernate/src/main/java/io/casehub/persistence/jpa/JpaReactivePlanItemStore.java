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
import io.casehub.engine.common.spi.ReactivePlanItemStore;
import io.quarkus.panache.common.Parameters;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class JpaReactivePlanItemStore extends TenantAwareRepository
    implements ReactivePlanItemStore {

  @Override
  public Uni<Void> save(PlanItemSaveRequest request, String tenancyId) {
    return withTenantTransaction(
        tenancyId,
        () -> {
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
          return e.persist().replaceWithVoid();
        });
  }

  @Override
  public Uni<Void> updateStatus(String planItemId, TaskStatus status) {
    return withCrossTenantTransaction(
        () ->
            PlanItemEntity.getSession()
                .chain(session -> session.flush())
                .chain(
                    () ->
                        PlanItemEntity.update(
                            "status = :status WHERE planItemId = :planItemId",
                            Parameters.with("status", status).and("planItemId", planItemId)))
                .replaceWithVoid());
  }

  @Override
  public Uni<Void> updateStatus(String planItemId, TaskStatus status, String tenancyId) {
    return withTenantTransaction(
        tenancyId,
        () ->
            PlanItemEntity.getSession()
                .chain(session -> session.flush())
                .chain(
                    () ->
                        PlanItemEntity.update(
                            "status = :status WHERE planItemId = :planItemId",
                            Parameters.with("status", status).and("planItemId", planItemId)))
                .replaceWithVoid());
  }

  @Override
  public Uni<List<PlanItemRecord>> findByCaseId(UUID caseId, String tenancyId) {
    return withTenantTransaction(
        tenancyId,
        () ->
            PlanItemEntity.<PlanItemEntity>find("caseId = ?1 AND tenancyId = ?2", caseId, tenancyId)
                .list()
                .map(list -> list.stream().map(this::toRecord).collect(Collectors.toList())));
  }

  @Override
  public Uni<List<PlanItemRecord>> findDelegated(UUID caseId, String tenancyId) {
    return withTenantTransaction(
        tenancyId,
        () ->
            PlanItemEntity.<PlanItemEntity>find(
                    "caseId = ?1 AND status = ?2 AND tenancyId = ?3",
                    caseId,
                    TaskStatus.DELEGATED,
                    tenancyId)
                .list()
                .map(list -> list.stream().map(this::toRecord).collect(Collectors.toList())));
  }

  @Override
  public Uni<List<PlanItemRecord>> findDelegatedCrossTenant(UUID caseId) {
    return withCrossTenantTransaction(
        () ->
            PlanItemEntity.<PlanItemEntity>find(
                    "caseId = ?1 AND status = ?2", caseId, TaskStatus.DELEGATED)
                .list()
                .map(list -> list.stream().map(this::toRecord).collect(Collectors.toList())));
  }

  @Override
  public Uni<List<PlanItemRecord>> findAllDelegated() {
    // Recovery path: needs all tenants — startup only
    return withCrossTenantTransaction(
        () ->
            PlanItemEntity.<PlanItemEntity>find("status", TaskStatus.DELEGATED)
                .list()
                .map(list -> list.stream().map(this::toRecord).collect(Collectors.toList())));
  }

  private PlanItemRecord toRecord(PlanItemEntity e) {
    return new PlanItemRecord(
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
