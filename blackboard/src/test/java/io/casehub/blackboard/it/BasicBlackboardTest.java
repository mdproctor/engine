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
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.Milestone;
import io.casehub.api.model.TaskStatus;
import io.casehub.blackboard.registry.BlackboardRegistry;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Happy path integration tests — verifies {@link
 * io.casehub.blackboard.control.PlanningStrategyLoopControl} routes eligible bindings through the
 * blackboard control layer, populates the {@link BlackboardRegistry}, and marks PlanItems through
 * their lifecycle. See casehubio/engine#76.
 *
 * <p>Design notes:
 *
 * <ul>
 *   <li>Workers write output that falsifies their own trigger condition (phase "start" → "done"),
 *       preventing re-trigger of the same binding and stable PlanItem status assertions.
 *   <li>Output schemas use {@code { key: .key }} JQ form — {@code {}} is an empty JQ object and
 *       would filter all output to empty, preventing goal/context propagation.
 * </ul>
 */
@QuarkusTest
class BasicBlackboardTest {

  @Inject BlackboardRegistry registry;
  @Inject CaseInstanceCache caseInstanceCache;
  @Inject OnceCaseBean onceCase;
  @Inject NeverTriggerCaseBean neverTriggerCase;
  @Inject CompletingCaseBean completingCase;
  @Inject MilestoneCaseBean milestoneCase;

  // ------------------------------------------------------------------ //
  // Registry population                                                  //
  // ------------------------------------------------------------------ //

