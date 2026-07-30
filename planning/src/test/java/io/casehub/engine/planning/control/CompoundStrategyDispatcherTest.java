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
package io.casehub.engine.planning.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.api.engine.PlanExecutionContext;
import io.casehub.api.model.Binding;
import io.casehub.api.model.ExecutorRef;
import io.casehub.engine.planning.plan.CasePlanModel;
import io.casehub.engine.planning.plan.CompletionSemantics;
import io.casehub.engine.planning.plan.DefaultCasePlanModel;
import io.casehub.engine.planning.plan.DispatchMode;
import io.casehub.engine.planning.plan.PlanItemDefinition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CompoundStrategyDispatcherTest {

  private DefaultCasePlanModel model;
  private PlanExecutionContext ctx;
  private Map<String, PlanningStrategy> strategies;
  private CompoundStrategyDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    model = new DefaultCasePlanModel(UUID.randomUUID());
    ctx = mock(PlanExecutionContext.class);
    strategies = new HashMap<>();
    strategies.put("default", new ChoreographyStrategy());
    strategies.put("sequential", new SequentialPlanningStrategy());
    dispatcher = new CompoundStrategyDispatcher(strategies::get);
  }

  private Binding binding(String name) {
    var b = mock(Binding.class);
    when(b.getName()).thenReturn(name);
    return b;
  }

  private PlanItemDefinition.Primitive primitive(String id) {
    return new PlanItemDefinition.Primitive(id, id, ExecutorRef.of("worker"), null);
  }

  private PlanItemDefinition.Compound compound(
      String id, String strategy, PlanItemDefinition... children) {
    return new PlanItemDefinition.Compound(
        id,
        id,
        List.of(children),
        strategy,
        CompletionSemantics.all(),
        DispatchMode.ORCHESTRATED,
        null,
        null,
        false,
        java.util.Map.of());
  }

  // ── Grouping ──────────────────────────────────────────────────────────────

  @Test
  void bindings_grouped_by_containing_compound() {
    var p1 = primitive("task-a");
    var p2 = primitive("task-b");
    var comp = compound("phase-1", "default", p1, p2);
    model.registerDefinition(comp);

    var selected = dispatcher.dispatch(model, ctx, List.of(binding("task-a"), binding("task-b")));
    assertThat(selected).hasSize(2);
  }

  @Test
  void free_floating_bindings_use_default_strategy() {
    var selected =
        dispatcher.dispatch(model, ctx, List.of(binding("orphan-a"), binding("orphan-b")));
    assertThat(selected).hasSize(2);
  }

  @Test
  void free_floating_bindings_use_case_level_planning_strategy() {
    var recording = new RecordingStrategy("sequential");
    strategies.put("sequential", recording);

    var definition = mock(io.casehub.api.model.CaseDefinition.class);
    when(definition.getPlanningStrategy()).thenReturn("sequential");
    when(ctx.definition()).thenReturn(definition);

    dispatcher.dispatch(model, ctx, List.of(binding("orphan-a"), binding("orphan-b")));

    assertThat(recording.invoked).isTrue();
    assertThat(recording.receivedBindingNames).containsExactly("orphan-a", "orphan-b");
  }

  @Test
  void mixed_compound_and_free_floating() {
    var p1 = primitive("task-a");
    var comp = compound("phase-1", "default", p1);
    model.registerDefinition(comp);

    var selected = dispatcher.dispatch(model, ctx, List.of(binding("task-a"), binding("orphan")));
    assertThat(selected).hasSize(2);
  }

  // ── Per-compound strategy resolution ──────────────────────────────────────

  @Test
  void different_compounds_use_different_strategies() {
    var recordingA = new RecordingStrategy("strat-a");
    var recordingB = new RecordingStrategy("strat-b");
    strategies.put("strat-a", recordingA);
    strategies.put("strat-b", recordingB);

    var p1 = primitive("task-a");
    var p2 = primitive("task-b");
    var compA = compound("comp-a", "strat-a", p1);
    var compB = compound("comp-b", "strat-b", p2);
    model.registerDefinition(compA);
    model.registerDefinition(compB);

    dispatcher.dispatch(model, ctx, List.of(binding("task-a"), binding("task-b")));

    assertThat(recordingA.invoked).isTrue();
    assertThat(recordingA.receivedBindingNames).containsExactly("task-a");
    assertThat(recordingB.invoked).isTrue();
    assertThat(recordingB.receivedBindingNames).containsExactly("task-b");
  }

  @Test
  void null_strategy_resolves_to_default() {
    var p1 = primitive("task-a");
    var comp = compound("comp-null", null, p1);
    model.registerDefinition(comp);

    var selected = dispatcher.dispatch(model, ctx, List.of(binding("task-a")));
    assertThat(selected).hasSize(1);
  }

  // ── Strategy filtering ────────────────────────────────────────────────────

  @Test
  void sequential_strategy_selects_subset() {
    var p1 = primitive("task-a");
    var p2 = primitive("task-b");
    var comp = compound("seq-phase", "sequential", p1, p2);
    model.registerDefinition(comp);

    var b1 = binding("task-a");
    var b2 = binding("task-b");
    var selected = dispatcher.dispatch(model, ctx, List.of(b1, b2));

    assertThat(selected.size()).isLessThanOrEqualTo(2);
  }

  // ── Edge cases ────────────────────────────────────────────────────────────

  @Test
  void empty_eligible_returns_empty() {
    var selected = dispatcher.dispatch(model, ctx, List.of());
    assertThat(selected).isEmpty();
  }

  @Test
  void binding_not_matching_any_registered_definition_treated_as_free_floating() {
    var p1 = primitive("registered");
    var comp = compound("comp", "default", p1);
    model.registerDefinition(comp);

    var selected = dispatcher.dispatch(model, ctx, List.of(binding("unregistered")));
    assertThat(selected).hasSize(1);
  }

  @Test
  void compound_with_no_eligible_bindings_produces_no_output() {
    var p1 = primitive("task-a");
    var comp = compound("comp", "default", p1);
    model.registerDefinition(comp);

    var selected = dispatcher.dispatch(model, ctx, List.of(binding("other-task")));
    assertThat(selected).hasSize(1);
  }

  // ── Helper ────────────────────────────────────────────────────────────────

  static class RecordingStrategy implements PlanningStrategy {
    final String strategyId;
    boolean invoked;
    List<String> receivedBindingNames = new ArrayList<>();

    RecordingStrategy(String id) {
      this.strategyId = id;
    }

    @Override
    public String id() {
      return strategyId;
    }

    @Override
    public String getName() {
      return strategyId;
    }

    @Override
    public List<Binding> select(CasePlanModel plan, PlanExecutionContext ctx, List<Binding> el) {
      invoked = true;
      el.forEach(b -> receivedBindingNames.add(b.getName()));
      return el;
    }

    @Override
    public List<Binding> select(
        CasePlanModel plan,
        PlanExecutionContext ctx,
        PlanItemDefinition.Compound compound,
        List<Binding> eligible) {
      invoked = true;
      eligible.forEach(b -> receivedBindingNames.add(b.getName()));
      return eligible;
    }
  }
}
