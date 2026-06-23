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
package io.casehub.engine.internal.worker;

import io.casehub.api.spi.ProvisioningException;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

/**
 * Default no-op WorkerExecutionManager. Signals misconfiguration on submit() — replace with a real
 * implementation (scheduler-quartz, workers-camel, etc.) to enable worker dispatch. All other
 * operations complete with no side-effects.
 */
@DefaultBean
@ApplicationScoped
public class NoOpWorkerExecutionManager implements WorkerExecutionManager {

  @Override
  public Uni<Void> submit(
      Long eventLogId,
      CaseInstance instance,
      Worker worker,
      Capability capability,
      Map<String, Object> inputData) {
    return Uni.createFrom()
        .failure(
            new ProvisioningException(
                "No WorkerExecutionManager configured — add an @ApplicationScoped"
                    + " WorkerExecutionManager implementation (scheduler-quartz or workers-camel)"));
  }

  @Override
  public Uni<Void> schedulePersistedEvent(EventLog scheduledEventLog) {
    return Uni.createFrom().voidItem();
  }

  @Override
  public int getActiveWorkCount(String workerId) {
    return 0;
  }
}
