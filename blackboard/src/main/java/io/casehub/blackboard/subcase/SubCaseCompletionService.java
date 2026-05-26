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
package io.casehub.blackboard.subcase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.OnThresholdReached;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.blackboard.event.BlackboardEventBusAddresses;
import io.casehub.blackboard.event.SubCaseExecutionCompleted;
import io.casehub.blackboard.registry.BlackboardRegistry;
import io.casehub.engine.common.internal.event.CaseLifecycleEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.jq.ValidationResult;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.GroupStatus;
import io.casehub.engine.common.internal.model.PlanItemStatus;
import io.casehub.engine.common.internal.model.SubCaseGroup;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.SubCaseGroupRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.internal.work.CaseResumptionService;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Business logic for sub-case completion handling. When a terminal {@link CaseLifecycleEvent}
 * arrives and the terminating case is a child (its UUID appears in a parent's SUBCASE_STARTED
 * EventLog entry), updates the parent context and resumes the parent if it was WAITING.
 *
 * <p>Grouped path: delegates to {@link SubCaseGroupRepository} and {@link SubCaseGroupPolicy} to
 * track threshold progress. Resumes parent only when the group reaches COMPLETED threshold.
 *
 * <p>After resuming the parent, publishes {@link SubCaseExecutionCompleted} on the event bus so
 * {@link io.casehub.blackboard.handler.PlanItemCompletionHandler} can mark the SubCase PlanItem
 * COMPLETED and evaluate stage autocomplete.
 *
 * <p>See casehubio/engine#112, engine#252, engine#322.
 */
@ApplicationScoped
public class SubCaseCompletionService {

