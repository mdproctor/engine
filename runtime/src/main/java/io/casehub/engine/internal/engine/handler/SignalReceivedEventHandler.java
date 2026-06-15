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
import io.casehub.api.context.ContextPanel;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.SignalReceivedEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.EventLogRepository;
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

  @Inject Vertx vertx;

  @Inject EventBus eventBus;

  @Inject CaseInstanceCache caseInstanceCache;

  @Inject WorkerExecutionRecoveryService recoveryService;

  @Inject EventLogRepository eventLogRepository;

  @Inject Event<CaseLifecycleEvent> lifecycleEvents;

  @Inject LedgerTraceIdProvider traceIdProvider;

  @ConsumeEvent(value = EventBusAddresses.SIGNAL_RECEIVED)
  public Uni<Void> onSignalReceived(SignalReceivedEvent event) {
    CaseInstance cached = caseInstanceCache.get(event.caseId());
    if (cached != null) {
      return applySignal(cached, event);
    }
    LOG.warnf("CaseInstance not found in cache for caseId=%s, trying recovery", event.caseId());
    return recoveryService
        .loadOrRestoreCaseInstance(event.caseId())
        .chain(instance -> applySignal(instance, event));
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

    return eventLogRepository
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
}
