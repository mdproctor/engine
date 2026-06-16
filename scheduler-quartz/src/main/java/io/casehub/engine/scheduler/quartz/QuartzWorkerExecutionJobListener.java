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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.api.spi.WorkerStatusListener;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.utils.ReactiveUtils;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.ledger.api.spi.LedgerTraceIdProvider;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.jboss.logging.Logger;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobListener;

/**
 * Quartz lifecycle listener — fires {@code WorkerExecutionStarted} events and persists the start
 * event log. Completion and failure handling is owned by {@link QuartzWorkerExecutionJob} via
 * {@link QuartzRetryService}.
 *
 * <p>Refs casehubio/engine#463.
 */
@ApplicationScoped
class QuartzWorkerExecutionJobListener implements JobListener {

  @Inject Vertx vertx;

  @Inject WorkerStatusListener workerStatusListener;

  @Inject Event<CaseLifecycleEvent> lifecycleEvents;

  @Inject EventLogRepository eventLogRepository;

  @Inject LedgerTraceIdProvider traceIdProvider;

  private static final Logger LOG = Logger.getLogger(QuartzWorkerExecutionJobListener.class);

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Override
  public String getName() {
    return QuartzWorkerExecutionJobListener.class.getSimpleName();
  }

  @Override
  public void jobToBeExecuted(JobExecutionContext context) {
    if (isNotWorkerExecutionJob(context)) {
      return;
    }

    String jobName = context.getJobDetail().getKey().toString();
    String idempotency = context.getMergedJobDataMap().getString("inputDataHash");
    String workerId = context.getMergedJobDataMap().getString("workerId");
    String caseHubInstanceUuid = context.getMergedJobDataMap().getString("caseHubInstanceUuid");
    String tenancyId = context.getMergedJobDataMap().getString("tenancyId");
    if (tenancyId == null) {
      LOG.warnf(
          "tenancyId absent from Quartz job data for job %s — lifecycle event will carry null tenancyId",
          jobName);
    }
    LOG.infof("Job is about to be executed: %s, idempotency=%s", jobName, idempotency);
    workerStatusListener.onWorkerStarted(workerId, Map.of("caseId", caseHubInstanceUuid));
    lifecycleEvents.fireAsync(
        new CaseLifecycleEvent(
            UUID.fromString(caseHubInstanceUuid),
            tenancyId,
            "ExecuteWorker",
            "WorkerExecutionStarted",
            null,
            workerId,
            "WORKER",
            traceIdProvider.currentTraceId().orElse(null)));

    EventLog eventLog =
        createEventLog(
            context,
            CaseHubEventType.WORKER_EXECUTION_STARTED,
            OBJECT_MAPPER.createObjectNode().put("inputDataHash", idempotency));

    persistEventLog(jobName, eventLog, tenancyId)
        .subscribe()
        .with(
            ignored -> LOG.debugf("Persisted start event for %s", jobName),
            ex -> LOG.errorf(ex, "Failed to persist start event for %s", jobName));
  }

  @Override
  public void jobExecutionVetoed(JobExecutionContext context) {
    if (isNotWorkerExecutionJob(context)) {
      return;
    }

    String jobName = context.getJobDetail().getKey().toString();
    LOG.info("Job execution was vetoed for job: " + jobName);
  }

  @Override
  public void jobWasExecuted(JobExecutionContext context, JobExecutionException jobException) {
    // No-op: fire-and-forget job handles success/failure via WorkerExecutor callbacks.
    // jobException is always null because execute() never throws — errors are handled
    // asynchronously by QuartzRetryService.
  }

  private boolean isNotWorkerExecutionJob(JobExecutionContext context) {
    return !QuartzWorkerExecutionJob.class.equals(context.getJobDetail().getJobClass());
  }

  private static EventLog createEventLog(
      JobExecutionContext context, CaseHubEventType eventType, JsonNode metadata) {
    String caseHubInstanceUuid = context.getMergedJobDataMap().getString("caseHubInstanceUuid");
    String workerId = context.getMergedJobDataMap().getString("workerId");

    EventLog eventLog = new EventLog();
    eventLog.setCaseId(UUID.fromString(caseHubInstanceUuid));
    eventLog.setWorkerId(workerId);
    eventLog.setEventType(eventType);
    eventLog.setStreamType(EventStreamType.CASE);
    eventLog.setTimestamp(context.getFireTime().toInstant());
    eventLog.setMetadata(metadata);
    return eventLog;
  }

  private Uni<Void> persistEventLog(String jobName, EventLog eventLog, String tenancyId) {
    return runOnSafeVertxContext(() -> eventLogRepository.append(eventLog, tenancyId))
        .onFailure()
        .invoke(ex -> LOG.errorf(ex, "Failed to persist event for job: %s", jobName));
  }

  private <T> Uni<T> runOnSafeVertxContext(Supplier<Uni<? extends T>> action) {
    return ReactiveUtils.runOnSafeVertxContext(vertx, action);
  }
}
