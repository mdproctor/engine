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

import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.spi.CrossTenantEventLogRepository;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.query.EventLogQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * In-memory blocking {@link EventLogRepository} and {@link CrossTenantEventLogRepository} for
 * tests. Canonical implementation — {@link InMemoryReactiveEventLogRepository} delegates to this.
 */
@Alternative
@ApplicationScoped
public class InMemoryEventLogRepository
    implements EventLogRepository, CrossTenantEventLogRepository {

  private final AtomicLong idSeq = new AtomicLong(0);
  private final AtomicLong seqCounter = new AtomicLong(0);
  private final ConcurrentHashMap<Long, EventLog> store = new ConcurrentHashMap<>();
  private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

  // ── Tenant-scoped methods ────────────────────────────────────────────────

  @Override
  public void append(EventLog eventLog, String tenancyId) {
    rwLock.writeLock().lock();
    try {
      eventLog.id = idSeq.incrementAndGet();
      eventLog.tenancyId = tenancyId;
      eventLog.setSeq(seqCounter.incrementAndGet());
      store.put(eventLog.id, eventLog);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  @Override
  public Long appendAndReturnId(EventLog eventLog, String tenancyId) {
    rwLock.writeLock().lock();
    try {
      eventLog.id = idSeq.incrementAndGet();
      eventLog.tenancyId = tenancyId;
      eventLog.setSeq(seqCounter.incrementAndGet());
      store.put(eventLog.id, eventLog);
      return eventLog.id;
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  @Override
  public EventLog findById(Long id, String tenancyId) {
    rwLock.readLock().lock();
    try {
      EventLog log = store.get(id);
      if (log != null && !tenancyId.equals(log.tenancyId)) return null;
      return log;
    } finally {
      rwLock.readLock().unlock();
    }
  }

  @Override
  public List<EventLog> findSchedulingEvents(
      UUID caseId, String workerId, Instant after, String tenancyId) {
    rwLock.readLock().lock();
    try {
      return store.values().stream()
          .filter(e -> tenancyId.equals(e.tenancyId))
          .filter(e -> caseId.equals(e.getCaseId()) && workerId.equals(e.getWorkerId()))
          .filter(
              e ->
                  e.getEventType() == CaseHubEventType.WORKER_SCHEDULED
                      || e.getEventType() == CaseHubEventType.WORKER_EXECUTION_STARTED
                      || e.getEventType() == CaseHubEventType.WORKER_EXECUTION_COMPLETED)
          .filter(e -> after == null || e.getTimestamp().isAfter(after))
          .sorted(Comparator.comparingLong(EventLog::getSeq))
          .toList();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  @Override
  public List<EventLog> findByCaseAndTypes(
      UUID caseId, Collection<CaseHubEventType> types, String tenancyId) {
    rwLock.readLock().lock();
    try {
      return store.values().stream()
          .filter(e -> tenancyId.equals(e.tenancyId))
          .filter(e -> caseId.equals(e.getCaseId()) && types.contains(e.getEventType()))
          .sorted(Comparator.comparingLong(EventLog::getSeq))
          .toList();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  @Override
  public List<EventLog> findByCaseAndWorkerAndType(
      UUID caseId, String workerId, CaseHubEventType type, String tenancyId) {
    rwLock.readLock().lock();
    try {
      return store.values().stream()
          .filter(e -> tenancyId.equals(e.tenancyId))
          .filter(
              e ->
                  caseId.equals(e.getCaseId())
                      && workerId.equals(e.getWorkerId())
                      && type == e.getEventType())
          .toList();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  @Override
  public List<EventLog> findByWorkerAndType(
      String workerId, CaseHubEventType type, String tenancyId) {
    rwLock.readLock().lock();
    try {
      return store.values().stream()
          .filter(e -> tenancyId.equals(e.tenancyId))
          .filter(e -> workerId.equals(e.getWorkerId()) && e.getEventType() == type)
          .toList();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  @Override
  public List<EventLog> findByCaseWithFilters(
      UUID caseId,
      Collection<CaseHubEventType> eventTypes,
      Collection<EventStreamType> streamTypes,
      String tenancyId) {
    rwLock.readLock().lock();
    try {
      return store.values().stream()
          .filter(e -> tenancyId.equals(e.tenancyId))
          .filter(e -> caseId.equals(e.getCaseId()))
          .filter(
              e ->
                  eventTypes == null
                      || eventTypes.isEmpty()
                      || eventTypes.contains(e.getEventType()))
          .filter(
              e ->
                  streamTypes == null
                      || streamTypes.isEmpty()
                      || streamTypes.contains(e.getStreamType()))
          .sorted(Comparator.comparingLong(EventLog::getSeq))
          .toList();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  @Override
  public List<EventLog> query(EventLogQuery query, String tenancyId) {
    rwLock.readLock().lock();
    try {
      return store.values().stream()
          .filter(e -> e.tenancyId != null && e.tenancyId.equals(tenancyId))
          .filter(e -> e.getCaseId().equals(query.caseId()))
          .filter(
              e ->
                  query.eventTypes() == null
                      || query.eventTypes().isEmpty()
                      || query.eventTypes().contains(e.getEventType()))
          .filter(
              e ->
                  query.streamTypes() == null
                      || query.streamTypes().isEmpty()
                      || query.streamTypes().contains(e.getStreamType()))
          .skip((long) query.page() * query.size())
          .limit(query.size())
          .toList();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  @Override
  public long count(EventLogQuery query, String tenancyId) {
    rwLock.readLock().lock();
    try {
      return store.values().stream()
          .filter(e -> e.tenancyId != null && e.tenancyId.equals(tenancyId))
          .filter(e -> e.getCaseId().equals(query.caseId()))
          .filter(
              e ->
                  query.eventTypes() == null
                      || query.eventTypes().isEmpty()
                      || query.eventTypes().contains(e.getEventType()))
          .filter(
              e ->
                  query.streamTypes() == null
                      || query.streamTypes().isEmpty()
                      || query.streamTypes().contains(e.getStreamType()))
          .count();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  // ── CrossTenantEventLogRepository methods ────────────────────────────────

  @Override
  public List<EventLog> findByTypes(Collection<CaseHubEventType> types) {
    rwLock.readLock().lock();
    try {
      return store.values().stream()
          .filter(e -> types.contains(e.getEventType()))
          .sorted(Comparator.comparingLong(EventLog::getSeq))
          .toList();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  @Override
  public List<EventLog> findByCaseAndTypes(UUID caseId, Collection<CaseHubEventType> types) {
    rwLock.readLock().lock();
    try {
      return store.values().stream()
          .filter(e -> caseId.equals(e.getCaseId()) && types.contains(e.getEventType()))
          .sorted(Comparator.comparingLong(EventLog::getSeq))
          .toList();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  @Override
  public List<String> findSubmittedWorkWithoutCompletion() {
    rwLock.readLock().lock();
    try {
      Set<String> submitted =
          store.values().stream()
              .filter(e -> e.getEventType() == CaseHubEventType.WORK_SUBMITTED)
              .map(
                  e ->
                      e.getMetadata() != null
                          ? e.getMetadata().path("correlationKey").asText(null)
                          : null)
              .filter(Objects::nonNull)
              .collect(Collectors.toSet());

      Set<String> completed =
          store.values().stream()
              .filter(e -> e.getEventType() == CaseHubEventType.WORK_COMPLETED)
              .map(
                  e ->
                      e.getMetadata() != null
                          ? e.getMetadata().path("correlationKey").asText(null)
                          : null)
              .filter(Objects::nonNull)
              .collect(Collectors.toSet());

      submitted.removeAll(completed);
      return List.copyOf(submitted);
    } finally {
      rwLock.readLock().unlock();
    }
  }

  @Override
  public EventLog findById(Long id) {
    rwLock.readLock().lock();
    try {
      return store.get(id);
    } finally {
      rwLock.readLock().unlock();
    }
  }

  @Override
  public List<EventLog> findByCaseAndWorkerAndType(
      UUID caseId, String workerId, CaseHubEventType type) {
    rwLock.readLock().lock();
    try {
      return store.values().stream()
          .filter(
              e ->
                  caseId.equals(e.getCaseId())
                      && workerId.equals(e.getWorkerId())
                      && type == e.getEventType())
          .toList();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  @Override
  public List<EventLog> findByWorkerAndTypeAcrossTenants(String workerId, CaseHubEventType type) {
    rwLock.readLock().lock();
    try {
      return store.values().stream()
          .filter(e -> workerId.equals(e.getWorkerId()) && e.getEventType() == type)
          .toList();
    } finally {
      rwLock.readLock().unlock();
    }
  }
}
