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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.WorkRequest;
import io.casehub.api.model.WorkerContext;
import io.casehub.api.spi.WorkerContextProvider;
import io.casehub.api.spi.routing.RetrievedExperience;
import io.casehub.engine.common.internal.event.WorkflowExecutionCompleted;
import io.casehub.engine.common.internal.executor.ExecutionMetadata;
import io.casehub.engine.common.internal.executor.WorkerExecutionConfig;
import io.casehub.engine.common.internal.executor.WorkerExecutor;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.qualifier.CrossTenant;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CrossTenantEventLogRepository;
import io.casehub.engine.common.spi.recovery.WorkerExecutionRecoveryService;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.vertx.core.Vertx;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
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

  @Inject @CrossTenant CrossTenantEventLogRepository eventLogRepository;

  @Inject WorkerExecutionConfig executionConfig;

  @Inject QuartzRetryService retryService;
  @Inject io.casehub.engine.common.internal.context.BridgeResolver bridgeResolver;

  @Override
  public void execute(JobExecutionContext executionContext) {
    LOG.infof("Executing workflow task: %s", executionContext.getJobDetail().getKey());

    WorkerRetryContext retryCtx = WorkerRetryContext.from(executionContext);

    try {
      String inputDataHash = executionContext.getMergedJobDataMap().getString("inputDataHash");
      String eventLogId = executionContext.getMergedJobDataMap().getString("eventLogId");

      EventLog eventLog = findEventLog(eventLogId);

      if (eventLog == null) {
        onFailure(retryCtx, new RuntimeException("EventLog not found: id=" + eventLogId));
        return;
      }

      CaseInstance instance =
          workerExecutionRecoveryService.loadOrRestoreCaseInstance(eventLog.getCaseId());

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

      io.casehub.api.model.ExecutionMode executionMode =
          eventLog.getMetadata().has("executionMode")
              ? io.casehub.api.model.ExecutionMode.valueOf(
                  eventLog.getMetadata().get("executionMode").asText())
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

      String bridgeTypeName =
          eventLog.getMetadata().has("contextBridgeType")
              ? eventLog.getMetadata().get("contextBridgeType").asText(null)
              : null;
      io.casehub.api.context.ContextBridge<?> bridge =
          bridgeResolver.resolveByTypeName(bridgeTypeName);

      Object typedInput;
      if (bridge.isLiveView()) {
        typedInput =
            bridgeResolver.initialise(bridge, instance.getCaseContext(), eventLog.getPayload());
      } else {
        typedInput = bridgeResolver.deserialise(bridge, eventLog.getPayload());
      }

      Map<String, Object> inputDataForContext =
          OBJECT_MAPPER.convertValue(eventLog.getPayload(), Map.class);

      int timeoutMs = executionConfig.getEffectiveTimeout(worker.executionPolicy().timeoutMs());

      WorkerContext baseContext =
          workerContextProvider.buildContext(
              workerId,
              eventLog.getCaseId(),
              WorkRequest.of(capabilityName, inputDataForContext),
              instance.getPropagationContext());

      List<RetrievedExperience> experiences = deserializeExperiences(eventLog);
      WorkerContext workerContext =
          new WorkerContext(
              baseContext.taskDescription(),
              baseContext.caseId(),
              baseContext.channels(),
              baseContext.priorWorkers(),
              baseContext.propagationContext(),
              baseContext.properties(),
              experiences);

      ExecutionMetadata metadata =
          new ExecutionMetadata(workerId, inputDataHash, bindingName, executionMode);

      io.casehub.worker.api.WorkerResult<?> workerResult =
          workerExecutor.execute(
              worker.function(),
              typedInput,
              workerContext,
              timeoutMs,
              capability.outputSchema(),
              metadata);

      Map<String, Object> output = toMap(workerResult.output());
      if ((output == null || output.isEmpty()) && bridge.isLiveView()) {
        output = bridgeResolver.extractOutput(bridge, typedInput);
      }
      if (output != null && !output.equals(workerResult.output())) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        var replaced = new io.casehub.worker.api.WorkerResult(output, workerResult.outcome());
        workerResult = replaced;
      }
      onSuccess(
          instance, worker, inputDataHash, workerResult, bindingName, signalId, executionMode);
    } catch (Exception e) {
      onFailure(retryCtx, e);
    }
  }

  private void onSuccess(
      CaseInstance instance,
      Worker worker,
      String inputDataHash,
      io.casehub.worker.api.WorkerResult<?> workerResult,
      String bindingName,
      java.util.UUID signalId,
      io.casehub.api.model.ExecutionMode executionMode) {
    if (executionMode != null && executionMode != io.casehub.api.model.ExecutionMode.TRANSIENT) {
      if (!(workerResult.outcome() instanceof io.casehub.worker.api.WorkerOutcome.Completed)) {
        Map<String, Object> output = toMap(workerResult.output());
        if (output != null && !output.isEmpty()) {
          eventBus.publish(
              io.casehub.engine.common.internal.event.EventBusAddresses.SCOPED_WORKER_OUTPUT,
              new io.casehub.engine.common.internal.event.ScopedWorkerOutputEvent(
                  instance, bindingName, output, executionMode));
        }
        LOG.debugf(
            "Scoped worker %s returned Success — output applied, PlanItem stays RUNNING",
            bindingName);
        return;
      }
    }
    Map<String, Object> output = toMap(workerResult.output());
    eventBus.publish(
        WORKER_EXECUTION_FINISHED,
        new WorkflowExecutionCompleted(
            instance,
            worker,
            inputDataHash,
            output,
            bindingName,
            workerResult.outcome(),
            signalId));
  }

  private void onFailure(WorkerRetryContext retryCtx, Throwable failure) {
    LOG.errorf(
        "Worker execution failed: caseId=%s worker=%s cause=%s",
        retryCtx.caseId(), retryCtx.workerId(), failure.getMessage());

    try {
      retryService.handleFailure(retryCtx, failure.getMessage());
    } catch (Exception ex) {
      LOG.errorf(ex, "Retry handling failed for worker %s", retryCtx.workerId());
    }
  }

  private EventLog findEventLog(String eventLogId) {
    return eventLogRepository.findById(Long.parseLong(eventLogId));
  }

  private static List<RetrievedExperience> deserializeExperiences(EventLog eventLog) {
    JsonNode experiencesNode = eventLog.getMetadata().get("experiences");
    if (experiencesNode == null || experiencesNode.isNull() || experiencesNode.isEmpty()) {
      return List.of();
    }
    try {
      return OBJECT_MAPPER.convertValue(
          experiencesNode,
          OBJECT_MAPPER
              .getTypeFactory()
              .constructCollectionType(List.class, RetrievedExperience.class));
    } catch (Exception e) {
      LOG.warnf(
          e, "Failed to deserialize CBR experiences from EventLog metadata — proceeding without");
      return List.of();
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> toMap(Object output) {
    if (output == null) {
      return null;
    }
    if (output instanceof Map) {
      return (Map<String, Object>) output;
    }
    return OBJECT_MAPPER.convertValue(
        output, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
  }
}
