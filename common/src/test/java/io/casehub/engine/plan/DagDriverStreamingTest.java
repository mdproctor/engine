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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DagDriverStreamingTest {

  @Test
  void asymmetricDepth_streamingDispatchesEarly() throws Exception {
    var dDispatched = new CountDownLatch(1);
    var cStarted = new CountDownLatch(1);

    var plan =
        new DagPlan<>(
            Map.of(
                "a", new DagNode<>("a", "root", Set.of(), io.casehub.engine.plan.JoinType.ALL_OF),
                "b", new DagNode<>("b", "fast", Set.of("a"), JoinType.ALL_OF),
                "c", new DagNode<>("c", "slow", Set.of("a"), JoinType.ALL_OF),
                "d", new DagNode<>("d", "after-fast", Set.of("b"), JoinType.ALL_OF)));

    var driver =
        new DagDriver<String, String>(
            plan,
            DispatchMode.STREAMING,
            java.util.List.of(
                new DagEventListener<String, String>() {
                  @Override
                  public void onNodeDispatched(String nodeId, String task) {
                    if ("d".equals(nodeId)) dDispatched.countDown();
                  }
                }));

    CompletableFuture.runAsync(
        () ->
            driver.execute(
                task -> {
                  if ("slow".equals(task)) {
                    cStarted.countDown();
                    try {
                      Thread.sleep(2000);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                  }
                  return task;
                }));

    cStarted.await(5, TimeUnit.SECONDS);
    boolean dDispatchedBeforeCFinished = dDispatched.await(1, TimeUnit.SECONDS);
    assertThat(dDispatchedBeforeCFinished).isTrue();
  }

  @Test
  void anyOfSiblingContinuation_remainingPredecessorsComplete() {
    var completed = new CopyOnWriteArrayList<String>();
    var plan =
        new DagPlan<>(
            Map.of(
                "a", new DagNode<>("a", "fast", Set.of(), JoinType.ALL_OF),
                "b", new DagNode<>("b", "slow", Set.of(), JoinType.ALL_OF),
                "join", new DagNode<>("join", "join", Set.of("a", "b"), JoinType.ANY_OF)));
    var driver = new DagDriver<String, String>(plan, DispatchMode.STREAMING);
    DagResult<String> result =
        driver.execute(
            task -> {
              if ("slow".equals(task)) {
                try {
                  Thread.sleep(200);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
              completed.add(task);
              return task;
            });

    assertThat(result.nodeStates().get("join")).isInstanceOf(NodeState.Completed.class);
    assertThat(result.nodeStates().get("a")).isInstanceOf(NodeState.Completed.class);
    assertThat(result.nodeStates().get("b")).isInstanceOf(NodeState.Completed.class);
  }

  @Test
  void cancellation_pendingBecomesCancelled() throws Exception {
    var rootStarted = new CountDownLatch(1);
    var plan =
        new DagPlan<>(
            Map.of(
                "a", new DagNode<>("a", "slow-root", Set.of(), JoinType.ALL_OF),
                "b", new DagNode<>("b", "child", Set.of("a"), JoinType.ALL_OF)));
    var driver = new DagDriver<String, String>(plan, DispatchMode.STREAMING);

    CompletableFuture<DagResult<String>> future =
        CompletableFuture.supplyAsync(
            () ->
                driver.execute(
                    task -> {
                      if ("slow-root".equals(task)) {
                        rootStarted.countDown();
                        try {
                          Thread.sleep(2000);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                      }
                      return task;
                    }));

    rootStarted.await(5, TimeUnit.SECONDS);
    driver.cancel();

    DagResult<String> result = future.get(5, TimeUnit.SECONDS);
    assertThat(result.nodeStates().get("b")).isInstanceOf(NodeState.Cancelled.class);
  }

  @Test
  void raceCondition_10ParallelPaths_cleanTransitions() {
    Map<String, DagNode<Integer>> nodes = new LinkedHashMap<>();
    for (int i = 0; i < 10; i++) {
      nodes.put("p" + i, new DagNode<>("p" + i, i, Set.of(), JoinType.ALL_OF));
    }
    nodes.put("join", new DagNode<>("join", 99, Set.copyOf(nodes.keySet()), JoinType.ALL_OF));
    var plan = new DagPlan<>(nodes);
    var driver = new DagDriver<Integer, Integer>(plan, DispatchMode.STREAMING);
    DagResult<Integer> result =
        driver.execute(
            t -> {
              try {
                Thread.sleep(ThreadLocalRandom.current().nextInt(10, 50));
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return t * 2;
            });

    assertThat(result.allSucceeded()).isTrue();
    assertThat(result.completedResults()).hasSize(11);
    assertThat(result.nodeStates().values()).allMatch(NodeState::isTerminal);
  }

  @Test
  void complexDag_mixedJoinTypes() {
    var plan =
        new DagPlan<>(
            Map.of(
                "a", new DagNode<>("a", "root", Set.of(), JoinType.ALL_OF),
                "b", new DagNode<>("b", "left", Set.of("a"), JoinType.ALL_OF),
                "c", new DagNode<>("c", "right", Set.of("a"), JoinType.ALL_OF),
                "d", new DagNode<>("d", "any-join", Set.of("b", "c"), JoinType.ANY_OF),
                "e", new DagNode<>("e", "final", Set.of("d"), JoinType.ALL_OF)));
    var driver = new DagDriver<String, String>(plan, DispatchMode.STREAMING);
    DagResult<String> result = driver.execute(t -> t);

    assertThat(result.allSucceeded()).isTrue();
    assertThat(result.completedResults()).hasSize(5);
  }

  @Test
  void slowNodeDoesNotBlockIndependentPath() throws Exception {
    var fastCompleted = new CountDownLatch(1);
    var plan =
        new DagPlan<>(
            Map.of(
                "slow", new DagNode<>("slow", "slow", Set.of(), JoinType.ALL_OF),
                "fast", new DagNode<>("fast", "fast", Set.of(), JoinType.ALL_OF)));

    var driver =
        new DagDriver<String, String>(
            plan,
            DispatchMode.STREAMING,
            java.util.List.of(
                new DagEventListener<String, String>() {
                  @Override
                  public void onNodeCompleted(String nodeId, String task, String result) {
                    if ("fast".equals(nodeId)) fastCompleted.countDown();
                  }
                }));

    CompletableFuture.runAsync(
        () ->
            driver.execute(
                task -> {
                  if ("slow".equals(task)) {
                    try {
                      Thread.sleep(2000);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                  }
                  return task;
                }));

    boolean fastDone = fastCompleted.await(1, TimeUnit.SECONDS);
    assertThat(fastDone).isTrue();
  }
}
