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
import io.casehub.engine.common.internal.history.EventLog;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Blocking cross-tenant event log access for startup recovery services only.
 *
 * @see CrossTenantEventLogRepository
 */
public interface CrossTenantEventLogRepository {

  /** All events of the given types across all tenants. Recovery: reschedule orphaned workers. */
  List<EventLog> findByTypes(Collection<CaseHubEventType> types);

  /**
   * Events for a specific case — caseId is UUID (globally unique). Recovery: rebuild case state
   * context.
   */
  List<EventLog> findByCaseAndTypes(UUID caseId, Collection<CaseHubEventType> types);

  /** Find WORK_SUBMITTED events with no matching WORK_COMPLETED across all tenants. */
  List<String> findSubmittedWorkWithoutCompletion();

  /** Cross-tenant variant of findByWorkerAndType — recovery only. */
  List<EventLog> findByWorkerAndTypeAcrossTenants(String workerId, CaseHubEventType type);

  /**
   * Look up an event log entry by surrogate id without tenant filter. Used by Quartz jobs that have
   * the event log id from job data but no principal context.
   */
  EventLog findById(Long id);

  /**
   * Cross-tenant variant of findByCaseAndWorkerAndType. Used by DLQ replay and system services that
   * operate across tenants.
   */
  List<EventLog> findByCaseAndWorkerAndType(UUID caseId, String workerId, CaseHubEventType type);
}
