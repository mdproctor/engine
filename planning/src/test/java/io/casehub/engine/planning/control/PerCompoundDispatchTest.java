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

import io.casehub.api.engine.PlanExecutionContext;
import io.casehub.api.model.Binding;
import io.casehub.engine.planning.plan.CasePlanModel;
import io.casehub.engine.planning.plan.CompletionSemantics;
import io.casehub.engine.planning.plan.DefaultCasePlanModel;
import io.casehub.engine.planning.plan.DispatchMode;
import io.casehub.engine.planning.plan.PlanItemDefinition;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PerCompoundDispatchTest {

  @Test
  void compound_parameter_passed_to_strategy() {
    var compound =
        new PlanItemDefinition.Compound(
            "comp-1",
            "phase-1",
            List.of(),
            "sequential",
            CompletionSemantics.all(),
            DispatchMode.ORCHESTRATED,
            null,
            null,
            false,
            java.util.Set.of());

    var binding = org.mockito.Mockito.mock(Binding.class);
    org.mockito.Mockito.when(binding.getName()).thenReturn("task-a");
    var eligible = List.of(binding);

    var capturedCompound = new PlanItemDefinition.Compound[1];

    PlanningStrategy strategy =
        new PlanningStrategy() {
          @Override
          public String id() {
            return "test";
          }

          @Override
          public String getName() {
            return "Test";
          }

          @Override
          public List<Binding> select(
              CasePlanModel plan, PlanExecutionContext ctx, List<Binding> el) {
            return el;
          }

          @Override
          public List<Binding> select(
              CasePlanModel plan,
              PlanExecutionContext ctx,
              PlanItemDefinition.Compound comp,
              List<Binding> el) {
            capturedCompound[0] = comp;
            return el;
          }
        };

    var model = new DefaultCasePlanModel(UUID.randomUUID());
    var result = strategy.select(model, null, compound, eligible);

    assertThat(capturedCompound[0]).isSameAs(compound);
    assertThat(result).containsExactly(binding);
  }

  @Test
  void default_method_delegates_to_three_arg_select() {
    var compound =
        new PlanItemDefinition.Compound(
            "comp-2",
            "phase-2",
            List.of(),
            null,
            CompletionSemantics.all(),
            DispatchMode.ORCHESTRATED,
            null,
            null,
            false,
            java.util.Set.of());

    var binding = org.mockito.Mockito.mock(Binding.class);
    var threeArgCalled = new boolean[] {false};

    PlanningStrategy strategy =
        new PlanningStrategy() {
          @Override
          public String id() {
            return "legacy";
          }

          @Override
          public String getName() {
            return "Legacy";
          }

          @Override
          public List<Binding> select(
              CasePlanModel plan, PlanExecutionContext ctx, List<Binding> el) {
            threeArgCalled[0] = true;
            return el;
          }
        };

    var model = new DefaultCasePlanModel(UUID.randomUUID());
    strategy.select(model, null, compound, List.of(binding));

    assertThat(threeArgCalled[0]).isTrue();
  }

  @Test
  void choreography_strategy_handles_compound_parameter() {
    var strategy = new ChoreographyStrategy();
    var compound =
        new PlanItemDefinition.Compound(
            "comp-3",
            "phase-3",
            List.of(),
            null,
            CompletionSemantics.all(),
            DispatchMode.ORCHESTRATED,
            null,
            null,
            false,
            java.util.Set.of());

    var binding = org.mockito.Mockito.mock(Binding.class);
    var model = new DefaultCasePlanModel(UUID.randomUUID());
    var ctx = org.mockito.Mockito.mock(PlanExecutionContext.class);

    var result = strategy.select(model, ctx, compound, List.of(binding));
    assertThat(result).containsExactly(binding);
  }

  @Test
  void sequential_strategy_handles_compound_parameter() {
    var strategy = new SequentialPlanningStrategy();
    var compound =
        new PlanItemDefinition.Compound(
            "comp-4",
            "seq-phase",
            List.of(),
            "sequential",
            CompletionSemantics.all(),
            DispatchMode.ORCHESTRATED,
            null,
            null,
            false,
            java.util.Set.of());

    var model = new DefaultCasePlanModel(UUID.randomUUID());
    var ctx = org.mockito.Mockito.mock(PlanExecutionContext.class);

    var result = strategy.select(model, ctx, compound, List.of());
    assertThat(result).isEmpty();
  }
}
