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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.Capability;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.WorkRequest;
import io.casehub.api.model.Worker;
import io.casehub.api.model.WorkerContext;
import io.casehub.api.model.WorkerExecutionContext;
import io.casehub.api.model.WorkerResult;
import io.casehub.api.model.ai.Agent;
import io.casehub.api.spi.PlannedAction;
import io.casehub.api.spi.WorkerContextProvider;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkflowExecutionCompleted;
import io.casehub.engine.common.internal.event.WorkflowExecutionFailed;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.jq.ValidationResult;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.utils.ReactiveUtils;
import io.casehub.engine.common.internal.worker.WorkflowExecutor;
import io.casehub.engine.common.qualifier.CrossTenant;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CrossTenantEventLogRepository;
import io.casehub.engine.common.spi.recovery.WorkerExecutionRecoveryService;
import io.serverlessworkflow.api.types.Workflow;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.jboss.logging.Logger;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

@SuppressWarnings("unchecked")
@ApplicationScoped
class QuartzWorkerExecutionJob implements Job {

  @Inject WorkflowExecutor workflowExecutor;

  @Inject CaseDefinitionRegistry caseDefinitionRegistry;

  @Inject WorkerContextProvider workerContextProvider;

  @Inject Vertx vertx;

  @Inject EventBus eventBus;

  @Inject WorkerExecutionRecoveryService workerExecutionRecoveryService;

  @Inject @CrossTenant CrossTenantEventLogRepository eventLogRepository;

  @Inject WorkerExecutionConfig executionConfig;

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private static final Logger LOG = Logger.getLogger(QuartzWorkerExecutionJob.class);

  @Inject JQEvaluator jqEvaluator;

  @Override
  public void execute(JobExecutionContext executionContext) throws JobExecutionException {
    LOG.infof("Executing workflow task: %s", executionContext.getJobDetail().getKey());

    String inputDataHash = executionContext.getMergedJobDataMap().getString("inputDataHash");
    String eventLogId = executionContext.getMergedJobDataMap().getString("eventLogId");

    execute(inputDataHash, eventLogId);
  }

