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
package io.casehub.engine.scheduler.quartz;

import static org.quartz.JobBuilder.newJob;
import static org.quartz.TriggerBuilder.newTrigger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.RetryState;
import io.casehub.api.model.RetryState.RetryAttempt;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkerRetriesExhaustedEvent;
import io.casehub.engine.common.internal.executor.RetryDecision;
import io.casehub.engine.common.internal.executor.RetryPolicies;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.EventLogRepository;
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
import java.util.Date;
import java.util.List;
import org.jboss.logging.Logger;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Trigger;

/**
 * Owns worker failure handling: persists the failure event log, resolves retry policy, counts prior
 * failures, and either reschedules the worker or publishes {@link
 * EventBusAddresses#WORKER_RETRIES_EXHAUSTED}.
 *
 * <p>Extracted from {@code QuartzWorkerExecutionJobListener} so that both sync and async (flow)
 * failure paths converge on a single retry implementation. Uses {@link RetryPolicies} for the
 * retry/exhaust decision.
 *
 * <p>Refs casehubio/engine#463.
 */
@ApplicationScoped
class QuartzRetryService {

  private static final Logger LOG = Logger.getLogger(QuartzRetryService.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final EventLogRepository eventLogRepository;
  private final WorkerExecutionRecoveryService recoveryService;
  private final CaseDefinitionRegistry caseDefinitionRegistry;
  private final QuartzWorkerSchedulerService schedulerService;
  private final EventBus eventBus;

  private final io.casehub.engine.common.spi.recovery.RecoveryCoordinator recoveryCoordinator;

  @Inject
  QuartzRetryService(
      EventLogRepository eventLogRepository,
      WorkerExecutionRecoveryService recoveryService,
      CaseDefinitionRegistry caseDefinitionRegistry,
      QuartzWorkerSchedulerService schedulerService,
      EventBus eventBus,
      io.casehub.engine.common.spi.recovery.RecoveryCoordinator recoveryCoordinator) {
    this.eventLogRepository = eventLogRepository;
    this.recoveryService = recoveryService;
    this.caseDefinitionRegistry = caseDefinitionRegistry;
    this.schedulerService = schedulerService;
    this.eventBus = eventBus;
    this.recoveryCoordinator = recoveryCoordinator;
  }

  void handleFailure(WorkerRetryContext ctx, String errorMessage) {
    EventLog failureLog = buildFailureEventLog(ctx, errorMessage);
    eventLogRepository.append(failureLog, ctx.tenancyId());
    maybeRescheduleWorker(ctx);
  }

  private void maybeRescheduleWorker(WorkerRetryContext ctx) {
    CaseInstance instance = recoveryService.loadOrRestoreCaseInstance(ctx.caseId());
    RetryPolicy retryPolicy = resolveRetryPolicy(instance, ctx.workerId());
    if (retryPolicy == null) {
      return;
    }
    long failureCount = countFailedAttempts(ctx);
    applyRetryDecision(ctx, retryPolicy, failureCount);
  }

  private void applyRetryDecision(
      WorkerRetryContext ctx, RetryPolicy retryPolicy, long failureCount) {
    RetryDecision decision = RetryPolicies.evaluate((int) failureCount, retryPolicy);
    switch (decision) {
      case RetryDecision.Retry retry -> {
        LOG.infof(
            "Rescheduling worker %s: attempt %d/%d, delay=%dms",
            ctx.workerId(), failureCount + 1, retryPolicy.maxAttempts(), retry.delay().toMillis());
        rescheduleWorker(ctx, retry.delay().toMillis());
      }
      case RetryDecision.Exhaust exhaust -> {
        LOG.warnf(
            "Worker %s exhausted all %d retry attempts for case %s: %s",
            ctx.workerId(), retryPolicy.maxAttempts(), ctx.caseId(), exhaust.reason());
        var recoveryCtx =
            new io.casehub.engine.common.spi.recovery.RecoveryContext(
                ctx.caseId(),
                ctx.tenancyId(),
                ctx.bindingName(),
                ctx.workerId(),
                null,
                null,
                null,
                (int) failureCount,
                null);
        if (recoveryCoordinator.handleFailure(recoveryCtx)) {
          return;
        }
        RetryState retryState = buildRetryState(ctx);
        eventBus.publish(
            EventBusAddresses.WORKER_RETRIES_EXHAUSTED,
            new WorkerRetriesExhaustedEvent(
                ctx.caseId(),
                ctx.tenancyId(),
                ctx.workerId(),
                ctx.inputDataHash(),
                ctx.bindingName(),
                ctx.signalId(),
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

  private long countFailedAttempts(WorkerRetryContext ctx) {
    List<EventLog> eventLogs =
        eventLogRepository.findByCaseAndWorkerAndType(
            ctx.caseId(),
            ctx.workerId(),
            CaseHubEventType.WORKER_EXECUTION_FAILED,
            ctx.tenancyId());
    return eventLogs.stream()
        .filter(
            eventLog -> {
              JsonNode metadata = eventLog.getMetadata();
              JsonNode hashNode = metadata == null ? null : metadata.get("inputDataHash");
              return hashNode != null && ctx.inputDataHash().equals(hashNode.asText());
            })
        .count();
  }

  private RetryState buildRetryState(WorkerRetryContext ctx) {
    List<EventLog> eventLogs =
        eventLogRepository.findByCaseAndWorkerAndType(
            ctx.caseId(),
            ctx.workerId(),
            CaseHubEventType.WORKER_EXECUTION_FAILED,
            ctx.tenancyId());

    List<RetryAttempt> attempts = new ArrayList<>();
    Instant firstAttemptTime = null;
    Instant lastAttemptTime = null;

    for (EventLog log : eventLogs) {
      JsonNode metadata = log.getMetadata();
      JsonNode hashNode = metadata == null ? null : metadata.get("inputDataHash");
      if (hashNode == null || !ctx.inputDataHash().equals(hashNode.asText())) {
        continue;
      }

      Instant timestamp = log.getTimestamp();
      if (firstAttemptTime == null || timestamp.isBefore(firstAttemptTime)) {
        firstAttemptTime = timestamp;
      }
      if (lastAttemptTime == null || timestamp.isAfter(lastAttemptTime)) {
        lastAttemptTime = timestamp;
      }

      String errorMessage =
          metadata != null && metadata.has("errorMessage")
              ? metadata.get("errorMessage").asText()
              : "unknown";

      // Duration is not stored in event log metadata — use a sentinel value
      attempts.add(new RetryAttempt(timestamp, errorMessage, Duration.ZERO, false));
    }

    if (attempts.isEmpty()) {
      return RetryState.empty();
    }

    return RetryState.of(attempts, firstAttemptTime, lastAttemptTime);
  }

  private void rescheduleWorker(WorkerRetryContext ctx, long delayMs) {
    String group = ctx.caseId().toString();
    JobKey jobKey = new JobKey(ctx.inputDataHash(), group);

    JobDataMap dataMap = new JobDataMap();
    dataMap.put("inputDataHash", ctx.inputDataHash());
    dataMap.put("eventLogId", ctx.eventLogId());
    dataMap.put("workerId", ctx.workerId());
    dataMap.put("caseHubInstanceUuid", ctx.caseId().toString());
    dataMap.put("tenancyId", ctx.tenancyId());
    if (ctx.bindingName() != null) {
      dataMap.put("bindingName", ctx.bindingName());
    }
    if (ctx.signalId() != null) {
      dataMap.put("signalId", ctx.signalId().toString());
    }

    JobDetail job =
        newJob(QuartzWorkerExecutionJob.class)
            .withIdentity(jobKey)
            .storeDurably(false)
            .usingJobData(dataMap)
            .build();

    Trigger trigger =
        newTrigger()
            .withIdentity(ctx.inputDataHash(), group)
            .startAt(new Date(System.currentTimeMillis() + delayMs))
            .forJob(jobKey)
            .build();

    schedulerService.scheduleRetry(job, trigger);
  }

  private EventLog buildFailureEventLog(WorkerRetryContext ctx, String errorMessage) {
    EventLog eventLog = new EventLog();
    eventLog.setCaseId(ctx.caseId());
    eventLog.setWorkerId(ctx.workerId());
    eventLog.setEventType(CaseHubEventType.WORKER_EXECUTION_FAILED);
    eventLog.setStreamType(EventStreamType.CASE);
    eventLog.setTimestamp(Instant.now());
    eventLog.setMetadata(
        MAPPER
            .createObjectNode()
            .put("inputDataHash", ctx.inputDataHash())
            .put("errorMessage", errorMessage != null ? errorMessage : "unknown"));
    return eventLog;
  }
}
