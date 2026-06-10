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
package io.casehub.engine.internal.engine.recovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.context.CaseContext;
import io.casehub.api.context.ContextPanel;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.utils.ReactiveUtils;
import io.casehub.engine.common.qualifier.CrossTenant;
import io.casehub.engine.common.spi.CrossTenantCaseInstanceRepository;
import io.casehub.engine.common.spi.CrossTenantEventLogRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.common.spi.recovery.WorkerExecutionRecoveryService;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.engine.internal.context.CaseContextImpl;
import io.casehub.engine.internal.context.EpisodicPanelUpdater;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Default implementation of {@link WorkerExecutionRecoveryService}.
 *
 * <p>Restores in-flight workers and case state after a restart. Uses the repository SPI — no direct
 * Hibernate session access.
 */
@ApplicationScoped
public class DefaultWorkerExecutionRecoveryService implements WorkerExecutionRecoveryService {

  private static final Logger LOG = Logger.getLogger(DefaultWorkerExecutionRecoveryService.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final EnumSet<CaseHubEventType> RELEVANT_RECOVERY_EVENTS =
      EnumSet.of(
          CaseHubEventType.WORKER_SCHEDULED,
          CaseHubEventType.WORKER_EXECUTION_STARTED,
          CaseHubEventType.WORKER_EXECUTION_COMPLETED,
          CaseHubEventType.WORKER_EXECUTION_FAILED,
          CaseHubEventType.MILESTONE_ACTIVATED,
          CaseHubEventType.MILESTONE_COMPLETED,
          CaseHubEventType.MILESTONE_SLA_VIOLATED);

  @Inject @CrossTenant CrossTenantCaseInstanceRepository caseInstanceRepository;

  @Inject @CrossTenant CrossTenantEventLogRepository eventLogRepository;

  @Inject Vertx vertx;

  @Inject CaseInstanceCache caseInstanceCache;

  @Inject WorkerExecutionManager workflowExecutionManager;

  @Override
  public Uni<CaseInstance> loadOrRestoreCaseInstance(UUID caseId) {
    CaseInstance cached = caseInstanceCache.get(caseId);
    if (cached != null) {
      return Uni.createFrom().item(cached);
    }

    return runOnSafeContext(() -> caseInstanceRepository.findByUuid(caseId))
        .onItem()
        .ifNull()
        .failWith(() -> new IllegalStateException("CaseInstance not found for caseId=" + caseId))
        .chain(
            instance ->
                rebuildStateContext(caseId)
                    .map(
                        stateContext -> {
                          instance.setCaseContext(stateContext);
                          caseInstanceCache.put(instance);
                          return instance;
                        }));
  }

  @Override
  public Uni<Void> recoverPendingScheduledWorkers() {
    return runOnSafeContext(() -> eventLogRepository.findByTypes(RELEVANT_RECOVERY_EVENTS))
        .chain(this::reschedulePendingEvents);
  }

  private Uni<Void> reschedulePendingEvents(List<EventLog> eventLogs) {
    Set<String> alreadyProgressed = new HashSet<>();
    for (EventLog eventLog : eventLogs) {
      if (eventLog.getEventType() != CaseHubEventType.WORKER_SCHEDULED) {
        String key = executionKey(eventLog);
        if (key != null) {
          alreadyProgressed.add(key);
        }
      }
    }

    List<Uni<Void>> recoveries =
        eventLogs.stream()
            .filter(
                eventLog -> {
                  if (eventLog.getEventType() != CaseHubEventType.WORKER_SCHEDULED) {
                    return false;
                  }
                  String key = executionKey(eventLog);
                  return key != null && !alreadyProgressed.contains(key);
                })
            .map(workflowExecutionManager::schedulePersistedEvent)
            .toList();

    if (recoveries.isEmpty()) {
      return Uni.createFrom().voidItem();
    }

    return Uni.combine().all().unis(recoveries).discardItems();
  }

  @SuppressWarnings("unchecked")
  private Uni<CaseContext> rebuildStateContext(UUID caseId) {
    return runOnSafeContext(
            () ->
                eventLogRepository.findByCaseAndTypes(
                    caseId,
                    EnumSet.of(
                        CaseHubEventType.CASE_STARTED,
                        CaseHubEventType.WORKER_EXECUTION_COMPLETED,
                        CaseHubEventType.SUBCASE_COMPLETED,
                        CaseHubEventType.SIGNAL_RECEIVED,
                        CaseHubEventType.MILESTONE_ACTIVATED,
                        CaseHubEventType.MILESTONE_COMPLETED,
                        CaseHubEventType.MILESTONE_SLA_VIOLATED)))
        .map(
            eventLogs -> {
              CaseContextImpl caseContext = new CaseContextImpl();
              EventLog caseStartedEvent =
                  eventLogs.stream()
                      .filter(e -> e.getEventType() == CaseHubEventType.CASE_STARTED)
                      .findFirst()
                      .orElse(null);

              if (caseStartedEvent != null) {
                // CASE_STARTED payload is now a panel document
                // {"working":{...},"semantic":{...},...}
                caseContext = CaseContextImpl.fromPanelDocument(caseStartedEvent.getPayload());
              }

              for (EventLog eventLog : eventLogs) {
                if (eventLog.getEventType() == CaseHubEventType.CASE_STARTED) {
                  continue;
                }
                if (eventLog.getEventType() == CaseHubEventType.SIGNAL_RECEIVED) {
                  JsonNode patch = payloadAsPatch(eventLog.getPayload());
                  if (patch != null) {
                    caseContext.applyDiff(patch);
                  }
                } else if (eventLog.getEventType() == CaseHubEventType.WORKER_EXECUTION_COMPLETED) {
                  JsonNode contextChanges = getContextChanges(eventLog.getMetadata());
                  if (contextChanges != null) {
                    if (contextChanges.isArray()) {
                      // JSON Patch format (JsonPatchContextDiffStrategy)
                      caseContext.applyDiff(contextChanges);
                    } else if (contextChanges.isObject()) {
                      // TopLevel format (TopLevelContextDiffStrategy)
                      applyTopLevelChanges(caseContext, contextChanges);
                    }
                  } else {
                    LOG.warnf(
                        "WORKER_EXECUTION_COMPLETED has no contextChanges metadata — "
                            + "falling back to payload merge for caseId=%s seq=%s",
                        caseId, eventLog.getSeq());
                    caseContext.setAll(payloadAsMap(eventLog.getPayload()));
                  }
                  // Update episodic panel
                  String workerId = eventLog.getWorkerId();
                  if (workerId != null) {
                    EpisodicPanelUpdater.recordWorkerCompletion(caseContext, workerId, "COMPLETED");
                  }
                } else if (eventLog.getEventType() == CaseHubEventType.SUBCASE_COMPLETED) {
                  caseContext.setAll(payloadAsMap(eventLog.getPayload()));
                } else if (eventLog.getEventType() == CaseHubEventType.MILESTONE_ACTIVATED) {
                  applyMilestoneActivatedEvent(caseContext, eventLog);
                } else if (eventLog.getEventType() == CaseHubEventType.MILESTONE_COMPLETED) {
                  applyMilestoneCompletedEvent(caseContext, eventLog);
                  // Update episodic panel
                  JsonNode payload = eventLog.getPayload();
                  if (payload != null) {
                    String milestoneName = payload.path("milestoneName").asText(null);
                    if (milestoneName != null) {
                      EpisodicPanelUpdater.recordMilestoneReached(caseContext, milestoneName);
                    }
                  }
                } else if (eventLog.getEventType() == CaseHubEventType.MILESTONE_SLA_VIOLATED) {
                  applyMilestoneSLAViolatedEvent(caseContext, eventLog);
                } else {
                  LOG.warnf(
                      "Unexpected event type in rebuildStateContext: %s", eventLog.getEventType());
                }
              }
              // Re-freeze semantic and episodic panels (they were frozen at case start)
              caseContext.freezePanel(ContextPanel.SEMANTIC);
              caseContext.freezePanel(ContextPanel.EPISODIC);
              return caseContext;
            });
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> payloadAsMap(JsonNode payload) {
    return OBJECT_MAPPER.convertValue(
        payload == null ? OBJECT_MAPPER.createObjectNode() : payload, Map.class);
  }

  private JsonNode payloadAsPatch(JsonNode payload) {
    if (payload == null || payload.isNull()) return null;
    JsonNode patch = payload.get("patch");
    return patch != null && patch.isArray() ? patch : null;
  }

  private JsonNode getContextChanges(JsonNode metadata) {
    if (metadata == null || metadata.isNull()) return null;
    JsonNode contextChanges = metadata.get("contextChanges");
    // Support both JSON Patch (array) and TopLevel (object) formats
    if (contextChanges != null && (contextChanges.isArray() || contextChanges.isObject())) {
      return contextChanges;
    }
    return null;
  }

  private String executionKey(EventLog eventLog) {
    JsonNode metadata = eventLog.getMetadata();
    if (metadata == null || eventLog.getCaseId() == null || eventLog.getWorkerId() == null) {
      return null;
    }
    JsonNode inputDataHash = metadata.get("inputDataHash");
    if (inputDataHash == null || inputDataHash.isNull()) return null;
    return eventLog.getCaseId() + "|" + eventLog.getWorkerId() + "|" + inputDataHash.asText();
  }

  private <T> Uni<T> runOnSafeContext(java.util.function.Supplier<Uni<? extends T>> supplier) {
    return ReactiveUtils.runOnSafeVertxContext(vertx, supplier);
  }

  private void applyMilestoneActivatedEvent(CaseContext caseContext, EventLog eventLog) {
    JsonNode payload = eventLog.getPayload();
    if (payload == null || payload.isNull()) {
      return;
    }
    String milestoneName = payload.path("milestoneName").asText(null);
    if (milestoneName == null) {
      return;
    }
    String prefix = "milestones." + milestoneName + ".";
    String currentLifecycleStatus = caseContext.getPathAsString(prefix + "lifecycleStatus");
    if (isTerminalMilestoneLifecycleStatus(currentLifecycleStatus)) {
      return;
    }
    caseContext.setPath(
        prefix + "lifecycleStatus", payload.path("lifecycleStatus").asText("ACTIVE"));
    caseContext.setPath(prefix + "slaStatus", payload.path("slaStatus").asText("ON_TRACK"));
    if (payload.has("activatedAt")) {
      caseContext.setPath(prefix + "activatedAt", payload.get("activatedAt").asText());
    }
    if (payload.has("slaDeadline")) {
      caseContext.setPath(prefix + "slaDeadline", payload.get("slaDeadline").asText());
    }
  }

  private void applyMilestoneCompletedEvent(CaseContext caseContext, EventLog eventLog) {
    JsonNode payload = eventLog.getPayload();
    if (payload == null || payload.isNull()) {
      return;
    }
    String milestoneName = payload.path("milestoneName").asText(null);
    if (milestoneName == null) {
      return;
    }
    String prefix = "milestones." + milestoneName + ".";
    caseContext.setPath(
        prefix + "lifecycleStatus", payload.path("lifecycleStatus").asText("COMPLETED"));
    caseContext.setPath(prefix + "slaStatus", payload.path("slaStatus").asText("ON_TRACK"));
    if (payload.has("completedAt")) {
      caseContext.setPath(prefix + "completedAt", payload.get("completedAt").asText());
    }
  }

  private void applyMilestoneSLAViolatedEvent(CaseContext caseContext, EventLog eventLog) {
    JsonNode payload = eventLog.getPayload();
    if (payload == null || payload.isNull()) {
      return;
    }
    String milestoneName = payload.path("milestoneName").asText(null);
    if (milestoneName == null) {
      return;
    }
    String prefix = "milestones." + milestoneName + ".";
    caseContext.setPath(prefix + "slaStatus", payload.path("slaStatus").asText("BREACHED"));
  }

  private boolean isTerminalMilestoneLifecycleStatus(String lifecycleStatus) {
    return "COMPLETED".equals(lifecycleStatus)
        || "FAILED".equals(lifecycleStatus)
        || "CANCELLED".equals(lifecycleStatus);
  }

  /**
   * Applies TopLevel format context changes: {"key": {"before": oldVal, "after": newVal}}
   *
   * <p>This format is produced by TopLevelContextDiffStrategy. Each field contains "before" and/or
   * "after" nodes. Missing "after" means removal.
   */
  private void applyTopLevelChanges(CaseContext caseContext, JsonNode changes) {
    // After the panels migration, top-level keys in the diff are panel names (working, semantic,
    // episodic). Each changeNode has "before"/"after" for the panel's full contents — not a
    // single flat key. We must update the named panel rather than setting the panel name as a key
    // inside the working panel (which is what CaseContext.set() would do via the flat API).
    CaseContextImpl ctxImpl =
        caseContext instanceof CaseContextImpl c ? c : null;

    changes
        .fieldNames()
        .forEachRemaining(
            key -> {
              JsonNode changeNode = changes.get(key);
              if (changeNode == null || !changeNode.isObject()) {
                return;
              }
              JsonNode afterNode = changeNode.get("after");
              if (afterNode == null || afterNode.isNull()) {
                // Removal — panel cleared; call remove on flat API (no-op for panels but safe)
                caseContext.remove(key);
              } else if (ctxImpl != null && afterNode.isObject()) {
                // Panel-level diff: afterNode is the panel's FULL new contents — replace, not merge.
                // clear() then setAll() ensures removed keys are not left behind.
                @SuppressWarnings("unchecked")
                Map<String, Object> afterMap = OBJECT_MAPPER.convertValue(afterNode, Map.class);
                ctxImpl.writablePanel(key).clear().setAll(afterMap);
              } else {
                // Flat scalar (rare/legacy): fall back to flat set
                Object value = OBJECT_MAPPER.convertValue(afterNode, Object.class);
                caseContext.set(key, value);
              }
            });
  }
}
