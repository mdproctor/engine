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
package io.casehub.engine.planning.plan;

import io.casehub.api.model.ExecutorRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanItemDefinitionTest {

  @Test
  void primitive_holds_executor() {
    var executor = ExecutorRef.of("worker-a");
    var item = new PlanItemDefinition.Primitive("pi-1", "task-a", executor, null);

    assertThat(item.id()).isEqualTo("pi-1");
    assertThat(item.name()).isEqualTo("task-a");
    assertThat(item.executor()).isSameAs(executor);
    assertThat(item.entryCondition()).isNull();
  }

  @Test
  void compound_holds_children_and_strategy() {
    var child =
        new PlanItemDefinition.Primitive("pi-2", "subtask", ExecutorRef.of("worker-b"), null);
    var compound =
        new PlanItemDefinition.Compound(
            "pi-root",
            "phase-1",
            List.of(child),
            "sequential",
            CompletionSemantics.all(),
            DispatchMode.CHOREOGRAPHED,
            null,
            null,
            false,
            java.util.Map.of());

    assertThat(compound.children()).containsExactly(child);
    assertThat(compound.planningStrategy()).isEqualTo("sequential");
    assertThat(compound.completion()).isEqualTo(CompletionSemantics.all());
    assertThat(compound.repeatable()).isFalse();
  }

  @Test
  void compound_null_strategy_is_allowed() {
    var compound =
        new PlanItemDefinition.Compound(
            "pi-3",
            "root",
            List.of(),
            null,
            CompletionSemantics.all(),
            DispatchMode.ORCHESTRATED,
            null,
            null,
            false,
            java.util.Map.of());

    assertThat(compound.planningStrategy()).isNull();
  }

  @Test
  void compound_children_is_defensive_copy() {
    var child = new PlanItemDefinition.Primitive("pi-4", "task", ExecutorRef.of("w"), null);
    var mutableList = new java.util.ArrayList<PlanItemDefinition>(List.of(child));
    var compound =
        new PlanItemDefinition.Compound(
            "pi-5",
            "root",
            mutableList,
            null,
            CompletionSemantics.all(),
            DispatchMode.ORCHESTRATED,
            null,
            null,
            false,
            java.util.Map.of());

    mutableList.clear();
    assertThat(compound.children()).hasSize(1);
  }

  @Test
  void primitive_is_sealed_variant() {
    PlanItemDefinition item =
        new PlanItemDefinition.Primitive("pi-6", "task", ExecutorRef.of("w"), null);
    assertThat(item).isInstanceOf(PlanItemDefinition.Primitive.class);
  }

  @Test
  void compound_is_sealed_variant() {
    PlanItemDefinition item =
        new PlanItemDefinition.Compound(
            "pi-7",
            "group",
            List.of(),
            null,
            CompletionSemantics.all(),
            DispatchMode.ORCHESTRATED,
            null,
            null,
            false,
            java.util.Map.of());
    assertThat(item).isInstanceOf(PlanItemDefinition.Compound.class);
  }

  @Test
  void completion_semantics_all() {
    var all = CompletionSemantics.all();
    assertThat(all).isInstanceOf(CompletionSemantics.All.class);
  }

  @Test
  void completion_semantics_m_of_n() {
    var mOfN = CompletionSemantics.mOfN(3);
    assertThat(mOfN).isInstanceOf(CompletionSemantics.MOfN.class);
    assertThat(((CompletionSemantics.MOfN) mOfN).m()).isEqualTo(3);
  }

  @Test
  void completion_semantics_m_of_n_zero_throws() {
    assertThatThrownBy(() -> CompletionSemantics.mOfN(0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void completion_semantics_first_wins() {
    var firstWins = CompletionSemantics.firstWins();
    assertThat(firstWins).isInstanceOf(CompletionSemantics.FirstWins.class);
  }

  @Test
  void dispatch_mode_values() {
    assertThat(DispatchMode.values())
        .containsExactly(DispatchMode.ORCHESTRATED, DispatchMode.CHOREOGRAPHED);
  }

  @Test
  void pattern_matching_on_sealed_type() {
    PlanItemDefinition item =
        new PlanItemDefinition.Primitive("pi-8", "task", ExecutorRef.of("w"), null);

    String result =
        switch (item) {
          case PlanItemDefinition.Primitive p -> "primitive:" + p.executor().name();
          case PlanItemDefinition.Compound c -> "compound:" + c.planningStrategy();
        };

    assertThat(result).isEqualTo("primitive:w");
  }

  @Test
  void builder_creates_compound_with_defaults() {
    var compound = PlanItemDefinition.Compound.builder("my-compound").build();

    assertThat(compound.id()).isNotNull();
    assertThat(compound.name()).isEqualTo("my-compound");
    assertThat(compound.completion()).isEqualTo(CompletionSemantics.all());
    assertThat(compound.dispatchMode()).isEqualTo(DispatchMode.CHOREOGRAPHED);
    assertThat(compound.children()).isEmpty();
    assertThat(compound.scopedBindings()).isEmpty();
    assertThat(compound.planningStrategy()).isNull();
    assertThat(compound.entryCondition()).isNull();
    assertThat(compound.exitCondition()).isNull();
    assertThat(compound.repeatable()).isFalse();
  }

  @Test
  void builder_creates_compound_with_binding_names() {
    var compound =
        PlanItemDefinition.Compound.builder("stage")
            .binding("trigger-a")
            .binding("trigger-b")
            .build();

    assertThat(compound.scopedBindings().keySet()).containsExactlyInAnyOrder("trigger-a", "trigger-b");
  }

  @Test
  void builder_creates_compound_with_children_and_strategy() {
    var child =
        new PlanItemDefinition.Primitive("pi-child", "subtask", ExecutorRef.of("worker"), null);

    var compound =
        PlanItemDefinition.Compound.builder("phase-1")
            .child(child)
            .planningStrategy("sequential")
            .completion(CompletionSemantics.mOfN(2))
            .build();

    assertThat(compound.children()).containsExactly(child);
    assertThat(compound.planningStrategy()).isEqualTo("sequential");
    assertThat(compound.completion()).isInstanceOf(CompletionSemantics.MOfN.class);
  }

  @Test
  void builder_with_entry_and_exit_conditions() {
    var entry = new io.casehub.api.model.evaluator.LambdaExpressionEvaluator(ctx -> true);
    var exit = new io.casehub.api.model.evaluator.LambdaExpressionEvaluator(ctx -> false);

    var compound =
        PlanItemDefinition.Compound.builder("gated")
            .entryCondition(entry)
            .exitCondition(exit)
            .build();

    assertThat(compound.entryCondition()).isSameAs(entry);
    assertThat(compound.exitCondition()).isSameAs(exit);
  }

  @Test
  void builder_with_lambda_entry_condition() {
    var compound = PlanItemDefinition.Compound.builder("gated").entryCondition(ctx -> true).build();

    assertThat(compound.entryCondition()).isNotNull();
  }

  @Test
  void builder_repeatable_and_dispatch_mode() {
    var compound =
        PlanItemDefinition.Compound.builder("repeater")
            .repeatable(true)
            .dispatchMode(DispatchMode.ORCHESTRATED)
            .build();

    assertThat(compound.repeatable()).isTrue();
    assertThat(compound.dispatchMode()).isEqualTo(DispatchMode.ORCHESTRATED);
  }

  @Test
  void builder_custom_id() {
    var compound = PlanItemDefinition.Compound.builder("named").id("custom-id").build();

    assertThat(compound.id()).isEqualTo("custom-id");
  }

  @Test
  void compound_scopedBindings_is_defensive_copy() {
    var mutable = new java.util.HashMap<>(java.util.Map.of(
            "a", io.casehub.api.model.Participation.PARTICIPANT,
            "b", io.casehub.api.model.Participation.PARTICIPANT));
    var compound =
            new PlanItemDefinition.Compound(
                    "pi-x",
                    "test",
                    List.of(),
                    null,
                    CompletionSemantics.all(),
                    DispatchMode.CHOREOGRAPHED,
                    null,
                    null,
                    false,
                    mutable);

    mutable.clear();
    assertThat(compound.scopedBindings()).hasSize(2);
  }

    @Test
    void builder_creates_compound_with_binding_and_participation() {
        var compound = PlanItemDefinition.Compound.builder("stage")
                                                  .binding("monitor", io.casehub.api.model.Participation.COMPANION)
                                                  .binding("worker")
                                                  .build();

        assertThat(compound.scopedBindings())
                .containsEntry("monitor", io.casehub.api.model.Participation.COMPANION)
                .containsEntry("worker", io.casehub.api.model.Participation.PARTICIPANT);
    }
}
