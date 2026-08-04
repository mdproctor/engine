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
package io.casehub.engine.planning.subcase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.api.context.PropagationContext;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ExecutorRef;
import io.casehub.api.model.SubCase;
import io.casehub.api.model.TaskStatus;
import io.casehub.engine.common.internal.event.SubCaseScheduleEvent;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.internal.model.SubCaseGroup;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.SubCaseGroupRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.internal.engine.cache.CaseInstanceCacheImpl;
import io.casehub.engine.internal.work.PendingWorkRegistry;
import io.casehub.engine.planning.plan.DefaultCasePlanModel;
import io.casehub.engine.planning.plan.PlanItem;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for SubCaseExecutionHandler PlanItem lifecycle.
 *
 * <p>Verifies PENDING → DELEGATED transition, completion indexing, error path faulting, and
 * fire-and-forget immediate completion. See casehubio/engine#322.
 */
class SubCaseExecutionHandlerTest {

  private BlackboardRegistry registry;
  private CaseHubRuntime caseHubRuntime;
  private SubCaseExecutionHandler handler;
  private UUID parentCaseId;
  private DefaultCasePlanModel plan;
  private CaseInstanceCache caseInstanceCache;

  @BeforeEach
  void setUp() {
    registry = new BlackboardRegistry();
    caseHubRuntime = mock(CaseHubRuntime.class);
    CaseDefinitionRegistry definitionRegistry = mock(CaseDefinitionRegistry.class);
    CaseInstanceRepository instanceRepository = mock(CaseInstanceRepository.class);
    EventLogRepository eventLogRepository = mock(EventLogRepository.class);
    PendingWorkRegistry pendingWorkRegistry = mock(PendingWorkRegistry.class);
    SubCaseGroupRepository subCaseGroupRepository = mock(SubCaseGroupRepository.class);

    // CaseDefinitionRegistry returns a non-null definition by default
    when(definitionRegistry.getCaseDefinition(any()))
        .thenReturn(mock(io.casehub.api.model.CaseDefinition.class));

    // SubCaseGroupRepository: stub grouped path methods
    SubCaseGroup stubGroup = mock(SubCaseGroup.class);
    when(subCaseGroupRepository.getOrCreate(any(), any(), anyInt(), anyInt(), any(), any()))
        .thenReturn(stubGroup);
    when(subCaseGroupRepository.registerChild(any(), any(), any(), any())).thenReturn(stubGroup);

    caseInstanceCache = new CaseInstanceCacheImpl();

    handler =
        new SubCaseExecutionHandler(
            caseHubRuntime,
            definitionRegistry,
            instanceRepository,
            eventLogRepository,
            pendingWorkRegistry,
            subCaseGroupRepository,
            registry,
            caseInstanceCache);

    parentCaseId = UUID.randomUUID();
    plan = (DefaultCasePlanModel) registry.getOrCreate(parentCaseId, "test-tenant");
  }

  private CaseInstance parentInstance(UUID id) {
    CaseInstance instance = mock(CaseInstance.class);
    when(instance.getUuid()).thenReturn(id);
    when(instance.getState()).thenReturn(CaseStatus.RUNNING);
    when(instance.getPropagationContext()).thenReturn(PropagationContext.createRoot());
    return instance;
  }

  private SubCaseScheduleEvent eventFor(String bindingName, boolean waitForCompletion) {
    SubCase subCase =
        SubCase.builder()
            .namespace("test")
            .name("child-case")
            .version("1.0.0")
            .waitForCompletion(waitForCompletion)
            .build();
    return new SubCaseScheduleEvent(
        parentInstance(parentCaseId), subCase, Map.of(), null, bindingName);
  }

  @Test
  void subcase_spawn_marks_plan_item_delegated() {
    PlanItem item = PlanItem.create("spawn-child", ExecutorRef.of("unknown"), 0);
    assertThat(item.tryMarkDispatching()).isTrue();
    plan.addPlanItem(item);

    UUID childId = UUID.randomUUID();
    when(caseHubRuntime.startCase(any(), any(), any(), any())).thenReturn(childId);

    handler.onSubCaseSchedule(eventFor("spawn-child", true));

    assertThat(item.getStatus()).isEqualTo(TaskStatus.DELEGATED);
  }

  @Test
  void subcase_spawn_indexes_child_case_id_for_completion_routing() {
    PlanItem item = PlanItem.create("spawn-child", ExecutorRef.of("unknown"), 0);
    assertThat(item.tryMarkDispatching()).isTrue();
    plan.addPlanItem(item);

    UUID childId = UUID.randomUUID();
    when(caseHubRuntime.startCase(any(), any(), any(), any())).thenReturn(childId);

    handler.onSubCaseSchedule(eventFor("spawn-child", true));

    assertThat(registry.getPlanItemId(parentCaseId, childId.toString()))
        .as("child case ID must be indexed so SubCaseCompletionService can route completion")
        .contains(item.getPlanItemId());
  }

