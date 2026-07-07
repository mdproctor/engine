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
package io.casehub.blackboard.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.casehub.api.context.CaseContext;
import io.casehub.api.engine.PlanExecutionContext;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.blackboard.plan.DefaultCasePlanModel;
import io.casehub.platform.api.identity.TenancyConstants;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for DefaultPlanningStrategy. See casehubio/engine#76. Epic casehubio/engine#30. */
class DefaultPlanningStrategyTest {

  private final DefaultPlanningStrategy strategy = new DefaultPlanningStrategy();

  private PlanExecutionContext ctx() {
    return new PlanExecutionContext(
        UUID.randomUUID(),
        mock(CaseDefinition.class),
        mock(CaseContext.class),
        io.casehub.api.model.CaseStatus.RUNNING,
        TenancyConstants.DEFAULT_TENANT_ID,
        List.of(),
        null,
        null);
  }

  @Test
  void returns_all_eligible_bindings() {
    DefaultCasePlanModel plan = new DefaultCasePlanModel(UUID.randomUUID());
    Binding b1 = mock(Binding.class);
    Binding b2 = mock(Binding.class);
    List<Binding> eligible = List.of(b1, b2);

    List<Binding> result = strategy.select(plan, ctx(), eligible).await().indefinitely();

    assertThat(result).containsExactlyInAnyOrderElementsOf(eligible);
  }

  @Test
  void empty_eligible_returns_empty_not_null() {
    DefaultCasePlanModel plan = new DefaultCasePlanModel(UUID.randomUUID());
    List<Binding> result = strategy.select(plan, ctx(), List.of()).await().indefinitely();
    assertThat(result).isNotNull().isEmpty();
  }

  @Test
  void does_not_modify_plan_focus_or_budget() {
    DefaultCasePlanModel plan = new DefaultCasePlanModel(UUID.randomUUID());
    strategy.select(plan, ctx(), List.of()).await().indefinitely();
    assertThat(plan.getFocus()).isEmpty();
    assertThat(plan.getResourceBudget()).isEmpty();
  }
}
