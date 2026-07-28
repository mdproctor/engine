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
import io.casehub.api.model.Binding;
import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ExecutorRef;
import io.casehub.api.model.TaskStatus;
import io.casehub.engine.planning.plan.DefaultCasePlanModel;
import io.casehub.engine.planning.plan.PlanItem;
import io.casehub.engine.planning.plan.PlanItemDefinition;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import io.casehub.platform.api.identity.TenancyConstants;
import jakarta.enterprise.inject.Instance;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the staged-vs-free-floating binding gating in {@link PlanningStrategyLoopControl}.
 *
 * <p>ADR-0002 convention: presence of {@link PlanItemDefinition.Compound#scopedBindings()} is the
 * opt-in. Three modes emerge from one mechanism:
 *
 * <ul>
 *   <li>Pure choreography — no binding declarations anywhere → all bindings pass
 *   <li>Full gating — all bindings declared in stages → blocked until stage ACTIVE
 *   <li>Hybrid — some stages gate specific bindings; others don't
 * </ul>
 *
 * <p>The {@link PlanningStrategyLoopControl} is constructed directly (no CDI). The {@code
 * Instance<BlackboardPlanConfigurer>} dependency is mocked to return an empty stream, mirroring the
 * no-configurer case. See casehubio/engine#76.
 */
@SuppressWarnings("unchecked")
class BindingGatingTest {

  private BlackboardRegistry registry;
  private PlanningStrategyLoopControl loopControl;
  private UUID caseId;
  private PlanExecutionContext ctx;

  @BeforeEach
  void setUp() {
    registry = new BlackboardRegistry();

    // DefaultPlanningStrategy passes all eligible bindings through unchanged.
    ChoreographyStrategy strategy = new ChoreographyStrategy();

    // Mock Instance<PlanningStrategy> returning the default strategy
    @SuppressWarnings("unchecked")
    Instance<PlanningStrategy> strategyBeans = mock(Instance.class);
    List<PlanningStrategy> strategyList = List.of(strategy);
    when(strategyBeans.spliterator()).thenAnswer(inv -> strategyList.spliterator());

    // Mock an empty Instance<BlackboardPlanConfigurer> — no configurers needed for gating tests.
    Instance<BlackboardPlanConfigurer> emptyConfigurers = mock(Instance.class);
    when(emptyConfigurers.stream()).thenReturn(Stream.empty());

    loopControl =
        new PlanningStrategyLoopControl(
            registry,
            new CompoundLifecycleEvaluator(),
            new CompoundStrategyDispatcher(
                id ->
                    strategyList.stream().filter(s -> s.id().equals(id)).findFirst().orElse(null)),
            emptyConfigurers,
            new io.casehub.engine.internal.routing.NoOpImplementationRoutingStrategy());

    caseId = UUID.randomUUID();
    CaseDefinition def = mock(CaseDefinition.class);
    when(def.getWorkers()).thenReturn(List.of());
    ctx =
        new PlanExecutionContext(
            caseId,
            def,
            mock(CaseContext.class),
            io.casehub.api.model.CaseStatus.RUNNING,
            TenancyConstants.DEFAULT_TENANT_ID,
            List.of(),
            null,
            null);
  }

  /** Creates a minimal mock Binding with the given name and no capability. */
  private Binding binding(String name) {
    Binding b = mock(Binding.class);
    when(b.getName()).thenReturn(name);
    when(b.target()).thenReturn(null);
    return b;
  }

  /** Returns the DefaultCasePlanModel created in the registry for the current caseId. */
  private DefaultCasePlanModel plan() {
    return (DefaultCasePlanModel) registry.getOrCreate(caseId, "test-tenant");
  }

  // ------------------------------------------------------------------ //
  // Free-floating bindings (not declared in any stage)                  //
  // ------------------------------------------------------------------ //

  @Test
  void free_floating_binding_always_passes_when_no_stages_exist() {
    Binding b = binding("free-b");
    List<Binding> result = loopControl.select(ctx, List.of(b));
    assertThat(result.stream().map(Binding::getName)).contains("free-b");
  }

  @Test
  void free_floating_binding_passes_even_when_compounds_exist_but_declare_nothing() {
    var compound = PlanItemDefinition.Compound.builder("lifecycle-only").id("comp-1").build();
    plan().registerDefinition(compound);

    Binding b = binding("any-b");
    List<Binding> result = loopControl.select(ctx, List.of(b));
    assertThat(result.stream().map(Binding::getName))
        .as("binding must pass when no compound has scoped it")
        .contains("any-b");
  }

  // ------------------------------------------------------------------ //
  // Staged bindings — blocked when stage not ACTIVE                     //
  // ------------------------------------------------------------------ //

  @Test
  void scoped_binding_blocked_when_compound_is_pending() {
    var compound =
        PlanItemDefinition.Compound.builder("intake")
            .id("comp-1")
            .entryCondition(c -> false)
            .binding("staged-b")
            .build();
    plan().registerDefinition(compound);

    Binding b = binding("staged-b");
    List<Binding> result = loopControl.select(ctx, List.of(b));
    assertThat(result.stream().map(Binding::getName))
        .as("scoped binding must be blocked when its compound is PENDING")
        .doesNotContain("staged-b");
  }

  // ------------------------------------------------------------------ //
  // Staged bindings — pass when stage is ACTIVE                         //
  // ------------------------------------------------------------------ //

  @Test
  void scoped_binding_passes_when_compound_is_running() {
    var compound =
        PlanItemDefinition.Compound.builder("intake").id("comp-1").binding("staged-b").build();
    plan().registerDefinition(compound);
    plan().tryDefinitionTransition("comp-1", TaskStatus.PENDING, TaskStatus.RUNNING);

    Binding b = binding("staged-b");
    List<Binding> result = loopControl.select(ctx, List.of(b));
    assertThat(result.stream().map(Binding::getName))
        .as("scoped binding must pass when its compound is RUNNING")
        .contains("staged-b");
  }

  // ------------------------------------------------------------------ //
  // Hybrid: free-floating passes while staged binding is blocked        //
  // ------------------------------------------------------------------ //

  @Test
  void free_floating_passes_while_scoped_binding_is_blocked() {
    var compound =
        PlanItemDefinition.Compound.builder("intake")
            .id("comp-1")
            .entryCondition(c -> false)
            .binding("staged-b")
            .build();
    plan().registerDefinition(compound);

    Binding free = binding("free-b");
    Binding staged = binding("staged-b");
    List<Binding> result = loopControl.select(ctx, List.of(free, staged));

    assertThat(result.stream().map(Binding::getName))
        .as("free-floating binding must pass even when a scoped binding is blocked")
        .contains("free-b");
    assertThat(result.stream().map(Binding::getName))
        .as("scoped binding must be blocked when its compound is not RUNNING")
        .doesNotContain("staged-b");
  }

  // ------------------------------------------------------------------ //
  // Pure choreography — no binding declarations at all                  //
  // ------------------------------------------------------------------ //

  @Test
  void no_binding_declarations_means_pure_choreography() {
    var compound = PlanItemDefinition.Compound.builder("intake").id("comp-1").build();
    plan().registerDefinition(compound);

    Binding b = binding("any-b");
    List<Binding> result = loopControl.select(ctx, List.of(b));
    assertThat(result.stream().map(Binding::getName))
        .as("binding must pass when no compound has scoped it (pure choreography)")
        .contains("any-b");
  }

  // ------------------------------------------------------------------ //
  // Builder binding() declares at design time                           //
  // ------------------------------------------------------------------ //

  @Test
  void builder_declared_binding_is_gated_when_compound_pending() {
    var compound =
        PlanItemDefinition.Compound.builder("intake")
            .id("comp-1")
            .entryCondition(c -> false)
            .binding("design-b")
            .build();
    plan().registerDefinition(compound);

    Binding b = binding("design-b");
    List<Binding> result = loopControl.select(ctx, List.of(b));
    assertThat(result.stream().map(Binding::getName))
        .as("builder-declared binding must be gated when compound is PENDING")
        .doesNotContain("design-b");
  }

  @Test
  void builder_declared_binding_passes_when_compound_running() {
    var compound =
        PlanItemDefinition.Compound.builder("intake").id("comp-1").binding("design-b").build();
    plan().registerDefinition(compound);
    plan().tryDefinitionTransition("comp-1", TaskStatus.PENDING, TaskStatus.RUNNING);

    Binding b = binding("design-b");
    List<Binding> result = loopControl.select(ctx, List.of(b));
    assertThat(result.stream().map(Binding::getName))
        .as("builder-declared binding must pass when compound is RUNNING")
        .contains("design-b");
  }

  // ------------------------------------------------------------------ //
  // Case state handling — WAITING allowed, SUSPENDED/terminal blocked   //
  // ------------------------------------------------------------------ //

  @Test
  void suspendedCase_returnsEmptyList() {
    PlanExecutionContext suspended =
        new PlanExecutionContext(
            caseId,
            ctx.definition(),
            ctx.caseContext(),
            io.casehub.api.model.CaseStatus.SUSPENDED,
            TenancyConstants.DEFAULT_TENANT_ID,
            List.of(),
            null,
            null);
    Binding b = binding("any-b");

    List<Binding> result = loopControl.select(suspended, List.of(b));

    assertThat(result).isEmpty();
  }

  @Test
  void completedCase_returnsEmptyList() {
    PlanExecutionContext completed =
        new PlanExecutionContext(
            caseId,
            ctx.definition(),
            ctx.caseContext(),
            io.casehub.api.model.CaseStatus.COMPLETED,
            TenancyConstants.DEFAULT_TENANT_ID,
            List.of(),
            null,
            null);
    Binding b = binding("any-b");

    List<Binding> result = loopControl.select(completed, List.of(b));

    assertThat(result).isEmpty();
  }

  @Test
  void waitingCase_pendingPlanItem_isDispatched() {
    // WAITING case: binding has a PENDING PlanItem (never dispatched) → should fire
    Binding b = binding("fresh-b");
    PlanExecutionContext waiting =
        new PlanExecutionContext(
            caseId,
            ctx.definition(),
            ctx.caseContext(),
            io.casehub.api.model.CaseStatus.WAITING,
            TenancyConstants.DEFAULT_TENANT_ID,
            List.of(),
            null,
            null);

    // No PlanItem created yet — first time this binding is eligible
    List<Binding> result = loopControl.select(waiting, List.of(b));

    assertThat(result.stream().map(Binding::getName))
        .as("WAITING case must dispatch bindings with no existing PlanItem")
        .contains("fresh-b");
  }

  @Test
  void waitingCase_runningPlanItem_isFiltered() {
    // WAITING case: binding already has a RUNNING PlanItem → must not re-dispatch
    Binding b = binding("active-b");
    PlanExecutionContext waiting =
        new PlanExecutionContext(
            caseId,
            ctx.definition(),
            ctx.caseContext(),
            io.casehub.api.model.CaseStatus.WAITING,
            TenancyConstants.DEFAULT_TENANT_ID,
            List.of(),
            null,
            null);

    // Pre-populate a RUNNING PlanItem for this binding
    PlanItem item = PlanItem.create("active-b", ExecutorRef.of("some-worker"), 0);
    item.markRunning();
    plan().addPlanItem(item);

    List<Binding> result = loopControl.select(waiting, List.of(b));

    assertThat(result.stream().map(Binding::getName))
        .as("WAITING case must not re-dispatch a binding whose PlanItem is already RUNNING")
        .doesNotContain("active-b");
  }

  @Test
  void waitingCase_delegatedPlanItem_isFiltered() {
    // WAITING case: HumanTask already DELEGATED → must not re-dispatch
    Binding b = binding("delegated-b");
    PlanExecutionContext waiting =
        new PlanExecutionContext(
            caseId,
            ctx.definition(),
            ctx.caseContext(),
            io.casehub.api.model.CaseStatus.WAITING,
            TenancyConstants.DEFAULT_TENANT_ID,
            List.of(),
            null,
            null);

    PlanItem item = PlanItem.create("delegated-b", ExecutorRef.of("ht-worker"), 0);
    item.markDelegated();
    plan().addPlanItem(item);

    List<Binding> result = loopControl.select(waiting, List.of(b));

    assertThat(result.stream().map(Binding::getName))
        .as("WAITING case must not re-dispatch a binding whose PlanItem is DELEGATED")
        .doesNotContain("delegated-b");
  }

  @Test
  void waitingCase_completedPlanItem_canReDispatch() {
    // WAITING case: a COMPLETED binding CAN re-dispatch when its trigger fires again.
    // addPlanItemIfAbsent replaces COMPLETED items with a new PENDING PlanItem — intentional,
    // same binding can fire multiple times if conditions are met again.
    // Only IN-FLIGHT (RUNNING, DELEGATED) items are blocked from re-dispatch.
    Binding b = binding("done-b");
    PlanExecutionContext waiting =
        new PlanExecutionContext(
            caseId,
            ctx.definition(),
            ctx.caseContext(),
            io.casehub.api.model.CaseStatus.WAITING,
            TenancyConstants.DEFAULT_TENANT_ID,
            List.of(),
            null,
            null);

    PlanItem item = PlanItem.create("done-b", ExecutorRef.of("some-worker"), 0);
    item.markRunning();
    item.markCompleted();
    plan().addPlanItem(item);

    List<Binding> result = loopControl.select(waiting, List.of(b));

    assertThat(result.stream().map(Binding::getName))
        .as("COMPLETED binding may re-dispatch if trigger conditions are met again")
        .contains("done-b");
  }

  @Test
  void runningCase_runningPlanItem_isAlsoFiltered() {
    // filterToDispatchable benefits RUNNING cases too — prevents re-dispatch of in-flight bindings
    Binding b = binding("in-flight-b");

    PlanItem item = PlanItem.create("in-flight-b", ExecutorRef.of("some-worker"), 0);
    item.markRunning();
    plan().addPlanItem(item);

    List<Binding> result = loopControl.select(ctx, List.of(b));

    assertThat(result.stream().map(Binding::getName))
        .as("RUNNING case must not re-dispatch a binding whose PlanItem is already RUNNING")
        .doesNotContain("in-flight-b");
  }

  // ------------------------------------------------------------------ //
  // Auto-registration: PlanItems registered with owning stage           //
  // Refs casehubio/engine#497                                           //
  // ------------------------------------------------------------------ //

  @Test
  void scoped_binding_creates_planItem_and_dispatches_when_compound_running() {
    var compound =
        PlanItemDefinition.Compound.builder("intake").id("comp-1").binding("staged-b").build();
    plan().registerDefinition(compound);
    plan().tryDefinitionTransition("comp-1", TaskStatus.PENDING, TaskStatus.RUNNING);

    Binding b = binding("staged-b");
    List<Binding> result = loopControl.select(ctx, List.of(b));

    assertThat(result.stream().map(Binding::getName))
        .as("scoped binding must dispatch when compound is RUNNING")
        .contains("staged-b");
    assertThat(plan().getPlanItemByBindingName("staged-b"))
        .as("PlanItem must be created for the dispatched binding")
        .isPresent();
  }

  @Test
  void free_floating_binding_dispatches_independently_of_compounds() {
    var compound =
        PlanItemDefinition.Compound.builder("intake").id("comp-1").binding("other-b").build();
    plan().registerDefinition(compound);
    plan().tryDefinitionTransition("comp-1", TaskStatus.PENDING, TaskStatus.RUNNING);

    Binding b = binding("free-b");
    List<Binding> result = loopControl.select(ctx, List.of(b));

    assertThat(result.stream().map(Binding::getName))
        .as("free-floating binding dispatches regardless of compound state")
        .contains("free-b");
  }

  @Test
  void duplicate_dispatch_prevention_when_planItem_already_exists() {
    var compound =
        PlanItemDefinition.Compound.builder("intake").id("comp-1").binding("staged-b").build();
    plan().registerDefinition(compound);
    plan().tryDefinitionTransition("comp-1", TaskStatus.PENDING, TaskStatus.RUNNING);

    io.casehub.worker.api.Capability cap =
        io.casehub.worker.api.Capability.builder()
            .name("cap-1")
            .inputSchema(".")
            .outputSchema(".")
            .build();
    Binding b = mock(Binding.class);
    when(b.getName()).thenReturn("staged-b");
    when(b.target()).thenReturn(new CapabilityTarget(cap));

    List<Binding> firstResult = loopControl.select(ctx, List.of(b));
    assertThat(firstResult.stream().map(Binding::getName)).contains("staged-b");

    List<Binding> secondResult = loopControl.select(ctx, List.of(b));
    assertThat(secondResult.stream().map(Binding::getName))
        .as("second select must not re-dispatch — PlanItem already RUNNING via CAS")
        .doesNotContain("staged-b");
  }
}
