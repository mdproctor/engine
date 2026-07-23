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
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.spi.CrossTenantEventLogRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Blocking cross-tenant JPA {@link CrossTenantEventLogRepository}. Direct EntityManager
 * implementation.
 */
@ApplicationScoped
public class JpaCrossTenantEventLogRepository extends TenantAwareRepository
    implements CrossTenantEventLogRepository {

  @Override
  @Transactional
  public List<EventLog> findByTypes(Collection<CaseHubEventType> types) {
    setCrossTenantContext();
    return em
        .createQuery(
            "SELECT e FROM EventLogEntity e WHERE e.eventType IN :types ORDER BY e.seq ASC",
            EventLogEntity.class)
        .setParameter("types", types)
        .getResultList()
        .stream()
        .map(this::fromEntity)
        .toList();
  }

  @Override
  @Transactional
  public List<EventLog> findByCaseAndTypes(UUID caseId, Collection<CaseHubEventType> types) {
    setCrossTenantContext();
    return em
        .createQuery(
            "SELECT e FROM EventLogEntity e WHERE e.caseId = :caseId AND e.eventType IN :types ORDER BY e.seq ASC",
            EventLogEntity.class)
        .setParameter("caseId", caseId)
        .setParameter("types", types)
        .getResultList()
        .stream()
        .map(this::fromEntity)
        .toList();
  }

  @Override
  @Transactional
  public List<String> findSubmittedWorkWithoutCompletion() {
    setCrossTenantContext();
    List<EventLogEntity> submitted =
        em.createQuery(
                "SELECT e FROM EventLogEntity e WHERE e.eventType = :type", EventLogEntity.class)
            .setParameter("type", CaseHubEventType.WORK_SUBMITTED)
            .getResultList();
    List<EventLogEntity> completed =
        em.createQuery(
                "SELECT e FROM EventLogEntity e WHERE e.eventType = :type", EventLogEntity.class)
            .setParameter("type", CaseHubEventType.WORK_COMPLETED)
            .getResultList();
    var submittedKeys =
        submitted.stream()
            .map(e -> e.metadata != null ? e.metadata.path("correlationKey").asText(null) : null)
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());
    var completedKeys =
        completed.stream()
            .map(e -> e.metadata != null ? e.metadata.path("correlationKey").asText(null) : null)
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());
    submittedKeys.removeAll(completedKeys);
    return new java.util.ArrayList<>(submittedKeys);
  }

  @Override
  @Transactional
  public EventLog findById(Long id) {
    setCrossTenantContext();
    EventLogEntity entity = em.find(EventLogEntity.class, id);
    return entity == null ? null : fromEntity(entity);
  }

  @Override
  @Transactional
  public List<EventLog> findByCaseAndWorkerAndType(
      UUID caseId, String workerId, CaseHubEventType type) {
    setCrossTenantContext();
    return em
        .createQuery(
            "SELECT e FROM EventLogEntity e WHERE e.caseId = :caseId AND e.workerId = :workerId AND e.eventType = :type",
            EventLogEntity.class)
        .setParameter("caseId", caseId)
        .setParameter("workerId", workerId)
        .setParameter("type", type)
        .getResultList()
        .stream()
        .map(this::fromEntity)
        .toList();
  }

  @Override
  @Transactional
  public List<EventLog> findByWorkerAndTypeAcrossTenants(String workerId, CaseHubEventType type) {
    setCrossTenantContext();
    return em
        .createQuery(
            "SELECT e FROM EventLogEntity e WHERE e.workerId = :workerId AND e.eventType = :type",
            EventLogEntity.class)
        .setParameter("workerId", workerId)
        .setParameter("type", type)
        .getResultList()
        .stream()
        .map(this::fromEntity)
        .toList();
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
}
