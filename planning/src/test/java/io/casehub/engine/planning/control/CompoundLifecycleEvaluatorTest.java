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

import io.casehub.api.context.CaseContext;
import io.casehub.api.engine.PlanExecutionContext;
import io.casehub.api.model.TaskStatus;
import io.casehub.engine.planning.plan.CompletionSemantics;
import io.casehub.engine.planning.plan.DefaultCasePlanModel;
import io.casehub.engine.planning.plan.DispatchMode;
import io.casehub.engine.planning.plan.PlanItemDefinition;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CompoundLifecycleEvaluatorTest {

  private CompoundLifecycleEvaluator evaluator;
  private DefaultCasePlanModel plan;
  private PlanExecutionContext ctx;

  @BeforeEach
  void setUp() {
    evaluator = new CompoundLifecycleEvaluator();
    plan = new DefaultCasePlanModel(UUID.randomUUID());
    CaseContext caseContext = mock(CaseContext.class);
    ctx = mock(PlanExecutionContext.class);
    org.mockito.Mockito.when(ctx.caseContext()).thenReturn(caseContext);
    org.mockito.Mockito.when(ctx.caseId()).thenReturn(plan.getCaseId());
    org.mockito.Mockito.when(ctx.tenancyId()).thenReturn("test-tenant");
  }

  @Test
  void pending_compound_with_no_entry_condition_activates() {
    var compound = PlanItemDefinition.Compound.builder("phase-1").id("comp-1").build();
    plan.registerDefinition(compound);

    evaluator.evaluate(plan, ctx);

    assertThat(plan.getDefinitionStatus("comp-1")).isEqualTo(TaskStatus.RUNNING);
  }

  @Test
  void pending_compound_with_true_entry_condition_activates() {
    var compound =
        PlanItemDefinition.Compound.builder("phase-1")
            .id("comp-1")
            .entryCondition(c -> true)
            .build();
    plan.registerDefinition(compound);

    evaluator.evaluate(plan, ctx);

    assertThat(plan.getDefinitionStatus("comp-1")).isEqualTo(TaskStatus.RUNNING);
  }

  @Test
  void pending_compound_with_false_entry_condition_stays_pending() {
    var compound =
        PlanItemDefinition.Compound.builder("phase-1")
            .id("comp-1")
            .entryCondition(c -> false)
            .build();
    plan.registerDefinition(compound);

    evaluator.evaluate(plan, ctx);

    assertThat(plan.getDefinitionStatus("comp-1")).isEqualTo(TaskStatus.PENDING);
  }

  @Test
  void running_compound_with_true_exit_condition_completes() {
    var compound =
        PlanItemDefinition.Compound.builder("phase-1")
            .id("comp-1")
            .exitCondition(c -> true)
            .build();
    plan.registerDefinition(compound);
    plan.tryDefinitionTransition("comp-1", TaskStatus.PENDING, TaskStatus.RUNNING);

    evaluator.evaluate(plan, ctx);

    assertThat(plan.getDefinitionStatus("comp-1")).isEqualTo(TaskStatus.COMPLETED);
  }

  @Test
  void running_compound_with_false_exit_condition_stays_running() {
    var compound =
        PlanItemDefinition.Compound.builder("phase-1")
            .id("comp-1")
            .exitCondition(c -> false)
            .build();
    plan.registerDefinition(compound);
    plan.tryDefinitionTransition("comp-1", TaskStatus.PENDING, TaskStatus.RUNNING);

    evaluator.evaluate(plan, ctx);

    assertThat(plan.getDefinitionStatus("comp-1")).isEqualTo(TaskStatus.RUNNING);
  }

  @Test
  void running_compound_with_no_exit_condition_stays_running() {
    var compound = PlanItemDefinition.Compound.builder("phase-1").id("comp-1").build();
    plan.registerDefinition(compound);
    plan.tryDefinitionTransition("comp-1", TaskStatus.PENDING, TaskStatus.RUNNING);

    evaluator.evaluate(plan, ctx);

    assertThat(plan.getDefinitionStatus("comp-1")).isEqualTo(TaskStatus.RUNNING);
  }

  @Test
  void nested_compound_stays_pending_while_parent_is_pending() {
    var parent =
        PlanItemDefinition.Compound.builder("parent")
            .id("parent")
            .entryCondition(c -> false)
            .build();
    var child = PlanItemDefinition.Compound.builder("child").id("child").build();
    plan.registerDefinition(parent);
    plan.registerDefinition(child);
    // Manually set parent-child relationship via parentIndex
    // Child needs to be registered as a child of parent for getParentOf to work
    // Use a compound that declares the child structurally
    plan = new DefaultCasePlanModel(UUID.randomUUID());
    var parentWithChild =
        new PlanItemDefinition.Compound(
            "parent",
            "parent",
            java.util.List.of(child),
            null,
            CompletionSemantics.all(),
            DispatchMode.CHOREOGRAPHED,
            new io.casehub.api.model.evaluator.LambdaExpressionEvaluator(c -> false),
            null,
            false,
            java.util.Map.of());
    plan.registerDefinition(parentWithChild);

    evaluator.evaluate(plan, ctx);

    assertThat(plan.getDefinitionStatus("parent")).isEqualTo(TaskStatus.PENDING);
    assertThat(plan.getDefinitionStatus("child")).isEqualTo(TaskStatus.PENDING);
  }

  @Test
  void nested_compound_activates_after_parent_becomes_running() {
    var child = PlanItemDefinition.Compound.builder("child").id("child").build();
    var parent =
        new PlanItemDefinition.Compound(
            "parent",
            "parent",
            java.util.List.of(child),
            null,
            CompletionSemantics.all(),
            DispatchMode.CHOREOGRAPHED,
            null,
            null,
            false,
            java.util.Map.of());
    plan.registerDefinition(parent);

    evaluator.evaluate(plan, ctx);

    assertThat(plan.getDefinitionStatus("parent")).isEqualTo(TaskStatus.RUNNING);
    assertThat(plan.getDefinitionStatus("child")).isEqualTo(TaskStatus.RUNNING);
  }

  @Test
  void completed_compound_is_not_re_evaluated() {
    var compound = PlanItemDefinition.Compound.builder("done").id("comp-1").build();
    plan.registerDefinition(compound);
    plan.tryDefinitionTransition("comp-1", TaskStatus.PENDING, TaskStatus.RUNNING);
    plan.tryDefinitionTransition("comp-1", TaskStatus.RUNNING, TaskStatus.COMPLETED);

    evaluator.evaluate(plan, ctx);

    assertThat(plan.getDefinitionStatus("comp-1")).isEqualTo(TaskStatus.COMPLETED);
  }
}
