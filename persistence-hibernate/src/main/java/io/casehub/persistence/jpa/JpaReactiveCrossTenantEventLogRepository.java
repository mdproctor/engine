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
import io.casehub.engine.common.spi.ReactiveCrossTenantEventLogRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class JpaReactiveCrossTenantEventLogRepository extends TenantAwareRepository
    implements ReactiveCrossTenantEventLogRepository {

  @Override
  public Uni<List<EventLog>> findByTypes(Collection<CaseHubEventType> types) {
    return withCrossTenantTransaction(
        () ->
            EventLogEntity.<EventLogEntity>find("eventType in ?1 order by seq asc", types)
                .list()
                .map(list -> list.stream().map(this::fromEntity).toList()));
  }

  @Override
  public Uni<List<EventLog>> findByCaseAndTypes(UUID caseId, Collection<CaseHubEventType> types) {
    return withCrossTenantTransaction(
        () ->
            EventLogEntity.<EventLogEntity>find(
                    "caseId = ?1 and eventType in ?2 order by seq asc", caseId, types)
                .list()
                .map(list -> list.stream().map(this::fromEntity).toList()));
  }

  @Override
  public Uni<List<String>> findSubmittedWorkWithoutCompletion() {
    return withCrossTenantTransaction(
        () ->
            EventLogEntity.<EventLogEntity>list("eventType", CaseHubEventType.WORK_SUBMITTED)
                .chain(
                    submitted ->
                        EventLogEntity.<EventLogEntity>list(
                                "eventType", CaseHubEventType.WORK_COMPLETED)
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
                                          .collect(Collectors.toSet());
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
                                          .collect(Collectors.toSet());
                                  submittedKeys.removeAll(completedKeys);
                                  return new ArrayList<>(submittedKeys);
                                })));
  }

  @Override
  public Uni<EventLog> findById(Long id) {
    return withCrossTenantTransaction(
        () ->
            EventLogEntity.<EventLogEntity>findById(id)
                .map(entity -> entity == null ? null : fromEntity(entity)));
  }

  @Override
  public Uni<List<EventLog>> findByCaseAndWorkerAndType(
      UUID caseId, String workerId, CaseHubEventType type) {
    return withCrossTenantTransaction(
        () ->
            EventLogEntity.<EventLogEntity>find(
                    "caseId = ?1 and workerId = ?2 and eventType = ?3", caseId, workerId, type)
                .list()
                .map(list -> list.stream().map(this::fromEntity).toList()));
  }

  @Override
  public Uni<List<EventLog>> findByWorkerAndTypeAcrossTenants(
      String workerId, CaseHubEventType type) {
    return withCrossTenantTransaction(
        () ->
            EventLogEntity.<EventLogEntity>find("workerId = ?1 and eventType = ?2", workerId, type)
                .list()
                .map(list -> list.stream().map(this::fromEntity).toList()));
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

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
