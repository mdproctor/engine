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
 * Storage provider for {@link CaseInstance} lifecycle. Implementations handle their own
 * session/transaction management; callers do not wrap calls in Panache.withTransaction(). tenancyId
 * is an explicit parameter on every method — no CDI scope injection in implementations.
 */
public interface CaseInstanceRepository {

  /** Persist a new CaseInstance scoped to tenancyId. Sets {@code instance.id} on completion. */
  Uni<CaseInstance> save(CaseInstance instance, String tenancyId);

  /** Update mutable fields. tenancyId is included in the WHERE clause. */
  Uni<CaseInstance> update(CaseInstance instance, String tenancyId);

  /** Look up by business UUID within the given tenant. Returns null if not found. */
  Uni<CaseInstance> findByUuid(UUID uuid, String tenancyId);

  /** Atomically update state and append event log entry within the same tenant. */
  Uni<Void> updateStateAndAppendEvent(CaseInstance instance, EventLog eventLog, String tenancyId);
}
