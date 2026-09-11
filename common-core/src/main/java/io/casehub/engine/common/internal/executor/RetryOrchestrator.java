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
package io.casehub.engine.common.internal.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.RetryState;
import io.casehub.api.model.RetryState.RetryAttempt;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkerRetriesExhaustedEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.recovery.RecoveryContext;
import io.casehub.engine.common.spi.recovery.RecoveryCoordinator;
import io.casehub.engine.common.spi.recovery.WorkerExecutionRecoveryService;
import io.casehub.platform.api.governance.ExecutionPolicy;
import io.casehub.platform.api.governance.RetryPolicy;
import io.casehub.worker.api.Worker;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jboss.logging.Logger;

@ApplicationScoped
public class RetryOrchestrator {

  private static final Logger LOG = Logger.getLogger(RetryOrchestrator.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final EventLogRepository eventLogRepository;
  private final WorkerExecutionRecoveryService recoveryService;
  private final CaseDefinitionRegistry caseDefinitionRegistry;
  private final EventBus eventBus;
  private final RecoveryCoordinator recoveryCoordinator;

  @Inject
  public RetryOrchestrator(
      EventLogRepository eventLogRepository,
      WorkerExecutionRecoveryService recoveryService,
      CaseDefinitionRegistry caseDefinitionRegistry,
      EventBus eventBus,
      RecoveryCoordinator recoveryCoordinator) {
    this.eventLogRepository = eventLogRepository;
    this.recoveryService = recoveryService;
    this.caseDefinitionRegistry = caseDefinitionRegistry;
    this.eventBus = eventBus;
    this.recoveryCoordinator = recoveryCoordinator;
  }

  public void handleFailure(
      WorkerTaskData taskData,
      Throwable cause,
      String errorMessage,
      RescheduleCallback rescheduleCallback) {

    persistFailureEventLog(taskData, errorMessage);

    CaseInstance instance = recoveryService.loadOrRestoreCaseInstance(taskData.caseId());
    RetryPolicy retryPolicy = resolveRetryPolicy(instance, taskData.workerId());
    if (retryPolicy == null) {
      return;
    }

    long failureCount = countFailedAttempts(taskData);
    applyRetryDecision(taskData, retryPolicy, failureCount, rescheduleCallback);
  }

  private void applyRetryDecision(
      WorkerTaskData taskData,
      RetryPolicy retryPolicy,
      long failureCount,
      RescheduleCallback rescheduleCallback) {

    RetryDecision decision = RetryPolicies.evaluate((int) failureCount, retryPolicy);
    switch (decision) {
      case RetryDecision.Retry retry -> {
        LOG.infof(
            "Rescheduling worker %s: attempt %d/%d, delay=%dms",
            taskData.workerId(),
            failureCount + 1,
            retryPolicy.maxAttempts(),
            retry.delay().toMillis());
        rescheduleCallback.reschedule(taskData, retry.delay());
      }
      case RetryDecision.Exhaust exhaust -> {
        LOG.warnf(
            "Worker %s exhausted all %d retry attempts for case %s: %s",
            taskData.workerId(), retryPolicy.maxAttempts(), taskData.caseId(), exhaust.reason());

        RecoveryContext recoveryCtx =
            new RecoveryContext(
                taskData.caseId(),
                taskData.tenancyId(),
                taskData.bindingName(),
                taskData.workerId(),
                null,
                null,
                (int) failureCount,
                null);

        if (recoveryCoordinator.handleFailure(recoveryCtx)) {
          return;
        }

        RetryState retryState = buildRetryState(taskData);
        eventBus.publish(
            EventBusAddresses.WORKER_RETRIES_EXHAUSTED,
            new WorkerRetriesExhaustedEvent(
                taskData.caseId(),
                taskData.tenancyId(),
                taskData.workerId(),
                taskData.inputDataHash(),
                taskData.bindingName(),
                taskData.signalId(),
                retryState));
      }
    }
  }

  private RetryPolicy resolveRetryPolicy(CaseInstance instance, String workerId) {
    CaseDefinition definition =
        caseDefinitionRegistry.getCaseDefinition(instance.getCaseMetaModel());
    if (definition == null) {
      LOG.errorf("Cannot retry: CaseDefinition not found for caseId=%s", instance.getUuid());
      return null;
    }

    Worker worker =
        definition.getWorkers().stream()
            .filter(w -> w.name().equals(workerId))
            .findFirst()
            .orElse(null);
    if (worker == null) {
      LOG.errorf("Cannot retry: Worker not found: %s", workerId);
      return null;
    }

    ExecutionPolicy executionPolicy = worker.executionPolicy();
    if (executionPolicy == null || executionPolicy.retries() == null) {
      return new ExecutionPolicy().retries();
    }
    return executionPolicy.retries();
  }

  private long countFailedAttempts(WorkerTaskData taskData) {
    List<EventLog> eventLogs =
        eventLogRepository.findByCaseAndWorkerAndType(
            taskData.caseId(),
            taskData.workerId(),
            CaseHubEventType.WORKER_EXECUTION_FAILED,
            taskData.tenancyId());
    return eventLogs.stream()
        .filter(
            eventLog -> {
              JsonNode metadata = eventLog.getMetadata();
              JsonNode hashNode = metadata == null ? null : metadata.get("inputDataHash");
              return hashNode != null && taskData.inputDataHash().equals(hashNode.asText());
            })
        .count();
  }

  private RetryState buildRetryState(WorkerTaskData taskData) {
    List<EventLog> eventLogs =
        eventLogRepository.findByCaseAndWorkerAndType(
            taskData.caseId(),
            taskData.workerId(),
            CaseHubEventType.WORKER_EXECUTION_FAILED,
            taskData.tenancyId());

    List<RetryAttempt> attempts = new ArrayList<>();
    Instant firstAttemptTime = null;
    Instant lastAttemptTime = null;

    for (EventLog log : eventLogs) {
      JsonNode metadata = log.getMetadata();
      JsonNode hashNode = metadata == null ? null : metadata.get("inputDataHash");
      if (hashNode == null || !taskData.inputDataHash().equals(hashNode.asText())) {
        continue;
      }

      Instant timestamp = log.getTimestamp();
      if (firstAttemptTime == null || timestamp.isBefore(firstAttemptTime)) {
        firstAttemptTime = timestamp;
      }
      if (lastAttemptTime == null || timestamp.isAfter(lastAttemptTime)) {
        lastAttemptTime = timestamp;
      }

      String errorMsg =
          metadata.has("errorMessage") ? metadata.get("errorMessage").asText() : "unknown";
      attempts.add(new RetryAttempt(timestamp, errorMsg, Duration.ZERO, false));
    }

    if (attempts.isEmpty()) {
      return RetryState.empty();
    }
    return RetryState.of(attempts, firstAttemptTime, lastAttemptTime);
  }

  private void persistFailureEventLog(WorkerTaskData taskData, String errorMessage) {
    EventLog eventLog = new EventLog();
    eventLog.setCaseId(taskData.caseId());
    eventLog.setWorkerId(taskData.workerId());
    eventLog.setEventType(CaseHubEventType.WORKER_EXECUTION_FAILED);
    eventLog.setStreamType(EventStreamType.CASE);
    eventLog.setTimestamp(Instant.now());
    eventLog.setMetadata(
        MAPPER
            .createObjectNode()
            .put("inputDataHash", taskData.inputDataHash())
            .put("errorMessage", errorMessage != null ? errorMessage : "unknown"));
    eventLogRepository.append(eventLog, taskData.tenancyId());
  }
}
