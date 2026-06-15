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
package io.casehub.blackboard.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for Stage lifecycle transitions and containment. See casehubio/engine#76. CMMN 1.1
 * §5.4.4 alignment: casehubio/engine#84.
 */
class StageTest {

  @Test
  void new_stage_is_pending() {
    assertThat(Stage.create("intake").getStatus()).isEqualTo(StageStatus.PENDING);
  }

  @Test
  void activate_from_pending_transitions_to_active() {
    Stage s = Stage.create("intake");
    s.activate();
    assertThat(s.getStatus()).isEqualTo(StageStatus.ACTIVE);
  }

  @Test
  void activate_from_non_pending_is_noop() {
    Stage s = Stage.create("intake");
    s.activate();
    s.complete();
    s.activate(); // should be noop
    assertThat(s.getStatus()).isEqualTo(StageStatus.COMPLETED);
  }

  @Test
  void complete_from_active_transitions_to_completed() {
    Stage s = Stage.create("intake");
    s.activate();
    s.complete();
    assertThat(s.getStatus()).isEqualTo(StageStatus.COMPLETED);
  }

  @Test
  void terminate_from_active_transitions_to_terminated() {
    Stage s = Stage.create("intake");
    s.activate();
    s.terminate();
    assertThat(s.getStatus()).isEqualTo(StageStatus.TERMINATED);
  }

  @Test
  void suspend_and_resume_roundtrip() {
    Stage s = Stage.create("intake");
    s.activate();
    s.suspend();
    assertThat(s.getStatus()).isEqualTo(StageStatus.SUSPENDED);
    s.resume();
    assertThat(s.getStatus()).isEqualTo(StageStatus.ACTIVE);
  }

  @Test
  void isTerminal_true_for_completed_terminated_faulted() {
    Stage completed = Stage.create("a");
    completed.activate();
    completed.complete();
    Stage terminated = Stage.create("b");
    terminated.activate();
    terminated.terminate();
    assertThat(completed.isTerminal()).isTrue();
    assertThat(terminated.isTerminal()).isTrue();
    assertThat(Stage.create("c").isTerminal()).isFalse();
  }

  @Test
  void containment_addPlanItem_and_addRequiredItem() {
    Stage s = Stage.create("intake");
    s.addPlanItem("pi-1");
    s.addRequiredItem("pi-1");
    assertThat(s.getContainedPlanItemIds()).contains("pi-1");
    assertThat(s.getRequiredItemIds()).contains("pi-1");
  }

  @Test
  void containment_add_same_id_twice_produces_no_duplicates() {
    Stage s = Stage.create("intake");
    s.addPlanItem("pi-1");
    s.addPlanItem("pi-1");
    s.addRequiredItem("pi-1");
    s.addRequiredItem("pi-1");
    assertThat(s.getContainedPlanItemIds()).containsExactly("pi-1");
    assertThat(s.getRequiredItemIds()).containsExactly("pi-1");
  }

  @Test
  void autocomplete_defaults_true() {
    assertThat(Stage.create("intake").isAutocomplete()).isTrue();
  }

  @Test
  void manualActivation_defaults_false() {
    assertThat(Stage.create("intake").isManualActivation()).isFalse();
  }

  @Test
  void complete_from_suspended_transitions_to_completed() {
    Stage s = Stage.create("intake");
    s.activate();
    s.suspend();
    s.complete();
    assertThat(s.getStatus()).isEqualTo(StageStatus.COMPLETED);
  }

  @Test
  void terminate_from_suspended_transitions_to_terminated() {
    Stage s = Stage.create("intake");
    s.activate();
    s.suspend();
    s.terminate();
    assertThat(s.getStatus()).isEqualTo(StageStatus.TERMINATED);
  }

  @Test
  void isTerminal_true_for_faulted() {
    Stage s = Stage.create("x");
    s.fault();
    assertThat(s.isTerminal()).isTrue();
  }

  @Test
  void status_field_is_atomic_reference() throws NoSuchFieldException {
    java.lang.reflect.Field field = Stage.class.getDeclaredField("status");
    field.setAccessible(true);
    assertThat(field.getType())
        .as("Stage.status must be AtomicReference to prevent concurrent transition races")
        .isEqualTo(java.util.concurrent.atomic.AtomicReference.class);
  }

  @Test
  void fault_from_pending_transitions_to_faulted() {
    Stage s = Stage.create("x");
    s.fault();
    assertThat(s.getStatus()).isEqualTo(StageStatus.FAULTED);
    assertThat(s.isTerminal()).isTrue();
  }

  @Test
  void fault_from_faulted_is_noop() {
    Stage s = Stage.create("x");
    s.fault();
    s.fault(); // second call must not corrupt state
    assertThat(s.getStatus())
        .as("fault() on already-FAULTED stage must remain FAULTED")
        .isEqualTo(StageStatus.FAULTED);
  }

  @Test
  void fault_from_completed_is_noop() {
    Stage s = Stage.create("x");
    s.activate();
    s.complete();
    s.fault(); // must not overwrite COMPLETED
    assertThat(s.getStatus())
        .as("fault() must not overwrite a terminal COMPLETED state")
        .isEqualTo(StageStatus.COMPLETED);
  }

