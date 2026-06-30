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
package io.casehub.blackboard.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.casehub.blackboard.event.BlackboardEventBusAddresses;
import io.casehub.blackboard.event.SubCaseExecutionCompleted;
import io.casehub.blackboard.plan.DefaultCasePlanModel;
import io.casehub.blackboard.plan.PlanItem;
import io.casehub.blackboard.registry.BlackboardRegistry;
import io.casehub.blackboard.stage.Stage;
import io.casehub.engine.common.internal.event.WorkflowExecutionCompleted;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.PlanItemStatus;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.vertx.mutiny.core.eventbus.EventBus;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for PlanItemCompletionHandler — marks PlanItems COMPLETED and triggers Stage
 * autocomplete. See casehubio/engine#76.
 */
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
            new StageAutocompleteEvaluator(mockBus));
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
            .function(new WorkerFunction.Sync(i -> WorkerResult.of(Map.of())))
            .build();
    return WorkflowExecutionCompleted.approved(instance, worker, "idempotency-key", Map.of(), null);
  }

  @Test
  void marks_plan_item_completed_on_worker_finish() {
    PlanItem item = PlanItem.create("binding-a", "worker-a", 0);
    plan.addPlanItem(item);
    item.markRunning(); // simulates indexSelectedForCompletion in PlanningStrategyLoopControl
    registry.indexForCompletion(caseId, "worker-a", item.getPlanItemId());

    handler.onWorkerFinished(eventFor("worker-a"));

    assertThat(item.getStatus()).isEqualTo(PlanItemStatus.COMPLETED);
  }

  @Test
  void unknown_worker_does_not_throw() {
    handler.onWorkerFinished(eventFor("unknown-worker"));
  }

  @Test
  void stage_autocompletes_when_all_required_items_done() {
    PlanItem item = PlanItem.create("binding-a", "worker-a", 0);
    plan.addPlanItem(item);
    item.markRunning(); // simulates indexSelectedForCompletion in PlanningStrategyLoopControl
    registry.indexForCompletion(caseId, "worker-a", item.getPlanItemId());

    Stage stage = Stage.alwaysActivate("intake");
    stage.addPlanItem(item.getPlanItemId());
    stage.addRequiredItem(item.getPlanItemId());
    stage.activate();
    plan.addStage(stage);

    handler.onWorkerFinished(eventFor("worker-a"));

    assertThat(stage.isTerminal()).isTrue();
    verify(mockBus).publish(eq(BlackboardEventBusAddresses.STAGE_COMPLETED), any());
  }

  @Test
  void stage_does_not_autocomplete_when_not_all_required_done() {
    PlanItem item1 = PlanItem.create("binding-a", "worker-a", 0);
    PlanItem item2 = PlanItem.create("binding-b", "worker-b", 0);
    plan.addPlanItem(item1);
    plan.addPlanItem(item2);
    item1.markRunning(); // simulates indexSelectedForCompletion in PlanningStrategyLoopControl
    registry.indexForCompletion(caseId, "worker-a", item1.getPlanItemId());

    Stage stage = Stage.alwaysActivate("intake");
    stage.addPlanItem(item1.getPlanItemId());
    stage.addPlanItem(item2.getPlanItemId());
    stage.addRequiredItem(item1.getPlanItemId());
    stage.addRequiredItem(item2.getPlanItemId());
    stage.activate();
    plan.addStage(stage);

    handler.onWorkerFinished(eventFor("worker-a"));

    assertThat(stage.isTerminal()).isFalse();
    verifyNoInteractions(mockBus);
  }

  @Test
  void completed_plan_item_is_removed_from_active_tracking() {
    PlanItem item = PlanItem.create("binding-a", "worker-a", 0);
    plan.addPlanItem(item);
    item.markRunning(); // simulates indexSelectedForCompletion in PlanningStrategyLoopControl
    registry.indexForCompletion(caseId, "worker-a", item.getPlanItemId());

    handler.onWorkerFinished(eventFor("worker-a"));

    assertThat(plan.hasActivePlanItem("binding-a"))
        .as("completed PlanItem must be removed from active tracking")
        .isFalse();
  }

  @Test
  void autocomplete_with_unregistered_required_item_does_not_complete_stage() {
    PlanItem item = PlanItem.create("binding-a", "worker-a", 0);
    plan.addPlanItem(item);
    item.markRunning(); // simulates indexSelectedForCompletion in PlanningStrategyLoopControl
    registry.indexForCompletion(caseId, "worker-a", item.getPlanItemId());

    Stage stage = Stage.alwaysActivate("intake");
    stage.addRequiredItem("non-existent-id"); // not in plan
    stage.activate();
    plan.addStage(stage);

    handler.onWorkerFinished(eventFor("worker-a"));

    assertThat(stage.isTerminal())
        .as("stage must not autocomplete when required item is not registered")
        .isFalse();
    verifyNoInteractions(mockBus);
  }

  @Test
  void autocomplete_false_stage_does_not_complete_even_when_all_done() {
    PlanItem item = PlanItem.create("binding-a", "worker-a", 0);
    plan.addPlanItem(item);
    item.markRunning(); // simulates indexSelectedForCompletion in PlanningStrategyLoopControl
    registry.indexForCompletion(caseId, "worker-a", item.getPlanItemId());

    Stage stage = Stage.alwaysActivate("intake").withAutocomplete(false);
    stage.addRequiredItem(item.getPlanItemId());
    stage.activate();
    plan.addStage(stage);

    handler.onWorkerFinished(eventFor("worker-a"));

    assertThat(stage.isTerminal()).isFalse();
    verifyNoInteractions(mockBus);
  }

  // --- SubCase completion path ---

  @Test
  void marks_subcase_plan_item_completed_on_subcase_execution_completed() {
    PlanItem item = PlanItem.create("subcase-binding", "unknown", 0);
    plan.addPlanItem(item);
    item.markDelegated();
    UUID childCaseId = UUID.randomUUID();
    registry.indexForCompletion(caseId, childCaseId.toString(), item.getPlanItemId());

    handler.onSubCaseFinished(new SubCaseExecutionCompleted(caseId, childCaseId, "test-tenant"));

    assertThat(item.getStatus()).isEqualTo(PlanItemStatus.COMPLETED);
  }

  @Test
  void subcase_completion_triggers_stage_autocomplete() {
    PlanItem item = PlanItem.create("subcase-binding", "unknown", 0);
    plan.addPlanItem(item);
    item.markDelegated();
    UUID childCaseId = UUID.randomUUID();
    registry.indexForCompletion(caseId, childCaseId.toString(), item.getPlanItemId());

    Stage stage = Stage.alwaysActivate("intake");
    stage.addPlanItem(item.getPlanItemId());
    stage.addRequiredItem(item.getPlanItemId());
    stage.activate();
    plan.addStage(stage);

    handler.onSubCaseFinished(new SubCaseExecutionCompleted(caseId, childCaseId, "test-tenant"));

    assertThat(stage.isTerminal()).isTrue();
    verify(mockBus).publish(eq(BlackboardEventBusAddresses.STAGE_COMPLETED), any());
  }

  @Test
  void subcase_completion_unknown_tracking_key_does_not_throw() {
    handler.onSubCaseFinished(
        new SubCaseExecutionCompleted(caseId, UUID.randomUUID(), "test-tenant"));
  }

  @Test
  void mofn_grouped_subcase_any_child_routes_to_same_plan_item() {
    PlanItem item = PlanItem.create("subcase-group", "unknown", 0);
    plan.addPlanItem(item);
    item.markDelegated();
    UUID child1 = UUID.randomUUID();
    UUID child2 = UUID.randomUUID();
    // Both children indexed to the same planItemId (M-of-N pattern)
    registry.indexForCompletion(caseId, child1.toString(), item.getPlanItemId());
    registry.indexForCompletion(caseId, child2.toString(), item.getPlanItemId());

    // Completion arrives for child2 (threshold-triggering child)
    handler.onSubCaseFinished(new SubCaseExecutionCompleted(caseId, child2, "test-tenant"));

    assertThat(item.getStatus()).isEqualTo(PlanItemStatus.COMPLETED);
  }

  @Test
  void binding_name_lookup_path_marks_plan_item_completed() {
    PlanItem item = PlanItem.create("action-gate-handler", "worker-x", 0);
    plan.addPlanItem(item);
    item.markRunning();

    CaseInstance instance = mock(CaseInstance.class);
    when(instance.getUuid()).thenReturn(caseId);
    Worker worker =
        Worker.builder()
            .name("worker-x")
            .capabilityName("cap")
            .function(new WorkerFunction.Sync(i -> WorkerResult.of(Map.of())))
            .build();
    WorkflowExecutionCompleted event =
        WorkflowExecutionCompleted.approved(
            instance, worker, "idempotency-key", Map.of(), "action-gate-handler");

    handler.onWorkerFinished(event);

    assertThat(item.getStatus()).isEqualTo(PlanItemStatus.COMPLETED);
  }
}
