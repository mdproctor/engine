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
import io.casehub.api.spi.event.EventDispatcher;
import io.casehub.engine.common.internal.event.CaseStatusChanged;
import io.casehub.engine.common.internal.event.WorkerRetriesExhaustedEvent;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.internal.engine.SignalSettlementTracker;
import org.jboss.logging.Logger;

public class WorkerRetriesExhaustedEventHandler {

  private static final Logger LOG = Logger.getLogger(WorkerRetriesExhaustedEventHandler.class);

  private final CaseInstanceCache caseInstanceCache;
  private final EventDispatcher eventDispatcher;
  private final WorkerStatusListener workerStatusListener;
  private final SignalSettlementTracker settlementTracker;

  public WorkerRetriesExhaustedEventHandler(
      CaseInstanceCache caseInstanceCache,
      EventDispatcher eventDispatcher,
      WorkerStatusListener workerStatusListener,
      SignalSettlementTracker settlementTracker) {
    this.caseInstanceCache = caseInstanceCache;
    this.eventDispatcher = eventDispatcher;
    this.workerStatusListener = workerStatusListener;
    this.settlementTracker = settlementTracker;
  }

  public void handle(WorkerRetriesExhaustedEvent event) {
    try {
      if (event.signalId() != null) {
        settlementTracker.recordCompletion(event.signalId());
      }

      CaseInstance caseInstance = caseInstanceCache.get(event.caseId());
      String oldStatus = caseInstance.getState().name();

      LOG.warnf(
          "Worker retries exhausted for caseId=%s, workerId=%s", event.caseId(), event.workerId());
      workerStatusListener.onWorkerStalled(event.workerId());
      eventDispatcher.dispatch(
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
