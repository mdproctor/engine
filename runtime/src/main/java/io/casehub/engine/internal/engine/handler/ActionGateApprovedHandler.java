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
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.context.BridgeResolver;
import io.casehub.engine.common.internal.event.ActionGateApprovedEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkflowExecutionCompleted;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.PendingActionGate;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.worker.api.Worker;
import io.quarkus.vertx.ConsumeEvent;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Resolves an approved action gate by re-firing {@link WorkflowExecutionCompleted} with {@code
 * plannedAction=null}, letting the normal completion machinery apply the deferred output, mark the
 * PlanItem COMPLETED (via blackboard), and fire CONTEXT_CHANGED.
 *
 * <p>Uses {@link CaseInstanceCache} to access the live in-memory {@link CaseInstance}. The {@code
 * pendingActionGate} is persisted in the JPA entity as a jsonb column for restart resilience
 * (engine#433). Gate clears are persisted via {@code CaseInstanceRepository.update()}.
 *
 * <p>Terminal state guard: if the case is already COMPLETED/FAULTED/CANCELLED when approval
 * arrives, the gate is cleared in memory and the deferred output discarded. Refs engine#402.
 */
@ApplicationScoped
public class ActionGateApprovedHandler {

  private static final Logger LOG = Logger.getLogger(ActionGateApprovedHandler.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Inject CaseInstanceCache caseInstanceCache;
  @Inject CaseDefinitionRegistry caseDefinitionRegistry;
  @Inject EventLogRepository eventLogRepository;
  @Inject EventBus eventBus;
  @Inject BridgeResolver bridgeResolver;
  @Inject CaseInstanceRepository caseInstanceRepository;

  @ConsumeEvent(value = EventBusAddresses.ACTION_GATE_APPROVED)
  public void onActionGateApproved(final ActionGateApprovedEvent event) {
    final CaseInstance instance = caseInstanceCache.get(event.caseId());
    if (instance == null) {
      LOG.warnf(
          "CaseInstance not in cache for gate approval: caseId=%s gateId=%d — discarding",
          event.caseId(), event.gateId());
      return;
    }

    // Terminal state guard — case terminated while gate was pending
    if (instance.getState().isTerminal()) {
      LOG.warnf(
          "Gate approved on terminated case (state=%s): caseId=%s gateId=%d — discarding",
          instance.getState(), event.caseId(), event.gateId());
      instance.setPendingActionGate(null);
      caseInstanceRepository.update(instance, instance.tenancyId);
      return;
    }

    final PendingActionGate gate = instance.getPendingActionGate();
    if (gate == null || gate.gateId() != event.gateId()) {
      LOG.warnf(
          "PendingActionGate mismatch or absent: caseId=%s expected gateId=%d actual=%s — discarding",
          event.caseId(), event.gateId(), gate != null ? gate.gateId() : "null");
      return;
    }

    // Validate and deserialise typed resolution if resolutionTypeName is declared
    Object deserializedResolution = event.workItemResolution();
    if (event.resolutionTypeName() != null && event.workItemResolution() != null) {
      try {
        var bridge = bridgeResolver.resolveByTypeNameStrict(event.resolutionTypeName());
        JsonNode resolutionJson =
            OBJECT_MAPPER.readTree(event.workItemResolution());
        deserializedResolution = bridgeResolver.deserialise(bridge, resolutionJson);
      } catch (Exception e) {
        LOG.errorf(
            e,
            "Gate resolution validation failed for caseId=%s gateId=%d type=%s — discarding gate",
            event.caseId(),
            event.gateId(),
            event.resolutionTypeName());
        instance.setPendingActionGate(null);
        caseInstanceRepository.update(instance, instance.tenancyId);
        return;
      }
    }

    // Write actionGateApproved signal so downstream bindings can observe the approval
    instance
        .getCaseContext()
        .set(
            "actionGateApproved",
            Map.of(
                "actionType", gate.plannedAction().actionType(),
                "workerId", gate.workerId(),
                "approvedBy", event.approvedBy() != null ? event.approvedBy() : "unknown",
                "gateId", gate.gateId(),
                "resolution", deserializedResolution != null ? deserializedResolution : ""));

    instance.setPendingActionGate(null);
    caseInstanceRepository.update(instance, instance.tenancyId);

    // Write compliance EventLog entry, then re-fire the completion event
    writeResolutionEventLog(instance, gate);
    refireCompletion(instance, gate);
  }

  private void refireCompletion(final CaseInstance instance, final PendingActionGate gate) {
    final Worker worker = findWorker(instance, gate.workerId());
    if (worker == null) {
      LOG.errorf(
          "Worker '%s' not found in definition for caseId=%s — deferred output discarded",
          gate.workerId(), instance.getUuid());
      return;
    }
    // Re-fire WorkflowExecutionCompleted with plannedAction=null — normal completion path runs:
    // output applied to context, PlanItem COMPLETED (via blackboard), CONTEXT_CHANGED fired.
    eventBus.publish(
        EventBusAddresses.WORKER_EXECUTION_FINISHED,
        WorkflowExecutionCompleted.approved(
            instance, worker, gate.idempotency(), gate.deferredOutput(), gate.bindingName()));
    LOG.infof(
        "Gate approved — re-fired WorkflowExecutionCompleted: caseId=%s worker=%s gateId=%d",
        instance.getUuid(), gate.workerId(), gate.gateId());
  }

  private Worker findWorker(final CaseInstance instance, final String workerId) {
    final var def = caseDefinitionRegistry.getCaseDefinition(instance.getCaseMetaModel());
    if (def == null) return null;
    return def.getWorkers().stream()
        .filter(w -> w.name().equals(workerId))
        .findFirst()
        .orElse(null);
  }

  private void writeResolutionEventLog(final CaseInstance instance, final PendingActionGate gate) {
    final EventLog log = new EventLog();
    log.setCaseId(instance.getUuid());
    log.setWorkerId(gate.workerId());
    log.setStreamType(EventStreamType.CASE);
    log.setTimestamp(Instant.now());
    log.setEventType(CaseHubEventType.ACTION_GATE_APPROVED);
    eventLogRepository.append(log, instance.tenancyId);
  }
}
