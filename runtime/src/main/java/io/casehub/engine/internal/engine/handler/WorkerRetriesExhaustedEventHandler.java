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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.api.spi.WorkerStatusListener;
import io.casehub.engine.common.internal.event.CaseStatusChanged;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkerRetriesExhaustedEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import org.jboss.logging.Logger;

/**
 * Handles worker retry exhaustion by marking the case as FAULTED. Atomically updates the instance
 * state and appends the event log entry.
 */
@ApplicationScoped
public class WorkerRetriesExhaustedEventHandler {

  private static final Logger LOG = Logger.getLogger(WorkerRetriesExhaustedEventHandler.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Inject CaseInstanceCache caseInstanceCache;

  @Inject EventBus eventBus;

  @Inject CaseInstanceRepository caseInstanceRepository;

  @Inject WorkerStatusListener workerStatusListener;

  @ConsumeEvent(value = EventBusAddresses.WORKER_RETRIES_EXHAUSTED)
  public Uni<Void> onWorkerRetriesExhaustedEvent(WorkerRetriesExhaustedEvent event) {
    CaseInstance caseInstance = caseInstanceCache.get(event.caseId());
    String oldStatus = caseInstance.getState().name();
    caseInstance.setState(CaseStatus.FAULTED);

    EventLog eventLog = new EventLog();
    eventLog.setEventType(CaseHubEventType.CASE_FAULTED);
    eventLog.setCaseId(caseInstance.getUuid());
    eventLog.setStreamType(EventStreamType.CASE);
    eventLog.setTimestamp(Instant.now());
    eventLog.setWorkerId(event.workerId());
    eventLog.setMetadata(
        OBJECT_MAPPER
            .createObjectNode()
            .put("workerId", event.workerId())
            .put("inputDataHash", event.idempotency()));

    return caseInstanceRepository
        .updateStateAndAppendEvent(caseInstance, eventLog)
        .invoke(
            () -> {
              LOG.warnf(
                  "Worker retries exhausted for caseId=%s, workerId=%s",
                  event.caseId(), event.workerId());
              workerStatusListener.onWorkerStalled(event.workerId());
              eventBus.publish(
                  EventBusAddresses.CASE_STATUS_CHANGED,
                  new CaseStatusChanged(caseInstance, oldStatus, CaseStatus.FAULTED.name()));
            });
  }
}
