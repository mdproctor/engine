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
package io.casehub.engine.internal.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.WorkResult;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Shared logic for transitioning a WAITING case back to RUNNING after the work it was waiting for
 * has completed. Used by both {@link
 * io.casehub.engine.internal.engine.handler.WorkflowExecutionCompletedHandler} (Quartz worker path)
 * and SubCaseCompletionListener (SubCase path). See casehubio/engine#195.
 */
@ApplicationScoped
public class CaseResumptionService {

  private static final Logger LOG = Logger.getLogger(CaseResumptionService.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Inject CaseInstanceRepository caseInstanceRepository;
  @Inject PendingWorkRegistry pendingWorkRegistry;

  public void resumeIfWaiting(
      CaseInstance caseInstance,
      String correlationKey,
      String workerId,
      Map<String, Object> rawOutput,
      CaseHubEventType eventType) {

    boolean isWaiting = caseInstance.getState() == CaseStatus.WAITING;
    boolean isMatchingWork =
        correlationKey != null && correlationKey.equals(caseInstance.getWaitingForWorkId());

    if (!isWaiting || !isMatchingWork) {
      completeRegisteredFuture(correlationKey, workerId, rawOutput, caseInstance.getUuid());
      return;
    }

    caseInstance.setState(CaseStatus.RUNNING);
    caseInstance.setWaitingForWorkId(null);

    EventLog completedLog = new EventLog();
    completedLog.setCaseId(caseInstance.getUuid());
    completedLog.setWorkerId(workerId);
    completedLog.setStreamType(EventStreamType.CASE);
    completedLog.setTimestamp(Instant.now());
    completedLog.setEventType(eventType);
    ObjectNode meta = OBJECT_MAPPER.createObjectNode();
    meta.put("correlationKey", correlationKey);
    completedLog.setMetadata(meta);

    LOG.debugf(
        "Resuming WAITING case %s → RUNNING (correlationKey=%s eventType=%s)",
        caseInstance.getUuid(), correlationKey, eventType);

    caseInstanceRepository.updateStateAndAppendEvent(
        caseInstance, completedLog, caseInstance.tenancyId);
    completeRegisteredFuture(correlationKey, workerId, rawOutput, caseInstance.getUuid());
  }

  private void completeRegisteredFuture(
      String correlationKey, String workerId, Map<String, Object> output, java.util.UUID caseId) {
    if (correlationKey != null && pendingWorkRegistry.hasPending(correlationKey)) {
      pendingWorkRegistry.complete(
          correlationKey, WorkResult.completed(correlationKey, output, workerId, caseId));
    }
  }
}
