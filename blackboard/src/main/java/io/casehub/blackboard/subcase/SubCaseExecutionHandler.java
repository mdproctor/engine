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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.SubCase;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.blackboard.plan.PlanItem;
import io.casehub.blackboard.registry.BlackboardRegistry;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.SubCaseScheduleEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.internal.model.PlanItemStatus;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.ReactiveCaseInstanceRepository;
import io.casehub.engine.common.spi.ReactiveEventLogRepository;
import io.casehub.engine.common.spi.ReactiveSubCaseGroupRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.internal.work.PendingWorkRegistry;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.jboss.logging.Logger;

@ApplicationScoped
public class SubCaseExecutionHandler {

  private static final Logger LOG = Logger.getLogger(SubCaseExecutionHandler.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final CaseHubRuntime caseHubRuntime;
  private final CaseDefinitionRegistry caseDefinitionRegistry;
  private final ReactiveCaseInstanceRepository reactiveCaseInstanceRepository;
  private final ReactiveEventLogRepository reactiveEventLogRepository;
  private final PendingWorkRegistry pendingWorkRegistry;
  private final ReactiveSubCaseGroupRepository reactiveSubCaseGroupRepository;
  private final BlackboardRegistry registry;
  private final CaseInstanceCache caseInstanceCache;

  @Inject
  public SubCaseExecutionHandler(
      CaseHubRuntime caseHubRuntime,
      CaseDefinitionRegistry caseDefinitionRegistry,
      ReactiveCaseInstanceRepository reactiveCaseInstanceRepository,
      ReactiveEventLogRepository reactiveEventLogRepository,
      PendingWorkRegistry pendingWorkRegistry,
      ReactiveSubCaseGroupRepository reactiveSubCaseGroupRepository,
      BlackboardRegistry registry,
      CaseInstanceCache caseInstanceCache) {
    this.caseHubRuntime = caseHubRuntime;
    this.caseDefinitionRegistry = caseDefinitionRegistry;
    this.reactiveCaseInstanceRepository = reactiveCaseInstanceRepository;
    this.reactiveEventLogRepository = reactiveEventLogRepository;
    this.pendingWorkRegistry = pendingWorkRegistry;
    this.reactiveSubCaseGroupRepository = reactiveSubCaseGroupRepository;
    this.registry = registry;
    this.caseInstanceCache = caseInstanceCache;
  }

  @ConsumeEvent(value = EventBusAddresses.SUBCASE_SCHEDULE, blocking = true)
  public Uni<Void> onSubCaseSchedule(SubCaseScheduleEvent event) {
    CaseInstance parent = event.parentInstance();
    SubCase subCase = event.subCase();
    String bindingName = event.bindingName();

    CaseMetaModel parentMeta = parent.getCaseMetaModel();
    boolean selfReference =
        parentMeta != null
            && subCase.namespace().equals(parentMeta.getNamespace())
            && subCase.name().equals(parentMeta.getName())
            && subCase.version().equals(parentMeta.getVersion());

    if (selfReference) {
      int maxDepth = subCase.maxRecursionDepth();
      int depth = computeSameDefinitionDepth(parent, subCase, maxDepth);
      if (depth >= maxDepth) {
        LOG.warnf(
            "SubCase recursion depth %d reached limit %d for case %s (%s/%s/%s)",
            depth,
            maxDepth,
            parent.getUuid(),
            subCase.namespace(),
            subCase.name(),
            subCase.version());
        faultPlanItem(parent.getUuid(), bindingName);
        return Uni.createFrom().voidItem();
      }
    }

    CaseMetaModel childMeta = new CaseMetaModel();
    childMeta.setNamespace(subCase.namespace());
    childMeta.setName(subCase.name());
    childMeta.setVersion(subCase.version());

    var childDefinition = caseDefinitionRegistry.getCaseDefinition(childMeta);
    if (childDefinition == null) {
      LOG.errorf(
          "SubCaseExecutionHandler: no CaseDefinition for %s/%s/%s",
          subCase.namespace(), subCase.name(), subCase.version());
      faultPlanItem(parent.getUuid(), bindingName);
      return Uni.createFrom().voidItem();
    }

    UUID childCaseId;
    try {
      CompletionStage<UUID> childFuture =
          caseHubRuntime.startCase(
              childDefinition,
              event.childInitialContext(),
              parent.getUuid(),
              parent.getPropagationContext());
      childCaseId = childFuture.toCompletableFuture().join();
    } catch (Exception e) {
      LOG.errorf(
          e,
          "SubCaseExecutionHandler: startCase failed for binding '%s' on case %s",
          bindingName,
          parent.getUuid());
      faultPlanItem(parent.getUuid(), bindingName);
      return Uni.createFrom().voidItem();
    }

    LOG.infof(
        "SubCase spawned: parentCaseId=%s childCaseId=%s binding=%s grouped=%s",
        parent.getUuid(), childCaseId, bindingName, subCase.groupId() != null);

    // Mark the PlanItem DELEGATED (first spawn only) and register childCaseId as tracking key.
    // For M-of-N subsequent spawns, the item is already DELEGATED — skip markDelegated() but
    // still index the new childCaseId so any completing child routes to the same PlanItem.
    delegatePlanItem(parent.getUuid(), bindingName, childCaseId, subCase.waitForCompletion());

    if (subCase.groupId() != null) {
      return handleGrouped(parent, subCase, childCaseId, bindingName);
    } else {
      return handleUngrouped(parent, subCase, childCaseId);
    }
  }

  private void delegatePlanItem(
      UUID parentCaseId, String bindingName, UUID childCaseId, boolean waitForCompletion) {
    registry
        .get(parentCaseId)
        .flatMap(plan -> plan.getPlanItemByBindingName(bindingName))
        .ifPresent(
            pi -> {
              if (pi.getStatus() == PlanItemStatus.PENDING) {
                pi.markDelegated();
              }
              // Index ALL spawns → same planItemId (M-of-N: any completing child routes here)
              registry.indexForCompletion(parentCaseId, childCaseId.toString(), pi.getPlanItemId());

              // Fire-and-forget: completes immediately after spawning — no completion event needed.
              if (!waitForCompletion && pi.getStatus() == PlanItemStatus.DELEGATED) {
                pi.markCompleted();
              }
            });
  }

  private void faultPlanItem(UUID parentCaseId, String bindingName) {
    // getPlanItemByBindingName only returns PENDING/RUNNING/DELEGATED — all safe to fault
    registry
        .get(parentCaseId)
        .flatMap(plan -> plan.getPlanItemByBindingName(bindingName))
        .ifPresent(PlanItem::markFaulted);
  }

  private int computeSameDefinitionDepth(CaseInstance parent, SubCase subCase, int maxDepth) {
    int depth = 0;
    UUID ancestorId = parent.getParentCaseId();
    while (ancestorId != null && depth < maxDepth) {
      CaseInstance ancestor = caseInstanceCache.get(ancestorId);
      if (ancestor == null) {
        break;
      }
      CaseMetaModel meta = ancestor.getCaseMetaModel();
      if (meta != null
          && subCase.namespace().equals(meta.getNamespace())
          && subCase.name().equals(meta.getName())
          && subCase.version().equals(meta.getVersion())) {
        depth++;
      }
      ancestorId = ancestor.getParentCaseId();
    }
    return depth;
  }

  private Uni<Void> handleGrouped(
      CaseInstance parent, SubCase subCase, UUID childCaseId, String bindingName) {
    String groupId = subCase.groupId();

    if (subCase.totalInGroup() <= 0) {
      LOG.errorf(
          "SubCaseExecutionHandler: grouped SubCase binding '%s' has invalid totalInGroup=%d",
          bindingName, subCase.totalInGroup());
      faultPlanItem(parent.getUuid(), bindingName);
      return Uni.createFrom().voidItem();
    }

    return reactiveSubCaseGroupRepository
        .getOrCreate(
            parent.getUuid(),
            groupId,
            subCase.totalInGroup(),
            subCase.requiredCount(),
            subCase.onThresholdReached(),
            parent.tenancyId)
        .flatMap(
            group ->
                reactiveSubCaseGroupRepository.registerChild(
                    parent.getUuid(), groupId, childCaseId, parent.tenancyId))
        .flatMap(
            group -> {
              EventLog log = new EventLog();
              log.setCaseId(parent.getUuid());
              log.setWorkerId(childCaseId.toString());
              log.setEventType(CaseHubEventType.SUBCASE_STARTED);
              log.setStreamType(EventStreamType.CASE);
              log.setTimestamp(Instant.now());
              ObjectNode meta = OBJECT_MAPPER.createObjectNode();
              meta.put("childCaseId", childCaseId.toString());
              meta.put("groupId", groupId);
              meta.put("waitForCompletion", true);
              if (subCase.outputMapping() != null) {
                meta.put("outputMapping", subCase.outputMapping());
              }
              log.setMetadata(meta);

              boolean alreadyWaiting =
                  parent.getState() == CaseStatus.WAITING
                      && groupId.equals(parent.getWaitingForWorkId());
              if (!alreadyWaiting) {
                parent.setState(CaseStatus.WAITING);
                parent.setWaitingForWorkId(groupId);
                return reactiveCaseInstanceRepository
                    .updateStateAndAppendEvent(parent, log, parent.tenancyId)
                    .replaceWithVoid();
              } else {
                return reactiveEventLogRepository.append(log, parent.tenancyId).replaceWithVoid();
              }
            });
  }

  private Uni<Void> handleUngrouped(CaseInstance parent, SubCase subCase, UUID childCaseId) {
    EventLog log = new EventLog();
    log.setCaseId(parent.getUuid());
    log.setWorkerId(childCaseId.toString());
    log.setEventType(CaseHubEventType.SUBCASE_STARTED);
    log.setStreamType(EventStreamType.CASE);
    log.setTimestamp(Instant.now());
    ObjectNode meta = OBJECT_MAPPER.createObjectNode();
    meta.put("childCaseId", childCaseId.toString());
    meta.put("waitForCompletion", subCase.waitForCompletion());
    if (subCase.outputMapping() != null) {
      meta.put("outputMapping", subCase.outputMapping());
    }
    log.setMetadata(meta);

    if (subCase.waitForCompletion()) {
      pendingWorkRegistry.register(childCaseId.toString());
      parent.setState(CaseStatus.WAITING);
      parent.setWaitingForWorkId(childCaseId.toString());
      return reactiveCaseInstanceRepository
          .updateStateAndAppendEvent(parent, log, parent.tenancyId)
          .replaceWithVoid();
    } else {
      return reactiveEventLogRepository.append(log, parent.tenancyId).replaceWithVoid();
    }
  }
}