  @Test
  void builder_without_entry_condition_throws() {
    assertThatThrownBy(() -> Stage.builder("intake").build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("entryCondition");
  }

  @Test
  void builder_with_entry_condition_creates_stage() {
    Stage stage = Stage.builder("intake").entryCondition(ctx -> true).build();
    assertThat(stage.getName()).isEqualTo("intake");
    assertThat(stage.getEntryCondition()).isNotNull();
    assertThat(stage.getStatus()).isEqualTo(StageStatus.PENDING);
  }

  @Test
  void builder_fluent_options_retained() {
    Stage stage =
        Stage.builder("intake")
            .entryCondition(ctx -> true)
            .exitCondition(ctx -> false)
            .withManualActivation(true)
            .withAutocomplete(false)
            .build();
    assertThat(stage.getEntryCondition()).isNotNull();
    assertThat(stage.getExitCondition()).isNotNull();
    assertThat(stage.isManualActivation()).isTrue();
    assertThat(stage.isAutocomplete()).isFalse();
  }

  @Test
  void alwaysActivate_creates_stage_with_null_entry_condition() {
    Stage stage = Stage.alwaysActivate("intake");
    assertThat(stage.getName()).isEqualTo("intake");
    assertThat(stage.getEntryCondition()).isNull();
    assertThat(stage.getStatus()).isEqualTo(StageStatus.PENDING);
  }

  // ------------------------------------------------------------------ //
  // Binding declarations (design-time, ADR-0002)                        //
  // ------------------------------------------------------------------ //

  @Test
  void getContainedBindingNames_empty_by_default() {
    assertThat(Stage.alwaysActivate("intake").getContainedBindingNames()).isEmpty();
  }

  @Test
  void addBinding_stores_binding_name() {
    Stage s = Stage.alwaysActivate("intake");
    s.addBinding("trigger-on-docs");
    assertThat(s.getContainedBindingNames()).containsExactly("trigger-on-docs");
  }

  @Test
  void addBinding_null_throws() {
    assertThatThrownBy(() -> Stage.alwaysActivate("intake").addBinding(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void addBinding_same_name_twice_produces_no_duplicates() {
    Stage s = Stage.alwaysActivate("intake");
    s.addBinding("b1");
    s.addBinding("b1");
    assertThat(s.getContainedBindingNames()).containsExactly("b1");
  }

  @Test
  void withBinding_returns_stage_for_chaining() {
    Stage s = Stage.alwaysActivate("intake").withBinding("trigger-a").withBinding("trigger-b");
    assertThat(s.getContainedBindingNames()).containsExactlyInAnyOrder("trigger-a", "trigger-b");
  }

  @Test
  void builder_binding_method_declares_at_design_time() {
    Stage s =
        Stage.builder("intake")
            .entryCondition(ctx -> true)
            .binding("trigger-a")
            .binding("trigger-b")
            .build();
    assertThat(s.getContainedBindingNames()).containsExactlyInAnyOrder("trigger-a", "trigger-b");
  }

  @Test
  void getContainedBindingNames_returns_snapshot_not_live_set() {
    Stage s = Stage.alwaysActivate("intake");
    s.addBinding("b1");
    java.util.Set<String> snapshot = s.getContainedBindingNames();
    s.addBinding("b2");
    assertThat(snapshot).containsExactly("b1"); // snapshot unaffected by later add
  }

  // ------------------------------------------------------------------ //
  // Repeatable Stage (casehubio/engine#482)                             //
  // ------------------------------------------------------------------ //

  @Test
  void repeatable_defaults_to_false() {
    assertThat(Stage.create("intake").isRepeatable()).isFalse();
  }

  @Test
  void builder_repeatable_sets_flag() {
    Stage s = Stage.builder("intake").entryCondition(ctx -> true).repeatable(true).build();
    assertThat(s.isRepeatable()).isTrue();
  }

  @Test
  void resetForRepetition_on_repeatable_completed_stage() {
    Stage s = Stage.builder("intake").entryCondition(ctx -> true).repeatable(true).build();
    s.activate();
    s.addPlanItem("pi-1");
    s.addRequiredItem("pi-1");
    s.complete();

    boolean reset = s.resetForRepetition();

    assertThat(reset).isTrue();
    assertThat(s.getStatus()).isEqualTo(StageStatus.PENDING);
    assertThat(s.getInstanceIndex()).isEqualTo(1);
    assertThat(s.getContainedPlanItemIds()).isEmpty();
    assertThat(s.getRequiredItemIds()).isEmpty();
    assertThat(s.getContainedMilestoneIds()).isEmpty();
    assertThat(s.getActivatedAt()).isNull();
    assertThat(s.getCompletedAt()).isNull();
  }

  @Test
  void resetForRepetition_on_non_repeatable_returns_false() {
    Stage s = Stage.create("intake");
    s.activate();
    s.complete();

    assertThat(s.resetForRepetition()).isFalse();
    assertThat(s.getStatus()).isEqualTo(StageStatus.COMPLETED);
  }

  @Test
  void resetForRepetition_on_non_completed_returns_false() {
    Stage s = Stage.builder("intake").entryCondition(ctx -> true).repeatable(true).build();
    s.activate();

    assertThat(s.resetForRepetition()).isFalse();
    assertThat(s.getStatus()).isEqualTo(StageStatus.ACTIVE);
    assertThat(s.getInstanceIndex()).isEqualTo(0);
  }

  @Test
  void instanceIndex_increments_on_each_reset() {
    Stage s = Stage.builder("intake").entryCondition(ctx -> true).repeatable(true).build();
    assertThat(s.getInstanceIndex()).isEqualTo(0);

    s.activate();
    s.complete();
    s.resetForRepetition();
    assertThat(s.getInstanceIndex()).isEqualTo(1);

    s.activate();
    s.complete();
    s.resetForRepetition();
    assertThat(s.getInstanceIndex()).isEqualTo(2);
  }

  @Test
  void resetForRepetition_preserves_binding_names() {
    Stage s =
        Stage.builder("intake").entryCondition(ctx -> true).repeatable(true).binding("b1").build();
    s.activate();
    s.complete();
    s.resetForRepetition();

    assertThat(s.getContainedBindingNames())
        .as("containedBindingNames must persist across resets — they are design-time declarations")
        .containsExactly("b1");
  }
}
