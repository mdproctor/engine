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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.api.context.CaseContext;
import io.casehub.api.engine.PlanExecutionContext;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.spi.routing.ImplementationRoutingStrategy;
import io.casehub.api.spi.routing.ImplementationSelection;
import io.casehub.blackboard.plan.DefaultCasePlanModel;
import io.casehub.blackboard.registry.BlackboardRegistry;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.inject.Instance;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ImplementationRoutingStrategy} integration in {@link
 * PlanningStrategyLoopControl}. Refs casehubio/engine#476.
 */
@SuppressWarnings("unchecked")
class ImplementationRoutingTest {

  private BlackboardRegistry registry;
  private PlanningStrategyLoopControl loopControl;
  private UUID caseId;
  private PlanExecutionContext ctx;
  private ImplementationRoutingStrategy routingStrategy;

  @BeforeEach
  void setUp() {
    registry = new BlackboardRegistry();
    DefaultPlanningStrategy strategy = new DefaultPlanningStrategy();
    StageLifecycleEvaluator evaluator = mock(StageLifecycleEvaluator.class);
    when(evaluator.evaluate(any(), any())).thenReturn(Uni.createFrom().voidItem());
    Instance<BlackboardPlanConfigurer> emptyConfigurers = mock(Instance.class);
    when(emptyConfigurers.stream()).thenReturn(Stream.empty());

    routingStrategy = mock(ImplementationRoutingStrategy.class);

    loopControl =
        new PlanningStrategyLoopControl(
            registry, strategy, evaluator, emptyConfigurers, routingStrategy);

    caseId = UUID.randomUUID();

    Capability cap =
        Capability.builder().name("analyse").inputSchema(".").outputSchema(".").build();
    Worker w1 =
        Worker.builder()
            .name("worker-a")
            .capabilityName("analyse")
            .function(new WorkerFunction.Sync(input -> WorkerResult.of(java.util.Map.of())))
            .build();
    Worker w2 =
        Worker.builder()
            .name("worker-b")
            .capabilityName("analyse")
            .function(new WorkerFunction.Sync(input -> WorkerResult.of(java.util.Map.of())))
            .build();

    CaseDefinition def = mock(CaseDefinition.class);
    when(def.getWorkers()).thenReturn(List.of(w1, w2));

    ctx =
        new PlanExecutionContext(
            caseId,
            def,
            mock(CaseContext.class),
            io.casehub.api.model.CaseStatus.RUNNING,
            TenancyConstants.DEFAULT_TENANT_ID);
  }

  private Binding capabilityBinding(String name, String capName) {
    Capability cap = Capability.builder().name(capName).inputSchema(".").outputSchema(".").build();
    Binding b = mock(Binding.class);
    when(b.getName()).thenReturn(name);
    when(b.target()).thenReturn(new CapabilityTarget(cap));
    return b;
  }

  private DefaultCasePlanModel plan() {
    return (DefaultCasePlanModel) registry.getOrCreate(caseId, "test-tenant");
  }

  @Test
  void selected_single_binding_only_that_binding_passes() {
    when(routingStrategy.select(any(), any()))
        .thenReturn(Uni.createFrom().item(new ImplementationSelection.Selected(List.of("b1"))));

    Binding b1 = capabilityBinding("b1", "analyse");
    Binding b2 = capabilityBinding("b2", "analyse");

    List<Binding> result = loopControl.select(ctx, List.of(b1, b2)).await().indefinitely();

    assertThat(result.stream().map(Binding::getName))
        .as("Only the selected binding should pass")
        .containsExactly("b1");
    assertThat(plan().hasActivePlanItem("b2"))
        .as("Non-selected binding must not have a PlanItem")
        .isFalse();
  }

  @Test
  void runAll_all_bindings_pass() {
    when(routingStrategy.select(any(), any()))
        .thenReturn(Uni.createFrom().item(new ImplementationSelection.RunAll()));

    Binding b1 = capabilityBinding("b1", "analyse");
    Binding b2 = capabilityBinding("b2", "analyse");

    List<Binding> result = loopControl.select(ctx, List.of(b1, b2)).await().indefinitely();

    assertThat(result.stream().map(Binding::getName))
        .as("RunAll must pass all bindings")
        .containsExactlyInAnyOrder("b1", "b2");
  }

  @Test
  void runNone_no_bindings_pass() {
    when(routingStrategy.select(any(), any()))
        .thenReturn(Uni.createFrom().item(new ImplementationSelection.RunNone()));

    Binding b1 = capabilityBinding("b1", "analyse");
    Binding b2 = capabilityBinding("b2", "analyse");

    List<Binding> result = loopControl.select(ctx, List.of(b1, b2)).await().indefinitely();

    assertThat(result.stream().map(Binding::getName))
        .as("RunNone must block all bindings in the group")
        .doesNotContain("b1", "b2");
  }

  @Test
  void single_binding_per_capability_skips_routing() {
    Binding b1 = capabilityBinding("b1", "analyse");

    List<Binding> result = loopControl.select(ctx, List.of(b1)).await().indefinitely();

    assertThat(result.stream().map(Binding::getName)).contains("b1");
  }
}
