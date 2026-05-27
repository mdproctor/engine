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
import io.casehub.api.model.ExtensionTarget;
import io.casehub.api.model.HumanTaskTarget;
import io.casehub.api.model.SubCaseTarget;
import io.casehub.api.model.WorkResult;
import io.casehub.api.model.Worker;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.api.spi.ContextDiffStrategy;
import io.casehub.api.spi.WorkerStatusListener;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.CaseLifecycleEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkflowExecutionCompleted;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.internal.work.CaseResumptionService;
import io.casehub.ledger.api.spi.LedgerTraceIdProvider;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
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
                        worker.getName(),
                        "WORKER",
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
   * Finds the conflict resolver strategy for the given worker by looking up the binding whose
   * capability matches one of the worker's capabilities. Returns null if no binding is found
   * (default: LAST_WRITER_WINS).
   */
  private String resolveConflictStrategy(CaseInstance caseInstance, Worker worker) {
    CaseDefinition definition =
        caseDefinitionRegistry.getCaseDefinition(caseInstance.getCaseMetaModel());
    if (definition == null
        || definition.getBindings() == null
        || worker.getCapabilities() == null) {
      return null;
    }
    List<Binding> bindings = definition.getBindings();
    for (Binding binding : bindings) {
      CapabilityTarget ct =
          switch (binding.target()) {
            case CapabilityTarget cap -> cap;
            case SubCaseTarget st -> null;
            case HumanTaskTarget ht -> null;
            case ExtensionTarget et -> null;
          };
      if (ct == null) {
        continue;
      }
      String capabilityName = ct.capability().getName();
      boolean workerMatchesCapability =
          worker.getCapabilities().stream().anyMatch(c -> c.getName().equals(capabilityName));
      if (workerMatchesCapability) {
        return binding.getConflictResolverStrategy();
      }
    }
    return null;
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
