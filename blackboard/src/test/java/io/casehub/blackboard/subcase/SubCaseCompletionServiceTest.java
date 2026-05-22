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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.blackboard.event.BlackboardEventBusAddresses;
import io.casehub.blackboard.event.SubCaseExecutionCompleted;
import io.casehub.blackboard.plan.DefaultCasePlanModel;
import io.casehub.blackboard.plan.PlanItem;
import io.casehub.blackboard.registry.BlackboardRegistry;
import io.casehub.engine.internal.event.CaseLifecycleEvent;
import io.casehub.engine.internal.history.EventLog;
import io.casehub.engine.internal.jq.JQEvaluator;
import io.casehub.engine.internal.model.CaseInstance;
import io.casehub.engine.internal.model.PlanItemStatus;
import io.casehub.engine.internal.work.CaseResumptionService;
import io.casehub.engine.spi.EventLogRepository;
import io.casehub.engine.spi.SubCaseGroupRepository;
import io.casehub.engine.spi.cache.CaseInstanceCache;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for new SubCaseCompletionService behaviors: event publishing and M-of-N REJECTED
 * PlanItem cancellation. See casehubio/engine#322.
 */
class SubCaseCompletionServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private BlackboardRegistry registry;
  private EventBus mockBus;
  private CaseResumptionService caseResumptionService;
  private CaseInstanceCache caseInstanceCache;
  private EventLogRepository eventLogRepository;
  private CaseHubRuntime caseHubRuntime;
  private SubCaseCompletionService service;

  private UUID parentCaseId;
  private UUID childCaseId;

  @BeforeEach
  void setUp() {
    registry = new BlackboardRegistry();
    mockBus = mock(EventBus.class);
    caseResumptionService = mock(CaseResumptionService.class);
    caseInstanceCache = mock(CaseInstanceCache.class);
    eventLogRepository = mock(EventLogRepository.class);
    caseHubRuntime = mock(CaseHubRuntime.class);

    when(caseResumptionService.resumeIfWaiting(any(), any(), any(), any(), any()))
        .thenReturn(Uni.createFrom().voidItem());
    when(eventLogRepository.append(any())).thenReturn(Uni.createFrom().voidItem());

    service =
        new SubCaseCompletionService(
            eventLogRepository,
            mock(JQEvaluator.class),
            caseInstanceCache,
            caseResumptionService,
            mock(SubCaseGroupRepository.class),
            caseHubRuntime,
            mockBus,
            registry);

    parentCaseId = UUID.randomUUID();
    childCaseId = UUID.randomUUID();
  }

  private EventLog subcaseStartedEntry(UUID parentId, UUID childId, boolean waitForCompletion) {
    EventLog entry = mock(EventLog.class);
    ObjectNode meta = MAPPER.createObjectNode();
    meta.put("childCaseId", childId.toString());
    meta.put("waitForCompletion", waitForCompletion);
    when(entry.getMetadata()).thenReturn(meta);
    when(entry.getCaseId()).thenReturn(parentId);
    when(entry.getWorkerId()).thenReturn(childId.toString());
    return entry;
  }

  private CaseLifecycleEvent completionEvent(UUID caseId) {
    return new CaseLifecycleEvent(
        caseId, "CompleteCase", "CaseCompleted", "COMPLETED", null, "system");
  }

  @Test
  void ungrouped_completion_publishes_subcase_execution_completed_event() {
    EventLog startedEntry = subcaseStartedEntry(parentCaseId, childCaseId, true);
    when(eventLogRepository.findByWorkerAndType(
            eq(childCaseId.toString()), eq(CaseHubEventType.SUBCASE_STARTED)))
        .thenReturn(Uni.createFrom().item(List.of(startedEntry)));

    CaseInstance parent = mock(CaseInstance.class);
    when(parent.getUuid()).thenReturn(parentCaseId);
    when(caseInstanceCache.get(parentCaseId)).thenReturn(parent);

    service.handleCompletion(completionEvent(childCaseId));

    verify(mockBus)
        .publish(
            eq(BlackboardEventBusAddresses.SUBCASE_EXECUTION_COMPLETED),
            eq(new SubCaseExecutionCompleted(parentCaseId, childCaseId)));
  }

  @Test
  void mofn_rejected_cancels_plan_item_in_registry() {
    // Set up a DELEGATED plan item in the registry
    DefaultCasePlanModel plan = (DefaultCasePlanModel) registry.getOrCreate(parentCaseId);
    PlanItem subcaseItem = PlanItem.create("spawn-sites", "unknown", 0);
    plan.addPlanItem(subcaseItem);
    subcaseItem.markDelegated();
    registry.indexForCompletion(parentCaseId, childCaseId.toString(), subcaseItem.getPlanItemId());

    service.cancelPlanItemOnRejected(parentCaseId, childCaseId);

    assertThat(subcaseItem.getStatus())
        .as("M-of-N REJECTED must cancel the SubCase PlanItem for observability")
        .isEqualTo(PlanItemStatus.CANCELLED);
  }
}
