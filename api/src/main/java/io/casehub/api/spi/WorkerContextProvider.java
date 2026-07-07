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
package io.casehub.api.spi;

import io.casehub.api.context.PropagationContext;
import io.casehub.api.model.WorkRequest;
import io.casehub.api.model.WorkerContext;
import java.util.UUID;

/**
 * Builds startup context for a new worker.
 *
 * <p>Implementations query {@code CaseLedgerEntryRepository} (not EventLog) for prior worker
 * history, constructing {@link io.casehub.api.model.WorkerSummary} entries with {@code
 * ledgerEntryId} populated so new workers can set {@code causedByEntryId} on their own ledger
 * entries.
 *
 * <p>Implementations also populate {@link WorkerContext#channels()} by calling {@code
 * CaseChannelProvider.listChannels(caseId)}, giving workers access to the channels open for their
 * case during execution via {@link io.casehub.api.model.WorkerExecutionContext#current()}.
 */
public interface WorkerContextProvider {

  /**
   * Build context for a worker about to start work on a task.
   *
   * @param workerId the ID of the worker being started
   * @param caseId the ID of the case the worker is executing for; may be {@code null} for
   *     provisioning-only flows where no live case exists yet
   * @param task the work request describing what the worker should do
   * @return startup context including task description, open channels, and lineage
   */
  WorkerContext buildContext(String workerId, UUID caseId, WorkRequest task);

  /**
   * Build context for a worker, inheriting identity and tracing from the parent case's {@link
   * PropagationContext}. Callers should prefer this overload when a live case instance is available
   * so that traceId, inherited attributes (userId, roles), and budget/deadline propagate to the
   * worker via {@link PropagationContext#createChild()}.
   *
   * <p>The default delegates to the 3-arg overload for backward compatibility with existing
   * implementations.
   *
   * @param workerId the ID of the worker being started
   * @param caseId the ID of the case the worker is executing for; may be {@code null}
   * @param task the work request describing what the worker should do
   * @param parentContext the parent case's propagation context carrying identity and tracing
   * @return startup context with propagation context inherited from the parent
   */
  default WorkerContext buildContext(
      String workerId, UUID caseId, WorkRequest task, PropagationContext parentContext) {
    return buildContext(workerId, caseId, task);
  }
}
