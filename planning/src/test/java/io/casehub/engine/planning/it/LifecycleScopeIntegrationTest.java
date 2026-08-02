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
package io.casehub.engine.planning.it;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.ExecutionMode;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.LifecycleScope;
import io.casehub.api.model.Participation;
import io.casehub.api.model.StandardGoalKind;
import io.casehub.api.model.TaskStatus;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.engine.common.internal.worker.scope.ScopedWorkerRegistry;
import io.casehub.engine.common.internal.worker.scope.ScopedWorkerSession;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.planning.plan.PlanItemDefinition;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

@QuarkusTest
class LifecycleScopeIntegrationTest {

  @Inject CompanionCompletionBean companionCase;
  @Inject ReinvokedWorkerBean reinvokedCase;
  @Inject CaseInstanceCache caseInstanceCache;
  @Inject BlackboardRegistry blackboardRegistry;
  @Inject ScopedWorkerRegistry scopedWorkerRegistry;
  @Inject ScopeActivatedCompoundBean scopeActivatedCase;
  @Inject CaseScopeActivatedBean caseScopeCase;

  @Test
  void companion_binding_does_not_block_compound_completion() {
    UUID caseId = companionCase.startCase(Map.of("ready", true));

    await()
        .atMost(10, SECONDS)
        .untilAsserted(() -> assertThat(blackboardRegistry.get(caseId)).isPresent());

    var compound =
        PlanItemDefinition.Compound.builder("processing-stage")
            .binding("process-request", Participation.PARTICIPANT)
            .binding("monitor-activity", Participation.COMPANION)
            .build();
    blackboardRegistry.get(caseId).get().registerDefinition(compound);

    companionCase.signal(caseId, "request", "analyse-this");

    await()
        .atMost(15, SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseId);
              assertThat(instance).isNotNull();
              assertThat(instance.getState()).isEqualTo(CaseStatus.COMPLETED);
            });
  }

  @Test
  void reinvoked_success_suppresses_planitem_completion() {
    UUID caseId = reinvokedCase.startCase(Map.of("ready", true));

    await()
        .atMost(10, SECONDS)
        .untilAsserted(() -> assertThat(blackboardRegistry.get(caseId)).isPresent());

    reinvokedCase.signal(caseId, "input", "first");

    await()
        .atMost(10, SECONDS)
        .untilAsserted(
            () -> {
              var plan = blackboardRegistry.get(caseId);
              assertThat(plan).isPresent();
              var items = plan.get().getAllPlanItems();
              assertThat(items)
                  .as("Worker should have been dispatched — PlanItem exists")
                  .isNotEmpty();
              assertThat(items.stream().anyMatch(pi -> pi.status() == TaskStatus.RUNNING))
                  .as("PlanItem should stay RUNNING (completion suppressed for REINVOKED Success)")
                  .isTrue();
            });

    assertThat(caseInstanceCache.get(caseId).getState())
        .as("Case should still be active — reinvoked PlanItem stays RUNNING on Success")
        .isNotEqualTo(CaseStatus.COMPLETED);
  }

  @Test
  void case_termination_clears_scoped_worker_registry() {
    UUID caseId = companionCase.startCase(Map.of("ready", true));

    await()
        .atMost(10, SECONDS)
        .untilAsserted(() -> assertThat(caseInstanceCache.get(caseId)).isNotNull());

    var session =
        new ScopedWorkerSession.Reinvoked(
            "test-binding",
            caseId,
            "worker-1",
            LifecycleScope.CASE,
            Participation.COMPANION,
            new AtomicReference<>(Map.of()),
            new AtomicReference<>(null));
    scopedWorkerRegistry.register(
        new ScopedWorkerRegistry.ScopeKey(caseId, "test-binding"), session);

    assertThat(scopedWorkerRegistry.get(caseId, "test-binding")).isPresent();

    companionCase.signal(caseId, "request", "trigger-completion");

    await()
        .atMost(15, SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseId);
              assertThat(instance.getState()).isEqualTo(CaseStatus.COMPLETED);
            });

    assertThat(scopedWorkerRegistry.get(caseId, "test-binding"))
        .as("Scoped worker session should be removed on case termination")
        .isEmpty();
  }

  @Test
  void scope_activated_binding_dispatches_on_compound_activation() {
    UUID caseId = scopeActivatedCase.startCase(Map.of("ready", true));

    await()
        .atMost(15, SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseId);
              assertThat(instance).isNotNull();
              assertThat(instance.getCaseContext().get("monitored"))
                  .as("Scope-activated monitor should have run")
                  .isNotNull();
            });
  }

  @Test
  void case_scoped_binding_dispatches_on_case_start() {
    UUID caseId = caseScopeCase.startCase(Map.of("ready", true));

    await()
        .atMost(15, SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseId);
              assertThat(instance).isNotNull();
              assertThat(instance.getCaseContext().get("caseLogged"))
                  .as("Case-scoped scope-activated logger should have run")
                  .isNotNull();
            });
  }

  @ApplicationScoped
  public static class CompanionCompletionBean extends CaseHub {

    private final Capability processCap =
        Capability.builder()
            .name("process-request-ls")
            .inputSchema("{ request: .request }")
            .outputSchema("{ result: .result }")
            .build();

    private final Capability monitorCap =
        Capability.builder()
            .name("monitor-activity-ls")
            .inputSchema("{ request: .request }")
            .outputSchema("{ monitored: .monitored }")
            .build();

    @Override
    public CaseDefinition getDefinition() {
      Goal done =
          Goal.builder()
              .name("processing-complete")
              .kind(StandardGoalKind.SUCCESS)
              .condition(new JQExpressionEvaluator(".result != null"))
              .build();

      return CaseDefinition.builder()
          .namespace("lifecycle-scope-it")
          .name("Companion Completion")
          .version("1.0.0")
          .capabilities(processCap, monitorCap)
          .workers(
              Worker.builder()
                  .name("processor-ls")
                  .capabilityName("process-request-ls")
                  .function(
                      new WorkerFunction.Sync<>(
                          Map.class,
                          Map.class,
                          (input, scope) -> WorkerResult.of(Map.of("result", "done"))))
                  .build(),
              Worker.builder()
                  .name("monitor-ls")
                  .capabilityName("monitor-activity-ls")
                  .function(
                      new WorkerFunction.Sync<>(
                          Map.class,
                          Map.class,
                          (input, scope) -> WorkerResult.of(Map.of("monitored", true))))
                  .build())
          .bindings(
              Binding.builder()
                  .name("process-request")
                  .capability(processCap)
                  .on(new ContextChangeTrigger(".request != null and .result == null"))
                  .build(),
              Binding.builder()
                  .name("monitor-activity")
                  .capability(monitorCap)
                  .lifecycleScope(LifecycleScope.COMPOUND)
                  .participation(Participation.COMPANION)
                  .on(new ContextChangeTrigger(".request != null and .monitored == null"))
                  .build())
          .goals(done)
          .completion(GoalExpression.allOf(done))
          .build();
    }
  }

  @ApplicationScoped
  public static class ReinvokedWorkerBean extends CaseHub {

    private final Capability analyseCap =
        Capability.builder()
            .name("analyse-ls")
            .inputSchema("{ input: .input }")
            .outputSchema("{ processed: .processed }")
            .build();

    @Override
    public CaseDefinition getDefinition() {
      Goal done =
          Goal.builder()
              .name("analysis-complete")
              .kind(StandardGoalKind.SUCCESS)
              .condition(new JQExpressionEvaluator(".finalResult != null"))
              .build();

      return CaseDefinition.builder()
          .namespace("lifecycle-scope-it")
          .name("Reinvoked Completion")
          .version("1.0.0")
          .capabilities(analyseCap)
          .workers(
              Worker.builder()
                  .name("analyst-ls")
                  .capabilityName("analyse-ls")
                  .function(
                      new WorkerFunction.Sync<>(
                          Map.class,
                          Map.class,
                          (input, scope) -> {
                            String val =
                                input.get("input") != null
                                    ? input.get("input").toString()
                                    : "unknown";
                            return WorkerResult.of(Map.of("processed", val));
                          }))
                  .build())
          .bindings(
              Binding.builder()
                  .name("analyse-input")
                  .capability(analyseCap)
                  .lifecycleScope(LifecycleScope.COMPOUND)
                  .participation(Participation.PARTICIPANT)
                  .executionMode(ExecutionMode.REINVOKED)
                  .on(new ContextChangeTrigger(".input != null and .finalResult == null"))
                  .build())
          .goals(done)
          .completion(GoalExpression.allOf(done))
          .build();
    }
  }

  @ApplicationScoped
  public static class ScopeActivatedCompoundBean extends CaseHub {

    private final Capability processCap =
        Capability.builder().name("process-sa").inputSchema(".").outputSchema(".").build();
    private final Capability monitorCap =
        Capability.builder().name("monitor-sa").inputSchema(".").outputSchema(".").build();

    @Override
    public CaseDefinition getDefinition() {
      Goal done =
          Goal.builder()
              .name("sa-complete")
              .kind(StandardGoalKind.SUCCESS)
              .condition(new JQExpressionEvaluator(".result != null"))
              .build();

      return CaseDefinition.builder()
          .namespace("lifecycle-scope-it")
          .name("Scope Activated Compound")
          .version("1.0.0")
          .capabilities(processCap, monitorCap)
          .workers(
              Worker.builder()
                  .name("processor-sa")
                  .capabilityName("process-sa")
                  .function(
                      new WorkerFunction.Sync<>(
                          Map.class,
                          Map.class,
                          (input, scope) -> WorkerResult.of(Map.of("result", "done"))))
                  .build(),
              Worker.builder()
                  .name("monitor-sa")
                  .capabilityName("monitor-sa")
                  .function(
                      new WorkerFunction.Sync<>(
                          Map.class,
                          Map.class,
                          (input, scope) -> WorkerResult.of(Map.of("monitored", true))))
                  .build())
          .bindings(
              Binding.builder()
                  .name("process-request-sa")
                  .capability(processCap)
                  .on(new ContextChangeTrigger(".ready == true and .result == null"))
                  .build(),
              Binding.builder()
                  .name("monitor-activity-sa")
                  .capability(monitorCap)
                  .on(new io.casehub.api.model.ScopeActivatedTrigger())
                  .lifecycleScope(LifecycleScope.COMPOUND)
                  .participation(Participation.COMPANION)
                  .build())
          .goals(done)
          .completion(GoalExpression.allOf(done))
          .build();
    }
  }

  @ApplicationScoped
  public static class ScopeActivatedPlanConfigurer
      implements io.casehub.engine.planning.control.BlackboardPlanConfigurer {
    @Override
    public boolean supports(CaseDefinition definition) {
      return "Scope Activated Compound".equals(definition.getName());
    }

    @Override
    public void configure(
        io.casehub.engine.planning.plan.CasePlanModel plan,
        io.casehub.api.engine.PlanExecutionContext ctx) {
      plan.registerDefinition(
          PlanItemDefinition.Compound.builder("processing-stage")
              .id("sa-compound")
              .binding("process-request-sa", Participation.PARTICIPANT)
              .binding("monitor-activity-sa", Participation.COMPANION)
              .build());
    }
  }

  @ApplicationScoped
  public static class CaseScopeActivatedBean extends CaseHub {

    private final Capability loggerCap =
        Capability.builder().name("case-logger-cap").inputSchema(".").outputSchema(".").build();

    @Override
    public CaseDefinition getDefinition() {
      Goal done =
          Goal.builder()
              .name("case-logged")
              .kind(StandardGoalKind.SUCCESS)
              .condition(new JQExpressionEvaluator(".caseLogged != null"))
              .build();

      return CaseDefinition.builder()
          .namespace("lifecycle-scope-it")
          .name("Case Scope Activated")
          .version("1.0.0")
          .capabilities(loggerCap)
          .workers(
              Worker.builder()
                  .name("case-logger-worker")
                  .capabilityName("case-logger-cap")
                  .function(
                      new WorkerFunction.Sync<>(
                          Map.class,
                          Map.class,
                          (input, scope) -> WorkerResult.of(Map.of("caseLogged", true))))
                  .build())
          .bindings(
              Binding.builder()
                  .name("case-logger-binding")
                  .capability(loggerCap)
                  .on(new io.casehub.api.model.ScopeActivatedTrigger())
                  .lifecycleScope(LifecycleScope.CASE)
                  .participation(Participation.COMPANION)
                  .build())
          .goals(done)
          .completion(GoalExpression.allOf(done))
          .build();
    }
  }
}
