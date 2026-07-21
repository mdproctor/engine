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

import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.ReactiveCaseInstanceRepository;
import io.casehub.engine.common.spi.query.CaseInstanceQuery;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class JpaReactiveCaseInstanceRepository extends TenantAwareRepository
    implements ReactiveCaseInstanceRepository {

  @Override
  public Uni<CaseInstance> save(CaseInstance instance, String tenancyId) {
    return withTenantTransaction(
        tenancyId,
        () ->
            Panache.getSession()
                .chain(
                    session -> {
                      CaseInstanceEntity entity = new CaseInstanceEntity();
                      entity.tenancyId = tenancyId;
                      entity.uuid = instance.getUuid();
                      entity.state = instance.getState();
                      entity.parentCaseId = instance.getParentCaseId();
                      entity.parentPlanItemId = instance.getParentPlanItemId();
                      entity.waitingForWorkId = instance.getWaitingForWorkId();
                      entity.labels = new LinkedHashSet<>(instance.getLabels());
                      if (instance.getCaseMetaModel() != null) {
                        entity.caseMetaModel =
                            session.getReference(
                                CaseMetaModelEntity.class, instance.getCaseMetaModel().getId());
                      }
                      return entity
                          .persist()
                          .map(
                              v -> {
                                instance.id = entity.id;
                                instance.tenancyId = tenancyId;
                                return instance;
                              });
                    }));
  }

  @Override
  public Uni<CaseInstance> update(CaseInstance instance, String tenancyId) {
    return withTenantTransaction(
        tenancyId,
        () ->
            CaseInstanceEntity.<CaseInstanceEntity>find(
                    "id = ?1 and tenancyId = ?2", instance.id, tenancyId)
                .firstResult()
                .invoke(
                    entity -> {
                      entity.state = instance.getState();
                      entity.parentCaseId = instance.getParentCaseId();
                      entity.parentPlanItemId = instance.getParentPlanItemId();
                      entity.waitingForWorkId = instance.getWaitingForWorkId();
                      entity.labels = new LinkedHashSet<>(instance.getLabels());
                      // tenancyId is immutable — not updated
                    })
                .replaceWith(instance));
  }

  @Override
  public Uni<CaseInstance> findByUuid(UUID uuid, String tenancyId) {
    return withTenantTransaction(
        tenancyId,
        () ->
            CaseInstanceEntity.<CaseInstanceEntity>find(
                    "from CaseInstanceEntity ci join fetch ci.caseMetaModel "
                        + "where ci.uuid = ?1 and ci.tenancyId = ?2",
                    uuid,
                    tenancyId)
                .firstResult()
                .map(entity -> entity == null ? null : fromEntity(entity)));
  }

  @Override
  public Uni<Void> updateStateAndAppendEvent(
      CaseInstance instance, EventLog eventLog, String tenancyId) {
    EventLogEntity logEntity = new EventLogEntity();
    logEntity.tenancyId = tenancyId;
    logEntity.caseId = eventLog.getCaseId();
    logEntity.eventType = eventLog.getEventType();
    logEntity.streamType = eventLog.getStreamType();
    logEntity.workerId = eventLog.getWorkerId();
    logEntity.timestamp = eventLog.getTimestamp();
    logEntity.payload = eventLog.getPayload();
    logEntity.metadata = eventLog.getMetadata();

    return withTenantTransaction(
        tenancyId,
        () ->
            CaseInstanceEntity.<CaseInstanceEntity>find(
                    "id = ?1 and tenancyId = ?2", instance.id, tenancyId)
                .firstResult()
                .chain(
                    entity -> {
                      entity.state = instance.getState();
                      entity.parentCaseId = instance.getParentCaseId();
                      entity.parentPlanItemId = instance.getParentPlanItemId();
                      entity.waitingForWorkId = instance.getWaitingForWorkId();
                      return Panache.getSession().chain(s -> s.merge(entity));
                    })
                .chain(merged -> logEntity.persistAndFlush())
                .invoke(
                    () -> {
                      eventLog.id = logEntity.id;
                      eventLog.setSeq(logEntity.seq);
                    })
                .replaceWithVoid());
  }

  @Override
  public Uni<List<CaseInstance>> findByStatus(CaseStatus status, String tenancyId) {
    return withTenantTransaction(
        tenancyId,
        () ->
            CaseInstanceEntity.<CaseInstanceEntity>find(
                    "from CaseInstanceEntity ci join fetch ci.caseMetaModel "
                        + "where ci.state = ?1 and ci.tenancyId = ?2",
                    status,
                    tenancyId)
                .list()
                .map(entities -> entities.stream().map(this::fromEntity).toList()));
  }

  @Override
  public Uni<List<CaseInstance>> findAll(String tenancyId) {
    return withTenantTransaction(
        tenancyId,
        () ->
            CaseInstanceEntity.<CaseInstanceEntity>find(
                    "from CaseInstanceEntity ci join fetch ci.caseMetaModel "
                        + "where ci.tenancyId = ?1",
                    tenancyId)
                .list()
                .map(entities -> entities.stream().map(this::fromEntity).toList()));
  }

  @Override
  public Uni<List<CaseInstance>> findByNamespaceAndName(
      String namespace, String name, String tenancyId) {
    return withTenantTransaction(
        tenancyId,
        () ->
            CaseInstanceEntity.<CaseInstanceEntity>find(
                    "from CaseInstanceEntity ci join fetch ci.caseMetaModel m "
                        + "where m.namespace = ?1 and m.name = ?2 and ci.tenancyId = ?3",
                    namespace,
                    name,
                    tenancyId)
                .list()
                .map(entities -> entities.stream().map(this::fromEntity).toList()));
  }

  Uni<List<CaseInstance>> query(CaseInstanceQuery query, String tenancyId) {
    return withTenantTransaction(
        tenancyId,
        () -> {
          StringBuilder hql = new StringBuilder("tenancyId = ?1");
          java.util.ArrayList<Object> params = new java.util.ArrayList<>();
          params.add(tenancyId);
          int idx = 2;
          if (query.status() != null) {
            hql.append(" and state = ?").append(idx++);
            params.add(query.status());
          }
          if (query.namespace() != null) {
            hql.append(" and caseMetaModel.namespace = ?").append(idx++);
            params.add(query.namespace());
          }
          if (query.name() != null) {
            hql.append(" and caseMetaModel.name = ?").append(idx);
            params.add(query.name());
          }
          return CaseInstanceEntity.<CaseInstanceEntity>find(hql.toString(), params.toArray())
              .page(io.quarkus.panache.common.Page.of(query.page(), query.size()))
              .list()
              .map(entities -> entities.stream().map(this::fromEntity).toList());
        });
  }

  Uni<Long> count(CaseInstanceQuery query, String tenancyId) {
    return withTenantTransaction(
        tenancyId,
        () -> {
          StringBuilder hql = new StringBuilder("tenancyId = ?1");
          java.util.ArrayList<Object> params = new java.util.ArrayList<>();
          params.add(tenancyId);
          int idx = 2;
          if (query.status() != null) {
            hql.append(" and state = ?").append(idx++);
            params.add(query.status());
          }
          if (query.namespace() != null) {
            hql.append(" and caseMetaModel.namespace = ?").append(idx++);
            params.add(query.namespace());
          }
          if (query.name() != null) {
            hql.append(" and caseMetaModel.name = ?").append(idx);
            params.add(query.name());
          }
          return CaseInstanceEntity.count(hql.toString(), params.toArray());
        });
  }

  private CaseInstance fromEntity(CaseInstanceEntity entity) {
    CaseInstance instance = new CaseInstance();
    instance.id = entity.id;
    instance.tenancyId = entity.tenancyId;
    instance.setUuid(entity.uuid);
    instance.setState(entity.state);
    instance.setParentCaseId(entity.parentCaseId);
    instance.setParentPlanItemId(entity.parentPlanItemId);
    instance.setWaitingForWorkId(entity.waitingForWorkId);
    if (entity.caseMetaModel != null) {
      instance.setCaseMetaModel(fromMetaEntity(entity.caseMetaModel));
    }
    instance.setLabels(new LinkedHashSet<>(entity.labels));
    return instance;
  }

  private CaseMetaModel fromMetaEntity(CaseMetaModelEntity entity) {
    CaseMetaModel m = new CaseMetaModel();
    m.id = entity.id;
    m.tenancyId = entity.tenancyId;
    m.setName(entity.name);
    m.setNamespace(entity.namespace);
    m.setVersion(entity.version);
    m.setTitle(entity.title);
    m.setDsl(entity.dsl);
    m.setDefinition(entity.definition);
    m.setCreatedAt(entity.createdAt);
    return m;
  }
}
