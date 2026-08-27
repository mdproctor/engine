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
import java.util.Set;
import org.junit.jupiter.api.Test;

class DagNodeTest {

  @Test
  void contingency_nullIsAccepted() {
    var node = new DagNode<>("a", "task", Set.of(), JoinType.ALL_OF, null);
    assertThat(node.contingency()).isNull();
  }

  @Test
  void contingency_singleExitPlanIsAccepted() {
    var contingency = DagPlan.singleton("fallback");
    var node = new DagNode<>("a", "task", Set.of(), JoinType.ALL_OF, contingency);
    assertThat(node.contingency()).isEqualTo(contingency);
  }

  @Test
  void contingency_multiExitPlanIsRejected() {
    var contingency = DagPlan.parallel(List.of("alt-1", "alt-2"));
    assertThatThrownBy(() -> new DagNode<>("a", "task", Set.of(), JoinType.ALL_OF, contingency))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("single exit node");
  }

  @Test
  void contingency_sequentialPlanIsAccepted() {
    var contingency = DagPlan.sequence(List.of("step-1", "step-2"));
    var node = new DagNode<>("a", "task", Set.of(), JoinType.ALL_OF, contingency);
    assertThat(node.contingency()).isNotNull();
    assertThat(node.contingency().exitNodeIds()).hasSize(1);
  }

  @Test
  void backwardCompatibleConstructor_setsNullContingency() {
    var node = new DagNode<>("a", "task", Set.of(), JoinType.ALL_OF);
    assertThat(node.contingency()).isNull();
  }

  @Test
  void judgment_nullByDefault() {
    var node = new DagNode<>("a", "task", Set.of(), JoinType.ALL_OF);
    assertThat(node.judgment()).isNull();
  }

  @Test
  void judgment_nullWithContingencyConstructor() {
    var node = new DagNode<>("a", "task", Set.of(), JoinType.ALL_OF, null);
    assertThat(node.judgment()).isNull();
  }

  @Test
  void judgment_carriesTarget() {
    var target = io.casehub.api.model.JudgmentTarget.forHuman().prompt("Review the output").build();
    var node = new DagNode<>("a", "task", Set.of(), JoinType.ALL_OF, null, target);
    assertThat(node.judgment()).isNotNull();
    assertThat(node.judgment().prompt()).isEqualTo("Review the output");
  }

  @Test
  void judgment_coexistsWithContingency() {
    var contingency = DagPlan.singleton("fallback");
    var target = io.casehub.api.model.JudgmentTarget.forAny().prompt("Validate").build();
    var node = new DagNode<>("a", "task", Set.of(), JoinType.ALL_OF, contingency, target);
    assertThat(node.contingency()).isNotNull();
    assertThat(node.judgment()).isNotNull();
  }
}
