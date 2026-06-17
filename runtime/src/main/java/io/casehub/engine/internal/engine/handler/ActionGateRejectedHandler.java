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

import io.casehub.api.context.ContextPanel;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.WorkResult;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.api.spi.WorkerStatusListener;
import io.casehub.engine.common.internal.event.ActionGateRejectedEvent;
import io.casehub.engine.common.internal.event.ActionGateWorkerFaultedEvent;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.PendingActionGate;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Handles a rejected action gate: clears {@code pendingActionGate}, writes {@code
 * actionGateRejected} signal to the case context, notifies {@link WorkerStatusListener} that the
 * worker faulted, and fires CONTEXT_CHANGED.
 *
 * <p>Uses {@link CaseInstanceCache} — {@code pendingActionGate} is an in-memory field not persisted
 * by the JPA entity.
 *
 * <p>Ordering: gate is cleared BEFORE the signal is set, preventing a race where the test observes
 * the signal but not yet the cleared gate. EventLog write is best-effort — CONTEXT_CHANGED fires
 * regardless of EventLog success.
 *
 * <p>Case definitions must include a {@code contextChange(".actionGateRejected")} binding. The
 * blackboard module also consumes ACTION_GATE_REJECTED to mark the PlanItem FAULTED, enabling stage
 * autocomplete. Refs engine#402.
 */
@ApplicationScoped
public class ActionGateRejectedHandler {

  private static final Logger LOG = Logger.getLogger(ActionGateRejectedHandler.class);

  @Inject CaseInstanceCache caseInstanceCache;
  @Inject EventLogRepository eventLogRepository;
  @Inject EventBus eventBus;
  @Inject WorkerStatusListener workerStatusListener;

  // blocking=true: workerStatusListener.onWorkerCompleted() may do I/O in consumer impls
  @ConsumeEvent(value = EventBusAddresses.ACTION_GATE_REJECTED, blocking = true)
  public Uni<Void> onActionGateRejected(final ActionGateRejectedEvent event) {
    final CaseInstance instance = caseInstanceCache.get(event.caseId());
    if (instance == null) {
      LOG.warnf(
          "CaseInstance not in cache for gate rejection: caseId=%s gateId=%d — discarding",
          event.caseId(), event.gateId());
      return Uni.createFrom().voidItem();
    }

    if (isTerminal(instance.getState())) {
      LOG.warnf(
          "Gate rejected on terminated case (state=%s): caseId=%s gateId=%d — discarding",
          instance.getState(), event.caseId(), event.gateId());
      return Uni.createFrom().voidItem();
    }

    final PendingActionGate gate = instance.getPendingActionGate();
    if (gate == null || gate.gateId() != event.gateId()) {
      LOG.warnf(
          "PendingActionGate mismatch or absent: caseId=%s expected gateId=%d actual=%s"
              + " — discarding",
          event.caseId(), event.gateId(), gate != null ? gate.gateId() : "null");
      return Uni.createFrom().voidItem();
    }

    // Clear gate FIRST — before setting signal — prevents observer-thread race where the
    // rejectionSignal is visible in context before the gate field is cleared.
    instance.setPendingActionGate(null);

    // Write rejection signal — case definitions react via contextChange(".actionGateRejected")
    instance
        .getCaseContext()
        .set(
            "actionGateRejected",
            Map.of(
                "actionType",
                gate.plannedAction().actionType(),
                "workerId",
                gate.workerId(),
                "rejectedBy",
                event.rejectedBy() != null ? event.rejectedBy() : "unknown",
                "resolution",
                event.workItemResolution() != null ? event.workItemResolution() : ""));

    workerStatusListener.onWorkerCompleted(
        gate.workerId(),
        WorkResult.faulted(gate.idempotency(), gate.workerId(), instance.getUuid()));

    // Fire CONTEXT_CHANGED immediately — gate is already cleared and signal written
    eventBus.publish(
        EventBusAddresses.CONTEXT_CHANGED,
        new CaseContextChangedEvent(
            instance, instance.getCaseContext().snapshot(), ContextPanel.WORKING));

    // Notify the blackboard (if active) to mark the PlanItem FAULTED so stage autocomplete fires.
    // Uses ACTION_GATE_WORKER_FAULTED (not WORKER_RETRIES_EXHAUSTED) to avoid case-fault
    // transition.
    eventBus.publish(
        EventBusAddresses.ACTION_GATE_WORKER_FAULTED,
        new ActionGateWorkerFaultedEvent(
            instance.getUuid(), gate.workerId(), gate.idempotency(), instance.tenancyId));

    // EventLog write is best-effort and fire-and-forget — failures are logged, not propagated
    writeResolutionEventLog(instance, gate)
        .onFailure()
        .invoke(
            t ->
                LOG.warnf(
                    t,
                    "ACTION_GATE_REJECTED EventLog write failed: caseId=%s gateId=%d — gate"
                        + " resolution still applied",
                    instance.getUuid(),
                    gate.gateId()))
        .onFailure()
        .recoverWithNull()
        .subscribe()
        .asCompletionStage();

    return Uni.createFrom().voidItem();
  }

  private Uni<Void> writeResolutionEventLog(
      final CaseInstance instance, final PendingActionGate gate) {
    final EventLog log = new EventLog();
    log.setCaseId(instance.getUuid());
    log.setWorkerId(gate.workerId());
    log.setStreamType(EventStreamType.CASE);
    log.setTimestamp(Instant.now());
    log.setEventType(CaseHubEventType.ACTION_GATE_REJECTED);
    return eventLogRepository.append(log, instance.tenancyId);
  }

  private static boolean isTerminal(final CaseStatus state) {
    return state == CaseStatus.COMPLETED
        || state == CaseStatus.FAULTED
        || state == CaseStatus.CANCELLED;
  }
}