  @Test
  void fire_and_forget_subcase_marks_plan_item_completed_immediately() {
    PlanItem item = PlanItem.create("spawn-fire-forget", ExecutorRef.of("unknown"), 0);
    assertThat(item.tryMarkDispatching()).isTrue();
    plan.addPlanItem(item);

    UUID childId = UUID.randomUUID();
    when(caseHubRuntime.startCase(any(), any(), any(), any())).thenReturn(childId);

    handler.onSubCaseSchedule(eventFor("spawn-fire-forget", false));

    assertThat(item.getStatus())
        .as("fire-and-forget: plan item must be COMPLETED once child is spawned")
        .isEqualTo(TaskStatus.COMPLETED);
  }

  @Test
  void circular_dependency_marks_plan_item_faulted() {
    PlanItem item = PlanItem.create("spawn-child", ExecutorRef.of("unknown"), 0);
    assertThat(item.tryMarkDispatching()).isTrue();
    plan.addPlanItem(item);

    // Parent meta matches child definition — circular dependency detected
    CaseInstance parent = parentInstance(parentCaseId);
    CaseMetaModel parentMeta = new CaseMetaModel();
    parentMeta.setNamespace("test");
    parentMeta.setName("child-case");
    parentMeta.setVersion("1.0.0");
    when(parent.getCaseMetaModel()).thenReturn(parentMeta);

    SubCase selfReference =
        SubCase.builder().namespace("test").name("child-case").version("1.0.0").build();
    SubCaseScheduleEvent event =
        new SubCaseScheduleEvent(parent, selfReference, Map.of(), null, "spawn-child");

    handler.onSubCaseSchedule(event);

    assertThat(item.getStatus())
        .as("circular dependency must fault the PlanItem so the engine does not hang indefinitely")
        .isEqualTo(TaskStatus.FAULTED);
  }

  @Test
  void startCase_failure_marks_plan_item_faulted() {
    PlanItem item = PlanItem.create("spawn-child", ExecutorRef.of("unknown"), 0);
    assertThat(item.tryMarkDispatching()).isTrue();
    plan.addPlanItem(item);

    when(caseHubRuntime.startCase(any(), any(), any(), any()))
        .thenThrow(new RuntimeException("engine unavailable"));

    handler.onSubCaseSchedule(eventFor("spawn-child", true));

    assertThat(item.getStatus())
        .as("startCase failure must fault the PlanItem so the binding can be re-evaluated")
        .isEqualTo(TaskStatus.FAULTED);
  }

  @Test
  void no_case_definition_marks_plan_item_faulted() {
    BlackboardRegistry freshRegistry = new BlackboardRegistry();
    DefaultCasePlanModel freshPlan =
        (DefaultCasePlanModel) freshRegistry.getOrCreate(parentCaseId, "test-tenant");
    PlanItem item = PlanItem.create("spawn-child", ExecutorRef.of("unknown"), 0);
    assertThat(item.tryMarkDispatching()).isTrue();
    freshPlan.addPlanItem(item);

    CaseDefinitionRegistry nullDefRegistry = mock(CaseDefinitionRegistry.class);
    when(nullDefRegistry.getCaseDefinition(any())).thenReturn(null);
    SubCaseExecutionHandler handlerWithNullDef =
        new SubCaseExecutionHandler(
            caseHubRuntime,
            nullDefRegistry,
            mock(CaseInstanceRepository.class),
            mock(EventLogRepository.class),
            mock(PendingWorkRegistry.class),
            mock(SubCaseGroupRepository.class),
            freshRegistry,
            new CaseInstanceCacheImpl());

    handlerWithNullDef.onSubCaseSchedule(eventFor("spawn-child", true));

    assertThat(item.getStatus())
        .as("missing CaseDefinition must fault the PlanItem")
        .isEqualTo(TaskStatus.FAULTED);
  }

  @Test
  void mofn_second_spawn_does_not_re_mark_delegated_but_indexes() {
    PlanItem item = PlanItem.create("spawn-group", ExecutorRef.of("unknown"), 0);
    plan.addPlanItem(item);
    assertThat(item.tryMarkDispatching()).isTrue();
    item.markDelegated(); // first spawn already marked it DELEGATED

    UUID child2 = UUID.randomUUID();
    when(caseHubRuntime.startCase(any(), any(), any(), any())).thenReturn(child2);

    SubCase groupedSubCase =
        SubCase.builder()
            .namespace("test")
            .name("child-case")
            .version("1.0.0")
            .waitForCompletion(true)
            .groupId("group-1")
            .totalInGroup(2)
            .build();
    SubCaseScheduleEvent event =
        new SubCaseScheduleEvent(
            parentInstance(parentCaseId), groupedSubCase, Map.of(), null, "spawn-group");

    // Should not throw even though PlanItem is already DELEGATED
    handler.onSubCaseSchedule(event);

    // Still DELEGATED (not double-marked)
    assertThat(item.getStatus()).isEqualTo(TaskStatus.DELEGATED);
    // But child2 is indexed
    assertThat(registry.getPlanItemId(parentCaseId, child2.toString()))
        .contains(item.getPlanItemId());
  }
}
