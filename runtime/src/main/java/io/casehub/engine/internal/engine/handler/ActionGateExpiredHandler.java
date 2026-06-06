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

import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.WorkResult;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.api.spi.WorkerStatusListener;
import io.casehub.engine.common.internal.event.ActionGateExpiredEvent;
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
 * Handles an expired action gate: clears {@code pendingActionGate}, writes {@code
 * actionGateExpired} signal to the case context, notifies {@link WorkerStatusListener} that the
 * worker faulted (deadline missed), and fires CONTEXT_CHANGED.
 *
 * <p>Uses {@link CaseInstanceCache} — {@code pendingActionGate} is an in-memory field not persisted
 * by the JPA entity. CONTEXT_CHANGED fires immediately; EventLog write is fire-and-forget
 * (best-effort compliance record). Refs engine#402.
 */
@ApplicationScoped
public class ActionGateExpiredHandler {

  private static final Logger LOG = Logger.getLogger(ActionGateExpiredHandler.class);

  @Inject CaseInstanceCache caseInstanceCache;
  @Inject EventLogRepository eventLogRepository;
  @Inject EventBus eventBus;
  @Inject WorkerStatusListener workerStatusListener;

  @ConsumeEvent(value = EventBusAddresses.ACTION_GATE_EXPIRED)
  public Uni<Void> onActionGateExpired(final ActionGateExpiredEvent event) {
    final CaseInstance instance = caseInstanceCache.get(event.caseId());
    if (instance == null) {
      LOG.warnf(
          "CaseInstance not in cache for gate expiry: caseId=%s gateId=%d — discarding",
          event.caseId(), event.gateId());
      return Uni.createFrom().voidItem();
    }

    if (isTerminal(instance.getState())) {
      LOG.warnf(
          "Gate expired on terminated case (state=%s): caseId=%s gateId=%d — discarding",
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

    // Clear gate FIRST — before writing signal (same ordering as rejected handler)
    instance.setPendingActionGate(null);

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

    eventBus.publish(
        EventBusAddresses.CONTEXT_CHANGED,
        new CaseContextChangedEvent(instance, instance.getCaseContext().asJsonNode()));

    // EventLog write is best-effort — failures are logged, not propagated
    writeResolutionEventLog(instance, gate)
        .onFailure()
        .invoke(
            t ->
                LOG.warnf(
                    t,
                    "ACTION_GATE_EXPIRED EventLog write failed: caseId=%s gateId=%d"
                        + " — gate resolution still applied",
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
    log.setEventType(CaseHubEventType.ACTION_GATE_EXPIRED);
    return eventLogRepository.append(log, instance.tenancyId);
  }

  private static boolean isTerminal(final CaseStatus state) {
    return state == CaseStatus.COMPLETED
        || state == CaseStatus.FAULTED
        || state == CaseStatus.CANCELLED;
  }
}
