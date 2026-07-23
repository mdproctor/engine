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

import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.query.EventLogQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Blocking JPA {@link EventLogRepository}. Direct EntityManager implementation. */
@ApplicationScoped
public class JpaEventLogRepository extends TenantAwareRepository implements EventLogRepository {

  @Override
  @Transactional
  public void append(EventLog eventLog, String tenancyId) {
    setTenantContext(tenancyId);
    EventLogEntity entity = toEntity(eventLog, tenancyId);
    em.persist(entity);
    em.flush();
    eventLog.id = entity.id;
    eventLog.setSeq(entity.seq);
  }

  @Override
  @Transactional
  public Long appendAndReturnId(EventLog eventLog, String tenancyId) {
    setTenantContext(tenancyId);
    EventLogEntity entity = toEntity(eventLog, tenancyId);
    em.persist(entity);
    em.flush();
    eventLog.id = entity.id;
    eventLog.setSeq(entity.seq);
    return entity.id;
  }

  @Override
  @Transactional
  public EventLog findById(Long id, String tenancyId) {
    setTenantContext(tenancyId);
    List<EventLogEntity> results =
        em.createQuery(
                "SELECT e FROM EventLogEntity e WHERE e.id = :id AND e.tenancyId = :tid",
                EventLogEntity.class)
            .setParameter("id", id)
            .setParameter("tid", tenancyId)
            .getResultList();
    return results.isEmpty() ? null : fromEntity(results.get(0));
  }

  @Override
  @Transactional
  public List<EventLog> findSchedulingEvents(
      UUID caseId, String workerId, Instant after, String tenancyId) {
    setTenantContext(tenancyId);
    if (after == null) {
      return em
          .createQuery(
              "SELECT e FROM EventLogEntity e"
                  + " WHERE e.caseId = :caseId AND e.workerId = :workerId"
                  + " AND e.eventType IN (:t1, :t2, :t3)"
                  + " AND e.tenancyId = :tid ORDER BY e.seq ASC",
              EventLogEntity.class)
          .setParameter("caseId", caseId)
          .setParameter("workerId", workerId)
          .setParameter("t1", CaseHubEventType.WORKER_SCHEDULED)
          .setParameter("t2", CaseHubEventType.WORKER_EXECUTION_STARTED)
          .setParameter("t3", CaseHubEventType.WORKER_EXECUTION_COMPLETED)
          .setParameter("tid", tenancyId)
          .getResultList()
          .stream()
          .map(this::fromEntity)
          .toList();
    } else {
      return em
          .createQuery(
              "SELECT e FROM EventLogEntity e"
                  + " WHERE e.caseId = :caseId AND e.workerId = :workerId"
                  + " AND e.eventType IN (:t1, :t2, :t3)"
                  + " AND e.timestamp > :after AND e.tenancyId = :tid ORDER BY e.seq ASC",
              EventLogEntity.class)
          .setParameter("caseId", caseId)
          .setParameter("workerId", workerId)
          .setParameter("t1", CaseHubEventType.WORKER_SCHEDULED)
          .setParameter("t2", CaseHubEventType.WORKER_EXECUTION_STARTED)
          .setParameter("t3", CaseHubEventType.WORKER_EXECUTION_COMPLETED)
          .setParameter("after", after)
          .setParameter("tid", tenancyId)
          .getResultList()
          .stream()
          .map(this::fromEntity)
          .toList();
    }
  }

  @Override
  @Transactional
  public List<EventLog> findByCaseAndTypes(
      UUID caseId, Collection<CaseHubEventType> types, String tenancyId) {
    setTenantContext(tenancyId);
    return em
        .createQuery(
            "SELECT e FROM EventLogEntity e"
                + " WHERE e.caseId = :caseId AND e.eventType IN :types"
                + " AND e.tenancyId = :tid ORDER BY e.seq ASC",
            EventLogEntity.class)
        .setParameter("caseId", caseId)
        .setParameter("types", types)
        .setParameter("tid", tenancyId)
        .getResultList()
        .stream()
        .map(this::fromEntity)
        .toList();
  }

  @Override
  @Transactional
  public List<EventLog> findByCaseAndWorkerAndType(
      UUID caseId, String workerId, CaseHubEventType type, String tenancyId) {
    setTenantContext(tenancyId);
    return em
        .createQuery(
            "SELECT e FROM EventLogEntity e"
                + " WHERE e.caseId = :caseId AND e.workerId = :workerId"
                + " AND e.eventType = :type AND e.tenancyId = :tid",
            EventLogEntity.class)
        .setParameter("caseId", caseId)
        .setParameter("workerId", workerId)
        .setParameter("type", type)
        .setParameter("tid", tenancyId)
        .getResultList()
        .stream()
        .map(this::fromEntity)
        .toList();
  }

