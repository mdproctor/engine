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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class DagDriverBarrierTest {

  @Test
  void singleNode_completesSuccessfully() {
    var plan = DagPlan.<String>singleton("a", "task-a");
    var driver = new DagDriver<String, String>(plan, DispatchMode.BARRIER);
    DagResult<String> result = driver.execute(task -> "done:" + task);

    assertThat(result.allSucceeded()).isTrue();
    assertThat(result.completedResults()).containsEntry("a", "done:task-a");
  }

  @Test
  void linearChain_executesInOrder() {
    var order = new CopyOnWriteArrayList<String>();
    var plan =
        DagPlan.fromNodes(
            List.of(
                new DagNode<>("a", "first", Set.of(), io.casehub.engine.plan.JoinType.ALL_OF),
                new DagNode<>("b", "second", Set.of("a"), JoinType.ALL_OF),
                new DagNode<>("c", "third", Set.of("b"), JoinType.ALL_OF)));
    var driver = new DagDriver<String, String>(plan, DispatchMode.BARRIER);
    DagResult<String> result =
        driver.execute(
            task -> {
              order.add(task);
              return task;
            });

    assertThat(result.allSucceeded()).isTrue();
    assertThat(order).containsExactly("first", "second", "third");
  }

  @Test
  void fullParallel_allDispatchInFirstWave() {
    var dispatched = new CopyOnWriteArrayList<String>();
    var plan = DagPlan.parallel(List.of("a", "b", "c"));
    var driver = new DagDriver<String, String>(plan, DispatchMode.BARRIER);
    DagResult<String> result =
        driver.execute(
            task -> {
              dispatched.add(task);
              return task;
            });

    assertThat(result.allSucceeded()).isTrue();
    assertThat(dispatched).containsExactlyInAnyOrder("a", "b", "c");
  }

  @Test
  void diamond_correctExecutionOrder() {
    var order = Collections.synchronizedList(new ArrayList<String>());
    var plan =
        new DagPlan<>(
            Map.of(
                "a", new DagNode<>("a", "root", Set.of(), JoinType.ALL_OF),
                "b", new DagNode<>("b", "left", Set.of("a"), JoinType.ALL_OF),
                "c", new DagNode<>("c", "right", Set.of("a"), JoinType.ALL_OF),
                "d", new DagNode<>("d", "join", Set.of("b", "c"), JoinType.ALL_OF)));
    var driver = new DagDriver<String, String>(plan, DispatchMode.BARRIER);
    DagResult<String> result =
        driver.execute(
            task -> {
              order.add(task);
              return task;
            });

    assertThat(result.allSucceeded()).isTrue();
    assertThat(order.indexOf("root")).isLessThan(order.indexOf("left"));
    assertThat(order.indexOf("root")).isLessThan(order.indexOf("right"));
    assertThat(order.indexOf("left")).isLessThan(order.indexOf("join"));
    assertThat(order.indexOf("right")).isLessThan(order.indexOf("join"));
  }

  @Test
  void wideFanOut_allChildrenExecute() {
    var plan =
        new DagPlan<>(
            Map.of(
                "root", new DagNode<>("root", "r", Set.of(), JoinType.ALL_OF),
                "c1", new DagNode<>("c1", "1", Set.of("root"), JoinType.ALL_OF),
                "c2", new DagNode<>("c2", "2", Set.of("root"), JoinType.ALL_OF),
                "c3", new DagNode<>("c3", "3", Set.of("root"), JoinType.ALL_OF),
                "c4", new DagNode<>("c4", "4", Set.of("root"), JoinType.ALL_OF),
                "c5", new DagNode<>("c5", "5", Set.of("root"), JoinType.ALL_OF)));
    var driver = new DagDriver<String, String>(plan, DispatchMode.BARRIER);
    DagResult<String> result = driver.execute(t -> t);

    assertThat(result.allSucceeded()).isTrue();
    assertThat(result.completedResults()).hasSize(6);
  }

  @Test
  void wideFanIn_joinsAllPredecessors() {
    var plan =
        new DagPlan<>(
            Map.of(
                "a", new DagNode<>("a", "1", Set.of(), JoinType.ALL_OF),
                "b", new DagNode<>("b", "2", Set.of(), JoinType.ALL_OF),
                "c", new DagNode<>("c", "3", Set.of(), JoinType.ALL_OF),
                "join", new DagNode<>("join", "j", Set.of("a", "b", "c"), JoinType.ALL_OF)));
    var driver = new DagDriver<String, String>(plan, DispatchMode.BARRIER);
    DagResult<String> result = driver.execute(t -> t);

    assertThat(result.allSucceeded()).isTrue();
    assertThat(result.completedResults()).hasSize(4);
  }

  @Test
  void allOfPartialFail_dependentSkipped_independentContinues() {
    var plan =
        new DagPlan<>(
            Map.of(
                "a", new DagNode<>("a", "root", Set.of(), JoinType.ALL_OF),
                "b", new DagNode<>("b", "fail", Set.of("a"), JoinType.ALL_OF),
                "c", new DagNode<>("c", "ok", Set.of("a"), JoinType.ALL_OF),
                "d", new DagNode<>("d", "join", Set.of("b", "c"), JoinType.ALL_OF)));
    var driver = new DagDriver<String, String>(plan, DispatchMode.BARRIER);
    DagResult<String> result =
        driver.execute(
            task -> {
              if ("fail".equals(task)) throw new RuntimeException("boom");
              return task;
            });

    assertThat(result.allSucceeded()).isFalse();
    assertThat(result.nodeStates().get("b")).isInstanceOf(NodeState.Failed.class);
    assertThat(result.nodeStates().get("c")).isInstanceOf(NodeState.Completed.class);
    assertThat(result.nodeStates().get("d")).isInstanceOf(NodeState.Skipped.class);
  }

  @Test
  void transitiveFailure_allDependentsSkipped() {
    var plan =
        DagPlan.fromNodes(
            List.of(
                new DagNode<>("a", "fail", Set.of(), JoinType.ALL_OF),
                new DagNode<>("b", "b", Set.of("a"), JoinType.ALL_OF),
                new DagNode<>("c", "c", Set.of("b"), JoinType.ALL_OF)));
    var driver = new DagDriver<String, String>(plan, DispatchMode.BARRIER);
    DagResult<String> result =
        driver.execute(
            task -> {
              if ("fail".equals(task)) throw new RuntimeException("boom");
              return task;
            });

    assertThat(result.nodeStates().get("a")).isInstanceOf(NodeState.Failed.class);
    assertThat(result.nodeStates().get("b")).isInstanceOf(NodeState.Skipped.class);
    assertThat(result.nodeStates().get("c")).isInstanceOf(NodeState.Skipped.class);
  }

  @Test
  void independentPaths_failureIsolated() {
    var plan =
        new DagPlan<>(
            Map.of(
                "a", new DagNode<>("a", "fail", Set.of(), JoinType.ALL_OF),
                "b", new DagNode<>("b", "b", Set.of("a"), JoinType.ALL_OF),
                "c", new DagNode<>("c", "ok", Set.of(), JoinType.ALL_OF),
                "d", new DagNode<>("d", "d", Set.of("c"), JoinType.ALL_OF)));
    var driver = new DagDriver<String, String>(plan, DispatchMode.BARRIER);
    DagResult<String> result =
        driver.execute(
            task -> {
              if ("fail".equals(task)) throw new RuntimeException("boom");
              return task;
            });

    assertThat(result.nodeStates().get("a")).isInstanceOf(NodeState.Failed.class);
    assertThat(result.nodeStates().get("b")).isInstanceOf(NodeState.Skipped.class);
    assertThat(result.nodeStates().get("c")).isInstanceOf(NodeState.Completed.class);
    assertThat(result.nodeStates().get("d")).isInstanceOf(NodeState.Completed.class);
  }

  @Test
  void anyOf_firesOnFirstSuccess() {
    var plan =
        new DagPlan<>(
            Map.of(
                "a", new DagNode<>("a", "slow", Set.of(), JoinType.ALL_OF),
                "b", new DagNode<>("b", "fast", Set.of(), JoinType.ALL_OF),
                "c", new DagNode<>("c", "join", Set.of("a", "b"), JoinType.ANY_OF)));
    var driver = new DagDriver<String, String>(plan, DispatchMode.BARRIER);
    DagResult<String> result = driver.execute(t -> t);

    assertThat(result.nodeStates().get("c")).isInstanceOf(NodeState.Completed.class);
  }

  @Test
  void anyOf_allPredecessorsFail_skipped() {
    var plan =
        new DagPlan<>(
            Map.of(
                "a", new DagNode<>("a", "f1", Set.of(), JoinType.ALL_OF),
                "b", new DagNode<>("b", "f2", Set.of(), JoinType.ALL_OF),
                "c", new DagNode<>("c", "join", Set.of("a", "b"), JoinType.ANY_OF)));
    var driver = new DagDriver<String, String>(plan, DispatchMode.BARRIER);
    DagResult<String> result =
        driver.execute(
            task -> {
              throw new RuntimeException("fail");
            });

    assertThat(result.nodeStates().get("c")).isInstanceOf(NodeState.Skipped.class);
  }

  @Test
  void singleUse_secondExecuteThrows() {
    var plan = DagPlan.<String>singleton("a", "task");
    var driver = new DagDriver<String, String>(plan, DispatchMode.BARRIER);
    driver.execute(t -> t);

    assertThatThrownBy(() -> driver.execute(t -> t)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void listenerNotifications_fireCorrectly() {
    var dispatched = new CopyOnWriteArrayList<String>();
    var completed = new CopyOnWriteArrayList<String>();
    var listener =
        new DagEventListener<String, String>() {
          @Override
          public void onNodeDispatched(String nodeId, String task) {
            dispatched.add(nodeId);
          }

          @Override
          public void onNodeCompleted(String nodeId, String task, String result) {
            completed.add(nodeId);
          }
        };
    var plan =
        DagPlan.fromNodes(
            List.of(
                new DagNode<>("a", "first", Set.of(), JoinType.ALL_OF),
                new DagNode<>("b", "second", Set.of("a"), JoinType.ALL_OF)));
    var driver = new DagDriver<>(plan, DispatchMode.BARRIER, List.of(listener));
    driver.execute(t -> t);

    assertThat(dispatched).containsExactly("a", "b");
    assertThat(completed).containsExactly("a", "b");
  }

  @Test
  void listenerException_doesNotCrashExecution() {
    var listener =
        new DagEventListener<String, String>() {
          @Override
          public void onNodeDispatched(String nodeId, String task) {
            throw new RuntimeException("listener crash");
          }
        };
    var plan = DagPlan.<String>singleton("a", "task");
    var driver = new DagDriver<String, String>(plan, DispatchMode.BARRIER, List.of(listener));
    DagResult<String> result = driver.execute(t -> t);

    assertThat(result.allSucceeded()).isTrue();
  }

  @Test
  void customExecutor_usedForDispatch() {
    var pool = Executors.newFixedThreadPool(2);
    try {
      var threadNames = new CopyOnWriteArrayList<String>();
      var plan = DagPlan.parallel(List.of("a", "b"));
      var driver = new DagDriver<String, String>(plan, DispatchMode.BARRIER);
      driver.execute(
          task -> {
            threadNames.add(Thread.currentThread().getName());
            return task;
          },
          pool);

      assertThat(threadNames).allMatch(name -> name.startsWith("pool-"));
    } finally {
      pool.shutdown();
    }
  }

  @Test
  void largeFanOut_completesWithoutDeadlock() {
    Map<String, DagNode<Integer>> nodes = new LinkedHashMap<>();
    nodes.put("root", new DagNode<>("root", 0, Set.of(), JoinType.ALL_OF));
    for (int i = 1; i <= 100; i++) {
      String id = "n" + i;
      nodes.put(id, new DagNode<>(id, i, Set.of("root"), JoinType.ALL_OF));
    }
    var plan = new DagPlan<>(nodes);
    var driver = new DagDriver<Integer, Integer>(plan, DispatchMode.BARRIER);
    DagResult<Integer> result = driver.execute(t -> t * 2);

    assertThat(result.allSucceeded()).isTrue();
    assertThat(result.completedResults()).hasSize(101);
  }
}
