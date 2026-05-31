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
import io.smallrye.mutiny.Uni;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Storage provider for event log entries. tenancyId is an explicit parameter on every method.
 * Cross-tenant methods (findByTypes, findSubmittedWorkWithoutCompletion,
 * findByWorkerAndTypeAcrossTenants) are in CrossTenantEventLogRepository (runtime internal).
 */
public interface EventLogRepository {

  Uni<Void> append(EventLog eventLog, String tenancyId);

  Uni<Long> appendAndReturnId(EventLog eventLog, String tenancyId);

  Uni<EventLog> findById(Long id, String tenancyId);

  Uni<List<EventLog>> findSchedulingEvents(
      UUID caseId, String workerId, Instant after, String tenancyId);

  /**
   * Convenience overload with no time cutoff — equivalent to {@code findSchedulingEvents(caseId,
   * workerId, null, tenancyId)}.
   */
  default Uni<List<EventLog>> findSchedulingEvents(UUID caseId, String workerId, String tenancyId) {
    return findSchedulingEvents(caseId, workerId, null, tenancyId);
  }

  Uni<List<EventLog>> findByCaseAndTypes(
      UUID caseId, Collection<CaseHubEventType> types, String tenancyId);

  Uni<List<EventLog>> findByCaseAndWorkerAndType(
      UUID caseId, String workerId, CaseHubEventType type, String tenancyId);

  /**
   * Same-tenant lookup. Used by SubCaseCompletionService — sub-cases are always in the same tenant.
   * NOT the cross-tenant recovery variant (see
   * CrossTenantEventLogRepository.findByWorkerAndTypeAcrossTenants).
   */
  Uni<List<EventLog>> findByWorkerAndType(String workerId, CaseHubEventType type, String tenancyId);

  Uni<List<EventLog>> findByCaseWithFilters(
      UUID caseId,
      Collection<CaseHubEventType> eventTypes,
      Collection<EventStreamType> streamTypes,
      String tenancyId);
}
