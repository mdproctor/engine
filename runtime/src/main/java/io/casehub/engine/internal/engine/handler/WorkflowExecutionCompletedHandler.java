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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.context.CaseContext;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.WorkResult;
import io.casehub.api.model.Worker;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.api.spi.ContextDiffStrategy;
import io.casehub.api.spi.WorkerStatusListener;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkflowExecutionCompleted;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.engine.common.spi.event.WorkerDecisionEvent;
import io.casehub.engine.internal.work.CaseResumptionService;
import io.casehub.ledger.api.spi.LedgerTraceIdProvider;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Applies worker output to the case context, persists the completion event, and notifies listeners
 * that the context has changed.
 */
@ApplicationScoped
public class WorkflowExecutionCompletedHandler {

  @Inject EventBus eventBus;
  @Inject Event<CaseLifecycleEvent> lifecycleEvents;
  @Inject Event<WorkerDecisionEvent> workerDecisionEvents;
  @Inject ContextDiffStrategy contextDiffStrategy;
  @Inject EventLogRepository eventLogRepository;
  @Inject CaseDefinitionRegistry caseDefinitionRegistry;
  @Inject CaseResumptionService caseResumptionService;
  @Inject WorkerStatusListener workerStatusListener;
  @Inject LedgerTraceIdProvider traceIdProvider;

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final Logger LOG = Logger.getLogger(WorkflowExecutionCompletedHandler.class);

  @ConsumeEvent(value = EventBusAddresses.WORKER_EXECUTION_FINISHED)
  public Uni<Void> onWorkflowExecutionCompletedHandler(WorkflowExecutionCompleted event) {
    final String traceId = traceIdProvider.currentTraceId().orElse(null);
    final CaseInstance caseInstance = event.caseInstance();
    final Worker worker = event.worker();
    final Map<String, Object> rawOutput = event.output() == null ? Map.of() : event.output();
    final Instant now = Instant.now();

    JsonNode contextBefore = caseInstance.getCaseContext().snapshot().asJsonNode();
    applyOutputWithConflictResolution(caseInstance, worker, rawOutput);
    JsonNode contextAfter = caseInstance.getCaseContext().asJsonNode();
    JsonNode diff = contextDiffStrategy.compute(contextBefore, contextAfter);

    EventLog eventLog =
        buildEventLog(caseInstance, worker, rawOutput, event.idempotency(), now, diff);

    return eventLogRepository
        .append(eventLog)
        .chain(
            () ->
                caseResumptionService.resumeIfWaiting(
                    caseInstance,
                    event.idempotency(),
                    worker.getName(),
                    rawOutput,
                    CaseHubEventType.WORK_COMPLETED))
        .invoke(
            () ->
                workerStatusListener.onWorkerCompleted(
                    worker.getName(),
                    WorkResult.completed(
                        event.idempotency(), rawOutput, worker.getName(), caseInstance.getUuid())))
        .invoke(
            () ->
                lifecycleEvents.fireAsync(
                    new CaseLifecycleEvent(
                        caseInstance.getUuid(),
                        "ExecuteWorker",
                        "WorkerExecutionCompleted",
                        caseInstance.getState().name(),
                        // "system" — the engine applied the worker's output; the worker's decision
                        // record is written separately as WorkerDecisionEntry via
                        // WorkerDecisionEvent
                        "system",
                        "SYSTEM",
                        traceId)))
        .invoke(
            () ->
                workerDecisionEvents.fireAsync(
                    new WorkerDecisionEvent(
                        caseInstance.getUuid(),
                        worker.getName(),
                        extractCapabilityTag(caseInstance, worker),
                        traceId)))
        .invoke(
            () ->
                eventBus.publish(
                    EventBusAddresses.CONTEXT_CHANGED,
                    new CaseContextChangedEvent(caseInstance, contextAfter)))
        .replaceWithVoid()
        .onFailure()
        .invoke(
            t ->
                LOG.error(
                    "Failed to handle WorkflowExecutionCompleted for caseId: "
                        + caseInstance.getUuid(),
                    t));
  }

