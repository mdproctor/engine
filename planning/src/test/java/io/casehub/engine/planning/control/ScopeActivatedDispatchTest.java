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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.api.context.CaseContext;
import io.casehub.api.engine.ExpressionEngineRegistry;
import io.casehub.api.engine.PlanExecutionContext;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.ExecutionMode;
import io.casehub.api.model.LifecycleScope;
import io.casehub.api.model.Participation;
import io.casehub.api.model.ScopeActivatedTrigger;
import io.casehub.api.spi.routing.ImplementationRoutingStrategy;
import io.casehub.engine.planning.plan.DefaultCasePlanModel;
import io.casehub.engine.planning.plan.PlanItemDefinition;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import jakarta.enterprise.inject.Instance;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScopeActivatedDispatchTest {

  private BlackboardRegistry registry;
  private ExpressionEngineRegistry expressionEngineRegistry;
  private PlanningStrategyLoopControl loopControl;
  private DefaultCasePlanModel plan;
  private UUID caseId;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    caseId = UUID.randomUUID();
    plan = new DefaultCasePlanModel(caseId);
    registry = mock(BlackboardRegistry.class);
    when(registry.getOrCreate(any(), any())).thenReturn(plan);
    when(registry.markConfigured(any())).thenReturn(true);

    expressionEngineRegistry = mock(ExpressionEngineRegistry.class);
    when(expressionEngineRegistry.evaluate(any(ExpressionEvaluator.class), any(CaseContext.class)))
        .thenReturn(true);

    var compoundEvaluator = new CompoundLifecycleEvaluator();
    ChoreographyStrategy strategy = new ChoreographyStrategy();
    List<PlanningStrategy> strategyList = List.of(strategy);
    var compoundDispatcher =
        new CompoundStrategyDispatcher(
            id -> strategyList.stream().filter(s -> s.id().equals(id)).findFirst().orElse(null));
    Instance<BlackboardPlanConfigurer> configurers = mock(Instance.class);
    when(configurers.stream()).thenReturn(java.util.stream.Stream.empty());

    loopControl =
        new PlanningStrategyLoopControl(
            registry,
            compoundEvaluator,
            compoundDispatcher,
            configurers,
            mock(ImplementationRoutingStrategy.class),
            expressionEngineRegistry);
  }

  private PlanExecutionContext buildCtx(CaseDefinition definition) {
    CaseContext caseContext = mock(CaseContext.class);
    return new PlanExecutionContext(
        caseId, definition, caseContext, CaseStatus.RUNNING, "test-tenant", List.of(), null, null);
  }

  @Test
  void compound_scoped_binding_dispatches_on_compound_activation() {
    Capability cap =
        Capability.builder().name("monitor").inputSchema(".").outputSchema(".").build();
    Binding scopeBinding =
        Binding.builder()
            .name("monitor-binding")
            .capability(cap)
            .on(new ScopeActivatedTrigger())
            .lifecycleScope(LifecycleScope.COMPOUND)
            .participation(Participation.COMPANION)
            .executionMode(ExecutionMode.REINVOKED)
            .build();
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0.0")
            .capabilities(cap)
            .workers(
                Worker.builder()
                    .name("monitor-worker")
                    .capabilityName("monitor")
                    .noFunction()
                    .build())
            .bindings(scopeBinding)
            .build();

    var compound =
        PlanItemDefinition.Compound.builder("phase-1")
            .id("comp-1")
            .binding("monitor-binding", Participation.COMPANION)
            .build();
    plan.registerDefinition(compound);

    List<Binding> selected = loopControl.select(buildCtx(def), List.of());

    assertThat(selected).extracting(Binding::getName).contains("monitor-binding");
  }

  @Test
  void case_scoped_binding_dispatches_on_first_select() {
    Capability cap = Capability.builder().name("logger").inputSchema(".").outputSchema(".").build();
    Binding caseBinding =
        Binding.builder()
            .name("case-logger")
            .capability(cap)
            .on(new ScopeActivatedTrigger())
            .lifecycleScope(LifecycleScope.CASE)
            .participation(Participation.COMPANION)
            .executionMode(ExecutionMode.REINVOKED)
            .build();
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0.0")
            .capabilities(cap)
            .workers(
                Worker.builder()
                    .name("logger-worker")
                    .capabilityName("logger")
                    .noFunction()
                    .build())
            .bindings(caseBinding)
            .build();

    List<Binding> selected = loopControl.select(buildCtx(def), List.of());

    assertThat(selected).extracting(Binding::getName).contains("case-logger");
  }

  @Test
  void case_scoped_binding_does_not_dispatch_on_second_select() {
    Capability cap = Capability.builder().name("logger").inputSchema(".").outputSchema(".").build();
    Binding caseBinding =
        Binding.builder()
            .name("case-logger")
            .capability(cap)
            .on(new ScopeActivatedTrigger())
            .lifecycleScope(LifecycleScope.CASE)
            .participation(Participation.COMPANION)
            .executionMode(ExecutionMode.REINVOKED)
            .build();
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0.0")
            .capabilities(cap)
            .workers(
                Worker.builder()
                    .name("logger-worker")
                    .capabilityName("logger")
                    .noFunction()
                    .build())
            .bindings(caseBinding)
            .build();

    loopControl.select(buildCtx(def), List.of());

    when(registry.markConfigured(any())).thenReturn(false);
    List<Binding> second = loopControl.select(buildCtx(def), List.of());

    assertThat(second).extracting(Binding::getName).doesNotContain("case-logger");
  }

  @Test
  void when_guard_false_prevents_scope_activated_dispatch() {
    when(expressionEngineRegistry.evaluate(any(ExpressionEvaluator.class), any(CaseContext.class)))
        .thenReturn(false);

    Capability cap =
        Capability.builder().name("monitor").inputSchema(".").outputSchema(".").build();
    Binding scopeBinding =
        Binding.builder()
            .name("guarded-binding")
            .capability(cap)
            .on(new ScopeActivatedTrigger())
            .lifecycleScope(LifecycleScope.COMPOUND)
            .participation(Participation.COMPANION)
            .executionMode(ExecutionMode.REINVOKED)
            .when(".ready == true")
            .build();
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0.0")
            .capabilities(cap)
            .workers(
                Worker.builder()
                    .name("monitor-worker")
                    .capabilityName("monitor")
                    .noFunction()
                    .build())
            .bindings(scopeBinding)
            .build();

    var compound =
        PlanItemDefinition.Compound.builder("phase-1")
            .id("comp-1")
            .binding("guarded-binding", Participation.COMPANION)
            .build();
    plan.registerDefinition(compound);

    List<Binding> selected = loopControl.select(buildCtx(def), List.of());

    assertThat(selected).extracting(Binding::getName).doesNotContain("guarded-binding");
  }

  @Test
  void context_change_bindings_unaffected_by_scope_activated_logic() {
    Capability cap =
        Capability.builder().name("process").inputSchema(".").outputSchema(".").build();
    Binding ctxBinding =
        Binding.builder()
            .name("process-binding")
            .capability(cap)
            .on(new ContextChangeTrigger(".ready == true"))
            .build();
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0.0")
            .capabilities(cap)
            .workers(
                Worker.builder().name("processor").capabilityName("process").noFunction().build())
            .bindings(ctxBinding)
            .build();

    List<Binding> selected = loopControl.select(buildCtx(def), List.of(ctxBinding));

    assertThat(selected).extracting(Binding::getName).contains("process-binding");
  }

  @Test
  void compound_scoped_binding_not_in_any_compound_fails_validation() {
    Capability cap = Capability.builder().name("orphan").inputSchema(".").outputSchema(".").build();
    Binding orphan =
        Binding.builder()
            .name("orphan-binding")
            .capability(cap)
            .on(new ScopeActivatedTrigger())
            .lifecycleScope(LifecycleScope.COMPOUND)
            .participation(Participation.COMPANION)
            .executionMode(ExecutionMode.REINVOKED)
            .build();
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test")
            .version("1.0.0")
            .capabilities(cap)
            .workers(
                Worker.builder()
                    .name("orphan-worker")
                    .capabilityName("orphan")
                    .noFunction()
                    .build())
            .bindings(orphan)
            .build();

    assertThatThrownBy(() -> loopControl.select(buildCtx(def), List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("orphan-binding");
  }
}
