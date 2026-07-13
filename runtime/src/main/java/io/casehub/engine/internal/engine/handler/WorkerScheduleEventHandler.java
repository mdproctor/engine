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
import io.casehub.api.model.RetryState;
import io.casehub.api.model.WorkRequest;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.api.spi.CaseChannelProvider;
import io.casehub.api.spi.WorkerContextProvider;
import io.casehub.api.spi.WorkerExecutionGuard;
import io.casehub.api.spi.routing.RetrievedExperience;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkerRetriesExhaustedEvent;
import io.casehub.engine.common.internal.event.WorkerScheduleEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.jq.ValidationResult;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.utils.WorkerExecutionKeys;
import io.casehub.engine.common.spi.ReactiveEventLogRepository;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.eventbus.EventBus;
import io.vertx.mutiny.core.shareddata.Lock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class WorkerScheduleEventHandler {

  private static final Logger LOG = Logger.getLogger(WorkerScheduleEventHandler.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  @Inject Vertx vertx;

  @Inject WorkerExecutionManager workflowExecutionManager;

  @Inject WorkerExecutionGuard workerExecutionGuard;

  @Inject WorkerContextProvider workerContextProvider;

  @Inject CaseChannelProvider caseChannelProvider;

  @Inject EventBus eventBus;

  @Inject ReactiveEventLogRepository reactiveEventLogRepository;

  @Inject JQEvaluator jqEvaluator;
  @Inject io.casehub.engine.common.internal.context.BridgeResolver bridgeResolver;

  @Inject io.casehub.engine.common.spi.CaseDefinitionRegistry caseDefinitionRegistry;

  @ConfigProperty(name = "casehub.idempotency.window")
  Optional<Duration> idempotencyWindow;

  @ConsumeEvent(value = EventBusAddresses.WORKER_SCHEDULE)
  public Uni<Void> onWorkerScheduleEventHandler(WorkerScheduleEvent event) {
    CaseInstance instance = event.caseInstance();
    Worker worker = event.worker();
    Capability capability = event.capability();
    String bindingName = event.bindingName();

    JsonNode narrowedInput =
        evalJqAsJsonNode(
            instance.getCaseContext().layer(ContextLayer.WORKING).asJsonNode(),
            event.effectiveInputProjection());

    CaseDefinition definition =
        caseDefinitionRegistry.getCaseDefinition(instance.getCaseMetaModel());
    io.casehub.api.context.ContextBridge<?> bridge = bridgeResolver.resolve(worker, definition);
    Object typedInput = bridgeResolver.initialise(bridge, instance.getCaseContext(), narrowedInput);
    JsonNode serialisedPayload = bridgeResolver.serialise(bridge, typedInput);

    Map<String, Object> inputDataForHash = OBJECT_MAPPER.convertValue(narrowedInput, MAP_TYPE);
    String inputDataHash =
        WorkerExecutionKeys.inputDataHash(
            instance.getUuid(), worker.name(), capability.name(), inputDataForHash);

    if (workerExecutionGuard.isBlocked(worker.name(), instance.getUuid())) {
      LOG.warnf(
          "Worker blocked by guard (quarantined?): caseId=%s worker=%s — emitting retries exhausted",
          instance.getUuid(), worker.name());
      eventBus.publish(
          EventBusAddresses.WORKER_RETRIES_EXHAUSTED,
          new WorkerRetriesExhaustedEvent(
              instance.getUuid(),
              instance.tenancyId,
              worker.name(),
              inputDataHash,
              bindingName,
              event.signalId(),
              RetryState.empty()));
      return Uni.createFrom().voidItem();
    }

    workerContextProvider.buildContext(
        worker.name(), instance.getUuid(), WorkRequest.of(capability.name(), inputDataForHash));

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
            event.experiences());

    String lockKey = "wse:" + instance.getUuid() + ":" + worker.name() + ":" + inputDataHash;

    return vertx
        .sharedData()
        .getLocalLock(lockKey)
        .chain(
            lock ->
                scheduleUnderLock(
                    lock,
                    eventLog,
                    instance,
                    worker,
                    capability,
                    inputDataForHash,
                    inputDataHash,
                    bindingName));
  }

  private Uni<Void> scheduleUnderLock(
      Lock lock,
      EventLog eventLog,
      CaseInstance instance,
      Worker worker,
      Capability capability,
      Map<String, Object> inputData,
      String inputDataHash,
      String bindingName) {
    Instant idempotencyAfter = idempotencyWindow.map(w -> Instant.now().minus(w)).orElse(null);

    return reactiveEventLogRepository
        .findSchedulingEvents(
            instance.getUuid(), worker.name(), idempotencyAfter, instance.tenancyId)
        .map(existing -> decideAction(existing, inputDataHash))
        .chain(action -> executeAction(action, eventLog, instance, worker, capability))
        .chain(
            eventLogId ->
                submitIfNeeded(eventLogId, instance, worker, capability, inputData, bindingName))
        .invoke(
            () ->
                LOG.infof(
                    "WorkerScheduleEvent processed: caseId=%s worker=%s capability=%s",
                    instance.getUuid(), worker.name(), capability.name()))
        .invoke(lock::release)
        .replaceWithVoid()
        .onFailure()
        .invoke(
            t ->
                LOG.errorf(
                    t,
                    "WorkerScheduleEvent FAILED: caseId=%s worker=%s capability=%s",
                    instance.getUuid(),
                    worker.name(),
                    capability.name()))
        .onFailure()
        .invoke(t -> lock.release());
  }

  private EventLog buildEventLog(
      CaseInstance instance,
      Worker worker,
      Capability capability,
      JsonNode serialisedPayload,
      String inputDataHash,
      String bindingName,
      java.util.UUID signalId,
      io.casehub.api.model.event.ExecutionOrigin origin,
      String contextBridgeType,
      List<RetrievedExperience> experiences) {
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

    ObjectNode metaNode = OBJECT_MAPPER.valueToTree(metadataBuilder);
    if (experiences != null && !experiences.isEmpty()) {
      metaNode.set("experiences", OBJECT_MAPPER.valueToTree(experiences));
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

  private Uni<Long> executeAction(
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
        yield Uni.createFrom().nullItem();
      }
      case CREATE_NEW -> reactiveEventLogRepository.appendAndReturnId(eventLog, instance.tenancyId);
    };
  }

  private Uni<Void> submitIfNeeded(
      Long eventLogId,
      CaseInstance instance,
      Worker worker,
      Capability capability,
      Map<String, Object> inputData,
      String bindingName) {
    if (eventLogId == null) {
      return Uni.createFrom().voidItem();
    }
    return workflowExecutionManager
        .submit(eventLogId, instance, worker, capability, inputData, bindingName)
        .invoke(() -> dispatchCommand(instance, worker, capability, inputData, eventLogId));
  }

  private void dispatchCommand(
      CaseInstance instance,
      Worker worker,
      Capability capability,
      Map<String, Object> inputData,
      Long eventLogId) {
    CaseChannel channel =
        caseChannelProvider.openChannel(instance.getUuid(), "worker:" + worker.name());
    // ISO-8601 via Instant.toString(); consumer must use Instant.parse() to handle
    // optional sub-second precision (e.g. "...00Z" vs "...00.123Z")
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
        deadline);
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
      // Live duplicate schedule events must not re-submit the same Quartz job.
      // If a WORKER_SCHEDULED event was persisted but never executed due to a crash,
      // WorkerExecutionRecoveryService is responsible for replaying it.
      return ScheduleAction.skip();
    }
    return ScheduleAction.createNew();
  }

  private record ScheduleAction(ScheduleActionType type, Long eventLogId) {

    static ScheduleAction skip() {
      return new ScheduleAction(ScheduleActionType.SKIP, null);
    }

    static ScheduleAction createNew() {
      return new ScheduleAction(ScheduleActionType.CREATE_NEW, null);
    }
  }

  private enum ScheduleActionType {
    SKIP,
    CREATE_NEW
  }

  private static String serialize(final Object value) {
    try {
      return OBJECT_MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new RuntimeException("Failed to serialize " + value.getClass().getSimpleName(), e);
    }
  }

  private Map<String, Object> evalJqAsMap(JsonNode context, String expression) {
    if (expression == null || expression.isBlank()) return Map.of();
    try {
      ValidationResult vr = jqEvaluator.eval(expression, context);
      if (!vr.ok() || vr.output() == null || vr.output().isEmpty()) return Map.of();
      return OBJECT_MAPPER.convertValue(vr.output().get(0), MAP_TYPE);
    } catch (Exception e) {
      LOG.warnf(e, "jq evaluation failed for expression '%s'", expression);
      return Map.of();
    }
  }

  private JsonNode evalJqAsJsonNode(JsonNode context, String expression) {
    if (expression == null || expression.isBlank()) {
      return context;
    }
    try {
      ValidationResult vr = jqEvaluator.eval(expression, context);
      if (!vr.ok() || vr.output() == null || vr.output().isEmpty()) {
        return context;
      }
      return vr.output().get(0);
    } catch (Exception e) {
      LOG.warnf(e, "jq evaluation failed for expression '%s'", expression);
      return context;
    }
  }
}
