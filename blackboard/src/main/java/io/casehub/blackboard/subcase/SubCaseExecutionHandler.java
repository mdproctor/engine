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
import io.casehub.api.model.TaskStatus;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.blackboard.plan.PlanItem;
import io.casehub.blackboard.registry.BlackboardRegistry;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.SubCaseScheduleEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.SubCaseGroupRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.internal.work.PendingWorkRegistry;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class SubCaseExecutionHandler {

  private static final Logger LOG = Logger.getLogger(SubCaseExecutionHandler.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final CaseHubRuntime caseHubRuntime;
  private final CaseDefinitionRegistry caseDefinitionRegistry;
  private final CaseInstanceRepository caseInstanceRepository;
  private final EventLogRepository eventLogRepository;
  private final PendingWorkRegistry pendingWorkRegistry;
  private final SubCaseGroupRepository subCaseGroupRepository;
  private final BlackboardRegistry registry;
  private final CaseInstanceCache caseInstanceCache;

  @Inject
  public SubCaseExecutionHandler(
      CaseHubRuntime caseHubRuntime,
      CaseDefinitionRegistry caseDefinitionRegistry,
      CaseInstanceRepository caseInstanceRepository,
      EventLogRepository eventLogRepository,
      PendingWorkRegistry pendingWorkRegistry,
      SubCaseGroupRepository subCaseGroupRepository,
      BlackboardRegistry registry,
      CaseInstanceCache caseInstanceCache) {
    this.caseHubRuntime = caseHubRuntime;
    this.caseDefinitionRegistry = caseDefinitionRegistry;
    this.caseInstanceRepository = caseInstanceRepository;
    this.eventLogRepository = eventLogRepository;
    this.pendingWorkRegistry = pendingWorkRegistry;
    this.subCaseGroupRepository = subCaseGroupRepository;
    this.registry = registry;
    this.caseInstanceCache = caseInstanceCache;
  }

  @ConsumeEvent(EventBusAddresses.SUBCASE_SCHEDULE)
  @RunOnVirtualThread
  public void onSubCaseSchedule(SubCaseScheduleEvent event) {
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
        return;
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
      return;
    }

    UUID childCaseId;
    try {
      childCaseId =
          caseHubRuntime.startCase(
              childDefinition,
              event.childInitialContext(),
              parent.getUuid(),
              parent.getPropagationContext());
    } catch (Exception e) {
      LOG.errorf(
          e,
          "SubCaseExecutionHandler: startCase failed for binding '%s' on case %s",
          bindingName,
          parent.getUuid());
      faultPlanItem(parent.getUuid(), bindingName);
      return;
    }

    LOG.infof(
        "SubCase spawned: parentCaseId=%s childCaseId=%s binding=%s grouped=%s",
        parent.getUuid(), childCaseId, bindingName, subCase.groupId() != null);

    // Mark the PlanItem DELEGATED (first spawn only) and register childCaseId as tracking key.
    // For M-of-N subsequent spawns, the item is already DELEGATED — skip markDelegated() but
    // still index the new childCaseId so any completing child routes to the same PlanItem.
    delegatePlanItem(parent.getUuid(), bindingName, childCaseId, subCase.waitForCompletion());

    if (subCase.groupId() != null) {
      handleGrouped(parent, subCase, childCaseId, bindingName);
    } else {
      handleUngrouped(parent, subCase, childCaseId, bindingName);
    }
  }

  private void delegatePlanItem(
      UUID parentCaseId, String bindingName, UUID childCaseId, boolean waitForCompletion) {
    registry
        .get(parentCaseId)
        .flatMap(plan -> plan.getPlanItemByBindingName(bindingName))
        .ifPresent(
            pi -> {
              if (pi.getStatus() == TaskStatus.PENDING) {
                pi.markDelegated();
              }
              // Index ALL spawns → same planItemId (M-of-N: any completing child routes here)
              registry.indexForCompletion(parentCaseId, childCaseId.toString(), pi.getPlanItemId());

              // Fire-and-forget: completes immediately after spawning — no completion event needed.
              if (!waitForCompletion && pi.getStatus() == TaskStatus.DELEGATED) {
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

  private void handleGrouped(
      CaseInstance parent, SubCase subCase, UUID childCaseId, String bindingName) {
    String groupId = subCase.groupId();

    if (subCase.totalInGroup() <= 0) {
      LOG.errorf(
          "SubCaseExecutionHandler: grouped SubCase binding '%s' has invalid totalInGroup=%d",
          bindingName, subCase.totalInGroup());
      faultPlanItem(parent.getUuid(), bindingName);
      return;
    }

    subCaseGroupRepository.getOrCreate(
        parent.getUuid(),
        groupId,
        subCase.totalInGroup(),
        subCase.requiredCount(),
        subCase.onThresholdReached(),
        parent.tenancyId);
    subCaseGroupRepository.registerChild(parent.getUuid(), groupId, childCaseId, parent.tenancyId);

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
    meta.put("bindingName", bindingName);
    if (subCase.outputMapping() instanceof io.casehub.api.model.SubCaseMapping.Expression expr) {
      meta.put("outputMapping", expr.expression());
    }
    log.setMetadata(meta);

    boolean alreadyWaiting =
        parent.getState() == CaseStatus.WAITING && groupId.equals(parent.getWaitingForWorkId());
    if (!alreadyWaiting) {
      parent.setState(CaseStatus.WAITING);
      parent.setWaitingForWorkId(groupId);
      caseInstanceRepository.updateStateAndAppendEvent(parent, log, parent.tenancyId);
    } else {
      eventLogRepository.append(log, parent.tenancyId);
    }
  }

  private void handleUngrouped(
      CaseInstance parent, SubCase subCase, UUID childCaseId, String bindingName) {
    EventLog log = new EventLog();
    log.setCaseId(parent.getUuid());
    log.setWorkerId(childCaseId.toString());
    log.setEventType(CaseHubEventType.SUBCASE_STARTED);
    log.setStreamType(EventStreamType.CASE);
    log.setTimestamp(Instant.now());
    ObjectNode meta = OBJECT_MAPPER.createObjectNode();
    meta.put("childCaseId", childCaseId.toString());
    meta.put("waitForCompletion", subCase.waitForCompletion());
    meta.put("bindingName", bindingName);
    if (subCase.outputMapping() instanceof io.casehub.api.model.SubCaseMapping.Expression expr) {
      meta.put("outputMapping", expr.expression());
    }
    log.setMetadata(meta);

    if (subCase.waitForCompletion()) {
      pendingWorkRegistry.register(childCaseId.toString());
      parent.setState(CaseStatus.WAITING);
      parent.setWaitingForWorkId(childCaseId.toString());
      caseInstanceRepository.updateStateAndAppendEvent(parent, log, parent.tenancyId);
    } else {
      eventLogRepository.append(log, parent.tenancyId);
    }
  }
}
