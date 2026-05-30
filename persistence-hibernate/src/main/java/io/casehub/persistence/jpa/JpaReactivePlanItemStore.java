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

import io.casehub.engine.common.internal.model.PlanItemRecord;
import io.casehub.engine.common.internal.model.PlanItemSaveRequest;
import io.casehub.engine.common.internal.model.PlanItemStatus;
import io.casehub.engine.common.spi.ReactivePlanItemStore;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.panache.common.Parameters;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class JpaReactivePlanItemStore extends AbstractJpaRepository
    implements ReactivePlanItemStore {

  @Override
  public Uni<Void> save(PlanItemSaveRequest request) {
    return withSafeContext(
        () ->
            Panache.withTransaction(
                () -> {
                  PlanItemEntity e = new PlanItemEntity();
                  e.caseId = request.caseId();
                  e.planItemId = request.planItemId();
                  e.bindingName = request.bindingName();
                  e.status = request.status();
                  e.createdAt = request.createdAt();
                  e.targetType = request.targetType();
                  e.outputMappingExpression = request.outputMappingExpression();
                  return e.persist().replaceWithVoid();
                }));
  }

  @Override
  public Uni<Void> updateStatus(String planItemId, PlanItemStatus status) {
    return withSafeContext(
        () ->
            Panache.withTransaction(
                () ->
                    // Flush pending inserts so the JPQL UPDATE can see entities persisted
                    // earlier in this transaction but not yet written to the DB row store.
                    PlanItemEntity.getSession()
                        .chain(session -> session.flush())
                        .chain(
                            () ->
                                PlanItemEntity.update(
                                    "status = :status WHERE planItemId = :planItemId",
                                    Parameters.with("status", status)
                                        .and("planItemId", planItemId)))
                        .replaceWithVoid()));
  }

  @Override
  public Uni<List<PlanItemRecord>> findByCaseId(UUID caseId) {
    return withSafeContext(
        () ->
            Panache.withSession(
                () ->
                    PlanItemEntity.<PlanItemEntity>find("caseId", caseId)
                        .list()
                        .map(
                            list ->
                                list.stream().map(this::toRecord).collect(Collectors.toList()))));
  }

  @Override
  public Uni<List<PlanItemRecord>> findDelegated(UUID caseId) {
    return withSafeContext(
        () ->
            Panache.withSession(
                () ->
                    PlanItemEntity.<PlanItemEntity>find(
                            "caseId = ?1 AND status = ?2", caseId, PlanItemStatus.DELEGATED)
                        .list()
                        .map(
                            list ->
                                list.stream().map(this::toRecord).collect(Collectors.toList()))));
  }

  @Override
  public Uni<List<PlanItemRecord>> findAllDelegated() {
    return withSafeContext(
        () ->
            Panache.withSession(
                () ->
                    PlanItemEntity.<PlanItemEntity>find("status", PlanItemStatus.DELEGATED)
                        .list()
                        .map(
                            list ->
                                list.stream().map(this::toRecord).collect(Collectors.toList()))));
  }

  private PlanItemRecord toRecord(PlanItemEntity e) {
    return new PlanItemRecord(
        e.caseId,
        e.planItemId,
        e.bindingName,
        e.status,
        e.createdAt,
        e.targetType,
        e.outputMappingExpression);
  }
}
