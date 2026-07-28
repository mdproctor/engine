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
package io.casehub.engine.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DagPlanTest {

  @Test
  void singleton_createsOneNodePlan() {
    DagPlan<String> plan = DagPlan.singleton("task-a", "do something");
    assertThat(plan.nodes()).hasSize(1);
    assertThat(plan.entryNodeIds()).containsExactly("task-a");
    assertThat(plan.exitNodeIds()).containsExactly("task-a");
  }

  @Test
  void fromNodes_createsLinearChain() {
    DagPlan<String> plan =
        DagPlan.fromNodes(
            List.of(
                new DagNode<>("a", "first", Set.of(), io.casehub.engine.plan.JoinType.ALL_OF),
                new DagNode<>("b", "second", Set.of("a"), JoinType.ALL_OF),
                new DagNode<>("c", "third", Set.of("b"), JoinType.ALL_OF)));
    assertThat(plan.entryNodeIds()).containsExactly("a");
    assertThat(plan.exitNodeIds()).containsExactly("c");
    assertThat(plan.topologicalSort().stream().map(DagNode::id).toList())
        .containsExactly("a", "b", "c");
  }

  @Test
  void parallel_createsIndependentNodes() {
    DagPlan<String> plan = DagPlan.parallel(List.of("task-a", "task-b", "task-c"));
    assertThat(plan.nodes()).hasSize(3);
    assertThat(plan.entryNodeIds()).hasSize(3);
    assertThat(plan.exitNodeIds()).hasSize(3);
  }

  @Test
  void diamond_correctEntryAndExit() {
    DagPlan<String> plan =
        new DagPlan<>(
            Map.of(
                "a", new DagNode<>("a", "root", Set.of(), JoinType.ALL_OF),
                "b", new DagNode<>("b", "left", Set.of("a"), JoinType.ALL_OF),
                "c", new DagNode<>("c", "right", Set.of("a"), JoinType.ALL_OF),
                "d", new DagNode<>("d", "join", Set.of("b", "c"), JoinType.ALL_OF)));
    assertThat(plan.entryNodeIds()).containsExactly("a");
    assertThat(plan.exitNodeIds()).containsExactly("d");
  }

  @Test
  void emptyNodes_throwsIAE() {
    assertThatThrownBy(() -> new DagPlan<>(Map.of())).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void cycleDetected_throwsIAE() {
    assertThatThrownBy(
            () ->
                new DagPlan<>(
                    Map.of(
                        "a", new DagNode<>("a", "x", Set.of("b"), JoinType.ALL_OF),
                        "b", new DagNode<>("b", "y", Set.of("a"), JoinType.ALL_OF))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cycle");
  }

  @Test
  void selfReference_throwsIAE() {
    assertThatThrownBy(
            () -> new DagPlan<>(Map.of("a", new DagNode<>("a", "x", Set.of("a"), JoinType.ALL_OF))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void invalidReference_throwsIAE() {
    assertThatThrownBy(
            () ->
                new DagPlan<>(
                    Map.of("a", new DagNode<>("a", "x", Set.of("nonexistent"), JoinType.ALL_OF))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void topologicalSort_respectsDependencies() {
    DagPlan<String> plan =
        new DagPlan<>(
            Map.of(
                "c", new DagNode<>("c", "third", Set.of("b"), JoinType.ALL_OF),
                "a", new DagNode<>("a", "first", Set.of(), JoinType.ALL_OF),
                "b", new DagNode<>("b", "second", Set.of("a"), JoinType.ALL_OF)));
    List<String> order = plan.topologicalSort().stream().map(DagNode::id).toList();
    assertThat(order.indexOf("a")).isLessThan(order.indexOf("b"));
    assertThat(order.indexOf("b")).isLessThan(order.indexOf("c"));
  }

  @Test
  void nodeDefaults_emptyDepsAndAllOf() {
    DagNode<String> node = new DagNode<>("x", "task", null, null);
    assertThat(node.dependsOn()).isEmpty();
    assertThat(node.joinType()).isEqualTo(JoinType.ALL_OF);
  }

  @Test
  void sequentialMerge_two_plans_wires_exit_to_entry() {
    DagPlan<String> first = DagPlan.singleton("a", "task-a");
    DagPlan<String> second = DagPlan.singleton("b", "task-b");

    DagPlan<String> merged = DagPlan.sequentialMerge(List.of(first, second));

    assertThat(merged.nodes()).hasSize(2);
    assertThat(merged.entryNodeIds()).hasSize(1);
    assertThat(merged.exitNodeIds()).hasSize(1);
    DagNode<String> secondNode = merged.nodes().get("sub1-b");
    assertThat(secondNode.dependsOn()).containsExactly("sub0-a");
  }

  @Test
  void sequentialMerge_single_plan_returns_unchanged() {
    DagPlan<String> plan = DagPlan.singleton("x", "task-x");
    DagPlan<String> merged = DagPlan.sequentialMerge(List.of(plan));
    assertThat(merged).isSameAs(plan);
  }

  @Test
  void sequentialMerge_empty_throws() {
    assertThatThrownBy(() -> DagPlan.sequentialMerge(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void sequentialMerge_three_plans_preserves_internal_deps() {
    DagNode<String> a = new DagNode<>("a", "t-a", Set.of(), JoinType.ALL_OF);
    DagNode<String> b = new DagNode<>("b", "t-b", Set.of("a"), JoinType.ALL_OF);
    DagPlan<String> planAB = DagPlan.fromNodes(List.of(a, b));

    DagPlan<String> planC = DagPlan.singleton("c", "t-c");
    DagPlan<String> planD = DagPlan.singleton("d", "t-d");

    DagPlan<String> merged = DagPlan.sequentialMerge(List.of(planAB, planC, planD));

    assertThat(merged.nodes()).hasSize(4);
    assertThat(merged.nodes().get("sub0-b").dependsOn()).contains("sub0-a");
    assertThat(merged.nodes().get("sub1-c").dependsOn()).contains("sub0-b");
    assertThat(merged.nodes().get("sub2-d").dependsOn()).contains("sub1-c");
    assertThat(merged.topologicalSort()).hasSize(4);
  }

  @Test
  void sequentialMerge_parallel_to_singleton_wires_all_exits_to_entry() {
    DagPlan<String> parallel = DagPlan.parallel(List.of("t-a", "t-b"));
    DagPlan<String> single = DagPlan.singleton("c", "t-c");

    DagPlan<String> merged = DagPlan.sequentialMerge(List.of(parallel, single));

    assertThat(merged.nodes()).hasSize(3);
    DagNode<String> singleNode = merged.nodes().get("sub1-c");
    assertThat(singleNode.dependsOn()).containsExactlyInAnyOrder("sub0-node-0", "sub0-node-1");
    assertThat(merged.entryNodeIds()).hasSize(2);
    assertThat(merged.exitNodeIds()).hasSize(1);
  }

  // --- singleton(T) auto-ID factory ---

  @Test
  void singletonAutoId_createsOneNodePlan() {
    DagPlan<String> plan = DagPlan.singleton("do something");
    assertThat(plan.nodes()).hasSize(1);
    assertThat(plan.entryNodeIds()).containsExactly("node-0");
    assertThat(plan.exitNodeIds()).containsExactly("node-0");
  }

  @Test
  void singletonAutoId_preservesTask() {
    DagPlan<String> plan = DagPlan.singleton("my-task");
    DagNode<String> node = plan.nodes().get("node-0");
    assertThat(node.task()).isEqualTo("my-task");
    assertThat(node.joinType()).isEqualTo(JoinType.ALL_OF);
    assertThat(node.dependsOn()).isEmpty();
  }

  @Test
  void singletonAutoId_equivalentToExplicitId() {
    DagPlan<String> auto = DagPlan.singleton("task");
    DagPlan<String> explicit = DagPlan.singleton("node-0", "task");
    assertThat(auto.nodes().get("node-0").task()).isEqualTo(explicit.nodes().get("node-0").task());
  }

  // --- sequence(List<T>) auto-wired chain factory ---

  @Test
  void sequenceTasks_createsChainWithDependencies() {
    DagPlan<String> plan = DagPlan.sequence(List.of("first", "second", "third"));
    assertThat(plan.nodes()).hasSize(3);

    DagNode<String> n0 = plan.nodes().get("node-0");
    DagNode<String> n1 = plan.nodes().get("node-1");
    DagNode<String> n2 = plan.nodes().get("node-2");

    assertThat(n0.dependsOn()).isEmpty();
    assertThat(n1.dependsOn()).containsExactly("node-0");
    assertThat(n2.dependsOn()).containsExactly("node-1");
  }

  @Test
  void sequenceTasks_entryAndExitCorrect() {
    DagPlan<String> plan = DagPlan.sequence(List.of("a", "b", "c"));
    assertThat(plan.entryNodeIds()).containsExactly("node-0");
    assertThat(plan.exitNodeIds()).containsExactly("node-2");
  }

  @Test
  void sequenceTasks_topologicalSortPreservesOrder() {
    DagPlan<String> plan = DagPlan.sequence(List.of("first", "second", "third"));
    List<String> tasks = plan.topologicalSort().stream().map(DagNode::task).toList();
    assertThat(tasks).containsExactly("first", "second", "third");
  }

  @Test
  void sequenceTasks_singleElement() {
    DagPlan<String> plan = DagPlan.sequence(List.of("only"));
    assertThat(plan.nodes()).hasSize(1);
    assertThat(plan.entryNodeIds()).containsExactly("node-0");
    assertThat(plan.exitNodeIds()).containsExactly("node-0");
    assertThat(plan.nodes().get("node-0").task()).isEqualTo("only");
    assertThat(plan.nodes().get("node-0").dependsOn()).isEmpty();
  }

  @Test
  void sequenceTasks_twoElements() {
    DagPlan<String> plan = DagPlan.sequence(List.of("a", "b"));
    assertThat(plan.nodes().get("node-0").dependsOn()).isEmpty();
    assertThat(plan.nodes().get("node-1").dependsOn()).containsExactly("node-0");
  }

  @Test
  void sequenceTasks_emptyList_throwsIAE() {
    assertThatThrownBy(() -> DagPlan.sequence(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void sequenceTasks_joinTypeDefaultsToAllOf() {
    DagPlan<String> plan = DagPlan.sequence(List.of("a", "b", "c"));
    plan.nodes().values().forEach(n -> assertThat(n.joinType()).isEqualTo(JoinType.ALL_OF));
  }

  @Test
  void sequenceTasks_nodesImmutable() {
    DagPlan<String> plan = DagPlan.sequence(List.of("a", "b"));
    assertThatThrownBy(
            () -> plan.nodes().put("x", new DagNode<>("x", "x", Set.of(), JoinType.ALL_OF)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void sequenceTasks_autoGeneratedIds() {
    DagPlan<String> plan = DagPlan.sequence(List.of("a", "b", "c", "d"));
    assertThat(plan.nodes().keySet())
        .containsExactlyInAnyOrder("node-0", "node-1", "node-2", "node-3");
  }

  // --- interaction: sequentialMerge with sequence-built plans ---

  @Test
  void sequentialMerge_sequencePlans_wiresCorrectly() {
    DagPlan<String> chain1 = DagPlan.sequence(List.of("a", "b"));
    DagPlan<String> chain2 = DagPlan.sequence(List.of("c", "d"));

    DagPlan<String> merged = DagPlan.sequentialMerge(List.of(chain1, chain2));

    assertThat(merged.nodes()).hasSize(4);
    assertThat(merged.entryNodeIds()).hasSize(1);
    assertThat(merged.exitNodeIds()).hasSize(1);

    List<String> order = merged.topologicalSort().stream().map(DagNode::task).toList();
    assertThat(order.indexOf("a")).isLessThan(order.indexOf("b"));
    assertThat(order.indexOf("b")).isLessThan(order.indexOf("c"));
    assertThat(order.indexOf("c")).isLessThan(order.indexOf("d"));
  }

  // --- parallel wildcard accepts subtypes ---

  @Test
  void parallel_acceptsSubtypes() {
    List<Integer> ints = List.of(1, 2, 3);
    DagPlan<Number> plan = DagPlan.<Number>parallel(ints);
    assertThat(plan.nodes()).hasSize(3);
    assertThat(plan.nodes().values().iterator().next().task()).isInstanceOf(Integer.class);
  }

  @Test
  void sequence_acceptsSubtypes() {
    List<Integer> ints = List.of(10, 20);
    DagPlan<Number> plan = DagPlan.<Number>sequence(ints);
    assertThat(plan.nodes()).hasSize(2);
    assertThat(plan.nodes().get("node-1").dependsOn()).containsExactly("node-0");
  }

  // --- fromNodes (renamed from sequence) still works ---

  @Test
  void fromNodes_preservesPreWiredDependencies() {
    DagPlan<String> plan =
        DagPlan.fromNodes(
            List.of(
                new DagNode<>("x", "first", Set.of(), JoinType.ALL_OF),
                new DagNode<>("y", "second", Set.of("x"), JoinType.ANY_OF)));
    assertThat(plan.nodes().get("y").joinType()).isEqualTo(JoinType.ANY_OF);
    assertThat(plan.nodes().get("y").dependsOn()).containsExactly("x");
  }
}
