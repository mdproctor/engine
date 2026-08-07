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
package io.casehub.engine.plan.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.engine.plan.DagNode;
import io.casehub.engine.plan.DagPlan;
import io.casehub.engine.plan.JoinType;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DagPlanSnapshotTest {

  @Test
  void fromDagPlanExtractsNodeStructure() {
    var nodeA = new DagNode<>("a", "task-a", Set.of(), JoinType.ALL_OF);
    var nodeB = new DagNode<>("b", "task-b", Set.of("a"), JoinType.ANY_OF);
    var plan = DagPlan.fromNodes(List.of(nodeA, nodeB));
    var now = Instant.now();

    var snapshot = DagPlanSnapshot.from(plan, now);

    assertThat(snapshot.nodes()).hasSize(2);
    assertThat(snapshot.timestamp()).isEqualTo(now);

    var snapA = snapshot.nodes().get("a");
    assertThat(snapA.id()).isEqualTo("a");
    assertThat(snapA.joinType()).isEqualTo(JoinType.ALL_OF);
    assertThat(snapA.dependsOn()).isEmpty();

    var snapB = snapshot.nodes().get("b");
    assertThat(snapB.dependsOn()).containsExactly("a");
    assertThat(snapB.joinType()).isEqualTo(JoinType.ANY_OF);
  }

  @Test
  void fromDagPlanExtractsTaskDescriptorFields() {
    var leaf = new TestLeafTask("leaf-1", "Analyse input", "agent-alpha");
    var node = new DagNode<>("n1", leaf, Set.of(), JoinType.ALL_OF);
    var plan = DagPlan.fromNodes(List.of(node));

    var snapshot = DagPlanSnapshot.from(plan, Instant.now());
    var snap = snapshot.nodes().get("n1");

    assertThat(snap.taskId()).isEqualTo("leaf-1");
    assertThat(snap.taskDescription()).isEqualTo("Analyse input");
    assertThat(snap.executorName()).isEqualTo("agent-alpha");
  }

  @Test
  void fromDagPlanWithNonTaskDescriptorLeavesFieldsNull() {
    var node = new DagNode<>("n1", "plain-string-task", Set.of(), JoinType.ALL_OF);
    var plan = DagPlan.fromNodes(List.of(node));

    var snapshot = DagPlanSnapshot.from(plan, Instant.now());
    var snap = snapshot.nodes().get("n1");

    assertThat(snap.taskId()).isNull();
    assertThat(snap.taskDescription()).isNull();
    assertThat(snap.executorName()).isNull();
  }
}
