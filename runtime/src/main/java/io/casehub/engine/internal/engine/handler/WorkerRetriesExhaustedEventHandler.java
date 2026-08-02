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
package io.casehub.engine.internal.engine.handler;

import io.casehub.api.model.CaseStatus;
import io.casehub.api.spi.WorkerStatusListener;
import io.casehub.engine.common.internal.event.CaseStatusChanged;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkerRetriesExhaustedEvent;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.internal.engine.SignalSettlementTracker;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class WorkerRetriesExhaustedEventHandler {

  private static final Logger LOG = Logger.getLogger(WorkerRetriesExhaustedEventHandler.class);

  private final CaseInstanceCache caseInstanceCache;
  private final EventBus eventBus;
  private final WorkerStatusListener workerStatusListener;
  private final SignalSettlementTracker settlementTracker;

  @Inject
  WorkerRetriesExhaustedEventHandler(
      CaseInstanceCache caseInstanceCache,
      EventBus eventBus,
      WorkerStatusListener workerStatusListener,
      SignalSettlementTracker settlementTracker) {
    this.caseInstanceCache = caseInstanceCache;
    this.eventBus = eventBus;
    this.workerStatusListener = workerStatusListener;
    this.settlementTracker = settlementTracker;
  }

  @ConsumeEvent(value = EventBusAddresses.WORKER_RETRIES_EXHAUSTED)
  @RunOnVirtualThread
  void onWorkerRetriesExhaustedEvent(WorkerRetriesExhaustedEvent event) {
    try {
      if (event.signalId() != null) {
        settlementTracker.recordCompletion(event.signalId());
      }

      CaseInstance caseInstance = caseInstanceCache.get(event.caseId());
      String oldStatus = caseInstance.getState().name();

      LOG.warnf(
          "Worker retries exhausted for caseId=%s, workerId=%s", event.caseId(), event.workerId());
      workerStatusListener.onWorkerStalled(event.workerId());
      eventBus.publish(
          EventBusAddresses.CASE_STATUS_CHANGED,
          new CaseStatusChanged(caseInstance, oldStatus, CaseStatus.FAULTED.name()));
    } catch (Exception e) {
      LOG.errorf(
          e,
          "Failed to process WORKER_RETRIES_EXHAUSTED for caseId=%s workerId=%s",
          event.caseId(),
          event.workerId());
    }
  }
}
