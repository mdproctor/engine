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
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.query.CaseInstanceQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/** Blocking JPA {@link CaseInstanceRepository}. Direct EntityManager implementation. */
@ApplicationScoped
public class JpaCaseInstanceRepository extends TenantAwareRepository
    implements CaseInstanceRepository {

  @Override
  @Transactional
  public CaseInstance save(CaseInstance instance, String tenancyId) {
    setTenantContext(tenancyId);
    CaseInstanceEntity entity = new CaseInstanceEntity();
    entity.tenancyId = tenancyId;
    entity.uuid = instance.getUuid();
    entity.state = instance.getState();
    entity.parentCaseId = instance.getParentCaseId();
    entity.parentPlanItemId = instance.getParentPlanItemId();
    entity.waitingForWorkId = instance.getWaitingForWorkId();
    entity.actorId = instance.getActorId();
    entity.labels = new LinkedHashSet<>(instance.getLabels());
    entity.types = new LinkedHashSet<>(instance.getTypes());
    entity.exchangeHeaders =
        instance.getExchangeHeaders().isEmpty()
            ? null
            : new java.util.LinkedHashMap<>(instance.getExchangeHeaders());
    if (instance.getCaseMetaModel() != null) {
      entity.caseMetaModel =
          em.getReference(CaseMetaModelEntity.class, instance.getCaseMetaModel().getId());
    }
    em.persist(entity);
    em.flush();
    instance.id = entity.id;
    instance.tenancyId = tenancyId;
    return instance;
  }

  @Override
  @Transactional
  public CaseInstance update(CaseInstance instance, String tenancyId) {
    setTenantContext(tenancyId);
    CaseInstanceEntity entity =
        em.createQuery(
                "SELECT ci FROM CaseInstanceEntity ci WHERE ci.id = :id AND ci.tenancyId = :tid",
                CaseInstanceEntity.class)
            .setParameter("id", instance.id)
            .setParameter("tid", tenancyId)
            .getSingleResult();
    entity.state = instance.getState();
    entity.parentCaseId = instance.getParentCaseId();
    entity.parentPlanItemId = instance.getParentPlanItemId();
    entity.waitingForWorkId = instance.getWaitingForWorkId();
    entity.labels = new LinkedHashSet<>(instance.getLabels());
    entity.types = new LinkedHashSet<>(instance.getTypes());
    entity.exchangeHeaders =
        instance.getExchangeHeaders().isEmpty()
            ? null
            : new java.util.LinkedHashMap<>(instance.getExchangeHeaders());
    return instance;
  }

  @Override
  @Transactional
  public CaseInstance findByUuid(UUID uuid, String tenancyId) {
    setTenantContext(tenancyId);
    List<CaseInstanceEntity> results =
        em.createQuery(
                "SELECT ci FROM CaseInstanceEntity ci JOIN FETCH ci.caseMetaModel"
                    + " WHERE ci.uuid = :uuid AND ci.tenancyId = :tid",
                CaseInstanceEntity.class)
            .setParameter("uuid", uuid)
            .setParameter("tid", tenancyId)
            .getResultList();
    return results.isEmpty() ? null : fromEntity(results.get(0));
  }

  @Override
  @Transactional
  public void updateStateAndAppendEvent(
      CaseInstance instance, EventLog eventLog, String tenancyId) {
    setTenantContext(tenancyId);
    CaseInstanceEntity entity =
        em.createQuery(
                "SELECT ci FROM CaseInstanceEntity ci WHERE ci.id = :id AND ci.tenancyId = :tid",
                CaseInstanceEntity.class)
            .setParameter("id", instance.id)
            .setParameter("tid", tenancyId)
            .getSingleResult();
    entity.state = instance.getState();
    entity.parentCaseId = instance.getParentCaseId();
    entity.parentPlanItemId = instance.getParentPlanItemId();
    entity.waitingForWorkId = instance.getWaitingForWorkId();
    em.merge(entity);

    EventLogEntity logEntity = new EventLogEntity();
    logEntity.tenancyId = tenancyId;
    logEntity.caseId = eventLog.getCaseId();
    logEntity.eventType = eventLog.getEventType();
    logEntity.streamType = eventLog.getStreamType();
    logEntity.workerId = eventLog.getWorkerId();
    logEntity.timestamp = eventLog.getTimestamp();
    logEntity.payload = eventLog.getPayload();
    logEntity.metadata = eventLog.getMetadata();
    em.persist(logEntity);
    em.flush();
    eventLog.id = logEntity.id;
    eventLog.setSeq(logEntity.seq);
  }

  @Override
  @Transactional
  public List<CaseInstance> findByStatus(CaseStatus status, String tenancyId) {
    setTenantContext(tenancyId);
    return em
        .createQuery(
            "SELECT ci FROM CaseInstanceEntity ci JOIN FETCH ci.caseMetaModel"
                + " WHERE ci.state = :status AND ci.tenancyId = :tid",
            CaseInstanceEntity.class)
        .setParameter("status", status)
        .setParameter("tid", tenancyId)
        .getResultList()
        .stream()
        .map(this::fromEntity)
        .toList();
  }

  @Override
  @Transactional
  public List<CaseInstance> findAll(String tenancyId) {
    setTenantContext(tenancyId);
    return em
        .createQuery(
            "SELECT ci FROM CaseInstanceEntity ci JOIN FETCH ci.caseMetaModel"
                + " WHERE ci.tenancyId = :tid",
            CaseInstanceEntity.class)
        .setParameter("tid", tenancyId)
        .getResultList()
        .stream()
        .map(this::fromEntity)
        .toList();
  }

  @Override
  @Transactional
  public List<CaseInstance> findByNamespaceAndName(
      String namespace, String name, String tenancyId) {
    setTenantContext(tenancyId);
    return em
        .createQuery(
            "SELECT ci FROM CaseInstanceEntity ci JOIN FETCH ci.caseMetaModel m"
                + " WHERE m.namespace = :ns AND m.name = :name AND ci.tenancyId = :tid",
            CaseInstanceEntity.class)
        .setParameter("ns", namespace)
        .setParameter("name", name)
        .setParameter("tid", tenancyId)
        .getResultList()
        .stream()
        .map(this::fromEntity)
        .toList();
  }

  @Override
  @Transactional
  public List<CaseInstance> query(CaseInstanceQuery query, String tenancyId) {
    setTenantContext(tenancyId);
    StringBuilder hql =
        new StringBuilder("SELECT ci FROM CaseInstanceEntity ci WHERE ci.tenancyId = :tid");
    java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
    params.put("tid", tenancyId);
    if (query.status() != null) {
      hql.append(" AND ci.state = :status");
      params.put("status", query.status());
    }
    if (query.namespace() != null) {
      hql.append(" AND ci.caseMetaModel.namespace = :ns");
      params.put("ns", query.namespace());
    }
    if (query.name() != null) {
      hql.append(" AND ci.caseMetaModel.name = :name");
      params.put("name", query.name());
    }
    var typedQuery = em.createQuery(hql.toString(), CaseInstanceEntity.class);
    params.forEach(typedQuery::setParameter);
    typedQuery.setFirstResult(query.page() * query.size());
    typedQuery.setMaxResults(query.size());
    return typedQuery.getResultList().stream().map(this::fromEntity).toList();
  }

  @Override
  @Transactional
  public long count(CaseInstanceQuery query, String tenancyId) {
    setTenantContext(tenancyId);
    StringBuilder hql =
        new StringBuilder("SELECT COUNT(ci) FROM CaseInstanceEntity ci WHERE ci.tenancyId = :tid");
    java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
    params.put("tid", tenancyId);
    if (query.status() != null) {
      hql.append(" AND ci.state = :status");
      params.put("status", query.status());
    }
    if (query.namespace() != null) {
      hql.append(" AND ci.caseMetaModel.namespace = :ns");
      params.put("ns", query.namespace());
    }
    if (query.name() != null) {
      hql.append(" AND ci.caseMetaModel.name = :name");
      params.put("name", query.name());
    }
    var typedQuery = em.createQuery(hql.toString(), Long.class);
    params.forEach(typedQuery::setParameter);
    return typedQuery.getSingleResult();
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
    instance.setActorId(entity.actorId);
    if (entity.caseMetaModel != null) {
      instance.setCaseMetaModel(fromMetaEntity(entity.caseMetaModel));
    }
    instance.setLabels(new LinkedHashSet<>(entity.labels));
    instance.setTypes(new LinkedHashSet<>(entity.types));
    if (entity.exchangeHeaders != null) {
      instance.setExchangeHeaders(new java.util.LinkedHashMap<>(entity.exchangeHeaders));
    }
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
