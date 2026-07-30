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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.ExecutorRef;
import io.casehub.api.model.TaskStatus;
import io.casehub.engine.planning.plan.CasePlanModel;
import io.casehub.engine.planning.plan.PlanItem;
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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

@QuarkusTest
class CompoundBlackboardTest {

  @Inject BlackboardRegistry registry;
  @Inject SignalCaseBean signalCase;
  @Inject OnceSignalCaseBean onceSignalCase;

  @Test
  void compound_with_no_entry_condition_activates_on_next_evaluation_cycle() {
    UUID caseId = signalCase.startCase(Map.of("ready", true));
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(registry.get(caseId)).isPresent());

    var compound = PlanItemDefinition.Compound.builder("unconditional-compound").build();
    registry.get(caseId).get().registerDefinition(compound);

    signalCase.signal(caseId, "probe", "tick");

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(registry.get(caseId).get().getDefinitionStatus(compound.id()))
                    .as("compound with no entry condition must activate on evaluation")
                    .isEqualTo(TaskStatus.RUNNING));
  }

  @Test
  void compound_with_satisfied_entry_condition_activates() {
    UUID caseId = signalCase.startCase(Map.of("ready", true));
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(registry.get(caseId)).isPresent());

    var compound =
        PlanItemDefinition.Compound.builder("conditional-compound")
            .entryCondition(ctx -> true)
            .build();
    registry.get(caseId).get().registerDefinition(compound);

    signalCase.signal(caseId, "probe", "tick");

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(registry.get(caseId).get().getDefinitionStatus(compound.id()))
                    .as("compound with satisfied entry condition must activate")
                    .isEqualTo(TaskStatus.RUNNING));
  }

  @Test
  void compound_with_unsatisfied_entry_condition_stays_pending() {
    UUID caseId = signalCase.startCase(Map.of("ready", true));
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(registry.get(caseId)).isPresent());

    var compound =
        PlanItemDefinition.Compound.builder("blocked-compound")
            .entryCondition(ctx -> false)
            .build();
    registry.get(caseId).get().registerDefinition(compound);

    signalCase.signal(caseId, "probe", "tick");

    await()
        .during(2, TimeUnit.SECONDS)
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(registry.get(caseId).get().getDefinitionStatus(compound.id()))
                    .as("compound with unsatisfied entry condition must stay PENDING")
                    .isEqualTo(TaskStatus.PENDING));
  }

  @Test
  void compound_with_satisfied_exit_condition_completes() {
    UUID caseId = signalCase.startCase(Map.of("ready", true));
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(registry.get(caseId)).isPresent());

    var compound =
        PlanItemDefinition.Compound.builder("exit-compound")
            .entryCondition(ctx -> true)
            .exitCondition(ctx -> true)
            .build();
    registry.get(caseId).get().registerDefinition(compound);

    signalCase.signal(caseId, "probe", "tick");

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(registry.get(caseId).get().getDefinitionStatus(compound.id()))
                    .as("compound with satisfied exit condition must be COMPLETED")
                    .isEqualTo(TaskStatus.COMPLETED));
  }

  @Test
  void compound_completes_when_scoped_binding_plan_item_completes() {
    UUID caseId = onceSignalCase.startCase(Map.of());
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(registry.get(caseId)).isPresent());

    CasePlanModel plan = registry.get(caseId).get();

    var compound =
        PlanItemDefinition.Compound.builder("autocomplete-compound").binding("on-go-true").build();
    plan.registerDefinition(compound);

    onceSignalCase.signal(caseId, "go", true);

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(plan.getDefinitionStatus(compound.id()))
                    .as("compound must complete when its scoped binding's PlanItem completes")
                    .isEqualTo(TaskStatus.COMPLETED));
  }

  @Test
  void compound_without_scoped_bindings_stays_running() {
    UUID caseId = onceSignalCase.startCase(Map.of());
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(registry.get(caseId)).isPresent());

    CasePlanModel plan = registry.get(caseId).get();

    var compound = PlanItemDefinition.Compound.builder("no-bindings-compound").build();
    plan.registerDefinition(compound);
    plan.tryDefinitionTransition(compound.id(), TaskStatus.PENDING, TaskStatus.RUNNING);

    PlanItem syntheticItem =
        PlanItem.create("synthetic-binding", ExecutorRef.of("synthetic-worker"), 0);
    plan.addPlanItem(syntheticItem);
    syntheticItem.markRunning();
    syntheticItem.markCompleted();

    await()
        .during(2, TimeUnit.SECONDS)
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(plan.getDefinitionStatus(compound.id()))
                    .as("compound without scoped bindings must remain RUNNING")
                    .isEqualTo(TaskStatus.RUNNING));
  }

  @Test
  void nested_compound_activates_only_after_parent_is_running() {
    UUID caseId = signalCase.startCase(Map.of("ready", true));
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(registry.get(caseId)).isPresent());

    CasePlanModel plan = registry.get(caseId).get();

    var child = PlanItemDefinition.Compound.builder("child-compound").build();
    var parent = PlanItemDefinition.Compound.builder("parent-compound").child(child).build();
    plan.registerDefinition(parent);

    signalCase.signal(caseId, "probe", "tick-1");

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> assertThat(plan.getDefinitionStatus(parent.id())).isEqualTo(TaskStatus.RUNNING));

    signalCase.signal(caseId, "probe", "tick-2");

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(plan.getDefinitionStatus(child.id()))
                    .as("child must activate after parent becomes RUNNING")
                    .isEqualTo(TaskStatus.RUNNING));
  }

  @Test
  void compound_scoped_bindings_persist_and_compound_activates_end_to_end() {
    UUID caseId = signalCase.startCase(Map.of("ready", true));
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(registry.get(caseId)).isPresent());

    var compound =
        PlanItemDefinition.Compound.builder("gating-compound")
            .binding("trigger-on-go")
            .binding("trigger-b")
            .build();
    registry.get(caseId).get().registerDefinition(compound);

    assertThat(compound.scopedBindings().keySet())
        .as("scoped bindings must be present immediately after construction")
        .containsExactlyInAnyOrder("trigger-on-go", "trigger-b");

    signalCase.signal(caseId, "probe", "tick");

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(registry.get(caseId).get().getDefinitionStatus(compound.id()))
                    .as("compound with scoped bindings must still activate normally")
                    .isEqualTo(TaskStatus.RUNNING));

    assertThat(compound.scopedBindings().keySet())
        .as("scoped bindings must be preserved after compound activation")
        .containsExactlyInAnyOrder("trigger-on-go", "trigger-b");
  }

  @ApplicationScoped
  public static class SignalCaseBean extends CaseHub {

    private final Capability cap =
        Capability.builder()
            .name("signal-cap")
            .inputSchema("{ probe: .probe }")
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
                  .capabilityName("signal-cap")
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
          .build();
    }
  }

  @ApplicationScoped
  public static class OnceSignalCaseBean extends CaseHub {

    private final Capability cap =
        Capability.builder()
            .name("once-signal-cap")
            .inputSchema("{ go: .go }")
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
                  .capabilityName("once-signal-cap")
                  .function(
                      new WorkerFunction.Sync<>(
                          Map.class,
                          Map.class,
                          (input, scope) -> WorkerResult.of(Map.of("go", false))))
                  .build())
          .bindings(
              Binding.builder()
                  .name("on-go-true")
                  .capability(cap)
                  .on(new ContextChangeTrigger(".go == true"))
                  .build())
          .build();
    }
  }
}
