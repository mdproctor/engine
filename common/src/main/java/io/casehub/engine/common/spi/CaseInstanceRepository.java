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

import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.smallrye.mutiny.Uni;
import java.util.UUID;

/**
 * Storage provider for {@link CaseInstance} lifecycle. Implementations must handle their own
 * session/transaction management; callers do not wrap calls in Panache.withTransaction().
 */
public interface CaseInstanceRepository {

  /** Persist a new CaseInstance. Sets {@code instance.id} on completion. */
  Uni<CaseInstance> save(CaseInstance instance);

  /** Update mutable fields (state, parentPlanItemId) of an existing CaseInstance. */
  Uni<CaseInstance> update(CaseInstance instance);

  /** Look up a CaseInstance by its business UUID. Returns null if not found. */
  Uni<CaseInstance> findByUuid(UUID uuid);

  /**
   * Atomically update the instance state and append an event log entry. JPA implementations wrap
   * both writes in a single transaction. In-memory implementations perform both synchronously. Sets
   * {@code eventLog.id} and {@code eventLog.seq} on completion.
   */
  Uni<Void> updateStateAndAppendEvent(CaseInstance instance, EventLog eventLog);
}
