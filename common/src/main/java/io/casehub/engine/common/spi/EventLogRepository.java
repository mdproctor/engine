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
package io.casehub.engine.common.spi;

import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.history.EventLog;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Blocking SPI for event log entry persistence.
 *
 * <p>tenancyId is an explicit parameter on every method. Cross-tenant methods (findByTypes,
 * findSubmittedWorkWithoutCompletion, findByWorkerAndTypeAcrossTenants) are in {@link
 * CrossTenantEventLogRepository}.
 *
 * @see ReactiveEventLogRepository
 */
public interface EventLogRepository {

  void append(EventLog eventLog, String tenancyId);

  Long appendAndReturnId(EventLog eventLog, String tenancyId);

  EventLog findById(Long id, String tenancyId);

  List<EventLog> findSchedulingEvents(
      UUID caseId, String workerId, Instant after, String tenancyId);

  /**
   * Convenience overload with no time cutoff — equivalent to {@code findSchedulingEvents(caseId,
   * workerId, null, tenancyId)}.
   */
  default List<EventLog> findSchedulingEvents(UUID caseId, String workerId, String tenancyId) {
    return findSchedulingEvents(caseId, workerId, null, tenancyId);
  }

  List<EventLog> findByCaseAndTypes(
      UUID caseId, Collection<CaseHubEventType> types, String tenancyId);

  List<EventLog> findByCaseAndWorkerAndType(
      UUID caseId, String workerId, CaseHubEventType type, String tenancyId);

  /**
   * Same-tenant lookup. Used by SubCaseCompletionService — sub-cases are always in the same tenant.
   * NOT the cross-tenant recovery variant (see {@link
   * CrossTenantEventLogRepository#findByWorkerAndTypeAcrossTenants}).
   */
  List<EventLog> findByWorkerAndType(String workerId, CaseHubEventType type, String tenancyId);

  List<EventLog> findByCaseWithFilters(
      UUID caseId,
      Collection<CaseHubEventType> eventTypes,
      Collection<EventStreamType> streamTypes,
      String tenancyId);
}
