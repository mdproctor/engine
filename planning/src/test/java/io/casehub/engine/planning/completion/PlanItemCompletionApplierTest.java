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
package io.casehub.engine.planning.completion;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.api.context.CaseContext;
import io.casehub.api.context.ContextLayer;
import io.casehub.api.context.WritableLayer;
import io.casehub.api.model.TaskStatus;
import io.casehub.engine.common.internal.context.BridgeResolver;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CrossTenantCaseInstanceRepository;
import io.casehub.engine.common.spi.event.PlanItemObsoleteEvent;
import io.casehub.engine.common.spi.event.PlanItemStateChangedEvent;
import io.casehub.engine.planning.plan.CasePlanModel;
import io.casehub.engine.planning.plan.PlanItem;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.event.Event;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlanItemCompletionApplierTest {

  private PlanItemCompletionApplier applier;
  private BlackboardRegistry registry;
  private CrossTenantCaseInstanceRepository caseInstanceRepository;
  private EventBus eventBus;
  private Event<PlanItemStateChangedEvent> stateChangedEvents;
  private Event<PlanItemObsoleteEvent> obsoleteEvents;

  private static final UUID CASE_ID = UUID.randomUUID();
  private static final String PLAN_ITEM_ID = "pi-001";
  private static final String TENANCY_ID = "test-tenant";

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    registry = mock(BlackboardRegistry.class);
    caseInstanceRepository = mock(CrossTenantCaseInstanceRepository.class);
    eventBus = mock(EventBus.class);
    stateChangedEvents = mock(Event.class);
    obsoleteEvents = mock(Event.class);

    applier = new PlanItemCompletionApplier();
    applier.registry = registry;
    applier.caseInstanceRepository = caseInstanceRepository;
    applier.eventBus = eventBus;
    applier.jqEvaluator = mock(JQEvaluator.class);
    applier.bridgeResolver = mock(BridgeResolver.class);
    applier.caseDefinitionRegistry = mock(CaseDefinitionRegistry.class);
    applier.planItemStateChangedEvents = stateChangedEvents;
    applier.planItemObsoleteEvents = obsoleteEvents;
  }

  @Test
  void completed_marks_planItem_and_fires_context_changed() {
    PlanItem item = mockPlanItem(TaskStatus.DELEGATED);
    mockCaseInstance();

    applier.apply(CASE_ID, PLAN_ITEM_ID, TaskStatus.COMPLETED, null, null);

    verify(item).markCompleted();
    verify(eventBus).publish(eq("casehub.context.changed"), any(CaseContextChangedEvent.class));
  }

  @Test
  void rejected_marks_planItem_and_fires_state_changed_event() {
    PlanItem item = mockPlanItem(TaskStatus.DELEGATED);
    mockCaseInstance();

    applier.apply(CASE_ID, PLAN_ITEM_ID, TaskStatus.REJECTED, null, null);

    verify(item).markRejected();
    verify(stateChangedEvents).fireAsync(any(PlanItemStateChangedEvent.class));
    verify(eventBus).publish(eq("casehub.context.changed"), any(CaseContextChangedEvent.class));
  }

  @Test
  void faulted_marks_planItem_and_fires_state_changed_event() {
    PlanItem item = mockPlanItem(TaskStatus.DELEGATED);
    mockCaseInstance();

    applier.apply(CASE_ID, PLAN_ITEM_ID, TaskStatus.FAULTED, null, null);

    verify(item).markFaulted();
    verify(stateChangedEvents).fireAsync(any(PlanItemStateChangedEvent.class));
  }

  @Test
  void obsolete_marks_planItem_and_fires_obsolete_event() {
    PlanItem item = mockPlanItem(TaskStatus.DELEGATED);
    mockCaseInstance();

    applier.apply(CASE_ID, PLAN_ITEM_ID, TaskStatus.OBSOLETE, null, null);

    verify(item).markObsolete();
    verify(obsoleteEvents).fireAsync(any(PlanItemObsoleteEvent.class));
  }

  @Test
  void cancelled_marks_planItem() {
    PlanItem item = mockPlanItem(TaskStatus.DELEGATED);
    mockCaseInstance();

    applier.apply(CASE_ID, PLAN_ITEM_ID, TaskStatus.CANCELLED, null, null);

    verify(item).markCancelled();
  }

  @Test
  void planItem_not_found_does_nothing() {
    CasePlanModel plan = mock(CasePlanModel.class);
    when(registry.get(CASE_ID)).thenReturn(Optional.of(plan));
    when(plan.getPlanItem(PLAN_ITEM_ID)).thenReturn(Optional.empty());

    applier.apply(CASE_ID, PLAN_ITEM_ID, TaskStatus.COMPLETED, null, null);

    verify(eventBus, never()).publish(any(), any(CaseContextChangedEvent.class));
  }

  @Test
  void no_casePlanModel_does_nothing() {
    when(registry.get(CASE_ID)).thenReturn(Optional.empty());

    applier.apply(CASE_ID, PLAN_ITEM_ID, TaskStatus.COMPLETED, null, null);

    verify(eventBus, never()).publish(any(), any(CaseContextChangedEvent.class));
  }

  @Test
  void caseInstance_not_found_still_transitions_but_no_context_changed() {
    PlanItem item = mockPlanItem(TaskStatus.DELEGATED);
    when(caseInstanceRepository.findByUuid(CASE_ID)).thenReturn(null);

    applier.apply(CASE_ID, PLAN_ITEM_ID, TaskStatus.COMPLETED, null, null);

    verify(item).markCompleted();
    verify(eventBus, never()).publish(any(), any(CaseContextChangedEvent.class));
  }

  @Test
  void already_terminal_planItem_skips_silently() {
    PlanItem item = mockPlanItem(TaskStatus.COMPLETED);
    org.mockito.Mockito.doThrow(new IllegalStateException("already terminal"))
        .when(item)
        .markCompleted();
    mockCaseInstance();

    applier.apply(CASE_ID, PLAN_ITEM_ID, TaskStatus.COMPLETED, null, null);

    verify(eventBus, never()).publish(any(), any(CaseContextChangedEvent.class));
  }

  @Test
  void suspend_marks_planItem_suspended() {
    PlanItem item = mockPlanItem(TaskStatus.DELEGATED);

    applier.applySuspend(CASE_ID, PLAN_ITEM_ID);

    verify(item).markSuspended();
  }

  @Test
  void resume_marks_planItem_resumed_when_suspended() {
    PlanItem item = mockPlanItem(TaskStatus.SUSPENDED);

    applier.applyResume(CASE_ID, PLAN_ITEM_ID);

    verify(item).markResumed();
  }

  @Test
  void resume_skips_when_not_suspended() {
    PlanItem item = mockPlanItem(TaskStatus.DELEGATED);

    applier.applyResume(CASE_ID, PLAN_ITEM_ID);

    verify(item, never()).markResumed();
  }

  private PlanItem mockPlanItem(TaskStatus status) {
    CasePlanModel plan = mock(CasePlanModel.class);
    PlanItem item = mock(PlanItem.class);
    when(registry.get(CASE_ID)).thenReturn(Optional.of(plan));
    when(plan.getPlanItem(PLAN_ITEM_ID)).thenReturn(Optional.of(item));
    when(item.getStatus()).thenReturn(status);
    when(item.id()).thenReturn(PLAN_ITEM_ID);
    when(item.getBindingName()).thenReturn("test-binding");
    return item;
  }

  private CaseInstance mockCaseInstance() {
    CaseInstance instance = mock(CaseInstance.class);
    CaseContext context = mock(CaseContext.class);
    WritableLayer workingLayer = mock(WritableLayer.class);
    when(caseInstanceRepository.findByUuid(CASE_ID)).thenReturn(instance);
    when(instance.getUuid()).thenReturn(CASE_ID);
    when(instance.getCaseContext()).thenReturn(context);
    when(context.snapshot()).thenReturn(context);
    when(context.layer(ContextLayer.WORKING)).thenReturn(workingLayer);
    instance.tenancyId = TENANCY_ID;
    return instance;
  }
}
