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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DagDriverContingencyTest {

  @Test
  void primaryFails_contingencySucceeds_nodeCompleted() {
    var contingency = DagPlan.singleton("fallback-task");
    var node = new DagNode<>("a", "primary-task", Set.of(), JoinType.ALL_OF, contingency);
    var plan = new DagPlan<>(Map.of("a", node));

    var driver = new DagDriver<String, String>(plan, DispatchMode.STREAMING);
    DagResult<String> result =
        driver.execute(
            task -> {
              if ("primary-task".equals(task)) throw new RuntimeException("primary failed");
              return "fallback-result:" + task;
            });

    assertThat(result.allSucceeded()).isTrue();
    assertThat(result.completedResults().get("a")).isEqualTo("fallback-result:fallback-task");
  }

  @Test
  void primaryFails_contingencyFails_nodeFailed() {
    var contingency = DagPlan.singleton("fallback-task");
    var node = new DagNode<>("a", "primary-task", Set.of(), JoinType.ALL_OF, contingency);
    var plan = new DagPlan<>(Map.of("a", node));

    var driver = new DagDriver<String, String>(plan, DispatchMode.STREAMING);
    DagResult<String> result =
        driver.execute(
            task -> {
              throw new RuntimeException(task + " failed");
            });

    assertThat(result.allSucceeded()).isFalse();
    assertThat(result.nodeStates().get("a")).isInstanceOf(NodeState.Failed.class);
  }

  @Test
  void primarySucceeds_contingencyNotActivated() {
    var contingency = DagPlan.singleton("fallback-task");
    var node = new DagNode<>("a", "primary-task", Set.of(), JoinType.ALL_OF, contingency);
    var plan = new DagPlan<>(Map.of("a", node));

    var driver = new DagDriver<String, String>(plan, DispatchMode.STREAMING);
    DagResult<String> result = driver.execute(task -> "result:" + task);

    assertThat(result.allSucceeded()).isTrue();
    assertThat(result.completedResults().get("a")).isEqualTo("result:primary-task");
  }

  @Test
  void contingencyActivated_listenerReceivesDagResult() {
    var contingency = DagPlan.singleton("fallback-task");
    var node = new DagNode<>("a", "primary-task", Set.of(), JoinType.ALL_OF, contingency);
    var plan = new DagPlan<>(Map.of("a", node));

    AtomicReference<DagResult<String>> captured = new AtomicReference<>();
    DagEventListener<String, String> listener =
        new DagEventListener<>() {
          @Override
          public void onContingencyActivated(String nodeId, String task, DagResult<String> result) {
            captured.set(result);
          }
        };

    var driver = new DagDriver<String, String>(plan, DispatchMode.STREAMING, List.of(listener));
    driver.execute(
        task -> {
          if ("primary-task".equals(task)) throw new RuntimeException("failed");
          return "fallback:" + task;
        });

    assertThat(captured.get()).isNotNull();
    assertThat(captured.get().allSucceeded()).isTrue();
  }

  @Test
  void contingencySuccess_dependentNodeExecutes() {
    var contingency = DagPlan.singleton("fallback-task");
    var nodeA = new DagNode<>("a", "primary", Set.of(), JoinType.ALL_OF, contingency);
    var nodeB = new DagNode<>("b", "dependent", Set.of("a"), JoinType.ALL_OF);
    var plan = DagPlan.fromNodes(List.of(nodeA, nodeB));

    var driver = new DagDriver<String, String>(plan, DispatchMode.STREAMING);
    DagResult<String> result =
        driver.execute(
            task -> {
              if ("primary".equals(task)) throw new RuntimeException("failed");
              return "ok:" + task;
            });

    assertThat(result.allSucceeded()).isTrue();
    assertThat(result.completedResults().get("b")).isEqualTo("ok:dependent");
  }

  @Test
  void contingencyFailure_dependentNodeSkipped() {
    var contingency = DagPlan.singleton("fallback-task");
    var nodeA = new DagNode<>("a", "primary", Set.of(), JoinType.ALL_OF, contingency);
    var nodeB = new DagNode<>("b", "dependent", Set.of("a"), JoinType.ALL_OF);
    var plan = DagPlan.fromNodes(List.of(nodeA, nodeB));

    var driver = new DagDriver<String, String>(plan, DispatchMode.STREAMING);
    DagResult<String> result =
        driver.execute(
            task -> {
              throw new RuntimeException(task + " failed");
            });

    assertThat(result.nodeStates().get("a")).isInstanceOf(NodeState.Failed.class);
    assertThat(result.nodeStates().get("b")).isInstanceOf(NodeState.Skipped.class);
  }

  @Test
  void cancellation_duringContingency_nodeMarkedCancelled() {
    var contingency = DagPlan.singleton("slow-fallback");
    var node = new DagNode<>("a", "primary", Set.of(), JoinType.ALL_OF, contingency);
    var plan = new DagPlan<>(Map.of("a", node));

    var driver = new DagDriver<String, String>(plan, DispatchMode.STREAMING);
    DagResult<String> result =
        driver.execute(
            task -> {
              if ("primary".equals(task)) throw new RuntimeException("failed");
              driver.cancel();
              return "should-be-discarded";
            });

    assertThat(result.nodeStates().get("a")).isInstanceOf(NodeState.Cancelled.class);
  }

  @Test
  void barrierMode_contingencyWorks() {
    var contingency = DagPlan.singleton("fallback-task");
    var node = new DagNode<>("a", "primary-task", Set.of(), JoinType.ALL_OF, contingency);
    var plan = new DagPlan<>(Map.of("a", node));

    var driver = new DagDriver<String, String>(plan, DispatchMode.BARRIER);
    DagResult<String> result =
        driver.execute(
            task -> {
              if ("primary-task".equals(task)) throw new RuntimeException("failed");
              return "fallback:" + task;
            });

    assertThat(result.allSucceeded()).isTrue();
    assertThat(result.completedResults().get("a")).isEqualTo("fallback:fallback-task");
  }

  @Test
  void multiStepContingency_executesSequentially() {
    var contingency = DagPlan.sequence(List.of("step-1", "step-2"));
    var node = new DagNode<>("a", "primary", Set.of(), JoinType.ALL_OF, contingency);
    var plan = new DagPlan<>(Map.of("a", node));

    var driver = new DagDriver<String, String>(plan, DispatchMode.STREAMING);
    DagResult<String> result =
        driver.execute(
            task -> {
              if ("primary".equals(task)) throw new RuntimeException("failed");
              return "done:" + task;
            });

    assertThat(result.allSucceeded()).isTrue();
    assertThat(result.completedResults().get("a")).isEqualTo("done:step-2");
  }

  @Test
  void noContingency_normalFailureBehavior() {
    var node = new DagNode<>("a", "primary", Set.of(), JoinType.ALL_OF);
    var plan = new DagPlan<>(Map.of("a", node));

    var driver = new DagDriver<String, String>(plan, DispatchMode.STREAMING);
    DagResult<String> result =
        driver.execute(
            task -> {
              throw new RuntimeException("failed");
            });

    assertThat(result.allSucceeded()).isFalse();
    assertThat(result.nodeStates().get("a")).isInstanceOf(NodeState.Failed.class);
  }
}
