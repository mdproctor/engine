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
package io.casehub.engine.common.spi.scheduler;

import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface WorkerExecutionManager {

  /**
   * Submit a worker for execution.
   *
   * <p>{@code inputData} is {@code Map<String, Object>} at this layer because it is post-evaluation
   * data — the result of applying {@code inputMapping} expressions against {@link
   * io.casehub.api.context.CaseContext}. This is the correct type at the engine-internal layer.
   * Public entry points ({@link io.casehub.api.engine.CaseHub#startCase} and {@link
   * io.casehub.api.engine.CaseHubRuntime#startCase}) should accept {@code Object} to align with
   * {@code Flow.instance(Object)} — tracked in casehubio/engine#302.
   */
  Uni<Void> submit(
      Long eventLogId,
      CaseInstance instance,
      Worker worker,
      Capability capability,
      Map<String, Object> inputData);

  Uni<Void> schedulePersistedEvent(EventLog scheduledEventLog);

  int getActiveWorkCount(String workerId);

  /**
   * Returns the case UUIDs for all Quartz jobs currently executing for this worker.
   *
   * <p>This is a best-effort snapshot — a job completing between this call and the HTTP response
   * means the list may transiently contain cases whose work just finished. Acceptable for a
   * monitoring/observability endpoint.
   *
   * <p>NOTE: workerId here equals actorId at the REST layer — same string, different naming
   * convention.
   *
   * @param workerId the worker name from the case definition YAML
   * @return list of case UUIDs currently executing; empty list if none or on error
   */
  default List<UUID> getActiveCaseIds(String workerId) {
    return List.of();
  }
}
