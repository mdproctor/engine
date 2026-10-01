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
package io.casehub.engine.internal.improvement.area;

import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.spi.EventLogRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

class TestEventLogRepository implements EventLogRepository {

  final List<EventLog> entries = new CopyOnWriteArrayList<>();

  @Override
  public void append(EventLog eventLog, String tenancyId) {
    eventLog.tenancyId = tenancyId;
    entries.add(eventLog);
  }

  @Override
  public Long appendAndReturnId(EventLog eventLog, String tenancyId) {
    append(eventLog, tenancyId);
    return (long) entries.size();
  }

  @Override
  public EventLog findById(Long id, String tenancyId) {
    return null;
  }

  @Override
  public List<EventLog> findSchedulingEvents(
      UUID caseId, String workerId, Instant after, String tenancyId) {
    return List.of();
  }

  @Override
  public List<EventLog> findByCaseAndTypes(
      UUID caseId, Collection<CaseHubEventType> types, String tenancyId) {
    return entries.stream()
        .filter(
            e ->
                e.getCaseId().equals(caseId)
                    && types.contains(e.getEventType())
                    && tenancyId.equals(e.tenancyId))
        .toList();
  }

  @Override
  public List<EventLog> findByCaseAndWorkerAndType(
      UUID caseId, String workerId, CaseHubEventType type, String tenancyId) {
    return List.of();
  }

  @Override
  public List<EventLog> findByWorkerAndType(
      String workerId, CaseHubEventType type, String tenancyId) {
    return List.of();
  }

  @Override
  public List<EventLog> findByCaseWithFilters(
      UUID caseId,
      Collection<CaseHubEventType> eventTypes,
      Collection<EventStreamType> streamTypes,
      String tenancyId) {
    return List.of();
  }
}
