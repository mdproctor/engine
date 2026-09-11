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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

public class DagDriver<T, R> {

  private static final System.Logger LOG = System.getLogger(DagDriver.class.getName());
  private static final int DEFAULT_MAX_CONTINGENCY_DEPTH = 3;

  private final DagPlan<T> plan;
  private final DispatchMode mode;
  private final List<DagEventListener<T, R>> listeners;
  private final ConcurrentHashMap<String, NodeState<R>> states = new ConcurrentHashMap<>();
  private final AtomicBoolean executed = new AtomicBoolean(false);
  private final AtomicBoolean cancelSignal;
  // Set before execute() on nested drivers — safe because construction and execute() are sequential
  private int contingencyDepth;
  private int maxContingencyDepth = DEFAULT_MAX_CONTINGENCY_DEPTH;

  public DagDriver(DagPlan<T> plan) {
    this(plan, DispatchMode.STREAMING, List.of());
  }

  public DagDriver(DagPlan<T> plan, DispatchMode mode) {
    this(plan, mode, List.of());
  }

  public DagDriver(DagPlan<T> plan, DispatchMode mode, List<DagEventListener<T, R>> listeners) {
    this(plan, mode, listeners, new AtomicBoolean(false));
  }

  DagDriver(
      DagPlan<T> plan,
      DispatchMode mode,
      List<DagEventListener<T, R>> listeners,
      AtomicBoolean cancelSignal) {
    this.plan = Objects.requireNonNull(plan);
    this.mode = Objects.requireNonNull(mode);
    this.listeners = List.copyOf(listeners);
    this.cancelSignal = cancelSignal;
  }

  public DagResult<R> execute(Function<T, R> taskExecutor) {
    return execute(taskExecutor, Executors.newVirtualThreadPerTaskExecutor());
  }

  public DagResult<R> execute(Function<T, R> taskExecutor, Executor threadPool) {
    if (!executed.compareAndSet(false, true)) {
      throw new IllegalStateException(
          "DagDriver is single-use — construct a new instance for each execution");
    }
    plan.nodes().keySet().forEach(id -> states.put(id, new NodeState.Pending<>()));
    Instant start = Instant.now();

    if (mode == DispatchMode.BARRIER) {
      executeBarrier(taskExecutor, threadPool);
    } else {
      executeStreaming(taskExecutor, threadPool);
    }

    return buildResult(start);
  }

  public void cancel() {
    cancelSignal.set(true);
    for (var entry : states.entrySet()) {
      if (entry.getValue() instanceof NodeState.Pending) {
        if (states.replace(entry.getKey(), entry.getValue(), new NodeState.Cancelled<>())) {
          fireNodeCancelled(entry.getKey(), plan.nodes().get(entry.getKey()).task());
        }
      }
    }
  }

  private void executeBarrier(Function<T, R> taskExecutor, Executor threadPool) {
    while (!cancelSignal.get()) {
      propagateFailures();
      Set<String> ready = computeReadySet();
      if (ready.isEmpty()) {
        break;
      }

      List<CompletableFuture<Void>> wave = new ArrayList<>();
      for (String nodeId : ready) {
        DagNode<T> node = plan.nodes().get(nodeId);
        states.put(nodeId, new NodeState.Dispatched<>());
        fireNodeDispatched(nodeId, node.task());
        wave.add(
            CompletableFuture.runAsync(() -> executeNode(nodeId, node, taskExecutor), threadPool));
      }
      CompletableFuture.allOf(wave.toArray(new CompletableFuture[0])).join();
    }
    propagateFailures();
  }

