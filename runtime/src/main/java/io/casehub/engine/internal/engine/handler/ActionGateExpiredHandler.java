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
import io.casehub.api.context.ContextLayer;
import io.casehub.api.model.WorkResult;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.api.spi.WorkerStatusListener;
import io.casehub.api.spi.routing.AgentRoutingContext;
import io.casehub.api.spi.routing.RoutingOutcome;
import io.casehub.api.spi.routing.RoutingOutcomeRecorder;
import io.casehub.engine.common.internal.event.ActionGateExpiredEvent;
import io.casehub.engine.common.internal.event.ActionGateWorkerFaultedEvent;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.PendingActionGate;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.quarkus.vertx.ConsumeEvent;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Handles an expired action gate: clears {@code pendingActionGate}, writes {@code
 * actionGateExpired} signal to the case context, notifies {@link WorkerStatusListener} that the
 * worker faulted (deadline missed), and fires CONTEXT_CHANGED.
 *
 * <p>Uses {@link CaseInstanceCache} — {@code pendingActionGate} is persisted in the JPA entity as a
 * jsonb column for restart resilience (engine#433). CONTEXT_CHANGED fires immediately; EventLog
 * write is fire-and-forget (best-effort compliance record). Refs engine#402.
 */
@ApplicationScoped
public class ActionGateExpiredHandler {

  private static final Logger LOG = Logger.getLogger(ActionGateExpiredHandler.class);

  @Inject CaseInstanceCache caseInstanceCache;
  @Inject EventLogRepository eventLogRepository;
  @Inject EventBus eventBus;
  @Inject WorkerStatusListener workerStatusListener;
  @Inject CaseInstanceRepository caseInstanceRepository;

  @Inject Instance<RoutingOutcomeRecorder> outcomeRecorder;

  // blocking=true: workerStatusListener.onWorkerCompleted() may do I/O in consumer impls
  @ConsumeEvent(value = EventBusAddresses.ACTION_GATE_EXPIRED, blocking = true)
  public void onActionGateExpired(final ActionGateExpiredEvent event) {
    final CaseInstance instance = caseInstanceCache.get(event.caseId());
    if (instance == null) {
      LOG.warnf(
          "CaseInstance not in cache for gate expiry: caseId=%s gateId=%d — discarding",
          event.caseId(), event.gateId());
      return;
    }

    if (instance.getState().isTerminal()) {
      LOG.warnf(
          "Gate expired on terminated case (state=%s): caseId=%s gateId=%d — discarding",
          instance.getState(), event.caseId(), event.gateId());
      return;
    }

    final PendingActionGate gate = instance.getPendingActionGate();
    if (gate == null || gate.gateId() != event.gateId()) {
      LOG.warnf(
          "PendingActionGate mismatch or absent: caseId=%s expected gateId=%d actual=%s"
              + " — discarding",
          event.caseId(), event.gateId(), gate != null ? gate.gateId() : "null");
      return;
    }

    // Capture context snapshot BEFORE gate clearance and signal writes — consistent with
    // fireOutcomeRecorder's pre-modification snapshot pattern.
    final JsonNode contextSnapshot = instance.getCaseContext().snapshot().asJsonNode();

    // Clear gate FIRST — before writing signal (same ordering as rejected handler)
    instance.setPendingActionGate(null);
    caseInstanceRepository.update(instance, instance.tenancyId);

    instance
        .getCaseContext()
        .set(
            "actionGateExpired",
            Map.of(
                "actionType", gate.plannedAction().actionType(),
                "workerId", gate.workerId(),
                "gateId", gate.gateId()));

    workerStatusListener.onWorkerCompleted(
        gate.workerId(),
        WorkResult.faulted(gate.idempotency(), gate.workerId(), instance.getUuid()));

    if (!outcomeRecorder.isUnsatisfied() && gate.capabilityName() != null) {
      var ctx =
          new AgentRoutingContext(
              instance.getUuid(),
              gate.capabilityName(),
              contextSnapshot,
              instance.tenancyId,
              List.of(),
              null,
              null);
      try {
        outcomeRecorder
            .get()
            .record(ctx, gate.workerId(), gate.bindingName(), RoutingOutcome.GATE_EXPIRED, null);
      } catch (Exception err) {
        LOG.warnf(
            err,
            "Outcome recording failed for gate-expired caseId=%s worker=%s",
            instance.getUuid(),
            gate.workerId());
      }
    }

    eventBus.publish(
        EventBusAddresses.CONTEXT_CHANGED,
        new CaseContextChangedEvent(
            instance, instance.getCaseContext().snapshot(), ContextLayer.WORKING));

    // Notify the blackboard to mark the PlanItem FAULTED (gate-specific event, not
    // WORKER_RETRIES_EXHAUSTED)
    eventBus.publish(
        EventBusAddresses.ACTION_GATE_WORKER_FAULTED,
        new ActionGateWorkerFaultedEvent(
            instance.getUuid(), instance.tenancyId, gate.workerId(), gate.idempotency()));

    // EventLog write is best-effort — failures are logged, not propagated
    try {
      writeResolutionEventLog(instance, gate);
    } catch (Exception t) {
      LOG.warnf(
          t,
          "ACTION_GATE_EXPIRED EventLog write failed: caseId=%s gateId=%d"
              + " — gate resolution still applied",
          instance.getUuid(),
          gate.gateId());
    }
  }

  private void writeResolutionEventLog(final CaseInstance instance, final PendingActionGate gate) {
    final EventLog log = new EventLog();
    log.setCaseId(instance.getUuid());
    log.setWorkerId(gate.workerId());
    log.setStreamType(EventStreamType.CASE);
    log.setTimestamp(Instant.now());
    log.setEventType(CaseHubEventType.ACTION_GATE_EXPIRED);
    eventLogRepository.append(log, instance.tenancyId);
  }
}
