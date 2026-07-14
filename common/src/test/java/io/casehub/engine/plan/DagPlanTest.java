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
  void sequence_createsLinearChain() {
    DagPlan<String> plan =
        DagPlan.sequence(
            List.of(
                new DagNode<>("a", "first", Set.of(), JoinType.ALL_OF),
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
}
