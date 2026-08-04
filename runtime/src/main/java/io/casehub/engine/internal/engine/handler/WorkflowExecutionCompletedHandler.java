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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.context.ContextLayer;
import io.casehub.api.context.MutableCaseContext;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.OutcomeAction;
import io.casehub.api.model.OutcomePolicy;
import io.casehub.api.model.WorkResult;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.api.spi.ActionRiskClassifier;
import io.casehub.api.spi.RiskDecision;
import io.casehub.api.spi.WorkerStatusListener;
import io.casehub.api.spi.routing.AgentRoutingContext;
import io.casehub.api.spi.routing.CandidateSetContext;
import io.casehub.engine.common.internal.event.ActionGateScheduleEvent;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.CaseStatusChanged;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.OutcomeDisposition;
import io.casehub.engine.common.internal.event.WorkerOutcomeResolvedEvent;
import io.casehub.engine.common.internal.event.WorkflowExecutionCompleted;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.PendingActionGate;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.engine.common.spi.event.WorkerDecisionEvent;
import io.casehub.engine.internal.acl.WorkerGrantOrchestrator;
import io.casehub.engine.internal.context.EpisodicLayerUpdater;
import io.casehub.engine.internal.routing.GoalFailureRecorder;
import io.casehub.engine.internal.routing.PersonalitySignalRecorder;
import io.casehub.engine.internal.work.CaseResumptionService;
import io.casehub.ledger.api.spi.LedgerTraceIdProvider;
import io.casehub.worker.api.PlannedAction;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerOutcome;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
  @Inject EventLogRepository eventLogRepository;
  @Inject CaseDefinitionRegistry caseDefinitionRegistry;
  @Inject CaseResumptionService caseResumptionService;
  @Inject WorkerStatusListener workerStatusListener;
  @Inject LedgerTraceIdProvider traceIdProvider;
  @Inject ActionRiskClassifier actionRiskClassifier;
  @Inject CaseInstanceRepository caseInstanceRepository;
  @Inject io.casehub.engine.internal.engine.SignalSettlementTracker settlementTracker;
  @Inject PersonalitySignalRecorder personalitySignalRecorder;
  @Inject GoalFailureRecorder goalFailureRecorder;
  @Inject io.casehub.engine.internal.routing.AgentGoalCompletionMarker agentGoalCompletionMarker;

  @Inject WorkerGrantOrchestrator workerGrantOrchestrator;
  @Inject ContextOutputApplier contextOutputApplier;

  @Inject
  jakarta.enterprise.inject.Instance<io.casehub.api.spi.routing.RoutingOutcomeRecorder>
      outcomeRecorder;

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final Logger LOG = Logger.getLogger(WorkflowExecutionCompletedHandler.class);

  @ConsumeEvent(value = EventBusAddresses.WORKER_EXECUTION_FINISHED)
  @RunOnVirtualThread
  void onWorkflowExecutionCompletedHandler(WorkflowExecutionCompleted event) {
    try {
      final String traceId = traceIdProvider.currentTraceId().orElse(null);
      final CaseInstance caseInstance = event.caseInstance();
      final Worker worker = event.worker();

      if (event.workerCredentialToken() != null) {
        try {
          workerGrantOrchestrator.revokeForWorker(
              event.workerCredentialToken(), worker.name(), caseInstance.getUuid(), true);
        } catch (Exception ex) {
          LOG.warnf(
              ex,
              "Worker credential revocation failed for case=%s worker=%s",
              caseInstance.getUuid(),
              worker.name());
        }
      }

      // Outcome fork: non-success outcomes route to the semantic failure path.
      // Completed is treated like Success — it signals lifecycle scope completion.
      if (!(event.outcome() instanceof WorkerOutcome.Success)
          && !(event.outcome() instanceof WorkerOutcome.Completed)) {
        if (event.signalId() != null) {
          settlementTracker.recordCompletion(event.signalId());
        }
        handleSemanticFailure(event, traceId);
        return;
      }

      // Gate fork: if the worker declared a PlannedAction, classify before applying output.
      final PlannedAction topLevelAction =
          event.outcome() instanceof WorkerOutcome.Success s ? s.plannedAction() : null;
      if (topLevelAction != null) {
        if (event.signalId() != null) {
          settlementTracker.recordCompletion(event.signalId());
        }
        handleWithPlannedAction(event, topLevelAction, traceId);
        return;
      }

      final Map<String, Object> rawOutput = event.output() == null ? Map.of() : event.output();
      final String bindingName = event.bindingName();
      final Instant now = Instant.now();

      JsonNode contextBefore = caseInstance.getCaseContext().snapshot().asJsonNode();
      JsonNode diff = contextOutputApplier.apply(caseInstance, rawOutput, bindingName);
      if (caseInstance.getCaseContext() instanceof MutableCaseContext mctx) {
        EpisodicLayerUpdater.recordWorkerCompletion(mctx, worker.name(), "COMPLETED");
      }
      recordSuccessOutcome(caseInstance, worker.name(), bindingName, now);
      agentGoalCompletionMarker.markGoalsCompleted(caseInstance, worker.name());
      fireOutcomeRecorder(
          caseInstance,
          worker,
          bindingName,
          io.casehub.api.spi.routing.RoutingOutcome.SUCCESS,
          contextBefore);
      personalitySignalRecorder.record(
          caseInstance,
          worker.name(),
          extractCapabilityTag(caseInstance, worker, bindingName),
          event.outcome());

      // Settlement recorded AFTER output application
      if (event.signalId() != null) {
        settlementTracker.recordCompletion(event.signalId());
      }

      EventLog eventLog =
          buildEventLog(caseInstance, worker, rawOutput, event.idempotency(), now, diff);

      eventLogRepository.append(eventLog, caseInstance.tenancyId);

      caseResumptionService.resumeIfWaiting(
          caseInstance,
          event.idempotency(),
          worker.name(),
          rawOutput,
          CaseHubEventType.WORK_COMPLETED);

      workerStatusListener.onWorkerCompleted(
          worker.name(),
          WorkResult.completed(
              event.idempotency(), rawOutput, worker.name(), caseInstance.getUuid()));

      // Fire CDI audit events as true fire-and-forget
      lifecycleEvents
          .fireAsync(
              CaseLifecycleEvent.of(
                  caseInstance,
                  "ExecuteWorker",
                  "WorkerExecutionCompleted",
                  "system",
                  "SYSTEM",
                  traceId))
          .whenComplete(
              (v, t) -> {
                if (t != null) {
                  LOG.warnf(
                      t,
                      "CaseLifecycleEvent observer failed for caseId=%s event=WorkerExecutionCompleted",
                      caseInstance.getUuid());
                }
              });
      workerDecisionEvents
          .fireAsync(
              new WorkerDecisionEvent(
                  caseInstance.getUuid(),
                  caseInstance.tenancyId,
                  worker.name(),
                  extractCapabilityTag(caseInstance, worker, bindingName),
                  traceId))
          .whenComplete(
              (v, t) -> {
                if (t != null) {
                  LOG.warnf(
                      t,
                      "WorkerDecisionEvent observer failed for caseId=%s worker=%s",
                      caseInstance.getUuid(),
                      worker.name());
                }
              });

      eventBus.publish(
          EventBusAddresses.CONTEXT_CHANGED,
          new CaseContextChangedEvent(
              caseInstance, caseInstance.getCaseContext().snapshot(), ContextLayer.WORKING));
    } catch (Exception e) {
      LOG.errorf(
          e,
          "Failed to handle WorkflowExecutionCompleted for caseId=%s",
          event.caseInstance().getUuid());
    }
  }

  private void handleWithPlannedAction(
      final WorkflowExecutionCompleted event,
      final PlannedAction plannedAction,
      final String traceId) {
    final CaseInstance caseInstance = event.caseInstance();
    final Worker worker = event.worker();
    final String bindingName = event.bindingName();

    // v1 concurrent-gate constraint: only one pending gate per case.
    if (caseInstance.getPendingActionGate() != null) {
      LOG.errorf(
          "Concurrent gate not supported in v1: caseId=%s worker=%s already has a pending gate"
              + " — proceeding as Autonomous for second action",
          caseInstance.getUuid(), worker.name());
      final WorkflowExecutionCompleted withoutAction =
          WorkflowExecutionCompleted.approved(
              caseInstance, worker, event.idempotency(), event.output(), bindingName);
      onWorkflowExecutionCompletedHandler(withoutAction);
      return;
    }

    final CaseDefinition definition =
        caseDefinitionRegistry.getCaseDefinition(caseInstance.getCaseMetaModel());
    final io.casehub.api.spi.ClassificationContext classificationContext =
        new io.casehub.api.spi.ClassificationContext(
            worker.name(),
            caseInstance.getUuid(),
            caseInstance.tenancyId,
            definition != null ? definition.getName() : null,
            extractCapabilityTag(caseInstance, worker, bindingName),
            bindingName);

    RiskDecision decision;
    try {
      decision = actionRiskClassifier.classify(plannedAction, classificationContext);
    } catch (Exception t) {
      LOG.errorf(
          t,
          "ActionRiskClassifier threw for action type='%s' caseId=%s — applying fail-safe GateRequired",
          plannedAction.actionType(),
          caseInstance.getUuid());
      decision =
          new RiskDecision.GateRequired(
              "Classifier error — manual review required before proceeding",
              true,
              null,
              null,
              null,
              null,
              null);
    }

    if (decision instanceof RiskDecision.Autonomous) {
      final WorkflowExecutionCompleted withoutAction =
          WorkflowExecutionCompleted.approved(
              caseInstance, worker, event.idempotency(), event.output(), bindingName);
      onWorkflowExecutionCompletedHandler(withoutAction);
    } else {
      handleGate(event, plannedAction, (RiskDecision.GateRequired) decision, traceId);
    }
  }

  private void handleSemanticFailure(final WorkflowExecutionCompleted event, final String traceId) {
    final CaseInstance caseInstance = event.caseInstance();
    final Worker worker = event.worker();
    final String bindingName = event.bindingName();
    final Instant now = Instant.now();

    final Binding binding = findBindingByName(caseInstance, bindingName);
    final OutcomePolicy policy =
        binding != null && binding.getOutcomePolicy() != null
            ? binding.getOutcomePolicy()
            : new OutcomePolicy();

    final String outcomeStatus;
    final String reason;
    final OutcomeAction action;
    final CaseHubEventType eventType;

    switch (event.outcome()) {
      case WorkerOutcome.Declined d -> {
        outcomeStatus = "DECLINED";
        reason = d.reason();
        action = policy.onDecline();
        eventType = CaseHubEventType.WORKER_OUTCOME_DECLINED;
      }
      case WorkerOutcome.Failed f -> {
        outcomeStatus = "FAILED";
        reason = f.reason();
        action = policy.onFailure();
        eventType = CaseHubEventType.WORKER_OUTCOME_FAILED;
      }
      case WorkerOutcome.Expired e -> {
        outcomeStatus = "EXPIRED";
        reason = e.reason();
        action = policy.onExpired();
        eventType = CaseHubEventType.WORKER_OUTCOME_EXPIRED;
      }
      case WorkerOutcome.Success s ->
          throw new IllegalStateException("Success should not reach handleSemanticFailure");
      case WorkerOutcome.Completed c ->
          throw new IllegalStateException("Completed should not reach handleSemanticFailure");
    }

    fireOutcomeRecorder(
        caseInstance,
        worker,
        bindingName,
        io.casehub.api.spi.routing.RoutingOutcome.FAILURE,
        caseInstance.getCaseContext().snapshot().asJsonNode());
    String capabilityTag = extractCapabilityTag(caseInstance, worker, bindingName);
    personalitySignalRecorder.record(caseInstance, worker.name(), capabilityTag, event.outcome());
    goalFailureRecorder.record(caseInstance, worker.name(), capabilityTag, event.outcome());

    @SuppressWarnings("unchecked")
    final java.util.Map<String, Object> existingOutcomes =
        (java.util.Map<String, Object>) caseInstance.getCaseContext().get("_diagnostics");
    final ObjectNode outcomesRoot =
        existingOutcomes != null
            ? OBJECT_MAPPER.valueToTree(existingOutcomes).deepCopy()
            : OBJECT_MAPPER.createObjectNode();
    ObjectNode bindingOutcome =
        outcomesRoot.has(bindingName)
            ? (ObjectNode) outcomesRoot.get(bindingName)
            : OBJECT_MAPPER.createObjectNode();
    outcomesRoot.set(bindingName, bindingOutcome);

    final int attempts =
        bindingOutcome.has("attempts") ? bindingOutcome.get("attempts").asInt() + 1 : 1;
    final boolean exhausted =
        action == OutcomeAction.REROUTE && attempts >= policy.maxRerouteAttempts();

    bindingOutcome.put("status", exhausted ? "REROUTES_EXHAUSTED" : outcomeStatus);
    bindingOutcome.put("attempts", attempts);

    ArrayNode history =
        bindingOutcome.has("history")
            ? (ArrayNode) bindingOutcome.get("history")
            : OBJECT_MAPPER.createArrayNode();
    ObjectNode historyEntry =
        OBJECT_MAPPER
            .createObjectNode()
            .put("agent", worker.name())
            .put("status", outcomeStatus)
            .put("reason", reason)
            .put("timestamp", now.toString());
    if (event.output() != null && !event.output().isEmpty()) {
      historyEntry.set("partialOutput", OBJECT_MAPPER.valueToTree(event.output()));
    }
    history.add(historyEntry);
    bindingOutcome.set("history", history);

    ArrayNode excluded =
        bindingOutcome.has("excludedAgents")
            ? (ArrayNode) bindingOutcome.get("excludedAgents")
            : OBJECT_MAPPER.createArrayNode();
    excluded.add(worker.name());
    bindingOutcome.set("excludedAgents", excluded);

    @SuppressWarnings("unchecked")
    final java.util.Map<String, Object> outcomesMap =
        OBJECT_MAPPER.convertValue(outcomesRoot, java.util.Map.class);
    caseInstance.getCaseContext().set("_diagnostics", outcomesMap);

    if (caseInstance.getCaseContext() instanceof MutableCaseContext mctx) {
      EpisodicLayerUpdater.recordWorkerCompletion(mctx, worker.name(), outcomeStatus);
    }

    final EventLog eventLog = new EventLog();
    eventLog.setCaseId(caseInstance.getUuid());
    eventLog.setWorkerId(worker.name());
    eventLog.setStreamType(EventStreamType.CASE);
    eventLog.setTimestamp(now);
    eventLog.setEventType(eventType);
    eventLog.setMetadata(
        OBJECT_MAPPER
            .createObjectNode()
            .put("bindingName", bindingName)
            .put("reason", reason)
            .put("attempts", attempts)
            .put(
                "disposition",
                action == OutcomeAction.FAULT ? "FAULT" : exhausted ? "EXHAUSTED" : "REROUTE"));

    final OutcomeDisposition disposition =
        action == OutcomeAction.FAULT
            ? OutcomeDisposition.FAULT
            : exhausted ? OutcomeDisposition.EXHAUSTED : OutcomeDisposition.REROUTE;

    final String capabilityName = extractCapabilityTag(caseInstance, worker, bindingName);

    eventLogRepository.append(eventLog, caseInstance.tenancyId);

    final WorkResult workResult =
        switch (event.outcome()) {
          case WorkerOutcome.Declined d ->
              WorkResult.declined(event.idempotency(), worker.name(), caseInstance.getUuid());
          case WorkerOutcome.Failed f ->
              WorkResult.failed(event.idempotency(), worker.name(), caseInstance.getUuid());
          case WorkerOutcome.Expired e ->
              WorkResult.expired(event.idempotency(), worker.name(), caseInstance.getUuid());
          case WorkerOutcome.Success s ->
              throw new IllegalStateException("Success should not reach handleSemanticFailure");
          case WorkerOutcome.Completed c ->
              throw new IllegalStateException("Completed should not reach handleSemanticFailure");
        };
    workerStatusListener.onWorkerCompleted(worker.name(), workResult);

    lifecycleEvents
        .fireAsync(
            CaseLifecycleEvent.of(
                caseInstance,
                "WorkerOutcome",
                outcomeStatus + "Outcome",
                worker.name(),
                "WORKER",
                traceId))
        .whenComplete(
            (v, t) -> {
              if (t != null) {
                LOG.warnf(
                    t,
                    "CaseLifecycleEvent observer failed for caseId=%s event=%sOutcome",
                    caseInstance.getUuid(),
                    outcomeStatus);
              }
            });

    if (disposition == OutcomeDisposition.FAULT) {
      eventBus.publish(
          EventBusAddresses.CASE_STATUS_CHANGED,
          new CaseStatusChanged(
              caseInstance, caseInstance.getState().name(), CaseStatus.FAULTED.name()));
    }
    eventBus.publish(
        EventBusAddresses.WORKER_OUTCOME_RESOLVED,
        new WorkerOutcomeResolvedEvent(
            caseInstance, worker.name(), bindingName, capabilityName, disposition));
  }

  private void handleGate(
      final WorkflowExecutionCompleted event,
      final PlannedAction plannedAction,
      final RiskDecision.GateRequired gate,
      final String traceId) {
    final CaseInstance caseInstance = event.caseInstance();
    final Worker worker = event.worker();
    final Map<String, Object> rawOutput = event.output() == null ? Map.of() : event.output();
    final Instant now = Instant.now();
    final String bindingName = event.bindingName();
    final String capabilityName = extractCapabilityTag(caseInstance, worker, bindingName);

    Set<String> resolvedGroups;
    if (gate.candidateGroups() != null) {
      JsonNode contextNode = caseInstance.getCaseContext().layer(ContextLayer.WORKING).asJsonNode();
      try {
        resolvedGroups = gate.candidateGroups().evaluate(new CandidateSetContext(contextNode));
      } catch (Exception t) {
        LOG.warnf(
            t,
            "CandidateSetStrategy evaluation failed for caseId=%s — proceeding with empty candidate groups",
            caseInstance.getUuid());
        resolvedGroups = Set.of();
      }
    } else {
      resolvedGroups = Set.of();
    }

    final EventLog gateEventLog =
        buildGateEventLog(
            caseInstance, worker, rawOutput, plannedAction, gate, event.idempotency(), now);

    eventLogRepository.append(gateEventLog, caseInstance.tenancyId);

    caseInstance.setPendingActionGate(
        new PendingActionGate(
            gateEventLog.id,
            worker.name(),
            event.idempotency(),
            rawOutput,
            plannedAction,
            bindingName,
            capabilityName,
            gate.resolutionType()));
    caseInstanceRepository.update(caseInstance, caseInstance.tenancyId);

    eventBus.publish(
        EventBusAddresses.ACTION_GATE_SCHEDULE,
        new ActionGateScheduleEvent(
            caseInstance.getUuid(),
            caseInstance.tenancyId,
            gateEventLog.id,
            plannedAction,
            gate,
            resolvedGroups,
            gate.resolutionType() != null ? gate.resolutionType().getName() : null));

    lifecycleEvents
        .fireAsync(
            CaseLifecycleEvent.of(
                caseInstance, "ActionGate", "ActionGatePending", worker.name(), "WORKER", traceId))
        .whenComplete(
            (v, t) -> {
              if (t != null) {
                LOG.warnf(
                    t,
                    "CaseLifecycleEvent observer failed for caseId=%s event=ActionGatePending",
                    caseInstance.getUuid());
              }
            });
  }

  private EventLog buildGateEventLog(
      final CaseInstance caseInstance,
      final Worker worker,
      final Map<String, Object> rawOutput,
      final PlannedAction plannedAction,
      final RiskDecision.GateRequired gate,
      final String idempotency,
      final Instant timestamp) {
    final EventLog eventLog = new EventLog();
    eventLog.setCaseId(caseInstance.getUuid());
    eventLog.setWorkerId(worker.name());
    eventLog.setStreamType(EventStreamType.CASE);
    eventLog.setTimestamp(timestamp);
    eventLog.setEventType(CaseHubEventType.ACTION_GATE_PENDING);
    final ObjectNode payload = OBJECT_MAPPER.createObjectNode();
    payload.put("workerId", worker.name());
    payload.put("idempotency", idempotency);
    payload.set("deferredOutput", OBJECT_MAPPER.valueToTree(rawOutput));
    final ObjectNode actionNode = OBJECT_MAPPER.createObjectNode();
    actionNode.put("description", plannedAction.description());
    actionNode.put("actionType", plannedAction.actionType());
    actionNode.set("context", OBJECT_MAPPER.valueToTree(plannedAction.parameters()));
    payload.set("plannedAction", actionNode);
    final ObjectNode gateNode = OBJECT_MAPPER.createObjectNode();
    gateNode.put("reason", gate.reason());
    gateNode.put("reversible", gate.reversible());
    if (gate.expiresIn() != null) {
      gateNode.put("expiresInSeconds", gate.expiresIn().getSeconds());
    }
    payload.set("gateRequired", gateNode);
    eventLog.setPayload(payload);
    eventLog.setMetadata(OBJECT_MAPPER.createObjectNode().put("inputDataHash", idempotency));
    return eventLog;
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
    eventLog.setWorkerId(worker.name());
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
      var keys = OBJECT_MAPPER.createArrayNode();
      contextDiff.fieldNames().forEachRemaining(keys::add);
      if (!keys.isEmpty()) {
        metadata.set("producedKeys", keys);
      }
    }
    return metadata;
  }

  @SuppressWarnings("unchecked")
  private void recordSuccessOutcome(
      final CaseInstance caseInstance,
      final String workerName,
      final String bindingName,
      final Instant now) {
    if (bindingName == null) {
      return;
    }
    final java.util.Map<String, Object> existingOutcomes =
        (java.util.Map<String, Object>) caseInstance.getCaseContext().get("_diagnostics");
    if (existingOutcomes == null) {
      return;
    }
    final ObjectNode outcomesRoot = OBJECT_MAPPER.valueToTree(existingOutcomes).deepCopy();
    if (!outcomesRoot.has(bindingName)) {
      return;
    }
    ObjectNode bindingOutcome = (ObjectNode) outcomesRoot.get(bindingName);
    bindingOutcome.put("status", "COMPLETED");
    ArrayNode history =
        bindingOutcome.has("history")
            ? (ArrayNode) bindingOutcome.get("history")
            : OBJECT_MAPPER.createArrayNode();
    history.add(
        OBJECT_MAPPER
            .createObjectNode()
            .put("agent", workerName)
            .put("status", "COMPLETED")
            .put("timestamp", now.toString()));
    bindingOutcome.set("history", history);
    final java.util.Map<String, Object> outcomesMap =
        OBJECT_MAPPER.convertValue(outcomesRoot, java.util.Map.class);
    caseInstance.getCaseContext().set("_diagnostics", outcomesMap);
  }

  /**
   * Returns the binding with the specified name. Returns null if the definition is absent or no
   * binding matches.
   */
  private Binding findBindingByName(final CaseInstance caseInstance, final String bindingName) {
    final CaseDefinition definition =
        caseDefinitionRegistry.getCaseDefinition(caseInstance.getCaseMetaModel());
    if (definition == null || definition.getBindings() == null || bindingName == null) {
      return null;
    }
    return definition.getBindings().stream()
        .filter(b -> b.getName().equals(bindingName))
        .findFirst()
        .orElse(null);
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
        || worker.capabilityNames() == null) {
      return null;
    }
    for (final Binding binding : definition.getBindings()) {
      if (!(binding.target() instanceof CapabilityTarget ct)) {
        continue;
      }
      final String capabilityName = ct.capability().name();
      if (worker.capabilityNames().contains(capabilityName)) {
        return binding;
      }
    }
    return null;
  }

  private String extractCapabilityTag(
      final CaseInstance caseInstance, final Worker worker, final String bindingName) {
    final Binding binding =
        bindingName != null
            ? findBindingByName(caseInstance, bindingName)
            : findMatchingCapabilityBinding(caseInstance, worker);
    return binding != null && binding.target() instanceof CapabilityTarget ct
        ? ct.capability().name()
        : null;
  }

  private void fireOutcomeRecorder(
      CaseInstance caseInstance,
      Worker worker,
      String bindingName,
      io.casehub.api.spi.routing.RoutingOutcome outcome,
      JsonNode contextSnapshot) {
    if (outcomeRecorder.isUnsatisfied()) return;
    String capabilityName = extractCapabilityTag(caseInstance, worker, bindingName);
    if (capabilityName == null) return;
    var ctx =
        new AgentRoutingContext(
            caseInstance.getUuid(),
            capabilityName,
            contextSnapshot,
            caseInstance.tenancyId,
            List.of(),
            null,
            null);
    outcomeRecorder
        .get()
        .record(ctx, worker.name(), bindingName, outcome, null)
        .subscribe()
        .with(
            ignored -> {},
            err ->
                LOG.warnf(
                    err,
                    "Outcome recording failed for caseId=%s worker=%s",
                    caseInstance.getUuid(),
                    worker.name()));
  }
}
