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

import static io.casehub.engine.common.internal.event.EventBusAddresses.CONTEXT_CHANGED;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.context.ContextPanel;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.event.BulkSignalReceivedEvent;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.SignalReceivedEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.ReactiveEventLogRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.engine.common.spi.recovery.WorkerExecutionRecoveryService;
import io.casehub.ledger.api.spi.LedgerTraceIdProvider;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.eventbus.EventBus;
import io.vertx.mutiny.core.shareddata.Lock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Applies an external signal to the case context, persists the event, and notifies listeners that
 * the context has changed.
 */
@ApplicationScoped
public class SignalReceivedEventHandler {

  private static final Logger LOG = Logger.getLogger(SignalReceivedEventHandler.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final Vertx vertx;
  private final EventBus eventBus;
  private final CaseInstanceCache caseInstanceCache;
  private final WorkerExecutionRecoveryService recoveryService;
  private final ReactiveEventLogRepository reactiveEventLogRepository;
  private final Event<CaseLifecycleEvent> lifecycleEvents;
  private final LedgerTraceIdProvider traceIdProvider;

  @Inject
  SignalReceivedEventHandler(
      Vertx vertx,
      EventBus eventBus,
      CaseInstanceCache caseInstanceCache,
      WorkerExecutionRecoveryService recoveryService,
      ReactiveEventLogRepository reactiveEventLogRepository,
      Event<CaseLifecycleEvent> lifecycleEvents,
      LedgerTraceIdProvider traceIdProvider) {
    this.vertx = vertx;
    this.eventBus = eventBus;
    this.caseInstanceCache = caseInstanceCache;
    this.recoveryService = recoveryService;
    this.reactiveEventLogRepository = reactiveEventLogRepository;
    this.lifecycleEvents = lifecycleEvents;
    this.traceIdProvider = traceIdProvider;
  }

  private static final int MAX_STARTING_RETRIES = 30;
  private static final long STARTING_RETRY_DELAY_MS = 100L;

  @ConsumeEvent(value = EventBusAddresses.SIGNAL_RECEIVED)
  public Uni<Void> onSignalReceived(SignalReceivedEvent event) {
    return onSignalReceivedWithRetry(event, 0);
  }

  @ConsumeEvent(value = EventBusAddresses.BULK_SIGNAL_RECEIVED)
  public Uni<Void> onBulkSignalReceived(BulkSignalReceivedEvent event) {
    return onBulkSignalReceivedWithRetry(event, 0);
  }

  private Uni<Void> onSignalReceivedWithRetry(SignalReceivedEvent event, int attempt) {
    CaseInstance cached = caseInstanceCache.get(event.caseId());
    if (cached == null) {
      LOG.warnf("CaseInstance not found in cache for caseId=%s, trying recovery", event.caseId());
      return recoveryService
          .loadOrRestoreCaseInstance(event.caseId())
          .chain(instance -> applySignal(instance, event));
    }
    CaseStatus state = cached.getState();
    if (state == CaseStatus.RUNNING || state == CaseStatus.WAITING) {
      return applySignal(cached, event);
    }
    if (state != CaseStatus.STARTING) {
      LOG.warnf(
          "Ignoring signal path='%s' for caseId=%s — case is %s",
          event.path(), event.caseId(), state);
      return Uni.createFrom().voidItem();
    }
    if (attempt >= MAX_STARTING_RETRIES) {
      LOG.warnf(
          "CaseInstance caseId=%s still STARTING after %d retries, proceeding anyway",
          event.caseId(), MAX_STARTING_RETRIES);
      return applySignal(cached, event);
    }
    LOG.debugf(
        "CaseInstance caseId=%s is still STARTING (attempt %d/%d), retrying in %dms",
        event.caseId(), attempt + 1, MAX_STARTING_RETRIES, STARTING_RETRY_DELAY_MS);
    return Uni.createFrom()
        .emitter(em -> vertx.setTimer(STARTING_RETRY_DELAY_MS, id -> em.complete(null)))
        .chain(() -> onSignalReceivedWithRetry(event, attempt + 1));
  }

  private Uni<Void> applySignal(CaseInstance instance, SignalReceivedEvent event) {
    // Capture traceId synchronously before the async chain — the OTel ThreadLocal is intact here.
    final String traceId = traceIdProvider.currentTraceId().orElse(null);
    String lockKey = "signal:" + instance.getUuid() + ":" + event.path();
    return vertx
        .sharedData()
        .getLocalLock(lockKey)
        .chain(lock -> applySignalUnderLock(instance, event, lock, traceId));
  }

  private Uni<Void> applySignalUnderLock(
      CaseInstance instance, SignalReceivedEvent event, Lock lock, String traceId) {
    Optional<JsonNode> maybeDiff;
    try {
      maybeDiff = instance.getCaseContext().applyAndDiff(event.path(), event.value());
    } finally {
      // Release immediately: the lock only needs to protect the in-memory context update.
      // The subsequent DB transaction does not need to be serialized here.
      lock.release();
    }

    if (maybeDiff.isEmpty()) {
      LOG.debugf(
          "Signal path='%s' produced no state change for caseId=%s — skipping",
          event.path(), event.caseId());
      return Uni.createFrom().voidItem();
    }

    JsonNode diff = maybeDiff.get();
    EventLog eventLog = buildSignalEventLog(instance, diff);

    return reactiveEventLogRepository
        .append(eventLog, instance.tenancyId)
        .invoke(
            () -> {
              lifecycleEvents
                  .fireAsync(
                      new CaseLifecycleEvent(
                          instance.getUuid(),
                          instance.tenancyId,
                          "SignalCase",
                          "SignalReceived",
                          instance.getState().name(),
                          null,
                          "System",
                          traceId))
                  .whenComplete(
                      (v, t) -> {
                        if (t != null)
                          LOG.warnf(
                              t,
                              "CaseLifecycleEvent observer failed for caseId=%s event=SignalReceived",
                              instance.getUuid());
                      });
            })
        .invoke(
            () ->
                eventBus.publish(
                    CONTEXT_CHANGED,
                    new CaseContextChangedEvent(
                        instance,
                        instance.getCaseContext().snapshot(),
                        ContextPanel.WORKING,
                        event.triggerChannelId(),
                        event.triggerCorrelationId())))
        .replaceWithVoid()
        .onFailure()
        .invoke(
            t ->
                LOG.errorf(
                    t,
                    "Failed to process signal path='%s' for caseId=%s",
                    event.path(),
                    event.caseId()));
  }

  private EventLog buildSignalEventLog(CaseInstance instance, JsonNode diff) {
    EventLog eventLog = new EventLog();
    eventLog.setCaseId(instance.getUuid());
    eventLog.setEventType(CaseHubEventType.SIGNAL_RECEIVED);
    eventLog.setStreamType(EventStreamType.CASE);
    eventLog.setTimestamp(Instant.now());
    eventLog.setPayload(OBJECT_MAPPER.createObjectNode().set("patch", diff.deepCopy()));
    return eventLog;
  }

  private Uni<Void> onBulkSignalReceivedWithRetry(BulkSignalReceivedEvent event, int attempt) {
    CaseInstance cached = caseInstanceCache.get(event.caseId());
    if (cached == null) {
      LOG.warnf("CaseInstance not found in cache for caseId=%s, trying recovery", event.caseId());
      return recoveryService
          .loadOrRestoreCaseInstance(event.caseId())
          .chain(instance -> applyBulkSignal(instance, event));
    }
    CaseStatus state = cached.getState();
    if (state == CaseStatus.RUNNING || state == CaseStatus.WAITING) {
      return applyBulkSignal(cached, event);
    }
    if (state != CaseStatus.STARTING) {
      LOG.warnf("Ignoring bulk signal for caseId=%s — case is %s", event.caseId(), state);
      return Uni.createFrom().voidItem();
    }
    if (attempt >= MAX_STARTING_RETRIES) {
      LOG.warnf(
          "CaseInstance caseId=%s still STARTING after %d retries, proceeding anyway",
          event.caseId(), MAX_STARTING_RETRIES);
      return applyBulkSignal(cached, event);
    }
    LOG.debugf(
        "CaseInstance caseId=%s is still STARTING (attempt %d/%d), retrying in %dms",
        event.caseId(), attempt + 1, MAX_STARTING_RETRIES, STARTING_RETRY_DELAY_MS);
    return Uni.createFrom()
        .emitter(em -> vertx.setTimer(STARTING_RETRY_DELAY_MS, id -> em.complete(null)))
        .chain(() -> onBulkSignalReceivedWithRetry(event, attempt + 1));
  }

  private Uni<Void> applyBulkSignal(CaseInstance instance, BulkSignalReceivedEvent event) {
    final String traceId = traceIdProvider.currentTraceId().orElse(null);
    String lockKey = "signal:bulk:" + instance.getUuid();
    return vertx
        .sharedData()
        .getLocalLock(lockKey)
        .chain(lock -> applyBulkSignalUnderLock(instance, event, lock, traceId));
  }

  private Uni<Void> applyBulkSignalUnderLock(
      CaseInstance instance, BulkSignalReceivedEvent event, Lock lock, String traceId) {
    try {
      instance.getCaseContext().setAll(event.updates());
    } finally {
      lock.release();
    }

    EventLog eventLog = buildBulkSignalEventLog(instance, event.updates());

    return reactiveEventLogRepository
        .append(eventLog, instance.tenancyId)
        .invoke(
            () -> {
              lifecycleEvents
                  .fireAsync(
                      new CaseLifecycleEvent(
                          instance.getUuid(),
                          instance.tenancyId,
                          "SignalCase",
                          "BulkSignalReceived",
                          instance.getState().name(),
                          null,
                          "System",
                          traceId))
                  .whenComplete(
                      (v, t) -> {
                        if (t != null)
                          LOG.warnf(
                              t,
                              "CaseLifecycleEvent observer failed for caseId=%s event=BulkSignalReceived",
                              instance.getUuid());
                      });
            })
        .invoke(
            () ->
                eventBus.publish(
                    CONTEXT_CHANGED,
                    new CaseContextChangedEvent(
                        instance,
                        instance.getCaseContext().snapshot(),
                        ContextPanel.WORKING,
                        event.triggerChannelId(),
                        event.triggerCorrelationId(),
                        event.signalId())))
        .replaceWithVoid()
        .onFailure()
        .invoke(t -> LOG.errorf(t, "Failed to process bulk signal for caseId=%s", event.caseId()));
  }

  private EventLog buildBulkSignalEventLog(
      CaseInstance instance, java.util.Map<String, Object> updates) {
    EventLog eventLog = new EventLog();
    eventLog.setCaseId(instance.getUuid());
    eventLog.setEventType(CaseHubEventType.SIGNAL_RECEIVED);
    eventLog.setStreamType(EventStreamType.CASE);
    eventLog.setTimestamp(Instant.now());
    ObjectNode payload = OBJECT_MAPPER.createObjectNode();
    payload.put("type", "bulk_signal");
    payload.set("updates", OBJECT_MAPPER.valueToTree(updates));
    eventLog.setPayload(payload);
    eventLog.setMetadata(
        OBJECT_MAPPER
            .createObjectNode()
            .set("updatedKeys", OBJECT_MAPPER.valueToTree(updates.keySet())));
    return eventLog;
  }
}
