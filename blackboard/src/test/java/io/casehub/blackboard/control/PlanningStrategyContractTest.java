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
import io.casehub.blackboard.plan.CasePlanModel;
import io.casehub.blackboard.plan.DefaultCasePlanModel;
import io.casehub.platform.api.identity.TenancyConstants;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Abstract contract test — extend this in any custom PlanningStrategy module to verify the
 * implementation honours the PlanningStrategy contract. See casehubio/engine#76.
 */
public abstract class PlanningStrategyContractTest {

  protected abstract PlanningStrategy strategy();

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
  void never_returns_null_uni() {
    CasePlanModel plan = new DefaultCasePlanModel(UUID.randomUUID());
    assertThat(strategy().select(plan, ctx(), List.of())).isNotNull();
  }

  @Test
  void returns_only_bindings_from_eligible_list() {
    CasePlanModel plan = new DefaultCasePlanModel(UUID.randomUUID());
    Binding b1 = mock(Binding.class);
    Binding b2 = mock(Binding.class);
    Binding outsider = mock(Binding.class);
    List<Binding> eligible = List.of(b1, b2);

    List<Binding> result = strategy().select(plan, ctx(), eligible).await().indefinitely();

    assertThat(result).doesNotHaveDuplicates();
    assertThat(result).doesNotContain(outsider);
    assertThat(eligible).containsAll(result);
  }

  @Test
  void handles_empty_eligible_without_throwing() {
    CasePlanModel plan = new DefaultCasePlanModel(UUID.randomUUID());
    List<Binding> result = strategy().select(plan, ctx(), List.of()).await().indefinitely();
    assertThat(result).isNotNull();
  }

  @Test
  void result_does_not_contain_duplicates_when_eligible_has_duplicates() {
    CasePlanModel plan = new DefaultCasePlanModel(UUID.randomUUID());
    Binding b1 = mock(Binding.class);
    List<Binding> eligibleWithDuplicate = List.of(b1, b1);

    List<Binding> result =
        strategy().select(plan, ctx(), eligibleWithDuplicate).await().indefinitely();

    assertThat(result)
        .as("strategy must not return duplicate bindings even if eligible list contains duplicates")
        .doesNotHaveDuplicates();
  }
}
