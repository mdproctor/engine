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

import io.casehub.api.model.Capability;
import io.casehub.api.model.Worker;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.smallrye.mutiny.Uni;
import java.util.Map;

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
}
