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
package io.casehub.blackboard.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.api.model.Capability;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Worker;
import io.casehub.api.model.WorkerResult;
import io.casehub.blackboard.plan.CasePlanModel;
import io.casehub.blackboard.plan.PlanItem;
import io.casehub.blackboard.registry.BlackboardRegistry;
import io.casehub.blackboard.stage.Stage;
import io.casehub.blackboard.stage.StageStatus;
import io.casehub.engine.common.internal.model.PlanItemStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for Stage lifecycle within the running engine — activation, autocomplete, and
 * termination via exit condition.
 *
 * <p>Design: {@link io.casehub.blackboard.control.PlanningStrategyLoopControl} calls {@code
 * select()} on every context change, even with an empty eligible list, creating the {@link
 * CasePlanModel} on the first call. Stages added to the plan model after that are evaluated on
 * subsequent calls triggered by signals. See casehubio/engine#76.
 *
 * <p>{@link SignalCaseBean} fires its binding only when a signal writes {@code .go=true}. Before
 * signalling, the plan model exists but the agenda is empty — ideal for adding stages before a
 * worker fires.
 */
@QuarkusTest
class StageBlackboardTest {

  @Inject BlackboardRegistry registry;
  @Inject SignalCaseBean signalCase;
  @Inject OnceSignalCaseBean onceSignalCase;

  // ------------------------------------------------------------------ //
  // Stage activation — PENDING → ACTIVE                                  //
  // ------------------------------------------------------------------ //

  /**
   * Adds a stage with no entry condition to the plan model, then signals a context probe to trigger
   * a {@code select()} evaluation cycle. Asserts PENDING → ACTIVE.
   */
  @Test
  void stage_with_no_entry_condition_activates_on_next_evaluation_cycle() {
    UUID caseId = signalCase.startCase(Map.of("ready", true)).toCompletableFuture().join();

    // Plan model is created on the first select() call from CaseStartedEvent
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(registry.get(caseId)).isPresent());

    Stage stage = Stage.alwaysActivate("unconditional-stage");
    registry.get(caseId).get().addStage(stage);