  @Test
  void blackboard_registry_has_plan_model_after_case_starts_and_binding_fires() {
    UUID caseId = onceCase.startCase(Map.of("phase", "start"));

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(registry.get(caseId)).isPresent());
  }

  @Test
  void registry_indexes_worker_name_to_plan_item_id_after_selection() {
    UUID caseId = onceCase.startCase(Map.of("phase", "start"));

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(registry.getPlanItemId(caseId, "once-worker"))
                    .as("worker must be indexed in BlackboardRegistry after PlanItem is selected")
                    .isPresent());
  }

  // ------------------------------------------------------------------ //
  // PlanItem lifecycle: PENDING → RUNNING → COMPLETED                   //
  // ------------------------------------------------------------------ //

  @Test
  void plan_item_is_no_longer_pending_after_worker_runs() {
    UUID caseId = onceCase.startCase(Map.of("phase", "start"));

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var planOpt = registry.get(caseId);
              assertThat(planOpt).isPresent();
              // getAgenda() returns only PENDING items — once the worker is selected (RUNNING)
              // or completed, the item no longer appears here
              assertThat(planOpt.get().getAgenda())
                  .as("agenda must be empty after the single worker has been selected/run")
                  .isEmpty();
            });
  }

  @Test
  void plan_item_status_transitions_to_completed_after_worker_finishes() {
    // Worker writes phase=done → trigger (.phase == "start") no longer matches → no re-fire.
    // This gives a stable COMPLETED state to assert on.
    UUID caseId = onceCase.startCase(Map.of("phase", "start"));

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var planItemId = registry.getPlanItemId(caseId, "once-worker");
              assertThat(planItemId).isPresent();

              var planOpt = registry.get(caseId);
              assertThat(planOpt).isPresent();
              var item = planOpt.get().getPlanItem(planItemId.get());
              assertThat(item).isPresent();
              assertThat(item.get().getStatus())
                  .as("PlanItem must be COMPLETED after worker finishes")
                  .isEqualTo(TaskStatus.COMPLETED);
            });
  }

  // ------------------------------------------------------------------ //
  // Idle case: registry created but agenda stays empty                   //
  // ------------------------------------------------------------------ //

  @Test
  void idle_case_with_no_matching_binding_has_empty_agenda() {
    // The trigger never fires because "idle" doesn't match ".phase == \"start\"".
    // The registry plan model may still be created (select() is called with empty eligible),
    // but the agenda must remain empty.
    UUID caseId = neverTriggerCase.startCase(Map.of("status", "idle"));

    // Give the engine a moment to settle in RUNNING state
    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseId);
              assertThat(instance).isNotNull();
              assertThat(instance.getState()).isEqualTo(CaseStatus.RUNNING);
            });

    // Even if a plan model was created, the agenda must be empty (no PlanItems were ever added)
    registry
        .get(caseId)
        .ifPresent(
            plan ->
                assertThat(plan.getAgenda())
                    .as("idle case agenda must be empty — binding never triggered")
                    .isEmpty());
  }

  // ------------------------------------------------------------------ //
  // Completion via goal                                                   //
  // ------------------------------------------------------------------ //

  @Test
  void case_completes_after_worker_satisfies_success_goal() {
    UUID caseId = completingCase.startCase(Map.of("phase", "active"));

    await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseId);
              assertThat(instance).isNotNull();
              assertThat(instance.getState())
                  .as("case must reach COMPLETED state after worker writes phase=done")
                  .isEqualTo(CaseStatus.COMPLETED);
            });
  }

  // ------------------------------------------------------------------ //
  // Milestone tracking                                                   //
  // ------------------------------------------------------------------ //

  /**
   * Verifies that {@link io.casehub.blackboard.handler.MilestoneAchievementHandler} marks a tracked
   * milestone as achieved in the plan model when the engine publishes a MilestoneReachedEvent. The
   * milestone is pre-tracked in the plan model, the worker writes output that satisfies the
   * milestone condition, and the handler promotes it to ACHIEVED.
   */
  @Test
  void milestone_is_achieved_in_plan_model_after_condition_met() {
    UUID caseId = milestoneCase.startCase(Map.of("phase", "start"));

    // Wait for plan model to be created on first select()
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(registry.get(caseId)).isPresent());

    // Pre-track the milestone so the handler can promote it when MilestoneReachedEvent fires
    registry.get(caseId).get().trackMilestone("docs-received");

    // Signal the context change that triggers the worker to write docsUploaded=true,
    // which satisfies the milestone condition
    milestoneCase.signal(caseId, "go", true);

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(registry.get(caseId).get().isMilestoneAchieved("docs-received"))
                    .as("milestone must be ACHIEVED after condition is satisfied")
                    .isTrue());
  }

  // ------------------------------------------------------------------ //
  // Test beans                                                            //
  // ------------------------------------------------------------------ //

  /**
   * Case that fires once on {@code .phase == "start"}, then the worker writes {@code phase=done}
   * which falsifies the trigger — no re-scheduling. Stable for PlanItem completion assertions.
   */
  @ApplicationScoped
  public static class OnceCaseBean extends CaseHub {

    private final Capability cap =
        Capability.builder()
            .name("once-cap")
            .inputSchema("{ phase: .phase }")
            .outputSchema("{ phase: .phase }")
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("blackboard-basic-it")
          .name("Once Case")
          .version("1.0.0")
          .capabilities(cap)
          .workers(
              Worker.builder()
                  .name("once-worker")
                  .capabilityName("once-cap")
                  // Write phase=done — trigger (.phase == "start") won't match again
                  .function(
                      new WorkerFunction.Sync<>(
                          Map.class,
                          Map.class,
                          (input, scope) -> WorkerResult.of(Map.of("phase", "done"))))
                  .build())
          .bindings(
              Binding.builder()
                  .name("on-start-phase")
                  .capability(cap)
                  .on(new ContextChangeTrigger(".phase == \"start\""))
                  .build())
          // No goals — case stays RUNNING, enabling post-completion assertions
          .build();
    }
  }

  /**
   * Case whose binding trigger never fires (context has {@code status=idle}, trigger needs {@code
   * phase=start}). Agenda stays empty.
   */
  @ApplicationScoped
  public static class NeverTriggerCaseBean extends CaseHub {

    private final Capability cap =
        Capability.builder()
            .name("never-cap")
            .inputSchema("{ status: .status }")
            .outputSchema("{ status: .status }")
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("blackboard-basic-it")
          .name("Never Trigger Case")
          .version("1.0.0")
          .capabilities(cap)
          .workers(
              Worker.builder()
                  .name("never-worker")
                  .capabilityName("never-cap")
                  .function(
                      new WorkerFunction.Sync<>(
                          Map.class, Map.class, (input, scope) -> WorkerResult.of(Map.of())))
                  .build())
          .bindings(
              Binding.builder()
                  .name("trigger-when-active")
                  .capability(cap)
                  .on(
                      new ContextChangeTrigger(
                          ".phase == \"start\"")) // context has "status", not "phase"
                  .build())
          .build();
    }
  }

  /**
   * Case that fires on {@code .phase == "active"}, worker writes {@code phase=done}, and a SUCCESS
   * goal on {@code .phase == "done"} completes the case.
   */
  @ApplicationScoped
  public static class CompletingCaseBean extends CaseHub {

    private final Capability cap =
        Capability.builder()
            .name("completing-cap")
            .inputSchema("{ phase: .phase }")
            .outputSchema("{ phase: .phase }")
            .build();

    private final Goal successGoal =
        Goal.builder()
            .name("processingComplete")
            .condition(".phase == \"done\"")
            .kind(GoalKind.SUCCESS)
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("blackboard-basic-it")
          .name("Completing Case")
          .version("1.0.0")
          .capabilities(cap)
          .workers(
              Worker.builder()
                  .name("completing-worker")
                  .capabilityName("completing-cap")
                  .function(
                      new WorkerFunction.Sync<>(
                          Map.class,
                          Map.class,
                          (input, scope) -> WorkerResult.of(Map.of("phase", "done"))))
                  .build())
          .bindings(
              Binding.builder()
                  .name("trigger-on-active")
                  .capability(cap)
                  .on(new ContextChangeTrigger(".phase == \"active\""))
                  .build())
          .goals(successGoal)
          .completion(GoalExpression.allOf(successGoal))
          .build();
    }
  }

  /**
   * Case with a milestone condition ({@code .docsUploaded == true}). Binding fires when {@code .go
   * == true} — worker writes {@code docsUploaded=true}, satisfying the milestone. No goals — case
   * stays RUNNING for milestone assertion.
   */
  @ApplicationScoped
  public static class MilestoneCaseBean extends CaseHub {

    private final Capability cap =
        Capability.builder()
            .name("milestone-cap")
            .inputSchema("{ go: .go }")
            .outputSchema("{ go: .go, docsUploaded: .docsUploaded }")
            .build();

    private final Milestone docsReceived =
        Milestone.builder()
            .name("docs-received")
            .completionCriteria(".docsUploaded == true")
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("blackboard-basic-it")
          .name("Milestone Case")
          .version("1.0.0")
          .capabilities(cap)
          .workers(
              Worker.builder()
                  .name("milestone-worker")
                  .capabilityName("milestone-cap")
                  .function(
                      new WorkerFunction.Sync<>(
                          Map.class,
                          Map.class,
                          (input, scope) ->
                              WorkerResult.of(Map.of("go", false, "docsUploaded", true))))
                  .build())
          .bindings(
              Binding.builder()
                  .name("on-go-true")
                  .capability(cap)
                  .on(new ContextChangeTrigger(".go == true"))
                  .build())
          .milestones(docsReceived)
          // No goals — case stays RUNNING for milestone assertions
          .build();
    }
  }
}
