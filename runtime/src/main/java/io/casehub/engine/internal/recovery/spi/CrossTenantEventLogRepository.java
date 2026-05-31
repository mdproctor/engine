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
package io.casehub.engine.internal.recovery.spi;

import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.engine.common.internal.history.EventLog;
import io.smallrye.mutiny.Uni;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Cross-tenant event log access for startup recovery services only.
 * Lives in internal.recovery.spi to prevent accidental injection outside recovery context.
 */
public interface CrossTenantEventLogRepository {

  /** All events of the given types across all tenants. Recovery: reschedule orphaned workers. */
  Uni<List<EventLog>> findByTypes(Collection<CaseHubEventType> types);

  /**
   * Events for a specific case — caseId is UUID (globally unique).
   * Recovery: rebuild case state context.
   */
  Uni<List<EventLog>> findByCaseAndTypes(UUID caseId, Collection<CaseHubEventType> types);

  /** Find WORK_SUBMITTED events with no matching WORK_COMPLETED across all tenants. */
  Uni<List<String>> findSubmittedWorkWithoutCompletion();

  /** Cross-tenant variant of findByWorkerAndType — recovery only. */
  Uni<List<EventLog>> findByWorkerAndTypeAcrossTenants(String workerId, CaseHubEventType type);
}
