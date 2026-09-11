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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ExecutionMode;
import io.casehub.api.model.RetrievedMemory;
import io.casehub.api.model.WorkRequest;
import io.casehub.api.model.WorkerContext;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.api.spi.WorkerContextProvider;
import io.casehub.api.spi.WorkerStatusListener;
import io.casehub.api.spi.event.EventDispatcher;
import io.casehub.api.spi.routing.RetrievedExperience;
import io.casehub.engine.common.internal.context.BridgeResolver;
import io.casehub.engine.common.internal.event.ScopedWorkerOutputEvent;
import io.casehub.engine.common.internal.event.WorkflowExecutionCompleted;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CrossTenantEventLogRepository;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.engine.common.spi.recovery.WorkerExecutionRecoveryService;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Exchange;
import io.casehub.worker.api.ExchangeAwareFunction;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerOutcome;
import io.casehub.worker.api.WorkerResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.jboss.logging.Logger;

/**
 * Scheduler-agnostic orchestrator for worker execution. Extracts all domain logic from the Quartz
 * job and job listener into a single reusable bean that any scheduler backend can delegate to.
 */
@SuppressWarnings("unchecked")
public class WorkerExecutionOrchestrator {

  private static final Logger LOG = Logger.getLogger(WorkerExecutionOrchestrator.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final WorkerExecutor workerExecutor;
  private final CaseDefinitionRegistry caseDefinitionRegistry;
  private final WorkerContextProvider workerContextProvider;
  private final EventDispatcher eventDispatcher;
  private final WorkerExecutionRecoveryService workerExecutionRecoveryService;
  private final CrossTenantEventLogRepository crossTenantEventLogRepository;
  private final EventLogRepository eventLogRepository;
  private final WorkerExecutionConfig executionConfig;
  private final BridgeResolver bridgeResolver;
  private final WorkerStatusListener workerStatusListener;
  private final Consumer<CaseLifecycleEvent> lifecycleEventConsumer;
  private final io.casehub.ledger.api.spi.LedgerTraceIdProvider traceIdProvider;

  public WorkerExecutionOrchestrator(
      WorkerExecutor workerExecutor,
      CaseDefinitionRegistry caseDefinitionRegistry,
      WorkerContextProvider workerContextProvider,
      EventDispatcher eventDispatcher,
      WorkerExecutionRecoveryService workerExecutionRecoveryService,
      CrossTenantEventLogRepository crossTenantEventLogRepository,
      EventLogRepository eventLogRepository,
      WorkerExecutionConfig executionConfig,
      BridgeResolver bridgeResolver,
      WorkerStatusListener workerStatusListener,
      Consumer<CaseLifecycleEvent> lifecycleEventConsumer,
      io.casehub.ledger.api.spi.LedgerTraceIdProvider traceIdProvider) {
    this.workerExecutor = workerExecutor;
    this.caseDefinitionRegistry = caseDefinitionRegistry;
    this.workerContextProvider = workerContextProvider;
    this.eventDispatcher = eventDispatcher;
    this.workerExecutionRecoveryService = workerExecutionRecoveryService;
    this.crossTenantEventLogRepository = crossTenantEventLogRepository;
    this.eventLogRepository = eventLogRepository;
    this.executionConfig = executionConfig;
    this.bridgeResolver = bridgeResolver;
    this.workerStatusListener = workerStatusListener;
    this.lifecycleEventConsumer = lifecycleEventConsumer;
    this.traceIdProvider = traceIdProvider;
  }

  public void execute(WorkerTaskData taskData, RetryHandler retryHandler) {
    LOG.infof(
        "Executing worker task: eventLogId=%s workerId=%s",
        taskData.eventLogId(), taskData.workerId());

    try {
      firePreExecutionHooks(taskData);
      executeWorker(taskData, retryHandler);
    } catch (Exception e) {
      LOG.errorf(
          "Worker execution failed: caseId=%s worker=%s cause=%s",
          taskData.caseId(), taskData.workerId(), e.getMessage());
      try {
        retryHandler.handleFailure(taskData, e, e.getMessage());
      } catch (Exception ex) {
        LOG.errorf(ex, "Retry handling failed for worker %s", taskData.workerId());
      }
    }
  }

  private void firePreExecutionHooks(WorkerTaskData taskData) {
    try {
      workerStatusListener.onWorkerStarted(
          taskData.workerId(), Map.of("caseId", taskData.caseId().toString()));

      lifecycleEventConsumer.accept(
          CaseLifecycleEvent.of(
              taskData.caseId(),
              taskData.tenancyId(),
              "ExecuteWorker",
              "WorkerExecutionStarted",
              null,
              taskData.workerId(),
              "WORKER",
              traceIdProvider.currentTraceId().orElse(null)));

      EventLog startEvent = new EventLog();
      startEvent.setCaseId(taskData.caseId());
      startEvent.setWorkerId(taskData.workerId());
      startEvent.setEventType(CaseHubEventType.WORKER_EXECUTION_STARTED);
      startEvent.setStreamType(EventStreamType.CASE);
      startEvent.setTimestamp(Instant.now());
      startEvent.setMetadata(
          OBJECT_MAPPER.createObjectNode().put("inputDataHash", taskData.inputDataHash()));
      eventLogRepository.append(startEvent, taskData.tenancyId());
    } catch (Exception ex) {
      LOG.warnf(
          ex,
          "Pre-execution hook failed for worker %s — continuing execution",
          taskData.workerId());
    }
  }

  private void executeWorker(WorkerTaskData taskData, RetryHandler retryHandler) {
    EventLog eventLog = crossTenantEventLogRepository.findById(taskData.eventLogId());
    if (eventLog == null) {
      retryHandler.handleFailure(
          taskData,
          new RuntimeException("EventLog not found: id=" + taskData.eventLogId()),
          "EventLog not found");
      return;
    }

    CaseInstance instance =
        workerExecutionRecoveryService.loadOrRestoreCaseInstance(eventLog.getCaseId());

    CaseDefinition definition =
        caseDefinitionRegistry.getCaseDefinition(instance.getCaseMetaModel());
    if (definition == null) {
      retryHandler.handleFailure(
          taskData,
          new RuntimeException("CaseDefinition not found for caseId=" + eventLog.getCaseId()),
          "CaseDefinition not found");
      return;
    }

    String workerId = eventLog.getWorkerId();
    String capabilityName = eventLog.getMetadata().get("capabilityName").asText();
    String bindingName =
        eventLog.getMetadata().has("bindingName")
            ? eventLog.getMetadata().get("bindingName").asText()
            : null;
    UUID signalId =
        eventLog.getMetadata().has("signalId")
            ? UUID.fromString(eventLog.getMetadata().get("signalId").asText())
            : null;
    ExecutionMode executionMode =
        eventLog.getMetadata().has("executionMode")
            ? ExecutionMode.valueOf(eventLog.getMetadata().get("executionMode").asText())
            : null;

    WorkerTaskData effectiveTaskData = taskData.withBindingName(bindingName).withSignalId(signalId);

    Worker worker =
        definition.getWorkers().stream()
            .filter(w -> w.name().equals(workerId))
            .findFirst()
            .orElse(null);
    if (worker == null) {
      retryHandler.handleFailure(
          effectiveTaskData,
          new RuntimeException("Worker not found: " + workerId),
          "Worker not found");
      return;
    }

    Capability capability =
        definition.getCapabilities().stream()
            .filter(c -> c.name().equals(capabilityName))
            .findFirst()
            .orElse(null);
    if (capability == null) {
      retryHandler.handleFailure(
          effectiveTaskData,
          new RuntimeException("Capability not found: " + capabilityName),
          "Capability not found");
      return;
    }

    String bridgeTypeName =
        eventLog.getMetadata().has("contextBridgeType")
            ? eventLog.getMetadata().get("contextBridgeType").asText(null)
            : null;
    boolean isExchangeAware = worker.function() instanceof ExchangeAwareFunction<?, ?>;
    if (isExchangeAware) {
      bridgeTypeName = ((ExchangeAwareFunction<?, ?>) worker.function()).bodyInputType().getName();
    }
    io.casehub.api.context.ContextBridge<?> bridge =
        bridgeResolver.resolveByTypeName(bridgeTypeName);

    Object typedInput;
    if (bridge.isLiveView()) {
      typedInput =
          bridgeResolver.initialise(bridge, instance.getCaseContext(), eventLog.getPayload());
    } else {
      typedInput = bridgeResolver.deserialise(bridge, eventLog.getPayload());
    }

    if (isExchangeAware) {
      typedInput = Exchange.of(typedInput, instance.getExchangeHeaders());
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
    List<RetrievedMemory> memories = deserializeMemories(eventLog);
    WorkerContext workerContext =
        new WorkerContext(
            baseContext.taskDescription(),
            baseContext.caseId(),
            baseContext.channels(),
            baseContext.priorWorkers(),
            baseContext.propagationContext(),
            baseContext.properties(),
            experiences,
            memories);

    ExecutionMetadata metadata =
        new ExecutionMetadata(
            workerId, taskData.inputDataHash(), bindingName, executionMode, instance.tenancyId);

    HandlerResult handlerResult =
        workerExecutor.execute(
            worker.function(),
            typedInput,
            workerContext,
            timeoutMs,
            capability.outputProjection(),
            metadata);

    WorkerResult<?> workerResult = handlerResult.result();
    Map<String, Object> output = toMap(workerResult.output());
    if ((output == null || output.isEmpty()) && bridge.isLiveView()) {
      output = bridgeResolver.extractOutput(bridge, typedInput);
    }
    if (output != null && !output.equals(workerResult.output())) {
      var replaced = new WorkerResult(output, workerResult.outcome(), workerResult.reasoning());
      workerResult = replaced;
    }

    publishOutcome(
        instance,
        worker,
        taskData.inputDataHash(),
        workerResult,
        bindingName,
        signalId,
        executionMode,
        handlerResult.protocolMetadata());
  }

  private void publishOutcome(
      CaseInstance instance,
      Worker worker,
      String inputDataHash,
      WorkerResult<?> workerResult,
      String bindingName,
      UUID signalId,
      ExecutionMode executionMode,
      Map<String, Object> protocolMetadata) {

    if (executionMode != null && executionMode != ExecutionMode.TRANSIENT) {
      if (workerResult.outcome() instanceof WorkerOutcome.Success) {
        Map<String, Object> output = toMap(workerResult.output());
        if (output != null && !output.isEmpty()) {
          eventDispatcher.dispatch(
              new ScopedWorkerOutputEvent(
                  instance,
                  worker.name(),
                  output,
                  bindingName,
                  signalId,
                  workerResult.reasoning()));
        }
        LOG.debugf("Scoped worker %s returned Success — interim output published", bindingName);
        return;
      }
    }

    Map<String, Object> output = toMap(workerResult.output());
    eventDispatcher.dispatch(
        new WorkflowExecutionCompleted(
            instance,
            worker,
            inputDataHash,
            output,
            bindingName,
            workerResult.outcome(),
            signalId,
            null,
            null,
            protocolMetadata,
            workerResult.reasoning()));
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
      LOG.warnf(e, "Failed to deserialize CBR experiences — proceeding without");
      return List.of();
    }
  }

  private static List<RetrievedMemory> deserializeMemories(EventLog eventLog) {
    JsonNode memoriesNode = eventLog.getMetadata().get("memories");
    if (memoriesNode == null || memoriesNode.isNull() || memoriesNode.isEmpty()) {
      return List.of();
    }
    try {
      return OBJECT_MAPPER.convertValue(
          memoriesNode,
          OBJECT_MAPPER
              .getTypeFactory()
              .constructCollectionType(List.class, RetrievedMemory.class));
    } catch (Exception e) {
      LOG.warnf(e, "Failed to deserialize memories — proceeding without");
      return List.of();
    }
  }

  private static Map<String, Object> toMap(Object output) {
    if (output == null) {
      return null;
    }
    if (output instanceof Map) {
      return (Map<String, Object>) output;
    }
    return OBJECT_MAPPER.convertValue(output, new TypeReference<>() {});
  }
}
