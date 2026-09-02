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
package io.casehub.engine.planning.compensation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.api.model.Binding;
import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.ExecutorRef;
import io.casehub.api.model.TaskStatus;
import io.casehub.engine.common.internal.event.CaseStatusChanged;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkerScheduleEvent;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.JudgmentScheduleRequest;
import io.casehub.engine.common.spi.JudgmentScheduler;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.common.spi.event.PlanItemStateChangedEvent;
import io.casehub.engine.planning.plan.CasePlanModel;
import io.casehub.engine.planning.plan.PlanItem;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.vertx.core.eventbus.EventBus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CaseCompensationServiceImplTest {

  private CaseInstanceCache caseInstanceCache;
  private EventBus eventBus;
  private BlackboardRegistry blackboardRegistry;
  private CaseDefinitionRegistry caseDefinitionRegistry;
  private EventLogRepository eventLogRepository;

  @SuppressWarnings("unchecked")
  private final jakarta.enterprise.inject.Instance<JudgmentScheduler> judgmentSchedulerInstance =
      mock(jakarta.enterprise.inject.Instance.class);

  private CaseCompensationServiceImpl service;

  private UUID caseId;
  private CaseInstance instance;
  private CaseDefinition definition;

  @BeforeEach
  void setUp() {
    caseInstanceCache = mock(CaseInstanceCache.class);
    eventBus = mock(EventBus.class);
    blackboardRegistry = new BlackboardRegistry();
    caseDefinitionRegistry = mock(CaseDefinitionRegistry.class);
    eventLogRepository = mock(EventLogRepository.class);

    service =
        new CaseCompensationServiceImpl(
            caseInstanceCache,
            eventBus,
            blackboardRegistry,
            caseDefinitionRegistry,
            eventLogRepository,
            judgmentSchedulerInstance);

    caseId = UUID.randomUUID();
    instance = new CaseInstance();
    instance.setUuid(caseId);
    instance.tenancyId = "test-tenant";
    when(caseInstanceCache.get(caseId)).thenReturn(instance);
  }

  private Worker testWorker(String name, String capabilityName) {
    return Worker.builder()
        .name(name)
        .capabilityName(capabilityName)
        .function(
            new WorkerFunction.Sync<>(
                Map.class, Map.class, (i, scope) -> WorkerResult.of(Map.of())))
        .build();
  }

  private void setUpCompensableCase(
      String originalBindingName, String compensatingBindingName, String capabilityName) {
    Worker worker = testWorker(compensatingBindingName + "-worker", capabilityName);
    Capability capability = Capability.of(capabilityName, "", "");

    Binding original =
        Binding.builder()
            .name(originalBindingName)
            .target(new CapabilityTarget(capability))
            .on(new ContextChangeTrigger("."))
            .compensateRef(compensatingBindingName)
            .build();

    Binding compensating =
        Binding.builder()
            .name(compensatingBindingName)
            .target(new CapabilityTarget(capability))
            .on(new ContextChangeTrigger("."))
            .compensation(true)
            .build();

    definition = mock(CaseDefinition.class);
    when(definition.getBindings()).thenReturn(List.of(original, compensating));
    when(definition.getWorkers()).thenReturn(List.of(worker));

    io.casehub.engine.common.internal.model.CaseMetaModel metaModel =
        mock(io.casehub.engine.common.internal.model.CaseMetaModel.class);
    instance.setCaseMetaModel(metaModel);
    when(caseDefinitionRegistry.getCaseDefinition(metaModel)).thenReturn(definition);

    instance.setState(CaseStatus.COMPLETED);

    CasePlanModel plan = blackboardRegistry.getOrCreate(caseId, "test-tenant");
    PlanItem completedItem = PlanItem.create(originalBindingName, ExecutorRef.of("worker-a"), 0);
    plan.addPlanItem(completedItem);
    completedItem.markRunning();
    completedItem.markCompleted();
  }

  // --- Dispatch tests ---

  @Test
  void dispatch_publishes_workerScheduleEvent() {
    setUpCompensableCase("step-a", "undo-step-a", "undo-cap");

    service.compensate(caseId, "operator", "testing compensation");

    ArgumentCaptor<WorkerScheduleEvent> captor = ArgumentCaptor.forClass(WorkerScheduleEvent.class);
    verify(eventBus).publish(eq(EventBusAddresses.WORKER_SCHEDULE), captor.capture());

    WorkerScheduleEvent event = captor.getValue();
    assertThat(event.bindingName()).isEqualTo("undo-step-a");
    assertThat(event.worker().name()).isEqualTo("undo-step-a-worker");
    assertThat(event.capability().name()).isEqualTo("undo-cap");
    assertThat(event.caseInstance()).isSameAs(instance);
  }

  @Test
  void dispatch_marks_planItem_dispatching() {
    setUpCompensableCase("step-a", "undo-step-a", "undo-cap");

    service.compensate(caseId, "operator", "testing");

    CasePlanModel plan = blackboardRegistry.getOrCreate(caseId, "test-tenant");
    PlanItem compensatingItem =
        plan.getAllPlanItems().stream().filter(PlanItem::isCompensation).findFirst().orElse(null);
    assertThat(compensatingItem).isNotNull();
    assertThat(compensatingItem.getStatus()).isEqualTo(TaskStatus.DISPATCHING);
  }

  @Test
  void dispatch_unsupported_target_faults() {
    io.casehub.engine.common.internal.model.CaseMetaModel metaModel =
        mock(io.casehub.engine.common.internal.model.CaseMetaModel.class);
    instance.setCaseMetaModel(metaModel);
    instance.setState(CaseStatus.COMPLETED);

    Binding original =
        Binding.builder()
            .name("step-a")
            .target(mock(io.casehub.api.model.JudgmentTarget.class))
            .on(new ContextChangeTrigger("."))
            .compensateRef("undo-step-a")
            .build();

    Binding compensating =
        Binding.builder()
            .name("undo-step-a")
            .target(mock(io.casehub.api.model.JudgmentTarget.class))
            .on(new ContextChangeTrigger("."))
            .compensation(true)
            .build();

    definition = mock(CaseDefinition.class);
    when(definition.getBindings()).thenReturn(List.of(original, compensating));
    when(definition.getWorkers()).thenReturn(List.of());
    when(caseDefinitionRegistry.getCaseDefinition(metaModel)).thenReturn(definition);

    CasePlanModel plan = blackboardRegistry.getOrCreate(caseId, "test-tenant");
    PlanItem completedItem = PlanItem.create("step-a", ExecutorRef.of("worker-a"), 0);
    plan.addPlanItem(completedItem);
    completedItem.markRunning();
    completedItem.markCompleted();

    service.compensate(caseId, "operator", "testing");

    ArgumentCaptor<CaseStatusChanged> captor = ArgumentCaptor.forClass(CaseStatusChanged.class);
    verify(eventBus, org.mockito.Mockito.atLeastOnce())
        .publish(eq(EventBusAddresses.CASE_STATUS_CHANGED), captor.capture());
    List<CaseStatusChanged> events = captor.getAllValues();
    CaseStatusChanged lastEvent = events.get(events.size() - 1);
    assertThat(lastEvent.newStatus()).isEqualTo(CaseStatus.COMPENSATION_FAULTED.name());
  }

  @Test
  void dispatch_judgmentTarget_calls_judgmentScheduler() {
    JudgmentScheduler mockScheduler = mock(JudgmentScheduler.class);
    when(judgmentSchedulerInstance.isResolvable()).thenReturn(true);
    when(judgmentSchedulerInstance.get()).thenReturn(mockScheduler);

    io.casehub.engine.common.internal.model.CaseMetaModel metaModel =
        mock(io.casehub.engine.common.internal.model.CaseMetaModel.class);
    instance.setCaseMetaModel(metaModel);
    instance.setState(CaseStatus.COMPLETED);

    io.casehub.api.model.JudgmentTarget jt =
        io.casehub.api.model.JudgmentTarget.builder()
            .prompt("Undo the review")
            .title("Undo review")
            .build();

    ContextChangeTrigger trigger = new ContextChangeTrigger(".");
    Binding original =
        Binding.builder()
            .name("step-a")
            .target(jt)
            .on(trigger)
            .compensateRef("undo-step-a")
            .build();
    Binding compensating =
        Binding.builder().name("undo-step-a").target(jt).on(trigger).compensation(true).build();

    definition = mock(CaseDefinition.class);
    when(definition.getBindings()).thenReturn(List.of(original, compensating));
    when(definition.getWorkers()).thenReturn(List.of());
    when(caseDefinitionRegistry.getCaseDefinition(metaModel)).thenReturn(definition);

    CasePlanModel plan = blackboardRegistry.getOrCreate(caseId, "test-tenant");
    PlanItem completedItem = PlanItem.create("step-a", ExecutorRef.of("worker-a"), 0, jt);
    plan.addPlanItem(completedItem);
    completedItem.markRunning();
    completedItem.markCompleted();

    service.compensate(caseId, "operator", "testing judgment dispatch");

    ArgumentCaptor<JudgmentScheduleRequest> captor =
        ArgumentCaptor.forClass(JudgmentScheduleRequest.class);
    verify(mockScheduler).schedule(captor.capture());
    JudgmentScheduleRequest request = captor.getValue();
    assertThat(request.caseId()).isEqualTo(caseId);
    assertThat(request.bindingName()).isEqualTo("undo-step-a");
    assertThat(request.target()).isSameAs(jt);
  }

  // --- Advancement tests ---

  @Test
  void advancement_completed_fires_next_step() {
    setUpCompensableCase("step-a", "undo-step-a", "undo-cap");
    instance.setState(CaseStatus.COMPENSATING);

    CasePlanModel plan = blackboardRegistry.getOrCreate(caseId, "test-tenant");
    PlanItem compensatingItem =
        PlanItem.create("undo-step-a", ExecutorRef.of("undo-step-a-worker"), 0);
    compensatingItem.setCompensation(true);
    String originalPlanItemId =
        plan.getAllPlanItems().stream()
            .filter(pi -> !pi.isCompensation())
            .findFirst()
            .map(PlanItem::getPlanItemId)
            .orElseThrow();
    compensatingItem.setCompensatesItemId(originalPlanItemId);
    plan.addPlanItem(compensatingItem);
    compensatingItem.markRunning();
    compensatingItem.markCompleted();

    PlanItemStateChangedEvent event =
        new PlanItemStateChangedEvent(
            caseId,
            compensatingItem.getPlanItemId(),
            "undo-step-a",
            TaskStatus.RUNNING,
            TaskStatus.COMPLETED,
            "test-tenant");

    service.onCompensationPlanItemStateChanged(event);

    // No remaining compensable items → should transition to COMPENSATED
    ArgumentCaptor<CaseStatusChanged> captor = ArgumentCaptor.forClass(CaseStatusChanged.class);
    verify(eventBus).publish(eq(EventBusAddresses.CASE_STATUS_CHANGED), captor.capture());
    CaseStatusChanged statusChange = captor.getValue();
    assertThat(statusChange.newStatus()).isEqualTo(CaseStatus.COMPENSATED.name());
  }

  @Test
  void advancement_faulted_transitions_to_compensation_faulted() {
    setUpCompensableCase("step-a", "undo-step-a", "undo-cap");
    instance.setState(CaseStatus.COMPENSATING);

    CasePlanModel plan = blackboardRegistry.getOrCreate(caseId, "test-tenant");
    PlanItem compensatingItem =
        PlanItem.create("undo-step-a", ExecutorRef.of("undo-step-a-worker"), 0);
    compensatingItem.setCompensation(true);
    plan.addPlanItem(compensatingItem);

    PlanItemStateChangedEvent event =
        new PlanItemStateChangedEvent(
            caseId,
            compensatingItem.getPlanItemId(),
            "undo-step-a",
            TaskStatus.RUNNING,
            TaskStatus.FAULTED,
            "test-tenant");

    service.onCompensationPlanItemStateChanged(event);

    ArgumentCaptor<CaseStatusChanged> captor = ArgumentCaptor.forClass(CaseStatusChanged.class);
    verify(eventBus).publish(eq(EventBusAddresses.CASE_STATUS_CHANGED), captor.capture());
    assertThat(captor.getValue().newStatus()).isEqualTo(CaseStatus.COMPENSATION_FAULTED.name());
  }

  @Test
  void advancement_ignores_non_compensation_planItems() {
    setUpCompensableCase("step-a", "undo-step-a", "undo-cap");
    instance.setState(CaseStatus.COMPENSATING);

    CasePlanModel plan = blackboardRegistry.getOrCreate(caseId, "test-tenant");
    PlanItem normalItem =
        plan.getAllPlanItems().stream()
            .filter(pi -> !pi.isCompensation())
            .findFirst()
            .orElseThrow();

    PlanItemStateChangedEvent event =
        new PlanItemStateChangedEvent(
            caseId,
            normalItem.getPlanItemId(),
            "step-a",
            TaskStatus.RUNNING,
            TaskStatus.COMPLETED,
            "test-tenant");

    service.onCompensationPlanItemStateChanged(event);

    verify(eventBus, never()).publish(eq(EventBusAddresses.CASE_STATUS_CHANGED), any());
  }

  @Test
  void advancement_ignores_when_not_compensating() {
    setUpCompensableCase("step-a", "undo-step-a", "undo-cap");
    instance.setState(CaseStatus.RUNNING);

    CasePlanModel plan = blackboardRegistry.getOrCreate(caseId, "test-tenant");
    PlanItem compensatingItem =
        PlanItem.create("undo-step-a", ExecutorRef.of("undo-step-a-worker"), 0);
    compensatingItem.setCompensation(true);
    plan.addPlanItem(compensatingItem);

    PlanItemStateChangedEvent event =
        new PlanItemStateChangedEvent(
            caseId,
            compensatingItem.getPlanItemId(),
            "undo-step-a",
            TaskStatus.RUNNING,
            TaskStatus.COMPLETED,
            "test-tenant");

    service.onCompensationPlanItemStateChanged(event);

    verify(eventBus, never()).publish(eq(EventBusAddresses.CASE_STATUS_CHANGED), any());
  }

  @Test
  void full_saga_two_steps_compensated_in_reverse_order() {
    Worker worker = testWorker("undo-worker", "undo-cap");
    Capability capability = Capability.of("undo-cap", "", "");
    CapabilityTarget target = new CapabilityTarget(capability);

    ContextChangeTrigger trigger = new ContextChangeTrigger(".");
    Binding stepA =
        Binding.builder()
            .name("step-a")
            .target(target)
            .on(trigger)
            .produces("step-a-output")
            .compensateRef("undo-a")
            .build();
    Binding stepB =
        Binding.builder()
            .name("step-b")
            .target(target)
            .on(trigger)
            .consumes("step-a-output")
            .compensateRef("undo-b")
            .build();
    Binding undoA =
        Binding.builder().name("undo-a").target(target).on(trigger).compensation(true).build();
    Binding undoB =
        Binding.builder().name("undo-b").target(target).on(trigger).compensation(true).build();

    definition = mock(CaseDefinition.class);
    when(definition.getBindings()).thenReturn(List.of(stepA, stepB, undoA, undoB));
    when(definition.getWorkers()).thenReturn(List.of(worker));

    io.casehub.engine.common.internal.model.CaseMetaModel metaModel =
        mock(io.casehub.engine.common.internal.model.CaseMetaModel.class);
    instance.setCaseMetaModel(metaModel);
    when(caseDefinitionRegistry.getCaseDefinition(metaModel)).thenReturn(definition);

    instance.setState(CaseStatus.COMPLETED);

    CasePlanModel plan = blackboardRegistry.getOrCreate(caseId, "test-tenant");
    PlanItem itemA = PlanItem.create("step-a", ExecutorRef.of("worker-a"), 0);
    plan.addPlanItem(itemA);
    itemA.markRunning();
    itemA.markCompleted();

    PlanItem itemB = PlanItem.create("step-b", ExecutorRef.of("worker-b"), 0);
    plan.addPlanItem(itemB);
    itemB.markRunning();
    itemB.markCompleted();

    // Trigger compensation
    service.compensate(caseId, "operator", "full saga test");

    // First compensation step should be undo-b (reverse topo order: B depends on A → undo B first)
    ArgumentCaptor<WorkerScheduleEvent> scheduleCaptor =
        ArgumentCaptor.forClass(WorkerScheduleEvent.class);
    verify(eventBus).publish(eq(EventBusAddresses.WORKER_SCHEDULE), scheduleCaptor.capture());
    assertThat(scheduleCaptor.getValue().bindingName()).isEqualTo("undo-b");

    // Simulate undo-b completing
    PlanItem undoBItem =
        plan.getAllPlanItems().stream()
            .filter(pi -> pi.isCompensation() && "undo-b".equals(pi.getBindingName()))
            .findFirst()
            .orElseThrow();
    undoBItem.markDelegated();
    undoBItem.markCompleted();

    instance.setState(CaseStatus.COMPENSATING);

    PlanItemStateChangedEvent completedEvent =
        new PlanItemStateChangedEvent(
            caseId,
            undoBItem.getPlanItemId(),
            "undo-b",
            TaskStatus.DELEGATED,
            TaskStatus.COMPLETED,
            "test-tenant");

    service.onCompensationPlanItemStateChanged(completedEvent);

    // Second compensation step should be undo-a
    verify(eventBus, org.mockito.Mockito.times(2))
        .publish(eq(EventBusAddresses.WORKER_SCHEDULE), scheduleCaptor.capture());
    WorkerScheduleEvent secondDispatch = scheduleCaptor.getValue();
    assertThat(secondDispatch.bindingName()).isEqualTo("undo-a");
  }
}
