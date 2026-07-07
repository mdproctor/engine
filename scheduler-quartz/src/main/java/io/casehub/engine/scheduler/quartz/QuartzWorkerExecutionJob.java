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

import static io.casehub.engine.common.internal.event.EventBusAddresses.WORKER_EXECUTION_FINISHED;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.WorkRequest;
import io.casehub.api.model.WorkerContext;
import io.casehub.api.spi.WorkerContextProvider;
import io.casehub.engine.common.internal.event.WorkflowExecutionCompleted;
import io.casehub.engine.common.internal.executor.ExecutionMetadata;
import io.casehub.engine.common.internal.executor.WorkerExecutionConfig;
import io.casehub.engine.common.internal.executor.WorkerExecutor;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.utils.ReactiveUtils;
import io.casehub.engine.common.qualifier.CrossTenant;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.ReactiveCrossTenantEventLogRepository;
import io.casehub.engine.common.spi.recovery.WorkerExecutionRecoveryService;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import org.jboss.logging.Logger;
import org.quartz.Job;
import org.quartz.JobExecutionContext;

/**
 * Thin Quartz adapter — resolves context, delegates execution to {@link WorkerExecutor}, and
 * publishes the outcome. Fire-and-forget: the Quartz thread is freed immediately after
 * subscription; success publishes {@code WORKER_EXECUTION_FINISHED}, failure routes to {@link
 * QuartzRetryService}.
 *
 * <p>Refs casehubio/engine#463.
 */
@SuppressWarnings("unchecked")
@ApplicationScoped
class QuartzWorkerExecutionJob implements Job {

  private static final Logger LOG = Logger.getLogger(QuartzWorkerExecutionJob.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Inject WorkerExecutor workerExecutor;

  @Inject CaseDefinitionRegistry caseDefinitionRegistry;

  @Inject WorkerContextProvider workerContextProvider;

  @Inject Vertx vertx;

  @Inject EventBus eventBus;

  @Inject WorkerExecutionRecoveryService workerExecutionRecoveryService;

  @Inject @CrossTenant ReactiveCrossTenantEventLogRepository eventLogRepository;

  @Inject WorkerExecutionConfig executionConfig;

  @Inject QuartzRetryService retryService;

  @Override
  public void execute(JobExecutionContext executionContext) {
    LOG.infof("Executing workflow task: %s", executionContext.getJobDetail().getKey());

    WorkerRetryContext retryCtx = WorkerRetryContext.from(executionContext);

    try {
      String inputDataHash = executionContext.getMergedJobDataMap().getString("inputDataHash");
      String eventLogId = executionContext.getMergedJobDataMap().getString("eventLogId");

      EventLog eventLog =
          findEventLog(eventLogId).subscribe().asCompletionStage().toCompletableFuture().join();

      if (eventLog == null) {
        onFailure(retryCtx, new RuntimeException("EventLog not found: id=" + eventLogId));
        return;
      }

      Map<String, Object> inputData = OBJECT_MAPPER.convertValue(eventLog.getPayload(), Map.class);

      CaseInstance instance =
          workerExecutionRecoveryService
              .loadOrRestoreCaseInstance(eventLog.getCaseId())
              .await()
              .atMost(Duration.ofSeconds(10));

      CaseDefinition definition =
          caseDefinitionRegistry.getCaseDefinition(instance.getCaseMetaModel());

      if (definition == null) {
        onFailure(
            retryCtx,
            new RuntimeException("CaseDefinition not found for caseId=" + eventLog.getCaseId()));
        return;
      }

      String workerId = eventLog.getWorkerId();
      String capabilityName = eventLog.getMetadata().get("capabilityName").asText();
      String bindingName =
          eventLog.getMetadata().has("bindingName")
              ? eventLog.getMetadata().get("bindingName").asText()
              : null;
      java.util.UUID signalId =
          eventLog.getMetadata().has("signalId")
              ? java.util.UUID.fromString(eventLog.getMetadata().get("signalId").asText())
              : null;

      final WorkerRetryContext effectiveRetryCtx =
          retryCtx.withBindingName(bindingName).withSignalId(signalId);

      Worker worker =
          definition.getWorkers().stream()
              .filter(w -> w.name().equals(workerId))
              .findFirst()
              .orElse(null);

      if (worker == null) {
        onFailure(effectiveRetryCtx, new RuntimeException("Worker not found: " + workerId));
        return;
      }

      Capability capability =
          definition.getCapabilities().stream()
              .filter(c -> c.name().equals(capabilityName))
              .findFirst()
              .orElse(null);

      if (capability == null) {
        onFailure(
            effectiveRetryCtx, new RuntimeException("Capability not found: " + capabilityName));
        return;
      }

      int timeoutMs = executionConfig.getEffectiveTimeout(worker.executionPolicy().timeoutMs());

      WorkerContext workerContext =
          workerContextProvider.buildContext(
              workerId,
              eventLog.getCaseId(),
              WorkRequest.of(capabilityName, inputData),
              instance.getPropagationContext());

      ExecutionMetadata metadata = new ExecutionMetadata(workerId, inputDataHash);

      workerExecutor
          .execute(
              worker.function(),
              inputData,
              workerContext,
              timeoutMs,
              capability.outputSchema(),
              metadata)
          .subscribe()
          .with(
              workerResult ->
                  onSuccess(instance, worker, inputDataHash, workerResult, bindingName, signalId),
              failure -> onFailure(effectiveRetryCtx, failure));
    } catch (Exception e) {
      onFailure(retryCtx, e);
    }
  }

  private void onSuccess(
      CaseInstance instance,
      Worker worker,
      String inputDataHash,
      io.casehub.worker.api.WorkerResult workerResult,
      String bindingName,
      java.util.UUID signalId) {
    eventBus.publish(
        WORKER_EXECUTION_FINISHED,
        new WorkflowExecutionCompleted(
            instance,
            worker,
            inputDataHash,
            workerResult.output(),
            bindingName,
            workerResult.outcome(),
            signalId));
  }

  private void onFailure(WorkerRetryContext retryCtx, Throwable failure) {
    LOG.errorf(
        "Worker execution failed: caseId=%s worker=%s cause=%s",
        retryCtx.caseId(), retryCtx.workerId(), failure.getMessage());

    retryService
        .handleFailure(retryCtx, failure.getMessage())
        .subscribe()
        .with(
            ignored -> {},
            ex -> LOG.errorf(ex, "Retry handling failed for worker %s", retryCtx.workerId()));
  }

  private Uni<EventLog> findEventLog(String eventLogId) {
    return ReactiveUtils.runOnSafeVertxContext(
        vertx, () -> eventLogRepository.findById(Long.parseLong(eventLogId)));
  }
}