  private EventLog buildEventLog(
      CaseInstance caseInstance,
      Worker worker,
      Map<String, Object> output,
      String idempotency,
      Instant timestamp,
      JsonNode contextDiff) {
    EventLog eventLog = new EventLog();
    eventLog.setCaseId(caseInstance.getUuid());
    eventLog.setWorkerId(worker.getName());
    eventLog.setStreamType(EventStreamType.CASE);
    eventLog.setTimestamp(timestamp);
    eventLog.setEventType(CaseHubEventType.WORKER_EXECUTION_COMPLETED);
    eventLog.setPayload(OBJECT_MAPPER.valueToTree(output == null ? Map.of() : output));
    eventLog.setMetadata(buildMetadata(idempotency, contextDiff));
    return eventLog;
  }

  private JsonNode buildMetadata(String idempotency, JsonNode contextDiff) {
    ObjectNode metadata = OBJECT_MAPPER.createObjectNode();
    metadata.put("inputDataHash", idempotency);
    if (contextDiff != null) {
      metadata.set("contextChanges", contextDiff);
    }
    return metadata;
  }

  /**
   * Writes each key from rawOutput to CaseContext, applying the conflict resolver strategy
   * configured on the Binding that triggered this worker. If no strategy is set, the default is
   * LAST_WRITER_WINS (overwrite). See casehubio/engine#45, #51.
   */
  private void applyOutputWithConflictResolution(
      CaseInstance caseInstance, Worker worker, Map<String, Object> rawOutput) {
    if (rawOutput.isEmpty()) {
      return;
    }
    String strategy = resolveConflictStrategy(caseInstance, worker);
    CaseContext caseContext = caseInstance.getCaseContext();
    for (Map.Entry<String, Object> entry : rawOutput.entrySet()) {
      String key = entry.getKey();
      Object incoming = entry.getValue();
      Object existing = caseContext.get(key);
      Object resolved =
          (existing != null) ? applyStrategy(strategy, key, existing, incoming) : incoming;
      caseContext.set(key, resolved);
    }
  }

  /**
   * Returns the first binding with a {@link CapabilityTarget} whose capability name matches one of
   * the worker's declared capabilities. Returns null if the definition is absent or no binding
   * matches.
   */
  private Binding findMatchingCapabilityBinding(
      final CaseInstance caseInstance, final Worker worker) {
    final CaseDefinition definition =
        caseDefinitionRegistry.getCaseDefinition(caseInstance.getCaseMetaModel());
    if (definition == null
        || definition.getBindings() == null
        || worker.getCapabilities() == null) {
      return null;
    }
    for (final Binding binding : definition.getBindings()) {
      if (!(binding.target() instanceof CapabilityTarget ct)) {
        continue;
      }
      final String capabilityName = ct.capability().getName();
      if (worker.getCapabilities().stream().anyMatch(c -> c.getName().equals(capabilityName))) {
        return binding;
      }
    }
    return null;
  }

  private String extractCapabilityTag(final CaseInstance caseInstance, final Worker worker) {
    final Binding binding = findMatchingCapabilityBinding(caseInstance, worker);
    return binding != null ? ((CapabilityTarget) binding.target()).capability().getName() : null;
  }

  private String resolveConflictStrategy(final CaseInstance caseInstance, final Worker worker) {
    final Binding binding = findMatchingCapabilityBinding(caseInstance, worker);
    return binding != null ? binding.getConflictResolverStrategy() : null;
  }

  /**
   * Applies the named conflict resolution strategy. Null or unknown strategy defaults to
   * LAST_WRITER_WINS (return incoming). See casehubio/engine#45, #51.
   */
  private Object applyStrategy(String strategy, String key, Object existing, Object incoming) {
    if (strategy == null) return incoming; // default: last writer wins
    return switch (strategy) {
      case "FIRST_WRITER_WINS" -> existing;
      case "FAIL" ->
          throw new IllegalStateException(
              "Conflicting writes to key '"
                  + key
                  + "' — binding uses FAIL strategy. "
                  + "Refs casehubio/engine#45");
      default -> {
        LOG.warnf(
            "Unknown conflict resolver strategy '%s' for key '%s' — "
                + "falling back to LAST_WRITER_WINS. Refs casehubio/engine#45",
            strategy, key);
        yield incoming;
      }
    };
  }
}