  @Override
  @Transactional
  public List<EventLog> findByWorkerAndType(
      String workerId, CaseHubEventType type, String tenancyId) {
    setTenantContext(tenancyId);
    return em
        .createQuery(
            "SELECT e FROM EventLogEntity e"
                + " WHERE e.workerId = :workerId AND e.eventType = :type"
                + " AND e.tenancyId = :tid",
            EventLogEntity.class)
        .setParameter("workerId", workerId)
        .setParameter("type", type)
        .setParameter("tid", tenancyId)
        .getResultList()
        .stream()
        .map(this::fromEntity)
        .toList();
  }

  @Override
  @Transactional
  public List<EventLog> findByCaseWithFilters(
      UUID caseId,
      Collection<CaseHubEventType> eventTypes,
      Collection<EventStreamType> streamTypes,
      String tenancyId) {
    setTenantContext(tenancyId);
    StringBuilder query =
        new StringBuilder(
            "SELECT e FROM EventLogEntity e WHERE e.caseId = :caseId AND e.tenancyId = :tid");
    java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
    params.put("caseId", caseId);
    params.put("tid", tenancyId);
    if (eventTypes != null && !eventTypes.isEmpty()) {
      query.append(" AND e.eventType IN :eventTypes");
      params.put("eventTypes", eventTypes);
    }
    if (streamTypes != null && !streamTypes.isEmpty()) {
      query.append(" AND e.streamType IN :streamTypes");
      params.put("streamTypes", streamTypes);
    }
    query.append(" ORDER BY e.seq ASC");
    var typedQuery = em.createQuery(query.toString(), EventLogEntity.class);
    params.forEach(typedQuery::setParameter);
    return typedQuery.getResultList().stream().map(this::fromEntity).toList();
  }

  @Override
  @Transactional
  public List<EventLog> query(EventLogQuery query, String tenancyId) {
    setTenantContext(tenancyId);
    StringBuilder hql =
        new StringBuilder(
            "SELECT e FROM EventLogEntity e WHERE e.tenancyId = :tid AND e.caseId = :caseId");
    java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
    params.put("tid", tenancyId);
    params.put("caseId", query.caseId());
    if (query.eventTypes() != null && !query.eventTypes().isEmpty()) {
      hql.append(" AND e.eventType IN :eventTypes");
      params.put("eventTypes", query.eventTypes());
    }
    if (query.streamTypes() != null && !query.streamTypes().isEmpty()) {
      hql.append(" AND e.streamType IN :streamTypes");
      params.put("streamTypes", query.streamTypes());
    }
    hql.append(" ORDER BY e.timestamp ASC");
    var typedQuery = em.createQuery(hql.toString(), EventLogEntity.class);
    params.forEach(typedQuery::setParameter);
    typedQuery.setFirstResult(query.page() * query.size());
    typedQuery.setMaxResults(query.size());
    return typedQuery.getResultList().stream().map(this::fromEntity).toList();
  }

  @Override
  @Transactional
  public long count(EventLogQuery query, String tenancyId) {
    setTenantContext(tenancyId);
    StringBuilder hql =
        new StringBuilder(
            "SELECT COUNT(e) FROM EventLogEntity e WHERE e.tenancyId = :tid AND e.caseId = :caseId");
    java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
    params.put("tid", tenancyId);
    params.put("caseId", query.caseId());
    if (query.eventTypes() != null && !query.eventTypes().isEmpty()) {
      hql.append(" AND e.eventType IN :eventTypes");
      params.put("eventTypes", query.eventTypes());
    }
    if (query.streamTypes() != null && !query.streamTypes().isEmpty()) {
      hql.append(" AND e.streamType IN :streamTypes");
      params.put("streamTypes", query.streamTypes());
    }
    var typedQuery = em.createQuery(hql.toString(), Long.class);
    params.forEach(typedQuery::setParameter);
    return typedQuery.getSingleResult();
  }

  private EventLog fromEntity(EventLogEntity entity) {
    EventLog log = new EventLog();
    log.id = entity.id;
    log.tenancyId = entity.tenancyId;
    log.setSeq(entity.seq);
    log.setCaseId(entity.caseId);
    log.setEventType(entity.eventType);
    log.setStreamType(entity.streamType);
    log.setWorkerId(entity.workerId);
    log.setTimestamp(entity.timestamp);
    log.setPayload(entity.payload);
    log.setMetadata(entity.metadata);
    return log;
  }

  private EventLogEntity toEntity(EventLog log, String tenancyId) {
    EventLogEntity entity = new EventLogEntity();
    entity.tenancyId = tenancyId;
    entity.caseId = log.getCaseId();
    entity.eventType = log.getEventType();
    entity.streamType = log.getStreamType();
    entity.workerId = log.getWorkerId();
    entity.timestamp = log.getTimestamp();
    entity.payload = log.getPayload();
    entity.metadata = log.getMetadata();
    return entity;
  }
}