  private void executeStreaming(Function<T, R> taskExecutor, Executor threadPool) {
    int totalNodes = plan.nodes().size();
    CountDownLatch latch = new CountDownLatch(totalNodes);

    propagateFailures();
    countDownTerminal(latch);

    Set<String> initialReady = computeReadySet();
    for (String nodeId : initialReady) {
      dispatchStreaming(nodeId, taskExecutor, threadPool, latch);
    }

    try {
      latch.await(10, TimeUnit.MINUTES);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void dispatchStreaming(
      String nodeId, Function<T, R> taskExecutor, Executor threadPool, CountDownLatch latch) {
    DagNode<T> node = plan.nodes().get(nodeId);
    if (!states.replace(nodeId, new NodeState.Pending<>(), new NodeState.Dispatched<>())) {
      return;
    }
    fireNodeDispatched(nodeId, node.task());

    CompletableFuture.runAsync(() -> executeNode(nodeId, node, taskExecutor), threadPool)
        .whenComplete(
            (v, ex) -> {
              latch.countDown();
              propagateFailures();
              countDownTerminal(latch);

              if (!cancelSignal.get()) {
                Set<String> newlyReady = computeReadySet();
                for (String readyId : newlyReady) {
                  dispatchStreaming(readyId, taskExecutor, threadPool, latch);
                }
              }
            });
  }

  private void countDownTerminal(CountDownLatch latch) {
    if (states.values().stream().allMatch(NodeState::isTerminal)) {
      while (latch.getCount() > 0) {
        latch.countDown();
      }
    }
  }

  private void executeNode(String nodeId, DagNode<T> node, Function<T, R> taskExecutor) {
    try {
      R result = taskExecutor.apply(node.task());
      states.put(nodeId, new NodeState.Completed<>(result));
      fireNodeCompleted(nodeId, node.task(), result);
    } catch (Exception e) {
      if (node.contingency() != null
          && !cancelSignal.get()
          && contingencyDepth < maxContingencyDepth) {
        var contingencyDriver =
            new DagDriver<T, R>(node.contingency(), mode, List.of(), cancelSignal);
        contingencyDriver.contingencyDepth = this.contingencyDepth + 1;
        contingencyDriver.maxContingencyDepth = this.maxContingencyDepth;
        DagResult<R> contingencyResult = contingencyDriver.execute(taskExecutor);
        fireContingencyActivated(nodeId, node.task(), contingencyResult);

        if (cancelSignal.get()) {
          states.put(nodeId, new NodeState.Cancelled<>());
          fireNodeCancelled(nodeId, node.task());
        } else if (contingencyResult.allSucceeded()) {
          String exitId = node.contingency().exitNodeIds().iterator().next();
          R fallbackResult = contingencyResult.completedResults().get(exitId);
          states.put(nodeId, new NodeState.Completed<>(fallbackResult));
          fireNodeCompleted(nodeId, node.task(), fallbackResult);
        } else {
          states.put(nodeId, new NodeState.Failed<>(e.getMessage(), e));
          fireNodeFailed(nodeId, node.task(), e.getMessage(), e);
        }
      } else {
        states.put(nodeId, new NodeState.Failed<>(e.getMessage(), e));
        fireNodeFailed(nodeId, node.task(), e.getMessage(), e);
      }
    }
  }

  private Set<String> computeReadySet() {
    Set<String> ready = new LinkedHashSet<>();
    for (var entry : plan.nodes().entrySet()) {
      String nodeId = entry.getKey();
      DagNode<T> node = entry.getValue();
      if (!(states.get(nodeId) instanceof NodeState.Pending)) {
        continue;
      }
      if (cancelSignal.get()) {
        continue;
      }

      boolean satisfied =
          switch (node.joinType()) {
            case ALL_OF ->
                node.dependsOn().stream()
                    .allMatch(dep -> states.get(dep) instanceof NodeState.Completed);
            case ANY_OF ->
                node.dependsOn().isEmpty()
                    || node.dependsOn().stream()
                        .anyMatch(dep -> states.get(dep) instanceof NodeState.Completed);
          };
      if (satisfied) {
        ready.add(nodeId);
      }
    }
    return ready;
  }

  private void propagateFailures() {
    boolean changed = true;
    while (changed) {
      changed = false;
      for (var entry : plan.nodes().entrySet()) {
        String nodeId = entry.getKey();
        if (!(states.get(nodeId) instanceof NodeState.Pending)) {
          continue;
        }
        if (isUnreachable(nodeId)) {
          states.put(nodeId, new NodeState.Skipped<>("predecessor failed"));
          fireNodeSkipped(nodeId, entry.getValue().task(), "predecessor failed");
          changed = true;
        }
      }
    }
  }

  private boolean isUnreachable(String nodeId) {
    DagNode<T> node = plan.nodes().get(nodeId);
    return switch (node.joinType()) {
      case ALL_OF ->
          node.dependsOn().stream()
              .anyMatch(
                  dep -> {
                    var s = states.get(dep);
                    return s instanceof NodeState.Failed
                        || s instanceof NodeState.Skipped
                        || s instanceof NodeState.Cancelled;
                  });
      case ANY_OF ->
          !node.dependsOn().isEmpty()
              && node.dependsOn().stream()
                  .allMatch(
                      dep -> {
                        var s = states.get(dep);
                        return s instanceof NodeState.Failed
                            || s instanceof NodeState.Skipped
                            || s instanceof NodeState.Cancelled;
                      });
    };
  }

  private DagResult<R> buildResult(Instant start) {
    Map<String, R> completed = new LinkedHashMap<>();
    boolean allSucceeded = true;
    for (var entry : states.entrySet()) {
      if (entry.getValue() instanceof NodeState.Completed<R> c) {
        completed.put(entry.getKey(), c.result());
      } else {
        allSucceeded = false;
      }
    }
    Duration elapsed = Duration.between(start, Instant.now());
    var result = new DagResult<>(Map.copyOf(states), Map.copyOf(completed), allSucceeded, elapsed);
    fireExecutionComplete(result);
    return result;
  }

  private void fireNodeDispatched(String nodeId, T task) {
    for (var l : listeners) {
      try {
        l.onNodeDispatched(nodeId, task);
      } catch (Exception e) {
        LOG.log(System.Logger.Level.WARNING, "Listener threw on dispatch", e);
      }
    }
  }

  private void fireNodeCompleted(String nodeId, T task, R result) {
    for (var l : listeners) {
      try {
        l.onNodeCompleted(nodeId, task, result);
      } catch (Exception e) {
        LOG.log(System.Logger.Level.WARNING, "Listener threw on complete", e);
      }
    }
  }

  private void fireNodeFailed(String nodeId, T task, String reason, Throwable cause) {
    for (var l : listeners) {
      try {
        l.onNodeFailed(nodeId, task, reason, cause);
      } catch (Exception e) {
        LOG.log(System.Logger.Level.WARNING, "Listener threw on fail", e);
      }
    }
  }

  private void fireNodeSkipped(String nodeId, T task, String reason) {
    for (var l : listeners) {
      try {
        l.onNodeSkipped(nodeId, task, reason);
      } catch (Exception e) {
        LOG.log(System.Logger.Level.WARNING, "Listener threw on skip", e);
      }
    }
  }

  private void fireNodeCancelled(String nodeId, T task) {
    for (var l : listeners) {
      try {
        l.onNodeCancelled(nodeId, task);
      } catch (Exception e) {
        LOG.log(System.Logger.Level.WARNING, "Listener threw on cancel", e);
      }
    }
  }

  private void fireContingencyActivated(String nodeId, T task, DagResult<R> contingencyResult) {
    for (var l : listeners) {
      try {
        l.onContingencyActivated(nodeId, task, contingencyResult);
      } catch (Exception e) {
        LOG.log(System.Logger.Level.WARNING, "Listener threw on contingency activated", e);
      }
    }
  }

  private void fireExecutionComplete(DagResult<R> result) {
    for (var l : listeners) {
      try {
        l.onExecutionComplete(result);
      } catch (Exception e) {
        LOG.log(System.Logger.Level.WARNING, "Listener threw on execution complete", e);
      }
    }
  }
}
