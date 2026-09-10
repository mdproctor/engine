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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.context.ContextLayer;
import io.casehub.api.model.CaseChannel;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ExecutionMode;
import io.casehub.api.model.LifecycleScope;
import io.casehub.api.model.RetryState;
import io.casehub.api.model.WorkRequest;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.api.model.event.ExecutionOrigin;
import io.casehub.api.spi.CaseChannelProvider;
import io.casehub.api.spi.WorkerContextProvider;
import io.casehub.api.spi.WorkerExecutionGuard;
import io.casehub.api.spi.event.EventDispatcher;
import io.casehub.api.spi.routing.RetrievedExperience;
import io.casehub.engine.common.internal.context.BridgeResolver;
import io.casehub.engine.common.internal.event.WorkerRetriesExhaustedEvent;
import io.casehub.engine.common.internal.event.WorkerScheduleEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.utils.WorkerExecutionKeys;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.engine.internal.engine.QuiescenceTracker;
import io.casehub.engine.internal.memory.AgentMemoryRetriever;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

public class WorkerScheduleEventHandler {

  private static final Logger LOG = Logger.getLogger(WorkerScheduleEventHandler.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock> locks =
      new ConcurrentHashMap<>();

  private final WorkerExecutionManager workflowExecutionManager;
  private final WorkerExecutionGuard workerExecutionGuard;
  private final QuiescenceTracker quiescenceTracker;
  private final WorkerContextProvider workerContextProvider;
  private final CaseChannelProvider caseChannelProvider;
  private final EventDispatcher eventDispatcher;
  private final EventLogRepository eventLogRepository;
  private final io.casehub.api.engine.ExpressionEngineRegistry expressionEngineRegistry;
  private final BridgeResolver bridgeResolver;
  private final CaseDefinitionRegistry caseDefinitionRegistry;
  private final AgentMemoryRetriever agentMemoryRetriever;
  private final Optional<Duration> idempotencyWindow;

  public WorkerScheduleEventHandler(
      WorkerExecutionManager workflowExecutionManager,
      WorkerExecutionGuard workerExecutionGuard,
      QuiescenceTracker quiescenceTracker,
      WorkerContextProvider workerContextProvider,
      CaseChannelProvider caseChannelProvider,
      EventDispatcher eventDispatcher,
      EventLogRepository eventLogRepository,
      io.casehub.api.engine.ExpressionEngineRegistry expressionEngineRegistry,
      BridgeResolver bridgeResolver,
      CaseDefinitionRegistry caseDefinitionRegistry,
      AgentMemoryRetriever agentMemoryRetriever,
      Optional<Duration> idempotencyWindow) {
    this.workflowExecutionManager = workflowExecutionManager;
    this.workerExecutionGuard = workerExecutionGuard;
    this.quiescenceTracker = quiescenceTracker;
    this.workerContextProvider = workerContextProvider;
    this.caseChannelProvider = caseChannelProvider;
    this.eventDispatcher = eventDispatcher;
    this.eventLogRepository = eventLogRepository;
    this.expressionEngineRegistry = expressionEngineRegistry;
    this.bridgeResolver = bridgeResolver;
    this.caseDefinitionRegistry = caseDefinitionRegistry;
    this.agentMemoryRetriever = agentMemoryRetriever;
    this.idempotencyWindow = idempotencyWindow;
  }

  private static String serialize(final Object value) {
    try {
      return OBJECT_MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new RuntimeException("Failed to serialize " + value.getClass().getSimpleName(), e);
    }
  }

  public void handle(WorkerScheduleEvent event) {
    try {
      CaseInstance instance = event.caseInstance();
      Worker worker = event.worker();
      Capability capability = event.capability();
      String bindingName = event.bindingName();

      JsonNode workingLayer = instance.getCaseContext().layer(ContextLayer.WORKING).asJsonNode();
      ExpressionEvaluator projectionEval = event.effectiveInputProjection();
      JsonNode narrowedInput = transformSingle(projectionEval, workingLayer);

      CaseDefinition definition =
          caseDefinitionRegistry.getCaseDefinition(instance.getCaseMetaModel());
      io.casehub.api.context.ContextBridge<?> bridge = bridgeResolver.resolve(worker, definition);
      Object typedInput =
          bridgeResolver.initialise(bridge, instance.getCaseContext(), narrowedInput);
      JsonNode serialisedPayload = bridgeResolver.serialise(bridge, typedInput);

      Map<String, Object> inputDataForHash = OBJECT_MAPPER.convertValue(narrowedInput, MAP_TYPE);
      String inputDataHash =
          WorkerExecutionKeys.inputDataHash(
              instance.getUuid(), worker.name(), capability.name(), inputDataForHash);

      if (workerExecutionGuard.isBlocked(worker.name(), instance.getUuid())) {
        LOG.warnf(
            "Worker blocked by guard (quarantined?): caseId=%s worker=%s — emitting retries exhausted",
            instance.getUuid(), worker.name());
        quiescenceTracker.onWorkerCompleted(instance.getUuid());
        eventDispatcher.dispatch(
            new WorkerRetriesExhaustedEvent(
                instance.getUuid(),
                instance.tenancyId,
                worker.name(),
                inputDataHash,
                bindingName,
                event.signalId(),
                RetryState.empty()));
        return;
      }

      workerContextProvider.buildContext(
          worker.name(), instance.getUuid(), WorkRequest.of(capability.name(), inputDataForHash));

      java.util.List<io.casehub.api.model.RetrievedMemory> memories =
          agentMemoryRetriever.retrieve(
              worker.name(), instance.tenancyId, instance.getUuid(), capability.name(), definition);

      EventLog eventLog =
          buildEventLog(
              instance,
              worker,
              capability,
              serialisedPayload,
              inputDataHash,
              bindingName,
              event.signalId(),
              event.origin(),
              bridge.contextType().getName(),
              event.experiences(),
              event.lifecycleScope(),
              event.executionMode(),
              event.activationContext(),
              memories);

      String lockKey = "wse:" + instance.getUuid() + ":" + worker.name() + ":" + inputDataHash;
      java.util.concurrent.locks.ReentrantLock lock =
          locks.computeIfAbsent(lockKey, k -> new java.util.concurrent.locks.ReentrantLock());
      lock.lock();
      try {
        scheduleUnderLock(
            eventLog, instance, worker, capability, inputDataForHash, inputDataHash, bindingName);
      } finally {
        lock.unlock();
      }
    } catch (Exception e) {
      quiescenceTracker.onWorkerCompleted(event.caseInstance().getUuid());
      LOG.errorf(
          e,
          "WorkerScheduleEvent FAILED: caseId=%s worker=%s",
          event.caseInstance().getUuid(),
          event.worker().name());
    }
  }

  private void scheduleUnderLock(
      EventLog eventLog,
      CaseInstance instance,
      Worker worker,
      Capability capability,
      Map<String, Object> inputData,
      String inputDataHash,
      String bindingName) {
    Instant idempotencyAfter = idempotencyWindow.map(w -> Instant.now().minus(w)).orElse(null);

    List<EventLog> existing =
        eventLogRepository.findSchedulingEvents(
            instance.getUuid(), worker.name(), idempotencyAfter, instance.tenancyId);

    boolean isReinvoked =
        eventLog.getMetadata().has("executionMode")
            && "REINVOKED".equals(eventLog.getMetadata().get("executionMode").asText());
    ScheduleAction action =
        isReinvoked ? ScheduleAction.createNew() : decideAction(existing, inputDataHash);
    Long eventLogId = executeAction(action, eventLog, instance, worker, capability);
    if (eventLogId == null) {
      quiescenceTracker.onWorkerCompleted(instance.getUuid());
    }
    submitIfNeeded(eventLogId, instance, worker, capability, inputData, bindingName);

    LOG.infof(
        "WorkerScheduleEvent processed: caseId=%s worker=%s capability=%s",
        instance.getUuid(), worker.name(), capability.name());
  }

  private EventLog buildEventLog(
      CaseInstance instance,
      Worker worker,
      Capability capability,
      JsonNode serialisedPayload,
      String inputDataHash,
      String bindingName,
      UUID signalId,
      ExecutionOrigin origin,
      String contextBridgeType,
      List<RetrievedExperience> experiences,
      LifecycleScope lifecycleScope,
      ExecutionMode executionMode,
      JsonNode activationContext,
      java.util.List<io.casehub.api.model.RetrievedMemory> memories) {
    Map<String, String> metadataBuilder = new HashMap<>();
    metadataBuilder.put("workerName", worker.name());
    metadataBuilder.put("capabilityName", capability.name());
    metadataBuilder.put("inputDataHash", inputDataHash);
    if (bindingName != null) {
      metadataBuilder.put("bindingName", bindingName);
    }
    if (signalId != null) {
      metadataBuilder.put("signalId", signalId.toString());
    }
    if (origin != null) {
      metadataBuilder.put("origin", origin.name());
    }
    if (contextBridgeType != null) {
      metadataBuilder.put("contextBridgeType", contextBridgeType);
    }
    if (lifecycleScope != null) {
      metadataBuilder.put("lifecycleScope", lifecycleScope.name());
    }
    if (executionMode != null) {
      metadataBuilder.put("executionMode", executionMode.name());
    }

    ObjectNode metaNode = OBJECT_MAPPER.valueToTree(metadataBuilder);
    if (experiences != null && !experiences.isEmpty()) {
      metaNode.set("experiences", OBJECT_MAPPER.valueToTree(experiences));
    }
    if (memories != null && !memories.isEmpty()) {
      metaNode.set("memories", OBJECT_MAPPER.valueToTree(memories));
      metaNode.put("retrievedMemoryCount", memories.size());
    }
    if (activationContext != null && !activationContext.isNull()) {
      metaNode.set("activationContext", activationContext);
    }

    EventLog eventLog = new EventLog();
    eventLog.setCaseId(instance.getUuid());
    eventLog.setEventType(CaseHubEventType.WORKER_SCHEDULED);
    eventLog.setStreamType(EventStreamType.CASE);
    eventLog.setTimestamp(Instant.now());
    eventLog.setWorkerId(worker.name());
    eventLog.setMetadata(metaNode);
    eventLog.setPayload(serialisedPayload);
    return eventLog;
  }

  private Long executeAction(
      ScheduleAction action,
      EventLog eventLog,
      CaseInstance instance,
      Worker worker,
      Capability capability) {
    return switch (action.type()) {
      case SKIP -> {
        LOG.infof(
            "Skipping WorkerScheduleEvent: already scheduled/started/completed caseId=%s worker=%s capability=%s",
            instance.getUuid(), worker.name(), capability.name());
        yield null;
      }
      case CREATE_NEW -> eventLogRepository.appendAndReturnId(eventLog, instance.tenancyId);
    };
  }

  private void submitIfNeeded(
      Long eventLogId,
      CaseInstance instance,
      Worker worker,
      Capability capability,
      Map<String, Object> inputData,
      String bindingName) {
    if (eventLogId == null) {
      return;
    }
    workflowExecutionManager.submit(
        eventLogId, instance, worker, capability, inputData, bindingName);
    dispatchCommand(instance, worker, capability, inputData, eventLogId);
  }

  private void dispatchCommand(
      CaseInstance instance,
      Worker worker,
      Capability capability,
      Map<String, Object> inputData,
      Long eventLogId) {
    CaseChannel channel =
        caseChannelProvider.openChannel(instance.getUuid(), "worker:" + worker.name());
    final String deadline =
        instance.getPropagationContext().getDeadline().map(Object::toString).orElse(null);
    final CommandContent command =
        new CommandContent(
            "COMMAND", capability.name(), String.valueOf(eventLogId), inputData, deadline);
    caseChannelProvider.postToChannel(
        channel,
        "casehub-engine:orchestrator",
        serialize(command),
        MessageType.COMMAND,
        String.valueOf(eventLogId),
        deadline,
        worker.name());
    LOG.debugf(
        "COMMAND dispatched: caseId=%s worker=%s capability=%s correlationId=%d",
        instance.getUuid(), worker.name(), capability.name(), eventLogId);
  }

  private ScheduleAction decideAction(List<EventLog> existingEvents, String executionIdempotency) {
    List<EventLog> sameInputEvents =
        existingEvents.stream()
            .filter(
                eventLog -> {
                  JsonNode metadata = eventLog.getMetadata();
                  JsonNode existingHash = metadata == null ? null : metadata.get("inputDataHash");
                  return existingHash != null && executionIdempotency.equals(existingHash.asText());
                })
            .toList();

    boolean alreadyScheduledOrStartedOrCompleted =
        sameInputEvents.stream()
            .anyMatch(
                eventLog ->
                    eventLog.getEventType() == CaseHubEventType.WORKER_SCHEDULED
                        || eventLog.getEventType() == CaseHubEventType.WORKER_EXECUTION_STARTED
                        || eventLog.getEventType() == CaseHubEventType.WORKER_EXECUTION_COMPLETED);
    if (alreadyScheduledOrStartedOrCompleted) {
      return ScheduleAction.skip();
    }
    return ScheduleAction.createNew();
  }

  private JsonNode transformSingle(ExpressionEvaluator evaluator, JsonNode input) {
    if (evaluator == null) {
      return input;
    }
    try {
      List<JsonNode> result = expressionEngineRegistry.transform(evaluator, input);
      return result.isEmpty() ? input : result.get(0);
    } catch (Exception e) {
      LOG.warnf(e, "transform failed for expression (type=%s)", evaluator.type());
      return input;
    }
  }

  private enum ScheduleActionType {
    SKIP,
    CREATE_NEW
  }

  private record ScheduleAction(ScheduleActionType type, Long eventLogId) {

    static ScheduleAction skip() {
      return new ScheduleAction(ScheduleActionType.SKIP, null);
    }

    static ScheduleAction createNew() {
      return new ScheduleAction(ScheduleActionType.CREATE_NEW, null);
    }
  }
}
