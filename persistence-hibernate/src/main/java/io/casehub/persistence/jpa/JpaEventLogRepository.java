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
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Blocking JPA {@link EventLogRepository}. Delegates to {@link JpaReactiveEventLogRepository} and
 * awaits.
 */
@ApplicationScoped
public class JpaEventLogRepository implements EventLogRepository {

  @Inject JpaReactiveEventLogRepository delegate;

  @Override
  public void append(EventLog eventLog, String tenancyId) {
    delegate.append(eventLog, tenancyId).await().indefinitely();
  }

  @Override
  public Long appendAndReturnId(EventLog eventLog, String tenancyId) {
    return delegate.appendAndReturnId(eventLog, tenancyId).await().indefinitely();
  }

  @Override
  public EventLog findById(Long id, String tenancyId) {
    return delegate.findById(id, tenancyId).await().indefinitely();
  }

  @Override
  public List<EventLog> findSchedulingEvents(
      UUID caseId, String workerId, Instant after, String tenancyId) {
    return delegate.findSchedulingEvents(caseId, workerId, after, tenancyId).await().indefinitely();
  }

  @Override
  public List<EventLog> findByCaseAndTypes(
      UUID caseId, Collection<CaseHubEventType> types, String tenancyId) {
    return delegate.findByCaseAndTypes(caseId, types, tenancyId).await().indefinitely();
  }

  @Override
  public List<EventLog> findByCaseAndWorkerAndType(
      UUID caseId, String workerId, CaseHubEventType type, String tenancyId) {
    return delegate
        .findByCaseAndWorkerAndType(caseId, workerId, type, tenancyId)
        .await()
        .indefinitely();
  }

  @Override
  public List<EventLog> findByWorkerAndType(
      String workerId, CaseHubEventType type, String tenancyId) {
    return delegate.findByWorkerAndType(workerId, type, tenancyId).await().indefinitely();
  }

  @Override
  public List<EventLog> findByCaseWithFilters(
      UUID caseId,
      Collection<CaseHubEventType> eventTypes,
      Collection<EventStreamType> streamTypes,
      String tenancyId) {
    return delegate
        .findByCaseWithFilters(caseId, eventTypes, streamTypes, tenancyId)
        .await()
        .indefinitely();
  }

  @Override
  public List<EventLog> query(EventLogQuery query, String tenancyId) {
    return delegate.query(query, tenancyId).await().indefinitely();
  }

  @Override
  public long count(EventLogQuery query, String tenancyId) {
    return delegate.count(query, tenancyId).await().indefinitely();
  }
}