  private void execute(String inputDataHash, String eventLogId) throws JobExecutionException {
    EventLog eventLog =
        findEventLog(eventLogId)
            .subscribe()
            .asCompletionStage()
            .toCompletableFuture()
            .join(); // TODO

    if (eventLog == null) {
      throw new JobExecutionException("EventLog not found: id=" + eventLogId);
    }

    Map<String, Object> inputData = OBJECT_MAPPER.convertValue(eventLog.getPayload(), Map.class);

    // TODO
    CaseInstance instance =
        workerExecutionRecoveryService
            .loadOrRestoreCaseInstance(eventLog.getCaseId())
            .await()
            .atMost(Duration.ofSeconds(10));

    CaseDefinition definition =
        caseDefinitionRegistry.getCaseDefinition(instance.getCaseMetaModel());

    if (definition == null) {
      throw new JobExecutionException(
          "CaseDefinition not found for caseId=" + eventLog.getCaseId());
    }
    String workflowId = eventLog.getWorkerId();
    String capabilityName = eventLog.getMetadata().get("capabilityName").asText();

    // TODO use map
    Worker worker =
        definition.getWorkers().stream()
            .filter(w -> w.getName().equals(workflowId))
            .findFirst()
            .orElseThrow(
                () -> new RuntimeException("Worker not found in case definition: " + workflowId));

    // TODO use map
    Capability capability =
        definition.getCapabilities().stream()
            .filter(c -> c.getName().equals(capabilityName))
            .findFirst()
            .orElseThrow(
                () ->
                    new RuntimeException(
                        "Capability not found in case definition: " + capabilityName));

    int timeoutMs = executionConfig.getEffectiveTimeout(worker.getExecutionPolicy().timeoutMs());

    WorkerContext workerContext =
        workerContextProvider.buildContext(
            workflowId, eventLog.getCaseId(), WorkRequest.of(capabilityName, inputData));

    // Workflow workers run non-blocking — Quartz thread returns immediately.
    // Success/failure is communicated via event bus from the async whenComplete.
    if (worker.getFunction().getValue() instanceof Workflow workflow) {
      final Capability capabilityForClosure = capability;
      final Worker workerForClosure = worker;
      workflowExecutor
          .execute(workflow, inputData, instance, worker.getName(), inputDataHash)
          .thenApply(
              model ->
                  model
                      .asMap()
                      .orElseThrow(
                          () ->
                              new RuntimeException(
                                  "Workflow produced non-serializable model: " + worker.getName())))
          .thenApply(output -> evalJqAsMap(output, capabilityForClosure.getOutputSchema()))
          .whenComplete(
              (output, ex) -> {
                if (ex != null) {
                  handleWorkflowFailure(
                      instance,
                      workerForClosure,
                      capabilityForClosure,
                      inputDataHash,
                      eventLogId,
                      ex);
                } else {
                  // Workflow workers don't support PlannedAction in v1 — plannedAction=null.
                  eventBus.publish(
                      WORKER_EXECUTION_FINISHED,
                      new WorkflowExecutionCompleted(
                          instance, workerForClosure, inputDataHash, output, null));
                }
              });
      return; // Quartz marks the job complete; async path handles the rest
    }

    WorkerResult workerResult;
    try {
      if (worker.getFunction().getValue() instanceof Function function) {
        workerResult = function(function, inputData, workerContext, timeoutMs);
      } else if (worker.getFunction().getValue() instanceof Agent agent) {
        workerResult = agent(agent, inputData, workerContext, timeoutMs);
      } else {
        throw new RuntimeException(
            "Worker function is not a function or agent: "
                + worker.getName()
                + " "
                + worker.getFunction().getValue().getClass().getCanonicalName());
      }
    } catch (TimeoutException e) {
      throw new JobExecutionException(
          "Worker execution timed out after " + timeoutMs + "ms: " + worker.getName(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new JobExecutionException("Worker execution interrupted: " + worker.getName(), e);
    } catch (ExecutionException e) {
      throw new JobExecutionException("Worker execution failed: " + worker.getName(), e.getCause());
    }

    final Map<String, Object> outputData =
        evalJqAsMap(workerResult.output(), capability.getOutputSchema());
    // Enrich PlannedAction with workerId and caseId before passing to the completion handler.
    final PlannedAction enrichedAction =
        workerResult.plannedAction() != null
            ? workerResult.plannedAction().withIdentity(worker.getName(), instance.getUuid())
            : null;

    eventBus.publish(
        WORKER_EXECUTION_FINISHED,
        new WorkflowExecutionCompleted(
            instance, worker, inputDataHash, outputData, enrichedAction));
  }

  private void handleWorkflowFailure(
      final CaseInstance instance,
      final Worker worker,
      final Capability capability,
      final String inputDataHash,
      final String eventLogId,
      final Throwable cause) {
    LOG.errorf(
        "Workflow execution failed: caseId=%s worker=%s cause=%s",
        instance.getUuid(), worker.getName(), cause.getMessage());
    eventBus.publish(
        EventBusAddresses.WORKFLOW_EXECUTION_FAILED,
        new WorkflowExecutionFailed(
            instance, worker, capability, inputDataHash, eventLogId, cause));
  }

  private WorkerResult function(
      Function<Map<String, Object>, WorkerResult> function,
      Map<String, Object> inputData,
      WorkerContext workerContext,
      int timeoutMs)
      throws TimeoutException, InterruptedException, ExecutionException {

    LOG.debugf("Executing function with timeout: %d ms", timeoutMs);

    CompletableFuture<WorkerResult> cf =
        CompletableFuture.supplyAsync(
            () -> {
              WorkerExecutionContext.set(workerContext);
              try {
                return function.apply(inputData);
              } finally {
                WorkerExecutionContext.clear();
              }
            });

    return cf.get(timeoutMs, TimeUnit.MILLISECONDS);
  }

  private WorkerResult agent(
      Agent agent, Map<String, Object> inputData, WorkerContext workerContext, int timeoutMs)
      throws TimeoutException, InterruptedException, ExecutionException {

    LOG.debugf("Executing agent with timeout: %d ms", timeoutMs);

    CompletableFuture<WorkerResult> cf =
        CompletableFuture.supplyAsync(
            () -> {
              WorkerExecutionContext.set(workerContext);
              try {
                return agent.execute(inputData);
              } finally {
                WorkerExecutionContext.clear();
              }
            });

    return cf.get(timeoutMs, TimeUnit.MILLISECONDS);
  }

  private Map<String, Object> evalJqAsMap(Map<String, Object> data, String expression) {
    if (expression == null || expression.isBlank()) return data;
    try {
      ValidationResult vr = jqEvaluator.eval(expression, OBJECT_MAPPER.valueToTree(data));
      if (!vr.ok() || vr.output() == null || vr.output().isEmpty()) return data;
      return OBJECT_MAPPER.convertValue(vr.output().get(0), MAP_TYPE);
    } catch (Exception e) {
      LOG.warnf(e, "outputSchema jq evaluation failed — returning raw output data");
      return data;
    }
  }

  private Uni<EventLog> findEventLog(String eventLogId) {
    return ReactiveUtils.runOnSafeVertxContext(
        vertx, () -> eventLogRepository.findById(Long.parseLong(eventLogId)));
  }
}
