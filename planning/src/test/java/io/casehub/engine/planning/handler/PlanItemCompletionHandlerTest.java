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
package io.casehub.engine.planning.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.api.model.ExecutorRef;
import io.casehub.api.model.TaskStatus;
import io.casehub.engine.common.internal.event.WorkflowExecutionCompleted;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.planning.event.BlackboardEventBusAddresses;
import io.casehub.engine.planning.event.SubCaseExecutionCompleted;
import io.casehub.engine.planning.plan.DefaultCasePlanModel;
import io.casehub.engine.planning.plan.PlanItem;
import io.casehub.engine.planning.plan.PlanItemDefinition;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.vertx.mutiny.core.eventbus.EventBus;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlanItemCompletionHandlerTest {

  private BlackboardRegistry registry;
  private EventBus mockBus;
  private PlanItemCompletionHandler handler;
  private UUID caseId;
  private DefaultCasePlanModel plan;

  @BeforeEach
  void setUp() {
    registry = new BlackboardRegistry();
    mockBus = mock(EventBus.class);
    handler =
        new PlanItemCompletionHandler(
            registry,
            mockBus,
            mock(jakarta.enterprise.event.Event.class),
            new CompoundCompletionEvaluator(mockBus));
    caseId = UUID.randomUUID();
    plan = (DefaultCasePlanModel) registry.getOrCreate(caseId, "test-tenant");
  }

  private WorkflowExecutionCompleted eventFor(String workerName) {
    CaseInstance instance = mock(CaseInstance.class);
    when(instance.getUuid()).thenReturn(caseId);
    Worker worker =
        Worker.builder()
            .name(workerName)
            .capabilityName("cap")
            .function(
                new WorkerFunction.Sync<>(
                    Map.class, Map.class, (i, scope) -> WorkerResult.of(Map.of())))
            .build();
    return WorkflowExecutionCompleted.approved(instance, worker, "idempotency-key", Map.of(), null);
  }

  @Test
  void marks_plan_item_completed_on_worker_finish() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("worker-a"), 0);
    plan.addPlanItem(item);
    item.markRunning();
    registry.indexForCompletion(caseId, "worker-a", item.getPlanItemId());

    handler.onWorkerFinished(eventFor("worker-a"));

    assertThat(item.getStatus()).isEqualTo(TaskStatus.COMPLETED);
  }

  @Test
  void unknown_worker_does_not_throw() {
    handler.onWorkerFinished(eventFor("unknown-worker"));
  }

  @Test
  void compound_completes_when_all_scoped_bindings_done() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("worker-a"), 0);
    plan.addPlanItem(item);
    item.markRunning();
    registry.indexForCompletion(caseId, "worker-a", item.getPlanItemId());

    var compound = PlanItemDefinition.Compound.builder("intake").binding("binding-a").build();
    plan.registerDefinition(compound);
    plan.tryDefinitionTransition(compound.id(), TaskStatus.PENDING, TaskStatus.RUNNING);

    handler.onWorkerFinished(eventFor("worker-a"));

    assertThat(plan.getDefinitionStatus(compound.id())).isEqualTo(TaskStatus.COMPLETED);
    verify(mockBus).publish(eq(BlackboardEventBusAddresses.COMPOUND_COMPLETED), any());
  }

  @Test
  void compound_does_not_complete_when_not_all_scoped_bindings_done() {
    PlanItem item1 = PlanItem.create("binding-a", ExecutorRef.of("worker-a"), 0);
    PlanItem item2 = PlanItem.create("binding-b", ExecutorRef.of("worker-b"), 0);
    plan.addPlanItem(item1);
    plan.addPlanItem(item2);
    item1.markRunning();
    registry.indexForCompletion(caseId, "worker-a", item1.getPlanItemId());

    var compound =
        PlanItemDefinition.Compound.builder("intake")
            .binding("binding-a")
            .binding("binding-b")
            .build();
    plan.registerDefinition(compound);
    plan.tryDefinitionTransition(compound.id(), TaskStatus.PENDING, TaskStatus.RUNNING);

    handler.onWorkerFinished(eventFor("worker-a"));

    assertThat(plan.getDefinitionStatus(compound.id())).isEqualTo(TaskStatus.RUNNING);
  }

  @Test
  void completed_plan_item_is_removed_from_active_tracking() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("worker-a"), 0);
    plan.addPlanItem(item);
    item.markRunning();
    registry.indexForCompletion(caseId, "worker-a", item.getPlanItemId());

    handler.onWorkerFinished(eventFor("worker-a"));

    assertThat(plan.hasActivePlanItem("binding-a"))
        .as("completed PlanItem must be removed from active tracking")
        .isFalse();
  }

  @Test
  void unscoped_plan_item_completion_does_not_affect_compound() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("worker-a"), 0);
    plan.addPlanItem(item);
    item.markRunning();
    registry.indexForCompletion(caseId, "worker-a", item.getPlanItemId());

    var compound =
        PlanItemDefinition.Compound.builder("intake").binding("unrelated-binding").build();
    plan.registerDefinition(compound);
    plan.tryDefinitionTransition(compound.id(), TaskStatus.PENDING, TaskStatus.RUNNING);

    handler.onWorkerFinished(eventFor("worker-a"));

    assertThat(plan.getDefinitionStatus(compound.id()))
        .as("compound must not complete when unrelated binding completes")
        .isEqualTo(TaskStatus.RUNNING);
  }

  @Test
  void compound_without_scoped_bindings_does_not_complete() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("worker-a"), 0);
    plan.addPlanItem(item);
    item.markRunning();
    registry.indexForCompletion(caseId, "worker-a", item.getPlanItemId());

    var compound = PlanItemDefinition.Compound.builder("intake").build();
    plan.registerDefinition(compound);
    plan.tryDefinitionTransition(compound.id(), TaskStatus.PENDING, TaskStatus.RUNNING);

    handler.onWorkerFinished(eventFor("worker-a"));

    assertThat(plan.getDefinitionStatus(compound.id())).isEqualTo(TaskStatus.RUNNING);
  }

  @Test
  void marks_subcase_plan_item_completed_on_subcase_execution_completed() {
    PlanItem item = PlanItem.create("subcase-binding", ExecutorRef.of("unknown"), 0);
    plan.addPlanItem(item);
    item.markDelegated();
    UUID childCaseId = UUID.randomUUID();
    registry.indexForCompletion(caseId, childCaseId.toString(), item.getPlanItemId());

    handler.onSubCaseFinished(new SubCaseExecutionCompleted(caseId, childCaseId, "test-tenant"));

    assertThat(item.getStatus()).isEqualTo(TaskStatus.COMPLETED);
  }

  @Test
  void subcase_completion_triggers_compound_completion() {
    PlanItem item = PlanItem.create("subcase-binding", ExecutorRef.of("unknown"), 0);
    plan.addPlanItem(item);
    item.markDelegated();
    UUID childCaseId = UUID.randomUUID();
    registry.indexForCompletion(caseId, childCaseId.toString(), item.getPlanItemId());

    var compound = PlanItemDefinition.Compound.builder("intake").binding("subcase-binding").build();
    plan.registerDefinition(compound);
    plan.tryDefinitionTransition(compound.id(), TaskStatus.PENDING, TaskStatus.RUNNING);

    handler.onSubCaseFinished(new SubCaseExecutionCompleted(caseId, childCaseId, "test-tenant"));

    assertThat(plan.getDefinitionStatus(compound.id())).isEqualTo(TaskStatus.COMPLETED);
    verify(mockBus).publish(eq(BlackboardEventBusAddresses.COMPOUND_COMPLETED), any());
  }

  @Test
  void subcase_completion_unknown_tracking_key_does_not_throw() {
    handler.onSubCaseFinished(
        new SubCaseExecutionCompleted(caseId, UUID.randomUUID(), "test-tenant"));
  }

  @Test
  void mofn_grouped_subcase_any_child_routes_to_same_plan_item() {
    PlanItem item = PlanItem.create("subcase-group", ExecutorRef.of("unknown"), 0);
    plan.addPlanItem(item);
    item.markDelegated();
    UUID child1 = UUID.randomUUID();
    UUID child2 = UUID.randomUUID();
    registry.indexForCompletion(caseId, child1.toString(), item.getPlanItemId());
    registry.indexForCompletion(caseId, child2.toString(), item.getPlanItemId());

    handler.onSubCaseFinished(new SubCaseExecutionCompleted(caseId, child2, "test-tenant"));

    assertThat(item.getStatus()).isEqualTo(TaskStatus.COMPLETED);
  }

  @Test
  void binding_name_lookup_path_marks_plan_item_completed() {
    PlanItem item = PlanItem.create("action-gate-handler", ExecutorRef.of("worker-x"), 0);
    plan.addPlanItem(item);
    item.markRunning();

    CaseInstance instance = mock(CaseInstance.class);
    when(instance.getUuid()).thenReturn(caseId);
    Worker worker =
        Worker.builder()
            .name("worker-x")
            .capabilityName("cap")
            .function(
                new WorkerFunction.Sync<>(
                    Map.class, Map.class, (i, scope) -> WorkerResult.of(Map.of())))
            .build();
    WorkflowExecutionCompleted event =
        WorkflowExecutionCompleted.approved(
            instance, worker, "idempotency-key", Map.of(), "action-gate-handler");

    handler.onWorkerFinished(event);

    assertThat(item.getStatus()).isEqualTo(TaskStatus.COMPLETED);
  }
}
