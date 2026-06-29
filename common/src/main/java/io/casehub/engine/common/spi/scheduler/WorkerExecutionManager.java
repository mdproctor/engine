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
import io.casehub.worker.api.WorkerFunction;
import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface WorkerExecutionManager {

  /**
   * Returns whether this manager can handle the given capability for the specified tenant.
   *
   * <p>Called by {@code CompositeWorkerExecutionManager} during routing to filter eligible
   * backends. The manager should return {@code true} if it has workers configured to execute the
   * capability, {@code false} otherwise.
   *
   * @param capabilityName the capability name to check
   * @param tenancyId the tenant ID for multi-tenant deployments
   * @return {@code true} if this manager can execute the capability for this tenant
   */
  boolean supports(String capabilityName, String tenancyId);

  /**
   * Returns whether this manager can execute the given worker function type.
   *
   * <p>Called by routing strategies alongside {@code supports()} to determine backend eligibility.
   * In-process backends (Quartz) override to reject function types they cannot handle. External
   * backends inherit the default {@code true} — they dispatch externally regardless of function
   * type.
   *
   * @param function the worker function to check
   * @return {@code true} if this manager can execute the function type
   */
  default boolean canExecute(WorkerFunction function) {
    return true;
  }

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

  default Uni<Void> schedulePersistedEvent(EventLog scheduledEventLog) {
    return Uni.createFrom().voidItem();
  }

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
