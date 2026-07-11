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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.api.context.PropagationContext;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ExecutorRef;
import io.casehub.api.model.SubCase;
import io.casehub.api.model.TaskStatus;
import io.casehub.blackboard.plan.DefaultCasePlanModel;
import io.casehub.blackboard.plan.PlanItem;
import io.casehub.blackboard.registry.BlackboardRegistry;
import io.casehub.engine.common.internal.event.SubCaseScheduleEvent;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.internal.model.SubCaseGroup;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.ReactiveCaseInstanceRepository;
import io.casehub.engine.common.spi.ReactiveEventLogRepository;
import io.casehub.engine.common.spi.ReactiveSubCaseGroupRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.internal.engine.cache.CaseInstanceCacheImpl;
import io.casehub.engine.internal.work.PendingWorkRegistry;
import io.smallrye.mutiny.Uni;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
    ReactiveCaseInstanceRepository instanceRepository = mock(ReactiveCaseInstanceRepository.class);
    ReactiveEventLogRepository reactiveEventLogRepository = mock(ReactiveEventLogRepository.class);
    PendingWorkRegistry pendingWorkRegistry = mock(PendingWorkRegistry.class);
    ReactiveSubCaseGroupRepository reactiveSubCaseGroupRepository =
        mock(ReactiveSubCaseGroupRepository.class);

    // EventLogRepository returns successful Uni for all append calls
    when(reactiveEventLogRepository.append(any(), any())).thenReturn(Uni.createFrom().voidItem());

    // CaseDefinitionRegistry returns a non-null definition by default
    when(definitionRegistry.getCaseDefinition(any()))
        .thenReturn(mock(io.casehub.api.model.CaseDefinition.class));

    // CaseInstanceRepository returns Uni for updateStateAndAppendEvent
    when(instanceRepository.updateStateAndAppendEvent(any(), any(), any()))
        .thenReturn(Uni.createFrom().nullItem());

    // SubCaseGroupRepository: stub grouped path Uni methods
    SubCaseGroup stubGroup = mock(SubCaseGroup.class);
    when(reactiveSubCaseGroupRepository.getOrCreate(any(), any(), anyInt(), anyInt(), any(), any()))
        .thenReturn(Uni.createFrom().item(stubGroup));
    when(reactiveSubCaseGroupRepository.registerChild(any(), any(), any(), any()))
        .thenReturn(Uni.createFrom().item(stubGroup));

    caseInstanceCache = new CaseInstanceCacheImpl();

    handler =
        new SubCaseExecutionHandler(
            caseHubRuntime,
            definitionRegistry,
            instanceRepository,
            reactiveEventLogRepository,
            pendingWorkRegistry,
            reactiveSubCaseGroupRepository,
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
    return new SubCaseScheduleEvent(parentInstance(parentCaseId), subCase, Map.of(), bindingName);
  }

  @Test
  void subcase_spawn_marks_plan_item_delegated() {
    PlanItem item = PlanItem.create("spawn-child", ExecutorRef.of("unknown"), 0);
    plan.addPlanItem(item);

    UUID childId = UUID.randomUUID();
    when(caseHubRuntime.startCase(any(), any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(childId));

    handler.onSubCaseSchedule(eventFor("spawn-child", true)).await().indefinitely();

    assertThat(item.getStatus()).isEqualTo(TaskStatus.DELEGATED);
  }

  @Test
  void subcase_spawn_indexes_child_case_id_for_completion_routing() {
    PlanItem item = PlanItem.create("spawn-child", ExecutorRef.of("unknown"), 0);
    plan.addPlanItem(item);

    UUID childId = UUID.randomUUID();
    when(caseHubRuntime.startCase(any(), any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(childId));

    handler.onSubCaseSchedule(eventFor("spawn-child", true)).await().indefinitely();

    assertThat(registry.getPlanItemId(parentCaseId, childId.toString()))
        .as("child case ID must be indexed so SubCaseCompletionService can route completion")
        .contains(item.getPlanItemId());
  }

  @Test
  void fire_and_forget_subcase_marks_plan_item_completed_immediately() {
    PlanItem item = PlanItem.create("spawn-fire-forget", ExecutorRef.of("unknown"), 0);
    plan.addPlanItem(item);

    UUID childId = UUID.randomUUID();
    when(caseHubRuntime.startCase(any(), any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(childId));

    handler.onSubCaseSchedule(eventFor("spawn-fire-forget", false)).await().indefinitely();

    assertThat(item.getStatus())
        .as("fire-and-forget: plan item must be COMPLETED once child is spawned")
        .isEqualTo(TaskStatus.COMPLETED);
  }

  @Test
  void circular_dependency_marks_plan_item_faulted() {
    PlanItem item = PlanItem.create("spawn-child", ExecutorRef.of("unknown"), 0);
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
        new SubCaseScheduleEvent(parent, selfReference, Map.of(), "spawn-child");

    handler.onSubCaseSchedule(event).await().indefinitely();

    assertThat(item.getStatus())
        .as("circular dependency must fault the PlanItem so the engine does not hang indefinitely")
        .isEqualTo(TaskStatus.FAULTED);
  }

  @Test
  void startCase_failure_marks_plan_item_faulted() {
    PlanItem item = PlanItem.create("spawn-child", ExecutorRef.of("unknown"), 0);
    plan.addPlanItem(item);

    when(caseHubRuntime.startCase(any(), any(), any(), any()))
        .thenThrow(new RuntimeException("engine unavailable"));

    handler.onSubCaseSchedule(eventFor("spawn-child", true)).await().indefinitely();

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
    freshPlan.addPlanItem(item);

    CaseDefinitionRegistry nullDefRegistry = mock(CaseDefinitionRegistry.class);
    when(nullDefRegistry.getCaseDefinition(any())).thenReturn(null);
    SubCaseExecutionHandler handlerWithNullDef =
        new SubCaseExecutionHandler(
            caseHubRuntime,
            nullDefRegistry,
            mock(ReactiveCaseInstanceRepository.class),
            mock(ReactiveEventLogRepository.class),
            mock(PendingWorkRegistry.class),
            mock(ReactiveSubCaseGroupRepository.class),
            freshRegistry,
            new CaseInstanceCacheImpl());

    handlerWithNullDef.onSubCaseSchedule(eventFor("spawn-child", true)).await().indefinitely();

    assertThat(item.getStatus())
        .as("missing CaseDefinition must fault the PlanItem")
        .isEqualTo(TaskStatus.FAULTED);
  }

  @Test
  void mofn_second_spawn_does_not_re_mark_delegated_but_indexes() {
    PlanItem item = PlanItem.create("spawn-group", ExecutorRef.of("unknown"), 0);
    plan.addPlanItem(item);
    item.markDelegated(); // first spawn already marked it DELEGATED

    UUID child2 = UUID.randomUUID();
    when(caseHubRuntime.startCase(any(), any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(child2));

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
            parentInstance(parentCaseId), groupedSubCase, Map.of(), "spawn-group");

    // Should not throw even though PlanItem is already DELEGATED
    handler.onSubCaseSchedule(event).await().indefinitely();

    // Still DELEGATED (not double-marked)
    assertThat(item.getStatus()).isEqualTo(TaskStatus.DELEGATED);
    // But child2 is indexed
    assertThat(registry.getPlanItemId(parentCaseId, child2.toString()))
        .contains(item.getPlanItemId());
  }
}
