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
import io.casehub.api.engine.PlanExecutionContext;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.blackboard.control.BlackboardPlanConfigurer;
import io.casehub.blackboard.plan.CasePlanModel;
import io.casehub.blackboard.registry.BlackboardRegistry;
import io.casehub.blackboard.stage.Stage;
import io.casehub.blackboard.stage.StageStatus;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link BlackboardPlanConfigurer} — verifies that a CDI bean implementing
 * the interface has its {@code configure()} called when a case starts, that stages it adds appear
 * in the plan model, and that it is called exactly once per case instance. See casehubio/engine#76.
 *
 * <p>Design: the {@link ConfiguredCaseBean} starts with a value that does not trigger its binding
 * ({@code ready=true} vs. trigger on {@code .probe == "tick"}). The plan model is created on the
 * first {@code select()} call fired by the engine, giving the configurer a chance to run before any
 * worker fires. A subsequent signal triggers activity — allowing the "called exactly once"
 * assertion to be made after multiple evaluation cycles.
 */
@QuarkusTest
class PlanConfigurerBlackboardTest {

  /** Static call counter — persists across CDI proxy invocations of {@link ConfigurerBean}. */
  static final AtomicInteger callCount = new AtomicInteger(0);

  @Inject BlackboardRegistry registry;
  @Inject ConfiguredCaseBean configuredCase;

  // ------------------------------------------------------------------ //
  // configure() is called on case start                                  //
  // ------------------------------------------------------------------ //

  /**
   * Verifies that the configurer runs before any binding fires: the plan model exists and contains
   * the stage the configurer added, all before the probe signal is sent.
   */
  @Test
  void configurer_is_called_and_stage_appears_in_plan_model() {
    // Reset call count — tests may run in any order within the same CDI context
    callCount.set(0);

    // Start with a value that does NOT trigger the binding (.probe == "tick")
    UUID caseId = configuredCase.startCase(Map.of("ready", true)).toCompletableFuture().join();

    // Wait for the registry entry — plan model is created on first select() from CaseStartedEvent
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(registry.get(caseId)).isPresent());

    // Configurer must have run — the plan model should contain the stage it declared
    CasePlanModel plan = registry.get(caseId).get();
    assertThat(plan.getAllStages())
        .as("configurer must have added 'configurer-stage' to the plan model")
        .anyMatch(s -> s.getName().equals("configurer-stage"));
  }

  // ------------------------------------------------------------------ //
  // configure() is called exactly once per case instance                 //
  // ------------------------------------------------------------------ //

  /**
   * Verifies configure() is called exactly once even after multiple evaluation cycles. Two probe
   * signals force additional {@code select()} calls; the call count must not increase beyond 1.
   */
  @Test
  void configurer_is_called_exactly_once_per_case_instance() {
    callCount.set(0);

    UUID caseId = configuredCase.startCase(Map.of("ready", true)).toCompletableFuture().join();

    // Wait for plan model to be created — guarantees the first select() (and configure()) ran
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(registry.get(caseId)).isPresent());

    // Force additional select() cycles via signals — configure() must NOT be called again
    configuredCase.signal(caseId, "probe", "tick");
    configuredCase.signal(caseId, "probe", "tick-2");

    // Give the engine time to process both signals
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(callCount.get())
                    .as("configure() must have been called at least once")
                    .isGreaterThanOrEqualTo(1));

    assertThat(callCount.get())
        .as("configure() must be called exactly once per case — not on every select() cycle")
        .isEqualTo(1);
  }

  // ------------------------------------------------------------------ //
  // Stage added by configurer is evaluatable                             //
  // ------------------------------------------------------------------ //

  /**
   * Verifies that a stage with no entry condition (always activates) that was added by the
   * configurer transitions to ACTIVE on the first evaluation cycle triggered by a signal.
   */
  @Test
  void stage_added_by_configurer_activates_on_evaluation_cycle() {
    callCount.set(0);

    UUID caseId = configuredCase.startCase(Map.of("ready", true)).toCompletableFuture().join();

    // Wait for registry — configurer has run and stage is in the plan model
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(registry.get(caseId)).isPresent());

    CasePlanModel plan = registry.get(caseId).get();

    // Find the stage the configurer added
    Stage configurerStage =
        plan.getAllStages().stream()
            .filter(s -> s.getName().equals("configurer-stage"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("configurer-stage not found in plan model"));

    // Signal a probe value — triggers a CONTEXT_CHANGED → select() → stage evaluation cycle
    configuredCase.signal(caseId, "probe", "tick");

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(configurerStage.getStatus())
                    .as("configurer stage with no entry condition must activate on evaluation")
                    .isEqualTo(StageStatus.ACTIVE));
  }

  // ------------------------------------------------------------------ //
  // Test beans                                                            //
  // ------------------------------------------------------------------ //

  /**
   * {@link BlackboardPlanConfigurer} CDI bean. Adds a stage with no entry condition (always
   * activates) and increments the static call counter to verify exactly-once semantics.
   *
   * <p>{@code @ApplicationScoped} ensures CDI auto-discovery in the test CDI context. The static
   * {@link PlanConfigurerBlackboardTest#callCount} field is used rather than an instance field so
   * the count survives CDI proxy invocations.
   */
  @ApplicationScoped
  public static class ConfigurerBean implements BlackboardPlanConfigurer {

    @Override
    public void configure(CasePlanModel plan, PlanExecutionContext context) {
      callCount.incrementAndGet();
      plan.addStage(Stage.alwaysActivate("configurer-stage"));
    }
  }

  /**
   * Case whose binding fires only when a signal writes {@code probe=tick}. Starts with {@code
   * ready=true} — binding never fires on start. Suitable for idle-start + signal pattern.
   */
  @ApplicationScoped
  public static class ConfiguredCaseBean extends CaseHub {

    private final Capability cap =
        Capability.builder()
            .name("configured-cap")
            .inputSchema("{ probe: .probe }")
            .outputSchema("{ probe: .probe }")
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("blackboard-configurer-it")
          .name("Configured Case")
          .version("1.0.0")
          .capabilities(cap)
          .workers(
              Worker.builder()
                  .name("configured-worker")
                  .capabilityName("configured-cap")
                  .function(
                      new WorkerFunction.Sync<>(
                          Map.class,
                          Map.class,
                          (input, scope) -> WorkerResult.of(Map.of("probe", "done"))))
                  .build())
          .bindings(
              Binding.builder()
                  .name("on-probe-tick")
                  .capability(cap)
                  .on(new ContextChangeTrigger(".probe == \"tick\""))
                  .build())
          // No goals — case stays RUNNING for post-signal assertions
          .build();
    }
  }
}
