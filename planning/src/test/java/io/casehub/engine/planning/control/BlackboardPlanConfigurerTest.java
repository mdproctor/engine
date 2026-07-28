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

import io.casehub.api.context.CaseContext;
import io.casehub.api.engine.PlanExecutionContext;
import io.casehub.api.model.CaseDefinition;
import io.casehub.engine.planning.plan.CasePlanModel;
import io.casehub.engine.planning.plan.DefaultCasePlanModel;
import io.casehub.platform.api.identity.TenancyConstants;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BlackboardPlanConfigurerTest {

  @Test
  void default_supports_returns_true_for_any_definition() {
    BlackboardPlanConfigurer c = (plan, ctx) -> {};
    assertThat(c.supports(mock(CaseDefinition.class))).isTrue();
  }

  @Test
  void configure_receives_plan_and_context() {
    CasePlanModel[] captured = {null};
    BlackboardPlanConfigurer c = (plan, ctx) -> captured[0] = plan;
    CasePlanModel plan = new DefaultCasePlanModel(UUID.randomUUID());
    PlanExecutionContext ctx =
        new PlanExecutionContext(
            UUID.randomUUID(),
            mock(CaseDefinition.class),
            mock(CaseContext.class),
            io.casehub.api.model.CaseStatus.RUNNING,
            TenancyConstants.DEFAULT_TENANT_ID,
            List.of(),
            null,
            null);
    c.configure(plan, ctx);
    assertThat(captured[0]).isSameAs(plan);
  }

  @Test
  void supports_can_filter_by_definition_name() {
    BlackboardPlanConfigurer c =
        new BlackboardPlanConfigurer() {
          @Override
          public boolean supports(CaseDefinition def) {
            return "loan".equals(def.getName());
          }

          @Override
          public void configure(CasePlanModel plan, PlanExecutionContext ctx) {}
        };
    CaseDefinition loan = mock(CaseDefinition.class);
    when(loan.getName()).thenReturn("loan");
    CaseDefinition other = mock(CaseDefinition.class);
    when(other.getName()).thenReturn("other");
    assertThat(c.supports(loan)).isTrue();
    assertThat(c.supports(other)).isFalse();
  }
}
