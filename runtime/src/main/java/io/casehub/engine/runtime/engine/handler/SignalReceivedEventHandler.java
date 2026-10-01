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
import io.casehub.api.context.ContextLayer;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.event.BulkSignalReceivedEvent;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.SignalReceivedEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.engine.common.spi.recovery.WorkerExecutionRecoveryService;
import io.casehub.engine.internal.engine.QuiescenceTracker;
import io.casehub.ledger.api.spi.LedgerTraceIdProvider;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.jboss.logging.Logger;

/**
 * Applies an external signal to the case context, persists the event, and notifies listeners that
 * the context has changed.
 */
@ApplicationScoped
public class SignalReceivedEventHandler {

  private static final Logger LOG = Logger.getLogger(SignalReceivedEventHandler.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final int MAX_STARTING_RETRIES = 30;
  private static final long STARTING_RETRY_DELAY_MS = 100L;
  private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
  private final EventBus eventBus;
  private final CaseInstanceCache caseInstanceCache;
  private final WorkerExecutionRecoveryService recoveryService;
  private final EventLogRepository eventLogRepository;
  private final Event<CaseLifecycleEvent> lifecycleEvents;
  private final LedgerTraceIdProvider traceIdProvider;
  private final io.casehub.engine.common.internal.context.BridgeResolver bridgeResolver;
  private final QuiescenceTracker quiescenceTracker;

  @Inject
  SignalReceivedEventHandler(
      EventBus eventBus,
      CaseInstanceCache caseInstanceCache,
      WorkerExecutionRecoveryService recoveryService,
      EventLogRepository eventLogRepository,
      Event<CaseLifecycleEvent> lifecycleEvents,
      LedgerTraceIdProvider traceIdProvider,
      io.casehub.engine.common.internal.context.BridgeResolver bridgeResolver,
      QuiescenceTracker quiescenceTracker) {
    this.eventBus = eventBus;
    this.caseInstanceCache = caseInstanceCache;
    this.recoveryService = recoveryService;
    this.eventLogRepository = eventLogRepository;
    this.lifecycleEvents = lifecycleEvents;
    this.traceIdProvider = traceIdProvider;
    this.bridgeResolver = bridgeResolver;
    this.quiescenceTracker = quiescenceTracker;
  }

  public void onSignalReceived(SignalReceivedEvent event) {
    try {
      CaseInstance instance = resolveInstanceWithRetry(event.caseId(), event.path());
      if (instance == null) {
        return;
      }
      applySignal(instance, event);
    } catch (Exception e) {
      LOG.errorf(
          e, "Failed to process signal path='%s' for caseId=%s", event.path(), event.caseId());
    }
  }

  public void onBulkSignalReceived(BulkSignalReceivedEvent event) {
    try {
      CaseInstance instance = resolveInstanceWithRetry(event.caseId(), "bulk");
      if (instance == null) {
        return;
      }
      applyBulkSignal(instance, event);
    } catch (Exception e) {
      LOG.errorf(e, "Failed to process bulk signal for caseId=%s", event.caseId());
    }
  }

  public void onTypedSignalReceived(
      io.casehub.engine.common.internal.event.TypedSignalReceivedEvent event) {
    try {
      CaseInstance instance =
          resolveInstanceWithRetry(event.caseId(), "typed:" + event.signalName());
      if (instance == null) {
        return;
      }
      applyTypedSignal(instance, event);
    } catch (Exception e) {
      LOG.errorf(
          e,
          "Failed to process typed signal '%s' for caseId=%s",
          event.signalName(),
          event.caseId());
    }
  }

  private CaseInstance resolveInstanceWithRetry(java.util.UUID caseId, String label) {
    CaseInstance cached = caseInstanceCache.get(caseId);
    if (cached == null) {
      LOG.warnf("CaseInstance not found in cache for caseId=%s, trying recovery", caseId);
      return recoveryService.loadOrRestoreCaseInstance(caseId);
    }
    CaseStatus state = cached.getState();
    if (state == CaseStatus.RUNNING || state == CaseStatus.WAITING) {
      return cached;
    }
    if (state != CaseStatus.STARTING) {
      LOG.warnf("Ignoring signal '%s' for caseId=%s — case is %s", label, caseId, state);
      return null;
    }
    // Retry loop for STARTING state
    for (int attempt = 0; attempt < MAX_STARTING_RETRIES; attempt++) {
      LOG.debugf(
          "CaseInstance caseId=%s is still STARTING (attempt %d/%d), retrying in %dms",
          caseId, attempt + 1, MAX_STARTING_RETRIES, STARTING_RETRY_DELAY_MS);
      try {
        Thread.sleep(STARTING_RETRY_DELAY_MS);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        return cached;
      }
      cached = caseInstanceCache.get(caseId);
      if (cached == null) {
        return recoveryService.loadOrRestoreCaseInstance(caseId);
      }
      state = cached.getState();
      if (state == CaseStatus.RUNNING || state == CaseStatus.WAITING) {
        return cached;
      }
      if (state != CaseStatus.STARTING) {
        LOG.warnf("Ignoring signal '%s' for caseId=%s — case is %s", label, caseId, state);
        return null;
      }
    }
    LOG.warnf(
        "CaseInstance caseId=%s still STARTING after %d retries, proceeding anyway",
        caseId, MAX_STARTING_RETRIES);
    return cached;
  }

  private void applyTypedSignal(
      CaseInstance instance,
      io.casehub.engine.common.internal.event.TypedSignalReceivedEvent event) {
    final String traceId = traceIdProvider.currentTraceId().orElse(null);
    String lockKey = "signal:" + instance.getUuid() + ":signals." + event.signalName();
    ReentrantLock lock = locks.computeIfAbsent(lockKey, k -> new ReentrantLock());
    lock.lock();
    try {
      applyTypedSignalUnderLock(instance, event, traceId);
    } finally {
      lock.unlock();
    }
  }

  private void applyTypedSignalUnderLock(
      CaseInstance instance,
      io.casehub.engine.common.internal.event.TypedSignalReceivedEvent event,
      String traceId) {
    String signalPath = "signals." + event.signalName();
    Optional<JsonNode> maybeDiff =
        instance.getCaseContext().applyAndDiff(signalPath, event.payload());

    if (maybeDiff.isEmpty()) {
      LOG.debugf(
          "Typed signal '%s' produced no state change for caseId=%s — skipping",
          event.signalName(), event.caseId());
      return;
    }

    JsonNode diff = maybeDiff.get();
    EventLog eventLog = buildTypedSignalEventLog(instance, diff, event);

    eventLogRepository.append(eventLog, instance.tenancyId);

    lifecycleEvents
        .fireAsync(
            CaseLifecycleEvent.of(
                instance, "SignalCase", "TypedSignalReceived", null, "System", traceId))
        .whenComplete(
            (v, t) -> {
              if (t != null) {
                LOG.warnf(
                    t,
                    "CaseLifecycleEvent observer failed for caseId=%s event=TypedSignalReceived",
                    instance.getUuid());
              }
            });

    quiescenceTracker.onContextChangePublished(instance.getUuid());
    eventBus.publish(
        CONTEXT_CHANGED,
        new CaseContextChangedEvent(
            instance, instance.getCaseContext().snapshot(), ContextLayer.WORKING, null, null));
  }

  private EventLog buildTypedSignalEventLog(
      CaseInstance instance,
      JsonNode diff,
      io.casehub.engine.common.internal.event.TypedSignalReceivedEvent event) {
    EventLog eventLog = new EventLog();
    eventLog.setCaseId(instance.getUuid());
    eventLog.setEventType(CaseHubEventType.SIGNAL_RECEIVED);
    eventLog.setStreamType(EventStreamType.CASE);
    eventLog.setTimestamp(Instant.now());
    eventLog.setPayload(OBJECT_MAPPER.createObjectNode().set("patch", diff.deepCopy()));

    ObjectNode metadata = OBJECT_MAPPER.createObjectNode();
    metadata.put("origin", io.casehub.api.model.event.ExecutionOrigin.SIGNAL.name());
    metadata.put("signalTypeName", event.signalName());
    metadata.put("payloadType", event.payloadTypeName());

    io.casehub.api.context.ContextBridge<?> bridge =
        bridgeResolver.resolveByType(event.payloadType());
    try {
      JsonNode serialisedPayload = bridgeResolver.serialise(bridge, event.payload());
      metadata.set("typedPayload", serialisedPayload);
    } catch (Exception e) {
      LOG.warnf(
          e, "Failed to serialise typed signal payload for EventLog — audit data unavailable");
    }

    eventLog.setMetadata(metadata);
    return eventLog;
  }

  private void applySignal(CaseInstance instance, SignalReceivedEvent event) {
    final String traceId = traceIdProvider.currentTraceId().orElse(null);
    String lockKey = "signal:" + instance.getUuid() + ":" + event.path();
    ReentrantLock lock = locks.computeIfAbsent(lockKey, k -> new ReentrantLock());
    lock.lock();
    try {
      applySignalUnderLock(instance, event, traceId);
    } finally {
      lock.unlock();
    }
  }

  private void applySignalUnderLock(
      CaseInstance instance, SignalReceivedEvent event, String traceId) {
    Optional<JsonNode> maybeDiff =
        instance.getCaseContext().applyAndDiff(event.path(), event.value());

    if (maybeDiff.isEmpty()) {
      LOG.debugf(
          "Signal path='%s' produced no state change for caseId=%s — skipping",
          event.path(), event.caseId());
      return;
    }

    JsonNode diff = maybeDiff.get();
    EventLog eventLog = buildSignalEventLog(instance, diff, event.signalMetadata());

    eventLogRepository.append(eventLog, instance.tenancyId);

    lifecycleEvents
        .fireAsync(
            CaseLifecycleEvent.of(
                instance, "SignalCase", "SignalReceived", null, "System", traceId))
        .whenComplete(
            (v, t) -> {
              if (t != null) {
                LOG.warnf(
                    t,
                    "CaseLifecycleEvent observer failed for caseId=%s event=SignalReceived",
                    instance.getUuid());
              }
            });

    quiescenceTracker.onContextChangePublished(instance.getUuid());
    eventBus.publish(
        CONTEXT_CHANGED,
        new CaseContextChangedEvent(
            instance,
            instance.getCaseContext().snapshot(),
            ContextLayer.WORKING,
            event.triggerChannelId(),
            event.triggerCorrelationId()));
  }

  private EventLog buildSignalEventLog(
      CaseInstance instance, JsonNode diff, java.util.Map<String, Object> signalMetadata) {
    EventLog eventLog = new EventLog();
    eventLog.setCaseId(instance.getUuid());
    eventLog.setEventType(CaseHubEventType.SIGNAL_RECEIVED);
    eventLog.setStreamType(EventStreamType.CASE);
    eventLog.setTimestamp(Instant.now());
    eventLog.setPayload(OBJECT_MAPPER.createObjectNode().set("patch", diff.deepCopy()));
    ObjectNode metadataNode = OBJECT_MAPPER.createObjectNode();
    metadataNode.put("origin", io.casehub.api.model.event.ExecutionOrigin.SIGNAL.name());
    if (signalMetadata != null) {
      OBJECT_MAPPER
          .valueToTree(signalMetadata)
          .fields()
          .forEachRemaining(e -> metadataNode.set(e.getKey(), e.getValue()));
    }
    eventLog.setMetadata(metadataNode);
    return eventLog;
  }

  private void applyBulkSignal(CaseInstance instance, BulkSignalReceivedEvent event) {
    final String traceId = traceIdProvider.currentTraceId().orElse(null);
    String lockKey = "signal:bulk:" + instance.getUuid();
    ReentrantLock lock = locks.computeIfAbsent(lockKey, k -> new ReentrantLock());
    lock.lock();
    try {
      applyBulkSignalUnderLock(instance, event, traceId);
    } finally {
      lock.unlock();
    }
  }

  private void applyBulkSignalUnderLock(
      CaseInstance instance, BulkSignalReceivedEvent event, String traceId) {
    instance.getCaseContext().setAll(event.updates());

    EventLog eventLog = buildBulkSignalEventLog(instance, event.updates());

    eventLogRepository.append(eventLog, instance.tenancyId);

    lifecycleEvents
        .fireAsync(
            CaseLifecycleEvent.of(
                instance, "SignalCase", "BulkSignalReceived", null, "System", traceId))
        .whenComplete(
            (v, t) -> {
              if (t != null) {
                LOG.warnf(
                    t,
                    "CaseLifecycleEvent observer failed for caseId=%s event=BulkSignalReceived",
                    instance.getUuid());
              }
            });

    quiescenceTracker.onContextChangePublished(instance.getUuid());
    eventBus.publish(
        CONTEXT_CHANGED,
        new CaseContextChangedEvent(
            instance,
            instance.getCaseContext().snapshot(),
            ContextLayer.WORKING,
            event.triggerChannelId(),
            event.triggerCorrelationId(),
            event.signalId()));
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