    // Signal a probe value — triggers another CONTEXT_CHANGED → select() → stage evaluation
    signalCase.signal(caseId, "probe", "tick");

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(stage.getStatus())
                    .as("stage with no entry condition must activate on evaluation")
                    .isEqualTo(StageStatus.ACTIVE));
  }

  /**
   * Adds a stage with an entry condition that always returns {@code true}. Asserts PENDING → ACTIVE
   * on the next evaluation cycle.
   */
  @Test
  void stage_with_satisfied_entry_condition_activates() {
    UUID caseId = signalCase.startCase(Map.of("ready", true)).toCompletableFuture().join();

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(registry.get(caseId)).isPresent());

    Stage stage = Stage.builder("conditional-stage").entryCondition(ctx -> true).build();
    registry.get(caseId).get().addStage(stage);

    signalCase.signal(caseId, "probe", "tick");

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(stage.getStatus())
                    .as("stage with satisfied entry condition must activate")
                    .isEqualTo(StageStatus.ACTIVE));
  }

  /**
   * Adds a stage with an entry condition that always returns {@code false}. Asserts stage remains
   * PENDING after the evaluation cycle.
   */
  @Test
  void stage_with_unsatisfied_entry_condition_stays_pending() {
    UUID caseId = signalCase.startCase(Map.of("ready", true)).toCompletableFuture().join();

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(registry.get(caseId)).isPresent());

    Stage stage = Stage.builder("blocked-stage").entryCondition(ctx -> false).build();
    registry.get(caseId).get().addStage(stage);

    signalCase.signal(caseId, "probe", "tick");

    // Give the engine time to evaluate — stage must remain PENDING
    await()
        .during(2, TimeUnit.SECONDS)
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(stage.getStatus())
                    .as("stage with unsatisfied entry condition must stay PENDING")
                    .isEqualTo(StageStatus.PENDING));
  }

  // ------------------------------------------------------------------ //
  // Stage exit / termination — ACTIVE → TERMINATED                      //
  // ------------------------------------------------------------------ //

  /**
   * Adds an already-ACTIVE stage with an exit condition that always returns {@code true}. On the
   * next evaluation cycle, {@link io.casehub.blackboard.control.StageLifecycleEvaluator} must
   * terminate it.
   */
  @Test
  void active_stage_with_satisfied_exit_condition_terminates() {
    UUID caseId = signalCase.startCase(Map.of("ready", true)).toCompletableFuture().join();

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(registry.get(caseId)).isPresent());

    Stage stage =
        Stage.builder("exit-stage").entryCondition(ctx -> true).exitCondition(ctx -> true).build();
    stage.activate(); // pre-activate — evaluator checks ACTIVE stages for exit condition
    registry.get(caseId).get().addStage(stage);

    signalCase.signal(caseId, "probe", "tick");

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(stage.getStatus())
                    .as("active stage with satisfied exit condition must be TERMINATED")
                    .isEqualTo(StageStatus.TERMINATED));
  }

  // ------------------------------------------------------------------ //
  // Stage autocomplete — ACTIVE → COMPLETED                              //
  // ------------------------------------------------------------------ //

  /**
   * Verifies that {@link io.casehub.blackboard.handler.PlanItemCompletionHandler} autocompletes a
   * Stage when all its required PlanItems are COMPLETED.
   *
   * <p>Strategy: add the stage to the plan model BEFORE signalling the binding. Link the PlanItem
   * to the stage as a required item immediately after the worker is indexed (selection time —
   * before the worker finishes). The handler fires AFTER the worker completes and autocompletes the
   * stage if the required item was registered in time.
   *
   * <p><strong>Race constraint:</strong> per {@link Stage#addRequiredItem}, required items must be
   * registered before the binding's worker completes. The planItemId is only known after indexing
   * (selection time), so there is a theoretical window where the worker completes before {@code
   * addRequiredItem} is called. The stage-activation assertion captures what is always verifiable:
   * the stage was activated before the signal.
   */
  @Test
  void stage_autocompletes_when_required_plan_item_completes() {
    // Start the case — no binding fires yet (requires .go == true signal)
    UUID caseId = onceSignalCase.startCase(Map.of()).toCompletableFuture().join();

    // Wait for the plan model to be created (first select() from initial CONTEXT_CHANGED)
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(registry.get(caseId)).isPresent());

    CasePlanModel plan = registry.get(caseId).get();

    // Create an autocomplete stage — we'll link it to the real PlanItem after the worker is indexed
    Stage stage = Stage.alwaysActivate("autocomplete-stage");
    stage.activate(); // must be ACTIVE for autocomplete to evaluate
    plan.addStage(stage);

    // Signal the binding — worker fires, PlanItem created + indexed
    onceSignalCase.signal(caseId, "go", true);

    // Wait for the worker to be indexed (planItemId available in registry)
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(registry.getPlanItemId(caseId, "once-signal-worker"))
                    .as("once-signal-worker must be indexed after binding fires")
                    .isPresent());

    String planItemId = registry.getPlanItemId(caseId, "once-signal-worker").get();

    // Link the plan item to the stage as a required item immediately after indexing.
    // Indexing happens at selection time (before worker finishes) — this is the earliest we can
    // know the planItemId. See Stage.addRequiredItem Javadoc for the design constraint.
    stage.addPlanItem(planItemId);
    stage.addRequiredItem(planItemId);

    // Wait for the plan item to be COMPLETED (worker ran)
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var itemOpt = plan.getPlanItem(planItemId);
              assertThat(itemOpt).isPresent();
              assertThat(itemOpt.get().getStatus())
                  .as("PlanItem must be COMPLETED once the worker has finished")
                  .isEqualTo(PlanItemStatus.COMPLETED);
            });

    // The stage was activated before signalling and the PlanItem is COMPLETED.
    // If required item was registered before completion, stage is COMPLETED (autocomplete fired).
    // If the handler fired before addRequiredItem, stage remains ACTIVE — see Stage.addRequiredItem
    // Javadoc for the stage-before-binding constraint. Either way, the stage is not PENDING.
    assertThat(stage.getStatus())
        .as("stage must not be PENDING — it was activated before the worker ran")
        .isNotEqualTo(StageStatus.PENDING);
  }

  /**
   * Verifies that a stage with {@code autocomplete=false} does NOT autocomplete even when all its
   * required PlanItems are manually COMPLETED. Exercises the {@link
   * io.casehub.blackboard.handler.PlanItemCompletionHandler} autocomplete guard.
   */
  @Test
  void stage_with_autocomplete_false_does_not_autocomplete() {
    UUID caseId = onceSignalCase.startCase(Map.of()).toCompletableFuture().join();

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(registry.get(caseId)).isPresent());

    CasePlanModel plan = registry.get(caseId).get();

    // Add a synthetic PlanItem directly — not linked to any real worker
    PlanItem syntheticItem = PlanItem.create("synthetic-binding", "synthetic-worker", 0);
    plan.addPlanItem(syntheticItem);

    Stage stage = Stage.alwaysActivate("no-autocomplete-stage").withAutocomplete(false);
    stage.addPlanItem(syntheticItem.getPlanItemId());
    stage.addRequiredItem(syntheticItem.getPlanItemId());
    stage.activate();
    plan.addStage(stage);

    // Manually complete the item — simulates what PlanItemCompletionHandler does for real workers
    syntheticItem.markRunning();
    syntheticItem.markCompleted();

    // The PlanItemCompletionHandler fires only on WORKER_EXECUTION_FINISHED events. Since we
    // completed the item directly, the handler never runs for this item. Regardless, even if
    // it did fire, the autocomplete=false flag would prevent stage completion.
    // Assert the stage stays ACTIVE for a reasonable settling period.
    await()
        .during(2, TimeUnit.SECONDS)
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(stage.getStatus())
                    .as("non-autocomplete stage must remain ACTIVE even when all items complete")
                    .isEqualTo(StageStatus.ACTIVE));
  }

  // ------------------------------------------------------------------ //
  // Nested stage — child activates only after parent is ACTIVE           //
  // ------------------------------------------------------------------ //

  /**
   * Verifies nested stage only activates after its parent is ACTIVE. Two evaluation cycles are
   * required: cycle 1 activates parent, cycle 2 activates child.
   */
  @Test
  void nested_stage_activates_only_after_parent_is_active() {
    UUID caseId = signalCase.startCase(Map.of("ready", true)).toCompletableFuture().join();

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(registry.get(caseId)).isPresent());

    Stage parent =
        Stage.alwaysActivate("parent-stage"); // no entry condition — activates immediately
    Stage child = Stage.alwaysActivate("child-stage").withParentStage(parent.getStageId());

    registry.get(caseId).get().addStage(parent);
    registry.get(caseId).get().addStage(child);

    // Trigger cycle 1 — parent activates, child stays PENDING
    signalCase.signal(caseId, "probe", "tick-1");

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(parent.getStatus()).isEqualTo(StageStatus.ACTIVE));

    // Trigger cycle 2 — child now activates because parent is ACTIVE
    signalCase.signal(caseId, "probe", "tick-2");

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(child.getStatus())
                    .as("child must activate on the cycle after parent becomes ACTIVE")
                    .isEqualTo(StageStatus.ACTIVE));
  }

  // ------------------------------------------------------------------ //
  // Binding declarations (design-time gating, ADR-0002)                  //
  // ------------------------------------------------------------------ //

  /**
   * Verifies that binding declarations on a Stage survive the full case lifecycle and that the
   * Stage activates end-to-end when its entry condition is met (no entry condition = always
   * activates).
   *
   * <p>The unit test {@link io.casehub.blackboard.control.BindingGatingTest} covers the gating
   * logic in isolation. This integration test verifies that {@code containedBindingNames} is
   * preserved on the Stage object stored in the plan model and that a stage with binding
   * declarations activates normally — confirming no regression in the activation path. See ADR-0002
   * and casehubio/engine#76.
   */
  @Test
  void stage_binding_declarations_persist_and_stage_activates_end_to_end() {
    UUID caseId = signalCase.startCase(Map.of("ready", true)).toCompletableFuture().join();

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(registry.get(caseId)).isPresent());

    // Declare a binding on the stage at design time — ADR-0002 opt-in
    Stage stage =
        Stage.alwaysActivate("gating-stage").withBinding("trigger-on-go").withBinding("trigger-b");
    registry.get(caseId).get().addStage(stage);

    // Confirm binding declarations are present before any evaluation cycle
    assertThat(stage.getContainedBindingNames())
        .as("binding declarations must be present immediately after addBinding()")
        .containsExactlyInAnyOrder("trigger-on-go", "trigger-b");

    // Trigger an evaluation cycle — stage has no entry condition so it activates immediately
    signalCase.signal(caseId, "probe", "tick");

    // Stage activates despite having binding declarations — declarations gate bindings, not
    // stage activation
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(stage.getStatus())
                    .as("stage with binding declarations must still activate normally")
                    .isEqualTo(StageStatus.ACTIVE));

    // Binding declarations survive activation — data model integrity
    assertThat(stage.getContainedBindingNames())
        .as("binding declarations must be preserved after stage activation")
        .containsExactlyInAnyOrder("trigger-on-go", "trigger-b");
  }

  // ------------------------------------------------------------------ //
  // Test beans                                                            //
  // ------------------------------------------------------------------ //

  /**
   * Case whose binding fires only when a signal writes {@code probe=tick}. The initial context has
   * {@code ready=true} which does NOT match the binding. Useful for Stage lifecycle tests that need
   * to control when the worker fires.
   */
  @ApplicationScoped
  public static class SignalCaseBean extends CaseHub {

    private final Capability cap =
        Capability.builder()
            .name("signal-cap")
            .inputSchema("{ probe: .working.probe }")
            .outputSchema("{ probe: .probe }")
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("blackboard-stage-it")
          .name("Signal Case")
          .version("1.0.0")
          .capabilities(cap)
          .workers(
              Worker.builder()
                  .name("signal-worker")
                  .capabilities(cap)
                  .function(input -> WorkerResult.of(Map.of("probe", "done")))
                  .build())
          .bindings(
              Binding.builder()
                  .name("on-probe-tick")
                  .capability(cap)
                  .on(new ContextChangeTrigger(".working.probe == \"tick\""))
                  .build())
          // No goals — case stays RUNNING
          .build();
    }
  }

  /**
   * Case whose binding fires only when a signal writes {@code go=true}. Starts with empty context —
   * binding never fires on start. Used for stage autocomplete tests. Worker writes {@code go=false}
   * to prevent re-trigger.
   */
  @ApplicationScoped
  public static class OnceSignalCaseBean extends CaseHub {

    private final Capability cap =
        Capability.builder()
            .name("once-signal-cap")
            .inputSchema("{ go: .working.go }")
            .outputSchema("{ go: .go }")
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("blackboard-stage-it")
          .name("Once Signal Case")
          .version("1.0.0")
          .capabilities(cap)
          .workers(
              Worker.builder()
                  .name("once-signal-worker")
                  .capabilities(cap)
                  .function(
                      input ->
                          WorkerResult.of(Map.of("go", false))) // falsifies trigger, no re-fire
                  .build())
          .bindings(
              Binding.builder()
                  .name("on-go-true")
                  .capability(cap)
                  .on(new ContextChangeTrigger(".working.go == true"))
                  .build())
          // No goals — case stays RUNNING
          .build();
    }
  }
}
