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
package io.casehub.engine.planning.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.api.model.ExecutorRef;
import io.casehub.api.model.TaskStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CompoundPlanModelTest {

  private DefaultCasePlanModel model() {
    return new DefaultCasePlanModel(UUID.randomUUID());
  }

  private PlanItemDefinition.Primitive primitive(String id) {
    return new PlanItemDefinition.Primitive(id, id, ExecutorRef.of("worker"), null);
  }

  private PlanItemDefinition.Compound compound(String id, List<PlanItemDefinition> children) {
    return new PlanItemDefinition.Compound(
        id,
        id,
        children,
        null,
        CompletionSemantics.all(),
        DispatchMode.ORCHESTRATED,
        null,
        null,
        false,
        java.util.Map.of());
  }

  @Test
  void registerDefinition_and_getStatus_returns_pending() {
    var model = model();
    var def = primitive("pi-1");
    model.registerDefinition(def);
    assertThat(model.getDefinitionStatus(def.id())).isEqualTo(TaskStatus.PENDING);
  }

  @Test
  void tryDefinitionTransition_pending_to_running() {
    var model = model();
    var def = primitive("pi-1");
    model.registerDefinition(def);
    assertThat(model.tryDefinitionTransition("pi-1", TaskStatus.PENDING, TaskStatus.RUNNING))
        .isTrue();
    assertThat(model.getDefinitionStatus("pi-1")).isEqualTo(TaskStatus.RUNNING);
  }

  @Test
  void getChildrenOf_returns_declared_children() {
    var child1 = primitive("child-1");
    var child2 = primitive("child-2");
    var parent = compound("parent", List.of(child1, child2));
    var model = model();
    model.registerDefinition(parent);

    assertThat(model.getChildrenOf("parent")).containsExactlyInAnyOrder("child-1", "child-2");
  }

  @Test
  void addChild_extends_children() {
    var parent = compound("parent", List.of());
    var model = model();
    model.registerDefinition(parent);

    var newChild = primitive("runtime-child");
    model.addChild("parent", newChild);

    assertThat(model.getChildrenOf("parent")).contains("runtime-child");
    assertThat(model.getDefinitionStatus("runtime-child")).isEqualTo(TaskStatus.PENDING);
  }

  @Test
  void getParentOf_returns_parent_id() {
    var child = primitive("child-1");
    var parent = compound("parent", List.of(child));
    var model = model();
    model.registerDefinition(parent);

    assertThat(model.getParentOf("child-1")).contains("parent");
    assertThat(model.getParentOf("parent")).isEmpty();
  }

  @Test
  void evaluateCompletion_all_returns_true_when_all_terminal() {
    var child1 = primitive("c1");
    var child2 = primitive("c2");
    var parent = compound("parent", List.of(child1, child2));
    var model = model();
    model.registerDefinition(parent);

    assertThat(model.evaluateCompletion("parent")).isFalse();

    model.tryDefinitionTransition("c1", TaskStatus.PENDING, TaskStatus.RUNNING);
    model.tryDefinitionTransition("c1", TaskStatus.RUNNING, TaskStatus.COMPLETED);
    assertThat(model.evaluateCompletion("parent")).isFalse();

    model.tryDefinitionTransition("c2", TaskStatus.PENDING, TaskStatus.RUNNING);
    model.tryDefinitionTransition("c2", TaskStatus.RUNNING, TaskStatus.COMPLETED);
    assertThat(model.evaluateCompletion("parent")).isTrue();
  }

  @Test
  void evaluateCompletion_m_of_n() {
    var c1 = primitive("c1");
    var c2 = primitive("c2");
    var c3 = primitive("c3");
    var parent =
        new PlanItemDefinition.Compound(
            "parent",
            "parent",
            List.of(c1, c2, c3),
            null,
            CompletionSemantics.mOfN(2),
            DispatchMode.ORCHESTRATED,
            null,
            null,
            false,
            java.util.Map.of());
    var model = model();
    model.registerDefinition(parent);

    model.tryDefinitionTransition("c1", TaskStatus.PENDING, TaskStatus.RUNNING);
    model.tryDefinitionTransition("c1", TaskStatus.RUNNING, TaskStatus.COMPLETED);
    assertThat(model.evaluateCompletion("parent")).isFalse();

    model.tryDefinitionTransition("c2", TaskStatus.PENDING, TaskStatus.RUNNING);
    model.tryDefinitionTransition("c2", TaskStatus.RUNNING, TaskStatus.COMPLETED);
    assertThat(model.evaluateCompletion("parent")).isTrue();
  }

  @Test
  void evaluateCompletion_first_wins() {
    var c1 = primitive("c1");
    var c2 = primitive("c2");
    var parent =
        new PlanItemDefinition.Compound(
            "parent",
            "parent",
            List.of(c1, c2),
            null,
            CompletionSemantics.firstWins(),
            DispatchMode.ORCHESTRATED,
            null,
            null,
            false,
            java.util.Map.of());
    var model = model();
    model.registerDefinition(parent);

    model.tryDefinitionTransition("c1", TaskStatus.PENDING, TaskStatus.RUNNING);
    model.tryDefinitionTransition("c1", TaskStatus.RUNNING, TaskStatus.COMPLETED);
    assertThat(model.evaluateCompletion("parent")).isTrue();
  }

  @Test
  void nested_compounds_track_correctly() {
    var leaf = primitive("leaf");
    var inner = compound("inner", List.of(leaf));
    var outer = compound("outer", List.of(inner));
    var model = model();
    model.registerDefinition(outer);

    assertThat(model.getChildrenOf("outer")).containsExactly("inner");
    assertThat(model.getChildrenOf("inner")).containsExactly("leaf");
    assertThat(model.getParentOf("inner")).contains("outer");
    assertThat(model.getParentOf("leaf")).contains("inner");
  }

  @Test
  void evaluateCompletion_faulted_children_count_as_terminal() {
    var c1 = primitive("c1");
    var c2 = primitive("c2");
    var parent = compound("parent", List.of(c1, c2));
    var model = model();
    model.registerDefinition(parent);

    model.tryDefinitionTransition("c1", TaskStatus.PENDING, TaskStatus.RUNNING);
    model.tryDefinitionTransition("c1", TaskStatus.RUNNING, TaskStatus.COMPLETED);
    model.tryDefinitionTransition("c2", TaskStatus.PENDING, TaskStatus.RUNNING);
    model.tryDefinitionTransition("c2", TaskStatus.RUNNING, TaskStatus.FAULTED);
    assertThat(model.evaluateCompletion("parent")).isTrue();
  }

  @Test
  void evaluateCompletion_cancelled_children_count_as_terminal() {
    var c1 = primitive("c1");
    var c2 = primitive("c2");
    var parent = compound("parent", List.of(c1, c2));
    var model = model();
    model.registerDefinition(parent);

    model.tryDefinitionTransition("c1", TaskStatus.PENDING, TaskStatus.RUNNING);
    model.tryDefinitionTransition("c1", TaskStatus.RUNNING, TaskStatus.COMPLETED);
    model.tryDefinitionTransition("c2", TaskStatus.PENDING, TaskStatus.CANCELLED);
    assertThat(model.evaluateCompletion("parent")).isTrue();
  }

  @Test
  void getDefinitionStatus_unknown_id_throws() {
    var model = model();
    assertThatThrownBy(() -> model.getDefinitionStatus("nonexistent"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void tryDefinitionTransition_unknown_id_throws() {
    var model = model();
    assertThatThrownBy(
            () ->
                model.tryDefinitionTransition(
                    "nonexistent", TaskStatus.PENDING, TaskStatus.RUNNING))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void addChild_unknown_compound_throws() {
    var model = model();
    assertThatThrownBy(() -> model.addChild("nonexistent", primitive("c1")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void evaluateCompletion_m_of_n_boundary() {
    var c1 = primitive("c1");
    var c2 = primitive("c2");
    var c3 = primitive("c3");
    var parent =
        new PlanItemDefinition.Compound(
            "parent",
            "parent",
            List.of(c1, c2, c3),
            null,
            CompletionSemantics.mOfN(2),
            DispatchMode.ORCHESTRATED,
            null,
            null,
            false,
            java.util.Map.of());
    var model = model();
    model.registerDefinition(parent);

    model.tryDefinitionTransition("c1", TaskStatus.PENDING, TaskStatus.RUNNING);
    model.tryDefinitionTransition("c1", TaskStatus.RUNNING, TaskStatus.COMPLETED);
    assertThat(model.evaluateCompletion("parent")).isFalse();

    model.tryDefinitionTransition("c2", TaskStatus.PENDING, TaskStatus.RUNNING);
    model.tryDefinitionTransition("c2", TaskStatus.RUNNING, TaskStatus.COMPLETED);
    assertThat(model.evaluateCompletion("parent")).isTrue();

    model.tryDefinitionTransition("c3", TaskStatus.PENDING, TaskStatus.RUNNING);
    model.tryDefinitionTransition("c3", TaskStatus.RUNNING, TaskStatus.COMPLETED);
    assertThat(model.evaluateCompletion("parent")).isTrue();
  }

  @Test
  void concurrent_transitions_only_one_wins() throws InterruptedException {
    var model = model();
    var def = primitive("pi-1");
    model.registerDefinition(def);

    int threadCount = 10;
    var latch = new java.util.concurrent.CountDownLatch(threadCount);
    var successes = new java.util.concurrent.atomic.AtomicInteger(0);

    for (int i = 0; i < threadCount; i++) {
      Thread.ofVirtual()
          .start(
              () -> {
                latch.countDown();
                try {
                  latch.await();
                } catch (InterruptedException e) {
                  return;
                }
                if (model.tryDefinitionTransition("pi-1", TaskStatus.PENDING, TaskStatus.RUNNING)) {
                  successes.incrementAndGet();
                }
              });
    }

    Thread.sleep(200);
    assertThat(successes.get()).isEqualTo(1);
    assertThat(model.getDefinitionStatus("pi-1")).isEqualTo(TaskStatus.RUNNING);
  }

  @Test
  void registerDefinition_indexes_owned_binding_names_in_parent() {
    var compound =
        new PlanItemDefinition.Compound(
            "comp-1",
            "my-stage",
            List.of(),
            null,
            CompletionSemantics.all(),
            DispatchMode.CHOREOGRAPHED,
            null,
            null,
            false,
            java.util.Map.of(
                "binding-a",
                io.casehub.api.model.Participation.PARTICIPANT,
                "binding-b",
                io.casehub.api.model.Participation.PARTICIPANT));
    var model = model();
    model.registerDefinition(compound);

    assertThat(model.getParentOf("binding-a")).contains("comp-1");
    assertThat(model.getParentOf("binding-b")).contains("comp-1");
    assertThat(model.getChildrenOf("comp-1")).isEmpty();
  }

  @Test
  void registerDefinition_indexes_both_children_and_owned_bindings() {
    var child = primitive("child-1");
    var compound =
        new PlanItemDefinition.Compound(
            "comp-2",
            "hybrid",
            List.of(child),
            null,
            CompletionSemantics.all(),
            DispatchMode.CHOREOGRAPHED,
            null,
            null,
            false,
            java.util.Map.of("scoped-binding", io.casehub.api.model.Participation.PARTICIPANT));
    var model = model();
    model.registerDefinition(compound);

    assertThat(model.getParentOf("child-1")).contains("comp-2");
    assertThat(model.getParentOf("scoped-binding")).contains("comp-2");
    assertThat(model.getChildrenOf("comp-2")).containsExactly("child-1");
  }

  @Test
  void evaluateCompletion_works_with_scoped_bindings_and_children() {
    var child = primitive("c1");
    var compound =
        new PlanItemDefinition.Compound(
            "comp-3",
            "mixed",
            List.of(child),
            null,
            CompletionSemantics.all(),
            DispatchMode.CHOREOGRAPHED,
            null,
            null,
            false,
            java.util.Map.of("scoped-b", io.casehub.api.model.Participation.PARTICIPANT));
    var model = model();
    model.registerDefinition(compound);

    assertThat(model.evaluateCompletion("comp-3")).isFalse();

    model.tryDefinitionTransition("c1", TaskStatus.PENDING, TaskStatus.RUNNING);
    model.tryDefinitionTransition("c1", TaskStatus.RUNNING, TaskStatus.COMPLETED);
    assertThat(model.evaluateCompletion("comp-3"))
        .as("scoped binding not yet terminal — compound not complete")
        .isFalse();

    var pi = PlanItem.create("scoped-b", io.casehub.api.model.ExecutorRef.of("w"), 0);
    model.addPlanItem(pi);
    pi.markRunning();
    pi.markCompleted();
    assertThat(model.evaluateCompletion("comp-3"))
        .as("both structural child and scoped binding terminal — compound complete")
        .isTrue();
  }

  @Test
  void getAllCompounds_returns_registered_compounds() {
    var c1 = PlanItemDefinition.Compound.builder("phase-1").id("comp-1").build();
    var c2 = PlanItemDefinition.Compound.builder("phase-2").id("comp-2").build();
    var model = model();
    model.registerDefinition(c1);
    model.registerDefinition(c2);
    model.registerDefinition(primitive("leaf"));

    var compounds = model.getAllCompounds();
    assertThat(compounds).hasSize(2);
    assertThat(compounds.stream().map(PlanItemDefinition.Compound::id))
        .containsExactlyInAnyOrder("comp-1", "comp-2");
  }

  @Test
  void getCompoundsByStatus_filters_by_definition_status() {
    var c1 = PlanItemDefinition.Compound.builder("active").id("comp-1").build();
    var c2 = PlanItemDefinition.Compound.builder("pending").id("comp-2").build();
    var model = model();
    model.registerDefinition(c1);
    model.registerDefinition(c2);

    model.tryDefinitionTransition("comp-1", TaskStatus.PENDING, TaskStatus.RUNNING);

    assertThat(model.getCompoundsByStatus(TaskStatus.RUNNING)).hasSize(1);
    assertThat(model.getCompoundsByStatus(TaskStatus.RUNNING).get(0).id()).isEqualTo("comp-1");
    assertThat(model.getCompoundsByStatus(TaskStatus.PENDING)).hasSize(1);
    assertThat(model.getCompoundsByStatus(TaskStatus.PENDING).get(0).id()).isEqualTo("comp-2");
  }

  @Test
  void getAllCompounds_includes_nested_compounds() {
    var inner = PlanItemDefinition.Compound.builder("inner").id("inner").build();
    var outer = PlanItemDefinition.Compound.builder("outer").id("outer").child(inner).build();
    var model = model();
    model.registerDefinition(outer);

    assertThat(model.getAllCompounds()).hasSize(2);
  }

  @Test
  void evaluateCompletion_scopedBindings_completes_when_planItems_terminal() {
    var compound =
        PlanItemDefinition.Compound.builder("stage")
            .id("comp-1")
            .binding("binding-a")
            .binding("binding-b")
            .build();
    var model = model();
    model.registerDefinition(compound);

    assertThat(model.evaluateCompletion("comp-1")).isFalse();

    var piA = PlanItem.create("binding-a", io.casehub.api.model.ExecutorRef.of("w"), 0);
    model.addPlanItem(piA);
    piA.markRunning();
    piA.markCompleted();
    assertThat(model.evaluateCompletion("comp-1")).isFalse();

    var piB = PlanItem.create("binding-b", io.casehub.api.model.ExecutorRef.of("w"), 0);
    model.addPlanItem(piB);
    piB.markRunning();
    piB.markCompleted();
    assertThat(model.evaluateCompletion("comp-1")).isTrue();
  }

  @Test
  void evaluateCompletion_scopedBindings_not_yet_dispatched_counts_as_pending() {
    var compound =
        PlanItemDefinition.Compound.builder("stage").id("comp-1").binding("binding-a").build();
    var model = model();
    model.registerDefinition(compound);

    assertThat(model.evaluateCompletion("comp-1"))
        .as("no PlanItem created yet — binding is pending, compound is not complete")
        .isFalse();
  }

  @Test
  void evaluateCompletion_companion_binding_excluded_from_completion() {
    var compound =
        PlanItemDefinition.Compound.builder("stage")
            .id("comp-1")
            .binding("required-binding")
            .binding("companion-binding", io.casehub.api.model.Participation.COMPANION)
            .build();
    var model = model();
    model.registerDefinition(compound);

    var piRequired =
        PlanItem.create("required-binding", io.casehub.api.model.ExecutorRef.of("w"), 0);
    model.addPlanItem(piRequired);
    piRequired.markRunning();
    piRequired.markCompleted();

    assertThat(model.evaluateCompletion("comp-1"))
        .as("COMPANION binding should not block completion")
        .isTrue();
  }

  @Test
  void evaluateCompletion_participant_binding_blocks_completion() {
    var compound =
        PlanItemDefinition.Compound.builder("stage")
            .id("comp-1")
            .binding("binding-a")
            .binding("binding-b", io.casehub.api.model.Participation.PARTICIPANT)
            .build();
    var model = model();
    model.registerDefinition(compound);

    var piA = PlanItem.create("binding-a", io.casehub.api.model.ExecutorRef.of("w"), 0);
    model.addPlanItem(piA);
    piA.markRunning();
    piA.markCompleted();

    assertThat(model.evaluateCompletion("comp-1"))
        .as("PARTICIPANT binding-b not yet dispatched — blocks completion")
        .isFalse();
  }
}
