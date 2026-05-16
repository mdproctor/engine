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
import io.casehub.engine.internal.history.EventLog;
import io.casehub.engine.spi.EventLogRepository;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@ApplicationScoped
public class JpaEventLogRepository extends AbstractJpaRepository implements EventLogRepository {

  @Override
  public Uni<Void> append(EventLog eventLog) {
    EventLogEntity entity = toEntity(eventLog);
    return withSafeContext(
        () ->
            Panache.withTransaction(() -> entity.persistAndFlush())
                .invoke(
                    () -> {
                      eventLog.id = entity.id;
                      eventLog.setSeq(entity.seq);
                    })
                .replaceWithVoid());
  }

  @Override
  public Uni<Long> appendAndReturnId(EventLog eventLog) {
    EventLogEntity entity = toEntity(eventLog);
    return withSafeContext(
        () ->
            Panache.withTransaction(() -> entity.persistAndFlush())
                .map(
                    v -> {
                      eventLog.id = entity.id;
                      eventLog.setSeq(entity.seq);
                      return entity.id;
                    }));
  }

  @Override
  public Uni<EventLog> findById(Long id) {
    return withSafeContext(
        () ->
            Panache.withSession(() -> EventLogEntity.<EventLogEntity>findById(id))
                .map(entity -> entity == null ? null : fromEntity(entity)));
  }

  @Override
  public Uni<List<EventLog>> findSchedulingEvents(UUID caseId, String workerId, Instant after) {
    return withSafeContext(
        () ->
            Panache.withSession(
                    () -> {
                      if (after == null) {
                        return EventLogEntity.<EventLogEntity>find(
                                "caseId = ?1 and workerId = ?2 and eventType in (?3, ?4, ?5)",
                                caseId,
                                workerId,
                                CaseHubEventType.WORKER_SCHEDULED,
                                CaseHubEventType.WORKER_EXECUTION_STARTED,
                                CaseHubEventType.WORKER_EXECUTION_COMPLETED)
                            .list();
                      } else {
                        return EventLogEntity.<EventLogEntity>find(
                                "caseId = ?1 and workerId = ?2 and eventType in (?3, ?4, ?5)"
                                    + " and timestamp > ?6",
                                caseId,
                                workerId,
                                CaseHubEventType.WORKER_SCHEDULED,
                                CaseHubEventType.WORKER_EXECUTION_STARTED,
                                CaseHubEventType.WORKER_EXECUTION_COMPLETED,
                                after)
                            .list();
                      }
                    })
                .map(list -> list.stream().map(this::fromEntity).toList()));
  }

  @Override
  public Uni<List<EventLog>> findByTypes(Collection<CaseHubEventType> types) {
    return withSafeContext(
        () ->
            Panache.withSession(
                    () ->
                        EventLogEntity.<EventLogEntity>find(
                                "eventType in ?1 order by seq asc", types)
                            .list())
                .map(list -> list.stream().map(this::fromEntity).toList()));
  }

  @Override
  public Uni<List<EventLog>> findByCaseAndTypes(UUID caseId, Collection<CaseHubEventType> types) {
    return withSafeContext(
        () ->
            Panache.withSession(
                    () ->
                        EventLogEntity.<EventLogEntity>find(
                                "caseId = ?1 and eventType in ?2 order by seq asc", caseId, types)
                            .list())
                .map(list -> list.stream().map(this::fromEntity).toList()));
  }

  @Override
  public Uni<List<EventLog>> findByCaseAndWorkerAndType(
      UUID caseId, String workerId, CaseHubEventType type) {
    return withSafeContext(
        () ->
            Panache.withSession(
                    () ->
                        EventLogEntity.<EventLogEntity>find(
                                "caseId = ?1 and workerId = ?2 and eventType = ?3",
                                caseId,
                                workerId,
                                type)
                            .list())
                .map(list -> list.stream().map(this::fromEntity).toList()));
  }

  @Override
  public Uni<List<EventLog>> findByWorkerAndType(String workerId, CaseHubEventType type) {
    return withSafeContext(
        () ->
            Panache.withSession(
                    () ->
                        EventLogEntity.<EventLogEntity>find(
                                "workerId = ?1 and eventType = ?2", workerId, type)
                            .list())
                .map(list -> list.stream().map(this::fromEntity).toList()));
  }

  public Uni<List<String>> findSubmittedWorkWithoutCompletion() {
    return withSafeContext(
        () ->
            Panache.withSession(
                    () ->
                        EventLogEntity.<EventLogEntity>list(
                            "eventType", CaseHubEventType.WORK_SUBMITTED))
                .chain(
                    submitted ->
                        Panache.withSession(
                                () ->
                                    EventLogEntity.<EventLogEntity>list(
                                        "eventType", CaseHubEventType.WORK_COMPLETED))
                            .map(
                                completed -> {
                                  var submittedKeys =
                                      submitted.stream()
                                          .map(
                                              e ->
                                                  e.metadata != null
                                                      ? e.metadata
                                                          .path("correlationKey")
                                                          .asText(null)
                                                      : null)
                                          .filter(Objects::nonNull)
                                          .collect(java.util.stream.Collectors.toSet());

                                  var completedKeys =
                                      completed.stream()
                                          .map(
                                              e ->
                                                  e.metadata != null
                                                      ? e.metadata
                                                          .path("correlationKey")
                                                          .asText(null)
                                                      : null)
                                          .filter(Objects::nonNull)
                                          .collect(java.util.stream.Collectors.toSet());

                                  submittedKeys.removeAll(completedKeys);
                                  return new ArrayList<>(submittedKeys);
                                })));
  }

  @Override
  public Uni<List<EventLog>> findByCaseWithFilters(
      UUID caseId,
      Collection<CaseHubEventType> eventTypes,
      Collection<EventStreamType> streamTypes) {
    return withSafeContext(
        () ->
            Panache.withSession(
                    () -> {
                      StringBuilder query = new StringBuilder("caseId = ?1");
                      List<Object> params = new ArrayList<>();
                      params.add(caseId);

                      if (eventTypes != null && !eventTypes.isEmpty()) {
                        query.append(" and eventType in ?").append(params.size() + 1);
                        params.add(eventTypes);
                      }

                      if (streamTypes != null && !streamTypes.isEmpty()) {
                        query.append(" and streamType in ?").append(params.size() + 1);
                        params.add(streamTypes);
                      }

                      query.append(" order by seq asc");

                      return EventLogEntity.<EventLogEntity>find(query.toString(), params.toArray())
                          .list();
                    })
                .map(list -> list.stream().map(this::fromEntity).toList()));
  }

  private EventLog fromEntity(EventLogEntity entity) {
    EventLog log = new EventLog();
    log.id = entity.id;
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

  private EventLogEntity toEntity(EventLog log) {
    EventLogEntity entity = new EventLogEntity();
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
