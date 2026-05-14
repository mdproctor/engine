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
import io.casehub.api.model.CaseDefinition;
import io.casehub.blackboard.plan.DefaultCasePlanModel;
import io.casehub.blackboard.registry.BlackboardRegistry;
import io.casehub.blackboard.stage.Stage;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.inject.Instance;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the staged-vs-free-floating binding gating in {@link PlanningStrategyLoopControl}.
 *
 * <p>ADR-0002 convention: presence of {@link Stage#addBinding(String)} is the opt-in. Three modes
 * emerge from one mechanism:
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
    DefaultPlanningStrategy strategy = new DefaultPlanningStrategy();

    // StageLifecycleEvaluator — package-private constructor for unit tests (no EventBus events).
    StageLifecycleEvaluator evaluator = mock(StageLifecycleEvaluator.class);
    when(evaluator.evaluate(any(), any())).thenReturn(Uni.createFrom().voidItem());

    // Mock an empty Instance<BlackboardPlanConfigurer> — no configurers needed for gating tests.
    Instance<BlackboardPlanConfigurer> emptyConfigurers = mock(Instance.class);
    when(emptyConfigurers.stream()).thenReturn(Stream.empty());

    loopControl = new PlanningStrategyLoopControl(registry, strategy, evaluator, emptyConfigurers);

    caseId = UUID.randomUUID();
    CaseDefinition def = mock(CaseDefinition.class);
    when(def.getWorkers()).thenReturn(List.of());
    ctx = new PlanExecutionContext(caseId, def, mock(CaseContext.class));
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
    return (DefaultCasePlanModel) registry.getOrCreate(caseId);
  }

  // ------------------------------------------------------------------ //
  // Free-floating bindings (not declared in any stage)                  //
  // ------------------------------------------------------------------ //

  @Test
  void free_floating_binding_always_passes_when_no_stages_exist() {
    Binding b = binding("free-b");
    List<Binding> result = loopControl.select(ctx, List.of(b)).await().indefinitely();
    assertThat(result.stream().map(Binding::getName)).contains("free-b");
  }

  @Test
  void free_floating_binding_passes_even_when_stages_exist_but_declare_nothing() {
    // Stage exists but has no addBinding() call — lifecycle-only, no gating
    plan().addStage(Stage.alwaysActivate("lifecycle-only"));

    Binding b = binding("any-b");
    List<Binding> result = loopControl.select(ctx, List.of(b)).await().indefinitely();
    assertThat(result.stream().map(Binding::getName))
        .as("binding must pass when no stage has declared ownership of it")
        .contains("any-b");
  }

  // ------------------------------------------------------------------ //
  // Staged bindings — blocked when stage not ACTIVE                     //
  // ------------------------------------------------------------------ //

  @Test
  void staged_binding_blocked_when_stage_is_pending() {
    Stage stage = Stage.alwaysActivate("intake"); // PENDING, not yet activated
    stage.addBinding("staged-b");
    plan().addStage(stage);

    Binding b = binding("staged-b");
    List<Binding> result = loopControl.select(ctx, List.of(b)).await().indefinitely();
    assertThat(result.stream().map(Binding::getName))
        .as("staged binding must be blocked when its stage is PENDING")
        .doesNotContain("staged-b");
  }

  // ------------------------------------------------------------------ //
  // Staged bindings — pass when stage is ACTIVE                         //
  // ------------------------------------------------------------------ //

  @Test
  void staged_binding_passes_when_stage_is_active() {
    Stage stage = Stage.alwaysActivate("intake");
    stage.activate(); // explicitly activate
    stage.addBinding("staged-b");
    plan().addStage(stage);

    Binding b = binding("staged-b");
    List<Binding> result = loopControl.select(ctx, List.of(b)).await().indefinitely();
    assertThat(result.stream().map(Binding::getName))
        .as("staged binding must pass when its stage is ACTIVE")
        .contains("staged-b");
  }

  // ------------------------------------------------------------------ //
  // Hybrid: free-floating passes while staged binding is blocked        //
  // ------------------------------------------------------------------ //

  @Test
  void free_floating_passes_while_staged_binding_is_blocked() {
    Stage stage = Stage.alwaysActivate("intake"); // PENDING
    stage.addBinding("staged-b");
    plan().addStage(stage);

    Binding free = binding("free-b");
    Binding staged = binding("staged-b");
    List<Binding> result = loopControl.select(ctx, List.of(free, staged)).await().indefinitely();

    assertThat(result.stream().map(Binding::getName))
        .as("free-floating binding must pass even when a staged binding is blocked")
        .contains("free-b");
    assertThat(result.stream().map(Binding::getName))
        .as("staged binding must be blocked when its stage is not ACTIVE")
        .doesNotContain("staged-b");
  }

  // ------------------------------------------------------------------ //
  // Pure choreography — no binding declarations at all                  //
  // ------------------------------------------------------------------ //

  @Test
  void no_binding_declarations_means_pure_choreography() {
    // Stage exists but declares no bindings — lifecycle-only, zero gating effect
    Stage stage = Stage.alwaysActivate("intake");
    plan().addStage(stage);

    Binding b = binding("any-b");
    List<Binding> result = loopControl.select(ctx, List.of(b)).await().indefinitely();
    assertThat(result.stream().map(Binding::getName))
        .as("binding must pass when no stage has declared ownership of it (pure choreography)")
        .contains("any-b");
  }

  // ------------------------------------------------------------------ //
  // Builder binding() declares at design time                           //
  // ------------------------------------------------------------------ //

  @Test
  void builder_declared_binding_is_gated_when_stage_pending() {
    Stage stage = Stage.builder("intake").entryCondition(c -> true).binding("design-b").build();
    // Note: stage is PENDING — not manually activated
    plan().addStage(stage);

    Binding b = binding("design-b");
    List<Binding> result = loopControl.select(ctx, List.of(b)).await().indefinitely();
    assertThat(result.stream().map(Binding::getName))
        .as("builder-declared binding must be gated just like addBinding()")
        .doesNotContain("design-b");
  }

  @Test
  void builder_declared_binding_passes_when_stage_active() {
    Stage stage = Stage.builder("intake").entryCondition(c -> true).binding("design-b").build();
    stage.activate();
    plan().addStage(stage);

    Binding b = binding("design-b");
    List<Binding> result = loopControl.select(ctx, List.of(b)).await().indefinitely();
    assertThat(result.stream().map(Binding::getName))
        .as("builder-declared binding must pass when stage is ACTIVE")
        .contains("design-b");
  }
}
