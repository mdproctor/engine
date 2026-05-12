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
import io.casehub.engine.internal.event.EventBusAddresses;
import io.casehub.engine.internal.event.SubCaseScheduleEvent;
import io.casehub.engine.internal.history.EventLog;
import io.casehub.engine.internal.model.CaseInstance;
import io.casehub.engine.internal.model.CaseMetaModel;
import io.casehub.engine.internal.work.PendingWorkRegistry;
import io.casehub.engine.spi.CaseDefinitionRegistry;
import io.casehub.engine.spi.CaseInstanceRepository;
import io.casehub.engine.spi.EventLogRepository;
import io.casehub.engine.spi.SubCaseGroupRepository;
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

  @Inject CaseHubRuntime caseHubRuntime;
  @Inject CaseDefinitionRegistry caseDefinitionRegistry;
  @Inject CaseInstanceRepository caseInstanceRepository;
  @Inject EventLogRepository eventLogRepository;
  @Inject PendingWorkRegistry pendingWorkRegistry;
  @Inject SubCaseGroupRepository subCaseGroupRepository;

  @ConsumeEvent(value = EventBusAddresses.SUBCASE_SCHEDULE, blocking = true)
  public Uni<Void> onSubCaseSchedule(SubCaseScheduleEvent event) {
    CaseInstance parent = event.parentInstance();
    SubCase subCase = event.subCase();

    CaseMetaModel parentMeta = parent.getCaseMetaModel();
    if (parentMeta != null
        && subCase.namespace().equals(parentMeta.getNamespace())
        && subCase.name().equals(parentMeta.getName())
        && subCase.version().equals(parentMeta.getVersion())) {
      LOG.errorf(
          "SubCase circular dependency: case %s cannot spawn itself (%s/%s/%s)",
          parent.getUuid(), subCase.namespace(), subCase.name(), subCase.version());
      return Uni.createFrom().voidItem();
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
      return Uni.createFrom().voidItem();
    }

    CompletionStage<UUID> childFuture =
        caseHubRuntime.startCase(
            childDefinition,
            event.childInitialContext(),
            parent.getUuid(),
            parent.getPropagationContext());
    UUID childCaseId = childFuture.toCompletableFuture().join();

    LOG.infof(
        "SubCase spawned: parentCaseId=%s childCaseId=%s grouped=%s",
        parent.getUuid(), childCaseId, subCase.groupId() != null);

    if (subCase.groupId() != null) {
      return handleGrouped(parent, subCase, childCaseId);
    } else {
      return handleUngrouped(parent, subCase, childCaseId);
    }
  }

  private Uni<Void> handleGrouped(CaseInstance parent, SubCase subCase, UUID childCaseId) {
    String groupId = subCase.groupId();

    return subCaseGroupRepository
        .getOrCreate(
            parent.getUuid(),
            groupId,
            subCase.totalInGroup(),
            subCase.requiredCount(),
            subCase.onThresholdReached())
        .flatMap(
            group -> subCaseGroupRepository.registerChild(parent.getUuid(), groupId, childCaseId))
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
                return caseInstanceRepository
                    .updateStateAndAppendEvent(parent, log)
                    .replaceWithVoid();
              } else {
                return eventLogRepository.append(log).replaceWithVoid();
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
      return caseInstanceRepository.updateStateAndAppendEvent(parent, log).replaceWithVoid();
    } else {
      return eventLogRepository.append(log).replaceWithVoid();
    }
  }
}
