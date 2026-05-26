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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.blackboard.event.StageCompletedEvent;
import io.casehub.blackboard.plan.CasePlanModel;
import io.casehub.blackboard.plan.PlanItem;
import io.casehub.blackboard.stage.Stage;
import io.casehub.engine.common.internal.model.PlanItemStatus;
import io.vertx.mutiny.core.eventbus.EventBus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for StageAutocompleteEvaluator. Covers the terminal-state gate: a stage autocompletes
 * when all required items have reached any terminal state.
 *
 * <p>Refs engine#338, ADR-0002.
 */
class StageAutocompleteEvaluatorTest {

  private StageAutocompleteEvaluator evaluator;
  private EventBus eventBus;

  @BeforeEach
  void setUp() {
    eventBus = mock(EventBus.class);
    evaluator = new StageAutocompleteEvaluator(eventBus);
  }

  // --- autocomplete DOES fire ---

  @Test
  void all_completed_triggers_autocomplete() {
    UUID caseId = UUID.randomUUID();
    CasePlanModel plan = planWith("item-1", PlanItemStatus.COMPLETED);
    Stage stage = autocompleteStage("item-1");
    when(plan.getActiveStages()).thenReturn(List.of(stage));

    evaluator.evaluate(caseId, plan, "item-1");

    verify(stage).complete();
    verify(eventBus)
        .publish(
            io.casehub.blackboard.event.BlackboardEventBusAddresses.STAGE_COMPLETED,
            new StageCompletedEvent(caseId, stage));
  }

  @Test
  void all_rejected_triggers_autocomplete() {
    UUID caseId = UUID.randomUUID();
    CasePlanModel plan = planWith("item-1", PlanItemStatus.REJECTED);
    Stage stage = autocompleteStage("item-1");
    when(plan.getActiveStages()).thenReturn(List.of(stage));

    evaluator.evaluate(caseId, plan, "item-1");

    verify(stage).complete();
  }

  @Test
  void all_faulted_triggers_autocomplete() {
    UUID caseId = UUID.randomUUID();
    CasePlanModel plan = planWith("item-1", PlanItemStatus.FAULTED);
    Stage stage = autocompleteStage("item-1");
    when(plan.getActiveStages()).thenReturn(List.of(stage));

    evaluator.evaluate(caseId, plan, "item-1");

    verify(stage).complete();
  }

  @Test
  void all_cancelled_triggers_autocomplete() {
    UUID caseId = UUID.randomUUID();
    CasePlanModel plan = planWith("item-1", PlanItemStatus.CANCELLED);
    Stage stage = autocompleteStage("item-1");
    when(plan.getActiveStages()).thenReturn(List.of(stage));

    evaluator.evaluate(caseId, plan, "item-1");

    verify(stage).complete();
  }

  @Test
  void mixed_terminal_states_triggers_autocomplete() {
    UUID caseId = UUID.randomUUID();
    CasePlanModel plan = mock(CasePlanModel.class);
    PlanItem item1 = itemWithStatus("item-1", PlanItemStatus.COMPLETED);
    PlanItem item2 = itemWithStatus("item-2", PlanItemStatus.REJECTED);
    PlanItem item3 = itemWithStatus("item-3", PlanItemStatus.FAULTED);
    when(plan.getPlanItem("item-1")).thenReturn(Optional.of(item1));
    when(plan.getPlanItem("item-2")).thenReturn(Optional.of(item2));
    when(plan.getPlanItem("item-3")).thenReturn(Optional.of(item3));
    Stage stage = autocompleteStage("item-1", "item-2", "item-3");
    when(plan.getActiveStages()).thenReturn(List.of(stage));

    evaluator.evaluate(caseId, plan, "item-1");

    verify(stage).complete();
  }

  // --- autocomplete does NOT fire ---

  @Test
  void non_terminal_item_blocks_autocomplete() {
    UUID caseId = UUID.randomUUID();
    CasePlanModel plan = mock(CasePlanModel.class);
    PlanItem item1 = itemWithStatus("item-1", PlanItemStatus.COMPLETED);
    PlanItem item2 = itemWithStatus("item-2", PlanItemStatus.RUNNING);
    when(plan.getPlanItem("item-1")).thenReturn(Optional.of(item1));
    when(plan.getPlanItem("item-2")).thenReturn(Optional.of(item2));
    Stage stage = autocompleteStage("item-1", "item-2");
    when(plan.getActiveStages()).thenReturn(List.of(stage));

    evaluator.evaluate(caseId, plan, "item-1");

    verify(stage, never()).complete();
  }

  @Test
  void delegated_item_blocks_autocomplete() {
    UUID caseId = UUID.randomUUID();
    CasePlanModel plan = mock(CasePlanModel.class);
    PlanItem item1 = itemWithStatus("item-1", PlanItemStatus.COMPLETED);
    PlanItem item2 = itemWithStatus("item-2", PlanItemStatus.DELEGATED);
    when(plan.getPlanItem("item-1")).thenReturn(Optional.of(item1));
    when(plan.getPlanItem("item-2")).thenReturn(Optional.of(item2));
    Stage stage = autocompleteStage("item-1", "item-2");
    when(plan.getActiveStages()).thenReturn(List.of(stage));

    evaluator.evaluate(caseId, plan, "item-1");

    verify(stage, never()).complete();
  }

  @Test
  void non_autocomplete_stage_never_fires() {
    UUID caseId = UUID.randomUUID();
    CasePlanModel plan = planWith("item-1", PlanItemStatus.COMPLETED);
    Stage stage = mock(Stage.class);
    when(stage.isAutocomplete()).thenReturn(false);
    when(stage.getRequiredItemIds()).thenReturn(List.of("item-1"));
    when(plan.getActiveStages()).thenReturn(List.of(stage));

    evaluator.evaluate(caseId, plan, "item-1");

    verify(stage, never()).complete();
  }

  @Test
  void stage_not_containing_changed_item_is_skipped() {
    UUID caseId = UUID.randomUUID();
    CasePlanModel plan = planWith("item-1", PlanItemStatus.COMPLETED);
    Stage stage = autocompleteStage("item-99"); // different item
    when(plan.getActiveStages()).thenReturn(List.of(stage));

    evaluator.evaluate(caseId, plan, "item-1");

    verify(stage, never()).complete();
  }

  // --- helpers ---

  private CasePlanModel planWith(String itemId, PlanItemStatus status) {
    CasePlanModel plan = mock(CasePlanModel.class);
    PlanItem item = itemWithStatus(itemId, status);
    when(plan.getPlanItem(itemId)).thenReturn(Optional.of(item));
    return plan;
  }

  private PlanItem itemWithStatus(String itemId, PlanItemStatus status) {
    PlanItem item = mock(PlanItem.class);
    when(item.getStatus()).thenReturn(status);
    return item;
  }

  private Stage autocompleteStage(String... requiredIds) {
    Stage stage = mock(Stage.class);
    when(stage.isAutocomplete()).thenReturn(true);
    when(stage.getRequiredItemIds()).thenReturn(List.of(requiredIds));
    return stage;
  }
}
