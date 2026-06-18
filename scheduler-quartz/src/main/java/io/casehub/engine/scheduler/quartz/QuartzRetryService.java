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
import io.casehub.api.model.ExecutionPolicy;
import io.casehub.api.model.RetryPolicy;
import io.casehub.api.model.Worker;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkerRetriesExhaustedEvent;
import io.casehub.engine.common.internal.executor.RetryDecision;
import io.casehub.engine.common.internal.executor.RetryPolicies;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.utils.ReactiveUtils;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.recovery.WorkerExecutionRecoveryService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Date;
import java.util.function.Supplier;
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
  private final Vertx vertx;

  @Inject
  QuartzRetryService(
      EventLogRepository eventLogRepository,
      WorkerExecutionRecoveryService recoveryService,
      CaseDefinitionRegistry caseDefinitionRegistry,
      QuartzWorkerSchedulerService schedulerService,
      EventBus eventBus,
      Vertx vertx) {
    this.eventLogRepository = eventLogRepository;
    this.recoveryService = recoveryService;
    this.caseDefinitionRegistry = caseDefinitionRegistry;
    this.schedulerService = schedulerService;
    this.eventBus = eventBus;
    this.vertx = vertx;
  }

  Uni<Void> handleFailure(WorkerRetryContext ctx, String errorMessage) {
    EventLog failureLog = buildFailureEventLog(ctx, errorMessage);

    return persistEventLog(failureLog, ctx.tenancyId()).chain(() -> maybeRescheduleWorker(ctx));
  }

  private Uni<Void> maybeRescheduleWorker(WorkerRetryContext ctx) {
    return recoveryService
        .loadOrRestoreCaseInstance(ctx.caseId())
        .chain(
            instance -> {
              RetryPolicy retryPolicy = resolveRetryPolicy(instance, ctx.workerId());
              if (retryPolicy == null) {
                return Uni.createFrom().voidItem();
              }
              return countFailedAttempts(ctx)
                  .chain(failureCount -> applyRetryDecision(ctx, retryPolicy, failureCount));
            });
  }

  private Uni<Void> applyRetryDecision(
      WorkerRetryContext ctx, RetryPolicy retryPolicy, long failureCount) {
    RetryDecision decision = RetryPolicies.evaluate((int) failureCount, retryPolicy);
    return switch (decision) {
      case RetryDecision.Retry retry -> {
        LOG.infof(
            "Rescheduling worker %s: attempt %d/%d, delay=%dms",
            ctx.workerId(), failureCount + 1, retryPolicy.maxAttempts(), retry.delay().toMillis());
        yield rescheduleWorker(ctx, retry.delay().toMillis());
      }
      case RetryDecision.Exhaust exhaust -> {
        LOG.warnf(
            "Worker %s exhausted all %d retry attempts for case %s: %s",
            ctx.workerId(), retryPolicy.maxAttempts(), ctx.caseId(), exhaust.reason());
        eventBus.publish(
            EventBusAddresses.WORKER_RETRIES_EXHAUSTED,
            new WorkerRetriesExhaustedEvent(
                ctx.caseId(), ctx.workerId(), ctx.inputDataHash(), ctx.bindingName()));
        yield Uni.createFrom().voidItem();
      }
    };
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
            .filter(w -> w.getName().equals(workerId))
            .findFirst()
            .orElse(null);

    if (worker == null) {
      LOG.errorf("Cannot retry: Worker not found: %s", workerId);
      return null;
    }

    ExecutionPolicy executionPolicy = worker.getExecutionPolicy();
    if (executionPolicy == null || executionPolicy.retries() == null) {
      return new ExecutionPolicy().retries();
    }
    return executionPolicy.retries();
  }

  private Uni<Long> countFailedAttempts(WorkerRetryContext ctx) {
    return runOnSafeVertxContext(
        () ->
            eventLogRepository
                .findByCaseAndWorkerAndType(
                    ctx.caseId(),
                    ctx.workerId(),
                    CaseHubEventType.WORKER_EXECUTION_FAILED,
                    ctx.tenancyId())
                .map(
                    eventLogs ->
                        eventLogs.stream()
                            .filter(
                                eventLog -> {
                                  JsonNode metadata = eventLog.getMetadata();
                                  JsonNode hashNode =
                                      metadata == null ? null : metadata.get("inputDataHash");
                                  return hashNode != null
                                      && ctx.inputDataHash().equals(hashNode.asText());
                                })
                            .count()));
  }

  private Uni<Void> rescheduleWorker(WorkerRetryContext ctx, long delayMs) {
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

    return schedulerService.scheduleRetryAsync(job, trigger);
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

  private Uni<Void> persistEventLog(EventLog eventLog, String tenancyId) {
    return runOnSafeVertxContext(() -> eventLogRepository.append(eventLog, tenancyId));
  }

  private <T> Uni<T> runOnSafeVertxContext(Supplier<Uni<? extends T>> action) {
    return ReactiveUtils.runOnSafeVertxContext(vertx, action);
  }
}