  private static final Logger LOG = Logger.getLogger(SubCaseCompletionService.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final EventLogRepository eventLogRepository;
  private final JQEvaluator jqEvaluator;
  private final CaseInstanceCache caseInstanceCache;
  private final CaseResumptionService caseResumptionService;
  private final SubCaseGroupRepository subCaseGroupRepository;
  private final CaseHubRuntime caseHubRuntime;
  private final EventBus eventBus;
  private final BlackboardRegistry registry;

  @Inject
  public SubCaseCompletionService(
      EventLogRepository eventLogRepository,
      JQEvaluator jqEvaluator,
      CaseInstanceCache caseInstanceCache,
      CaseResumptionService caseResumptionService,
      SubCaseGroupRepository subCaseGroupRepository,
      CaseHubRuntime caseHubRuntime,
      EventBus eventBus,
      BlackboardRegistry registry) {
    this.eventLogRepository = eventLogRepository;
    this.jqEvaluator = jqEvaluator;
    this.caseInstanceCache = caseInstanceCache;
    this.caseResumptionService = caseResumptionService;
    this.subCaseGroupRepository = subCaseGroupRepository;
    this.caseHubRuntime = caseHubRuntime;
    this.eventBus = eventBus;
    this.registry = registry;
  }

  public void handleCompletion(CaseLifecycleEvent event) {
    if (!isTerminal(event.commandType())) return;

    UUID childCaseId = event.caseId();

    EventLog startedEntry =
        eventLogRepository
            .findByWorkerAndType(childCaseId.toString(), CaseHubEventType.SUBCASE_STARTED)
            .await()
            .atMost(Duration.ofSeconds(10))
            .stream()
            .findFirst()
            .orElse(null);

    if (startedEntry == null) return;

    String groupId =
        startedEntry.getMetadata().has("groupId")
            ? startedEntry.getMetadata().get("groupId").asText(null)
            : null;

    if (groupId != null) {
      handleGroupedCompletion(childCaseId, event, startedEntry, groupId);
    } else {
      handleUngroupedCompletion(childCaseId, event, startedEntry);
    }
  }

  private void handleGroupedCompletion(
      UUID childCaseId, CaseLifecycleEvent event, EventLog startedEntry, String groupId) {
    UUID parentCaseId = startedEntry.getCaseId();
    CaseStatus childStatus =
        event.caseStatus() != null ? CaseStatus.valueOf(event.caseStatus()) : CaseStatus.FAULTED;
    boolean childCompleted = childStatus == CaseStatus.COMPLETED;

    SubCaseGroup group;
    if (childCompleted) {
      group =
          subCaseGroupRepository
              .incrementCompleted(parentCaseId, groupId)
              .await()
              .atMost(Duration.ofSeconds(10));
    } else {
      group =
          subCaseGroupRepository
              .incrementRejected(parentCaseId, groupId)
              .await()
              .atMost(Duration.ofSeconds(10));
    }

    GroupStatus groupStatus = SubCaseGroupPolicy.evaluate(group);
    if (groupStatus == null) return; // policyTriggered — already handled

    LOG.infof(
        "SubCaseGroup event: parentCaseId=%s groupId=%s status=%s completed=%d/%d",
        parentCaseId, groupId, groupStatus, group.getCompletedCount(), group.getRequiredCount());

    if (groupStatus == GroupStatus.COMPLETED || groupStatus == GroupStatus.REJECTED) {
      boolean won =
          subCaseGroupRepository
              .markPolicyTriggered(parentCaseId, groupId)
              .await()
              .atMost(Duration.ofSeconds(10));

      if (!won) {
        return;
      }

      if (groupStatus == GroupStatus.COMPLETED) {
        if (group.getOnThresholdReached() == OnThresholdReached.CANCEL) {
          cancelRemainingChildren(group, childCaseId);
        }

        Map<String, Object> appliedData =
            applyOutputMapping(startedEntry, childCaseId, parentCaseId);
        writeCompletedLog(parentCaseId, childCaseId, groupId, groupStatus, appliedData);

        CaseInstance parent = caseInstanceCache.get(parentCaseId);
        if (parent == null) {
          LOG.warnf(
              "SubCaseCompletionService: parent %s not in cache after group completed",
              parentCaseId);
          return;
        }

        caseResumptionService
            .resumeIfWaiting(
                parent,
                groupId,
                childCaseId.toString(),
                Map.of(),
                CaseHubEventType.SUBCASE_COMPLETED)
            .await()
            .atMost(Duration.ofSeconds(10));

        // Notify PlanItemCompletionHandler so it can mark the SubCase PlanItem COMPLETED
        // and evaluate stage autocomplete. childCaseId is the completion tracking key.
        eventBus.publish(
            BlackboardEventBusAddresses.SUBCASE_EXECUTION_COMPLETED,
            new SubCaseExecutionCompleted(parentCaseId, childCaseId));

      } else {
        // REJECTED — threshold is unreachable; cancel parent to prevent indefinite WAITING.
        // Cancel the PlanItem first so it is in a terminal state before the registry is evicted.
        cancelPlanItemOnRejected(parentCaseId, childCaseId);
        writeCompletedLog(parentCaseId, childCaseId, groupId, groupStatus, null);
        LOG.warnf(
            "SubCaseGroup REJECTED: parentCaseId=%s groupId=%s — threshold unreachable. Cancelling parent case.",
            parentCaseId, groupId);
        try {
          caseHubRuntime.cancelCase(parentCaseId);
        } catch (Exception e) {
          LOG.errorf(
              "SubCaseCompletionService: failed to cancel parent %s after group rejection: %s",
              parentCaseId, e.getMessage());
        }
      }
    } else {
      writeCompletedLog(parentCaseId, childCaseId, groupId, groupStatus, null);
    }
  }

  private void handleUngroupedCompletion(
      UUID childCaseId, CaseLifecycleEvent event, EventLog startedEntry) {
    UUID parentCaseId = startedEntry.getCaseId();
    String outputMapping =
        startedEntry.getMetadata().has("outputMapping")
            ? startedEntry.getMetadata().get("outputMapping").asText()
            : null;

    CaseStatus childStatus =
        event.caseStatus() != null ? CaseStatus.valueOf(event.caseStatus()) : CaseStatus.FAULTED;

    CaseInstance parent = caseInstanceCache.get(parentCaseId);
    if (parent == null) {
      LOG.warnf("SubCaseCompletionService: parent %s not in cache", parentCaseId);
      return;
    }

    Map<String, Object> appliedData = null;
    if (outputMapping != null) {
      appliedData = applyOutputMappingToParent(childCaseId, parent, outputMapping);
    }

    LOG.infof(
        "SubCaseCompletionService (ungrouped): child %s (%s) → parent %s",
        childCaseId, childStatus, parentCaseId);

    writeCompletedLog(parentCaseId, childCaseId, null, null, appliedData);

    caseResumptionService
        .resumeIfWaiting(
            parent,
            childCaseId.toString(),
            childCaseId.toString(),
            Map.of(),
            CaseHubEventType.SUBCASE_COMPLETED)
        .await()
        .atMost(Duration.ofSeconds(10));

    // Notify PlanItemCompletionHandler so it can mark the SubCase PlanItem COMPLETED and evaluate
    // stage autocomplete. For fire-and-forget subcases (waitForCompletion=false), the PlanItem was
    // already marked COMPLETED synchronously by SubCaseExecutionHandler when the child was spawned.
    // In that case PlanItemCompletionHandler will find the status not in COMPLETABLE and log a
    // debug
    // line — harmless, no transition occurs.
    eventBus.publish(
        BlackboardEventBusAddresses.SUBCASE_EXECUTION_COMPLETED,
        new SubCaseExecutionCompleted(parentCaseId, childCaseId));
  }

  /**
   * Cancels the SubCase PlanItem in the BlackboardRegistry when a grouped SubCase group is REJECTED
   * (threshold unreachable). Called before the parent case is cancelled and the registry is
   * evicted, so the terminal state is observable for the eviction window.
   */
  void cancelPlanItemOnRejected(UUID parentCaseId, UUID childCaseId) {
    registry
        .getPlanItemId(parentCaseId, childCaseId.toString())
        .flatMap(
            planItemId -> registry.get(parentCaseId).flatMap(plan -> plan.getPlanItem(planItemId)))
        .filter(
            pi ->
                pi.getStatus() != PlanItemStatus.COMPLETED
                    && pi.getStatus() != PlanItemStatus.FAULTED
                    && pi.getStatus() != PlanItemStatus.CANCELLED)
        .ifPresent(pi -> pi.markCancelled());
  }

  private Map<String, Object> applyOutputMapping(
      EventLog startedEntry, UUID childCaseId, UUID parentCaseId) {
    String outputMapping =
        startedEntry.getMetadata().has("outputMapping")
            ? startedEntry.getMetadata().get("outputMapping").asText()
            : null;
    if (outputMapping == null) return null;
    CaseInstance parent = caseInstanceCache.get(parentCaseId);
    if (parent != null) {
      return applyOutputMappingToParent(childCaseId, parent, outputMapping);
    }
    return null;
  }

  private Map<String, Object> applyOutputMappingToParent(
      UUID childCaseId, CaseInstance parent, String outputMapping) {
    CaseInstance child = caseInstanceCache.get(childCaseId);
    if (child != null) {
      try {
        ValidationResult vr = jqEvaluator.eval(outputMapping, child.getCaseContext().asJsonNode());
        if (!vr.ok() || vr.output() == null || vr.output().isEmpty()) return null;
        Map<String, Object> mapped = OBJECT_MAPPER.convertValue(vr.output().get(0), MAP_TYPE);
        mapped.forEach((k, v) -> parent.getCaseContext().set(k, v));
        return mapped;
      } catch (Exception e) {
        LOG.warnf(e, "outputMapping evaluation failed for child case %s", childCaseId);
        return null;
      }
    } else {
      LOG.warnf(
          "SubCaseCompletionService: child %s not in cache — outputMapping skipped", childCaseId);
    }
    return null;
  }

  private void cancelRemainingChildren(SubCaseGroup group, UUID justCompletedChildId) {
    group.getChildCaseIds().stream()
        .filter(id -> !id.equals(justCompletedChildId))
        .forEach(
            id -> {
              try {
                caseHubRuntime.cancelCase(id);
              } catch (Exception e) {
                LOG.warnf(
                    "SubCaseCompletionService: could not cancel child %s: %s", id, e.getMessage());
              }
            });
  }

  private void writeCompletedLog(
      UUID parentCaseId,
      UUID childCaseId,
      String groupId,
      GroupStatus groupStatus,
      Map<String, Object> appliedData) {
    EventLog log = new EventLog();
    log.setCaseId(parentCaseId);
    log.setWorkerId(childCaseId.toString());
    log.setEventType(CaseHubEventType.SUBCASE_COMPLETED);
    log.setStreamType(EventStreamType.CASE);
    log.setTimestamp(Instant.now());
    ObjectNode meta = OBJECT_MAPPER.createObjectNode();
    meta.put("childCaseId", childCaseId.toString());
    if (groupId != null) meta.put("groupId", groupId);
    if (groupStatus != null) meta.put("groupStatus", groupStatus.name());
    log.setMetadata(meta);
    if (appliedData != null && !appliedData.isEmpty()) {
      log.setPayload(OBJECT_MAPPER.valueToTree(appliedData));
    }
    eventLogRepository.append(log).await().atMost(Duration.ofSeconds(10));
  }

  private static boolean isTerminal(String commandType) {
    return "CompleteCase".equals(commandType)
        || "FaultCase".equals(commandType)
        || "CancelCase".equals(commandType);
  }
}
