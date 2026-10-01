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
import io.casehub.api.context.ContextLayer;
import io.casehub.api.context.MutableCaseContext;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CrossTenantEventLogRepository;
import io.casehub.engine.common.spi.recovery.CaseContextRecoveryStrategy;
import io.casehub.engine.internal.context.CaseContextImpl;
import io.casehub.engine.internal.context.EpisodicLayerUpdater;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Recovers CaseContext by replaying events from the event log. Experimental — known gaps exist
 * (#1151, #1152, #1154). Use {@link SnapshotRecoveryStrategy} (default) for production.
 *
 * <p>Extracted from {@code DefaultWorkerExecutionRecoveryService.rebuildStateContext()}.
 */
@Typed(EventLogReplayRecoveryStrategy.class)
@ApplicationScoped
public class EventLogReplayRecoveryStrategy implements CaseContextRecoveryStrategy {

  private static final Logger LOG = Logger.getLogger(EventLogReplayRecoveryStrategy.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  static final EnumSet<CaseHubEventType> REPLAYED_TYPES =
      EnumSet.of(
          CaseHubEventType.CASE_STARTED,
          CaseHubEventType.WORKER_EXECUTION_COMPLETED,
          CaseHubEventType.SUBCASE_COMPLETED,
          CaseHubEventType.SIGNAL_RECEIVED,
          CaseHubEventType.SCOPED_WORKER_OUTPUT,
          CaseHubEventType.CONTEXT_SIGNAL_APPLIED,
          CaseHubEventType.MILESTONE_ACTIVATED,
          CaseHubEventType.MILESTONE_COMPLETED,
          CaseHubEventType.MILESTONE_SLA_VIOLATED,
          CaseHubEventType.GOAL_REACHED);

  private final CrossTenantEventLogRepository eventLogRepository;

  public EventLogReplayRecoveryStrategy(
      @io.casehub.engine.common.qualifier.CrossTenant
          CrossTenantEventLogRepository eventLogRepository) {
    this.eventLogRepository = eventLogRepository;
  }

  @Override
  public CaseContext recover(CaseInstance instance) {
    return rebuildStateContext(instance.getUuid());
  }

  @Override
  public void onContextChanged(CaseInstance instance, CaseContext context) {
    // No-op — events are already appended by the mutation path
  }

  @SuppressWarnings("unchecked")
  private CaseContext rebuildStateContext(UUID caseId) {
    List<EventLog> eventLogs = eventLogRepository.findByCaseAndTypes(caseId, REPLAYED_TYPES);

    CaseContextImpl caseContext = new CaseContextImpl();
    EventLog caseStartedEvent =
        eventLogs.stream()
            .filter(e -> e.getEventType() == CaseHubEventType.CASE_STARTED)
            .findFirst()
            .orElse(null);

    if (caseStartedEvent != null) {
      caseContext = CaseContextImpl.fromLayerDocument(caseStartedEvent.getPayload());
    }

    EpisodicLayerUpdater.initBaseline(caseContext);

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
            caseContext.applyDiff(contextChanges);
          } else if (contextChanges.isObject()) {
            applyTopLevelChanges(caseContext, contextChanges);
          }
        } else {
          LOG.warnf(
              "WORKER_EXECUTION_COMPLETED has no contextChanges metadata — "
                  + "falling back to payload merge for caseId=%s seq=%s",
              caseId, eventLog.getSeq());
          caseContext.setAll(payloadAsMap(eventLog.getPayload()));
        }
        String workerId = eventLog.getWorkerId();
        if (workerId != null) {
          EpisodicLayerUpdater.recordWorkerCompletion(caseContext, workerId, "COMPLETED");
        }
      } else if (eventLog.getEventType() == CaseHubEventType.SUBCASE_COMPLETED) {
        caseContext.setAll(payloadAsMap(eventLog.getPayload()));
      } else if (eventLog.getEventType() == CaseHubEventType.SCOPED_WORKER_OUTPUT
          || eventLog.getEventType() == CaseHubEventType.CONTEXT_SIGNAL_APPLIED) {
        JsonNode contextChanges = getContextChanges(eventLog.getMetadata());
        if (contextChanges != null) {
          if (contextChanges.isArray()) {
            caseContext.applyDiff(contextChanges);
          } else if (contextChanges.isObject()) {
            applyTopLevelChanges(caseContext, contextChanges);
          }
        }
      } else if (eventLog.getEventType() == CaseHubEventType.MILESTONE_ACTIVATED) {
        applyMilestoneActivatedEvent(caseContext, eventLog);
      } else if (eventLog.getEventType() == CaseHubEventType.MILESTONE_COMPLETED) {
        applyMilestoneCompletedEvent(caseContext, eventLog);
        JsonNode payload = eventLog.getPayload();
        if (payload != null) {
          String milestoneName = payload.path("milestoneName").asText(null);
          if (milestoneName != null) {
            EpisodicLayerUpdater.recordMilestoneReached(caseContext, milestoneName);
          }
        }
      } else if (eventLog.getEventType() == CaseHubEventType.MILESTONE_SLA_VIOLATED) {
        applyMilestoneSLAViolatedEvent(caseContext, eventLog);
      } else if (eventLog.getEventType() == CaseHubEventType.GOAL_REACHED) {
        JsonNode metadata = eventLog.getMetadata();
        if (metadata != null) {
          String goalName = metadata.path("name").asText(null);
          if (goalName != null) {
            EpisodicLayerUpdater.recordGoalReached(caseContext, goalName);
          }
        }
      } else {
        LOG.warnf("Unexpected event type in rebuildStateContext: %s", eventLog.getEventType());
      }
    }
    caseContext.freezeLayer(ContextLayer.SEMANTIC);
    caseContext.freezeLayer(ContextLayer.EPISODIC);
    return caseContext;
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
    if (contextChanges != null && (contextChanges.isArray() || contextChanges.isObject())) {
      return contextChanges;
    }
    return null;
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

  private void applyTopLevelChanges(CaseContext caseContext, JsonNode changes) {
    MutableCaseContext mctx = caseContext instanceof MutableCaseContext m ? m : null;

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
                caseContext.remove(key);
              } else if (mctx != null && afterNode.isObject()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> afterMap = OBJECT_MAPPER.convertValue(afterNode, Map.class);
                mctx.writableLayer(key).clear().setAll(afterMap);
              } else {
                Object value = OBJECT_MAPPER.convertValue(afterNode, Object.class);
                caseContext.set(key, value);
              }
            });
  }
}
