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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.api.model.ExecutorRef;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanItemDefinitionTest {

  @Test
  void primitive_holds_executor_and_dispatch_mode() {
    var executor = ExecutorRef.of("worker-a");
    var item =
        new PlanItemDefinition.Primitive(
            "pi-1", "task-a", executor, DispatchMode.ORCHESTRATED, null);

    assertThat(item.id()).isEqualTo("pi-1");
    assertThat(item.name()).isEqualTo("task-a");
    assertThat(item.executor()).isSameAs(executor);
    assertThat(item.dispatchMode()).isEqualTo(DispatchMode.ORCHESTRATED);
    assertThat(item.entryCondition()).isNull();
  }

  @Test
  void compound_holds_children_and_strategy() {
    var child =
        new PlanItemDefinition.Primitive(
            "pi-2", "subtask", ExecutorRef.of("worker-b"), DispatchMode.ORCHESTRATED, null);
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
            false);

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
            false);

    assertThat(compound.planningStrategy()).isNull();
  }

  @Test
  void compound_children_is_defensive_copy() {
    var child =
        new PlanItemDefinition.Primitive(
            "pi-4", "task", ExecutorRef.of("w"), DispatchMode.ORCHESTRATED, null);
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
            false);

    mutableList.clear();
    assertThat(compound.children()).hasSize(1);
  }

  @Test
  void primitive_is_sealed_variant() {
    PlanItemDefinition item =
        new PlanItemDefinition.Primitive(
            "pi-6", "task", ExecutorRef.of("w"), DispatchMode.ORCHESTRATED, null);
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
            false);
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
        .containsExactly(
            DispatchMode.ORCHESTRATED, DispatchMode.CHOREOGRAPHED, DispatchMode.HYBRID);
  }

  @Test
  void pattern_matching_on_sealed_type() {
    PlanItemDefinition item =
        new PlanItemDefinition.Primitive(
            "pi-8", "task", ExecutorRef.of("w"), DispatchMode.ORCHESTRATED, null);

    String result =
        switch (item) {
          case PlanItemDefinition.Primitive p -> "primitive:" + p.executor().name();
          case PlanItemDefinition.Compound c -> "compound:" + c.planningStrategy();
        };

    assertThat(result).isEqualTo("primitive:w");
  }
}
