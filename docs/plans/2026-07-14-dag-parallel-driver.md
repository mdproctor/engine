# DAG-Aware Parallel Execution Driver Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #695 — DAG-aware parallel execution driver
**Issue group:** #725, #695

**Goal:** Add a DAG execution driver to casehub-engine-common that dispatches
independent tasks concurrently and gates dependent tasks on predecessor completion.

**Architecture:** Pure Java types in `io.casehub.engine.plan` package within
`casehub-engine-common`. `DagPlan<T>` is an immutable validated DAG. `DagDriver<T, R>`
executes plans with two modes: STREAMING (reactive dispatch on each completion) and
BARRIER (wave-based). All concurrency via `java.util.concurrent` — no CDI, no Mutiny.

**Tech Stack:** Java 21, `java.util.concurrent` (CompletableFuture, CountDownLatch,
ConcurrentHashMap, virtual threads)

## Global Constraints

- Package: `io.casehub.engine.plan` in module `casehub-engine-common`
- No CDI annotations, no Mutiny, no Quarkus runtime dependencies
- Pure POJOs — all types must work in unit tests without a container
- `NodeState.toTaskStatus()` maps to `io.casehub.api.model.TaskStatus` from engine-api
- `DagDriver` is single-use — `execute()` throws `IllegalStateException` on re-invocation
- Listener exceptions are caught and logged — never crash the scheduler
- `cancel()` marks pending nodes as CANCELLED (not PENDING)

---

### Task 1: Plan Data Model — JoinType, DagNode, DagPlan

**Files:**
- Create: `common/src/main/java/io/casehub/engine/plan/JoinType.java`
- Create: `common/src/main/java/io/casehub/engine/plan/DagNode.java`
- Create: `common/src/main/java/io/casehub/engine/plan/DagPlan.java`
- Test: `common/src/test/java/io/casehub/engine/plan/DagPlanTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: `DagPlan<T>` record, `DagNode<T>` record, `JoinType` enum — used by Task 2 (NodeState) and Task 3 (DagDriver)

- [ ] **Step 1: Write failing tests for DagPlan**

Create test class with validation tests, factory method tests, and query tests:

```java
package io.casehub.engine.plan;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
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
        DagPlan<String> plan = DagPlan.sequence(List.of(
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
        DagPlan<String> plan = DagPlan.parallel(
            List.of("task-a", "task-b", "task-c"));
        assertThat(plan.nodes()).hasSize(3);
        assertThat(plan.entryNodeIds()).hasSize(3);
        assertThat(plan.exitNodeIds()).hasSize(3);
    }

    @Test
    void diamond_correctEntryAndExit() {
        DagPlan<String> plan = new DagPlan<>(Map.of(
            "a", new DagNode<>("a", "root", Set.of(), JoinType.ALL_OF),
            "b", new DagNode<>("b", "left", Set.of("a"), JoinType.ALL_OF),
            "c", new DagNode<>("c", "right", Set.of("a"), JoinType.ALL_OF),
            "d", new DagNode<>("d", "join", Set.of("b", "c"), JoinType.ALL_OF)));
        assertThat(plan.entryNodeIds()).containsExactly("a");
        assertThat(plan.exitNodeIds()).containsExactly("d");
    }

    @Test
    void emptyNodes_throwsIAE() {
        assertThatThrownBy(() -> new DagPlan<>(Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cycleDetected_throwsIAE() {
        assertThatThrownBy(() -> new DagPlan<>(Map.of(
            "a", new DagNode<>("a", "x", Set.of("b"), JoinType.ALL_OF),
            "b", new DagNode<>("b", "y", Set.of("a"), JoinType.ALL_OF))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cycle");
    }

    @Test
    void selfReference_throwsIAE() {
        assertThatThrownBy(() -> new DagPlan<>(Map.of(
            "a", new DagNode<>("a", "x", Set.of("a"), JoinType.ALL_OF))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidReference_throwsIAE() {
        assertThatThrownBy(() -> new DagPlan<>(Map.of(
            "a", new DagNode<>("a", "x", Set.of("nonexistent"), JoinType.ALL_OF))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void topologicalSort_respectsDependencies() {
        DagPlan<String> plan = new DagPlan<>(Map.of(
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl common -Dtest=DagPlanTest -DfailIfNoTests=false -Dcheckstyle.skip=true`
Expected: compilation failure — classes don't exist yet

- [ ] **Step 3: Implement JoinType**

```java
package io.casehub.engine.plan;

public enum JoinType {
    ALL_OF,
    ANY_OF
}
```

- [ ] **Step 4: Implement DagNode**

```java
package io.casehub.engine.plan;

import java.util.Objects;
import java.util.Set;

public record DagNode<T>(String id, T task, Set<String> dependsOn, JoinType joinType) {
    public DagNode {
        Objects.requireNonNull(id, "id required");
        Objects.requireNonNull(task, "task required");
        dependsOn = dependsOn != null ? Set.copyOf(dependsOn) : Set.of();
        if (joinType == null) joinType = JoinType.ALL_OF;
    }
}
```

- [ ] **Step 5: Implement DagPlan**

```java
package io.casehub.engine.plan;

import java.util.*;
import java.util.stream.Collectors;

public record DagPlan<T>(Map<String, DagNode<T>> nodes) {

    public DagPlan {
        Objects.requireNonNull(nodes, "nodes required");
        if (nodes.isEmpty())
            throw new IllegalArgumentException("nodes must not be empty");
        nodes = Map.copyOf(nodes);
        validateReferences(nodes);
        validateNoCycles(nodes);
        if (computeEntryNodes(nodes).isEmpty())
            throw new IllegalArgumentException("plan must have at least one entry node");
    }

    public Set<String> entryNodeIds() {
        return computeEntryNodes(nodes);
    }

    public Set<String> exitNodeIds() {
        Set<String> referenced = nodes.values().stream()
            .flatMap(n -> n.dependsOn().stream())
            .collect(Collectors.toSet());
        return nodes.keySet().stream()
            .filter(id -> !referenced.contains(id))
            .collect(Collectors.toUnmodifiableSet());
    }

    public List<DagNode<T>> topologicalSort() {
        Map<String, Integer> inDegree = new HashMap<>();
        nodes.keySet().forEach(id -> inDegree.put(id, 0));
        Map<String, List<String>> successors = new HashMap<>();
        for (var n : nodes.values()) {
            for (String dep : n.dependsOn()) {
                successors.computeIfAbsent(dep, k -> new ArrayList<>()).add(n.id());
                inDegree.merge(n.id(), 1, Integer::sum);
            }
        }
        Queue<String> queue = new ArrayDeque<>();
        inDegree.forEach((id, deg) -> { if (deg == 0) queue.add(id); });
        List<DagNode<T>> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            sorted.add(nodes.get(current));
            for (String succ : successors.getOrDefault(current, List.of())) {
                if (inDegree.merge(succ, -1, Integer::sum) == 0) queue.add(succ);
            }
        }
        return Collections.unmodifiableList(sorted);
    }

    // --- Factories ---

    public static <T> DagPlan<T> singleton(String id, T task) {
        return new DagPlan<>(Map.of(id, new DagNode<>(id, task, Set.of(), JoinType.ALL_OF)));
    }

    public static <T> DagPlan<T> sequence(List<DagNode<T>> nodes) {
        if (nodes.isEmpty()) throw new IllegalArgumentException("nodes must not be empty");
        Map<String, DagNode<T>> map = new LinkedHashMap<>();
        for (var node : nodes) map.put(node.id(), node);
        return new DagPlan<>(map);
    }

    public static <T> DagPlan<T> parallel(List<T> tasks) {
        if (tasks.isEmpty()) throw new IllegalArgumentException("tasks must not be empty");
        Map<String, DagNode<T>> map = new LinkedHashMap<>();
        for (int i = 0; i < tasks.size(); i++) {
            String id = "node-" + i;
            map.put(id, new DagNode<>(id, tasks.get(i), Set.of(), JoinType.ALL_OF));
        }
        return new DagPlan<>(map);
    }

    // --- Validation ---

    private static <T> void validateReferences(Map<String, DagNode<T>> nodes) {
        for (var node : nodes.values()) {
            for (String dep : node.dependsOn()) {
                if (!nodes.containsKey(dep))
                    throw new IllegalArgumentException(
                        "Node '" + node.id() + "' depends on non-existent node '" + dep + "'");
            }
        }
    }

    private static <T> void validateNoCycles(Map<String, DagNode<T>> nodes) {
        Map<String, Integer> inDegree = new HashMap<>();
        nodes.keySet().forEach(id -> inDegree.put(id, 0));
        Map<String, List<String>> successors = new HashMap<>();
        for (var n : nodes.values()) {
            for (String dep : n.dependsOn()) {
                successors.computeIfAbsent(dep, k -> new ArrayList<>()).add(n.id());
                inDegree.merge(n.id(), 1, Integer::sum);
            }
        }
        Queue<String> queue = new ArrayDeque<>();
        inDegree.forEach((id, deg) -> { if (deg == 0) queue.add(id); });
        int visited = 0;
        while (!queue.isEmpty()) {
            visited++;
            String current = queue.poll();
            for (String succ : successors.getOrDefault(current, List.of())) {
                if (inDegree.merge(succ, -1, Integer::sum) == 0) queue.add(succ);
            }
        }
        if (visited != nodes.size())
            throw new IllegalArgumentException("Plan contains a cycle");
    }

    private static <T> Set<String> computeEntryNodes(Map<String, DagNode<T>> nodes) {
        return nodes.values().stream()
            .filter(n -> n.dependsOn().isEmpty())
            .map(DagNode::id)
            .collect(Collectors.toUnmodifiableSet());
    }
}
```

- [ ] **Step 6: Run tests**

Run: `mvn test -pl common -Dtest=DagPlanTest -DfailIfNoTests=false -Dcheckstyle.skip=true`
Expected: all 10 PASS

- [ ] **Step 7: Commit**

```
feat(#695): DagPlan, DagNode, JoinType — immutable DAG plan model

Validated at construction: no cycles, no dangling references, at least
one entry node. Factories: singleton, sequence, parallel.

Refs #695
```

---

### Task 2: Execution Types — NodeState, DispatchMode, DagResult, DagEventListener

**Files:**
- Create: `common/src/main/java/io/casehub/engine/plan/NodeState.java`
- Create: `common/src/main/java/io/casehub/engine/plan/DispatchMode.java`
- Create: `common/src/main/java/io/casehub/engine/plan/DagResult.java`
- Create: `common/src/main/java/io/casehub/engine/plan/DagEventListener.java`
- Test: `common/src/test/java/io/casehub/engine/plan/NodeStateTest.java`
- Test: `common/src/test/java/io/casehub/engine/plan/DagResultTest.java`

**Interfaces:**
- Consumes: `io.casehub.api.model.TaskStatus` from engine-api
- Produces: `NodeState<R>` sealed interface, `DispatchMode` enum, `DagResult<R>` record, `DagEventListener<T,R>` interface — used by Task 3

- [ ] **Step 1: Write failing tests**

NodeState tests:

```java
package io.casehub.engine.plan;

import static org.assertj.core.api.Assertions.*;
import io.casehub.api.model.TaskStatus;
import org.junit.jupiter.api.Test;

class NodeStateTest {

    @Test
    void pending_isNotTerminal() {
        assertThat(new NodeState.Pending<>().isTerminal()).isFalse();
    }

    @Test
    void dispatched_isNotTerminal() {
        assertThat(new NodeState.Dispatched<>().isTerminal()).isFalse();
    }

    @Test
    void completed_isTerminal() {
        assertThat(new NodeState.Completed<>("result").isTerminal()).isTrue();
    }

    @Test
    void failed_isTerminal() {
        assertThat(new NodeState.Failed<>("err", null).isTerminal()).isTrue();
    }

    @Test
    void skipped_isTerminal() {
        assertThat(new NodeState.Skipped<>("dep failed").isTerminal()).isTrue();
    }

    @Test
    void cancelled_isTerminal() {
        assertThat(new NodeState.Cancelled<>().isTerminal()).isTrue();
    }

    @Test
    void toTaskStatus_allMappings() {
        assertThat(new NodeState.Pending<>().toTaskStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(new NodeState.Dispatched<>().toTaskStatus()).isEqualTo(TaskStatus.RUNNING);
        assertThat(new NodeState.Completed<>("r").toTaskStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(new NodeState.Failed<>("e", null).toTaskStatus()).isEqualTo(TaskStatus.FAULTED);
        assertThat(new NodeState.Skipped<>("s").toTaskStatus()).isEqualTo(TaskStatus.OBSOLETE);
        assertThat(new NodeState.Cancelled<>().toTaskStatus()).isEqualTo(TaskStatus.CANCELLED);
    }
}
```

DagResult tests:

```java
package io.casehub.engine.plan;

import static org.assertj.core.api.Assertions.*;
import io.casehub.api.model.TaskStatus;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DagResultTest {

    @Test
    void completedResults_keyedByNodeId() {
        var result = new DagResult<>(
            Map.of("a", new NodeState.Completed<>("r1"),
                   "b", new NodeState.Failed<>("err", null)),
            Map.of("a", "r1"),
            false,
            Duration.ofMillis(100));
        assertThat(result.completedResults()).containsEntry("a", "r1");
        assertThat(result.completedResults()).doesNotContainKey("b");
    }

    @Test
    void taskStatuses_projectsCorrectly() {
        var result = new DagResult<>(
            Map.of("a", new NodeState.Completed<>("r"),
                   "b", new NodeState.Skipped<>("dep")),
            Map.of("a", "r"),
            false,
            Duration.ofMillis(50));
        assertThat(result.taskStatuses())
            .containsEntry("a", TaskStatus.COMPLETED)
            .containsEntry("b", TaskStatus.OBSOLETE);
    }

    @Test
    void allSucceeded_true_whenAllCompleted() {
        var result = new DagResult<>(
            Map.of("a", new NodeState.Completed<>("r")),
            Map.of("a", "r"),
            true,
            Duration.ZERO);
        assertThat(result.allSucceeded()).isTrue();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl common -Dtest="NodeStateTest+DagResultTest" -DfailIfNoTests=false -Dcheckstyle.skip=true`
Expected: compilation failure

- [ ] **Step 3: Implement all four types**

NodeState:
```java
package io.casehub.engine.plan;

import io.casehub.api.model.TaskStatus;

public sealed interface NodeState<R> {
    record Pending<R>() implements NodeState<R> {}
    record Dispatched<R>() implements NodeState<R> {}
    record Completed<R>(R result) implements NodeState<R> {}
    record Failed<R>(String reason, Throwable cause) implements NodeState<R> {}
    record Skipped<R>(String reason) implements NodeState<R> {}
    record Cancelled<R>() implements NodeState<R> {}

    default boolean isTerminal() {
        return this instanceof Completed || this instanceof Failed
            || this instanceof Skipped || this instanceof Cancelled;
    }

    default TaskStatus toTaskStatus() {
        return switch (this) {
            case Pending<?> p -> TaskStatus.PENDING;
            case Dispatched<?> d -> TaskStatus.RUNNING;
            case Completed<?> c -> TaskStatus.COMPLETED;
            case Failed<?> f -> TaskStatus.FAULTED;
            case Skipped<?> s -> TaskStatus.OBSOLETE;
            case Cancelled<?> x -> TaskStatus.CANCELLED;
        };
    }
}
```

DispatchMode:
```java
package io.casehub.engine.plan;

public enum DispatchMode {
    STREAMING,
    BARRIER
}
```

DagResult:
```java
package io.casehub.engine.plan;

import io.casehub.api.model.TaskStatus;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

public record DagResult<R>(
    Map<String, NodeState<R>> nodeStates,
    Map<String, R> completedResults,
    boolean allSucceeded,
    Duration elapsed
) {
    public Map<String, TaskStatus> taskStatuses() {
        return nodeStates.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().toTaskStatus()));
    }
}
```

DagEventListener:
```java
package io.casehub.engine.plan;

public interface DagEventListener<T, R> {
    default void onNodeDispatched(String nodeId, T task) {}
    default void onNodeCompleted(String nodeId, T task, R result) {}
    default void onNodeFailed(String nodeId, T task, String reason, Throwable cause) {}
    default void onNodeSkipped(String nodeId, T task, String reason) {}
    default void onNodeCancelled(String nodeId, T task) {}
    default void onExecutionComplete(DagResult<R> result) {}
}
```

- [ ] **Step 4: Run tests**

Run: `mvn test -pl common -Dtest="NodeStateTest+DagResultTest" -DfailIfNoTests=false -Dcheckstyle.skip=true`
Expected: all PASS

- [ ] **Step 5: Commit**

```
feat(#695): NodeState, DispatchMode, DagResult, DagEventListener

NodeState sealed interface maps to TaskStatus. DagResult carries
per-node states with TaskStatus projection. DagEventListener provides
observation callbacks for dispatch/completion/failure events.

Refs #695
```

---

### Task 3: DagDriver — BARRIER mode with full functional tests

**Files:**
- Create: `common/src/main/java/io/casehub/engine/plan/DagDriver.java`
- Test: `common/src/test/java/io/casehub/engine/plan/DagDriverBarrierTest.java`

**Interfaces:**
- Consumes: `DagPlan<T>`, `DagNode<T>`, `JoinType`, `NodeState<R>`, `DispatchMode`, `DagResult<R>`, `DagEventListener<T,R>` from Tasks 1-2
- Produces: `DagDriver<T, R>` — the execution driver, complete for BARRIER mode

- [ ] **Step 1: Write failing tests**

```java
package io.casehub.engine.plan;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DagDriverBarrierTest {

    @Test
    void singleNode_completesSuccessfully() {
        var plan = DagPlan.<String>singleton("a", "task-a");
        var driver = new DagDriver<>(plan, DispatchMode.BARRIER);
        DagResult<String> result = driver.execute(task -> "done:" + task);

        assertThat(result.allSucceeded()).isTrue();
        assertThat(result.completedResults()).containsEntry("a", "done:task-a");
    }

    @Test
    void linearChain_executesInOrder() {
        var order = new CopyOnWriteArrayList<String>();
        var plan = DagPlan.sequence(List.of(
            new DagNode<>("a", "first", Set.of(), JoinType.ALL_OF),
            new DagNode<>("b", "second", Set.of("a"), JoinType.ALL_OF),
            new DagNode<>("c", "third", Set.of("b"), JoinType.ALL_OF)));
        var driver = new DagDriver<>(plan, DispatchMode.BARRIER);
        DagResult<String> result = driver.execute(task -> {
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
        var driver = new DagDriver<>(plan, DispatchMode.BARRIER);
        DagResult<String> result = driver.execute(task -> {
            dispatched.add(task);
            return task;
        });

        assertThat(result.allSucceeded()).isTrue();
        assertThat(dispatched).containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    void diamond_correctExecutionOrder() {
        var order = Collections.synchronizedList(new ArrayList<String>());
        var plan = new DagPlan<>(Map.of(
            "a", new DagNode<>("a", "root", Set.of(), JoinType.ALL_OF),
            "b", new DagNode<>("b", "left", Set.of("a"), JoinType.ALL_OF),
            "c", new DagNode<>("c", "right", Set.of("a"), JoinType.ALL_OF),
            "d", new DagNode<>("d", "join", Set.of("b", "c"), JoinType.ALL_OF)));
        var driver = new DagDriver<>(plan, DispatchMode.BARRIER);
        DagResult<String> result = driver.execute(task -> {
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
        var plan = new DagPlan<>(Map.of(
            "root", new DagNode<>("root", "r", Set.of(), JoinType.ALL_OF),
            "c1", new DagNode<>("c1", "1", Set.of("root"), JoinType.ALL_OF),
            "c2", new DagNode<>("c2", "2", Set.of("root"), JoinType.ALL_OF),
            "c3", new DagNode<>("c3", "3", Set.of("root"), JoinType.ALL_OF),
            "c4", new DagNode<>("c4", "4", Set.of("root"), JoinType.ALL_OF),
            "c5", new DagNode<>("c5", "5", Set.of("root"), JoinType.ALL_OF)));
        var driver = new DagDriver<>(plan, DispatchMode.BARRIER);
        DagResult<String> result = driver.execute(t -> t);

        assertThat(result.allSucceeded()).isTrue();
        assertThat(result.completedResults()).hasSize(6);
    }

    @Test
    void wideFanIn_joinsAllPredecessors() {
        var plan = new DagPlan<>(Map.of(
            "a", new DagNode<>("a", "1", Set.of(), JoinType.ALL_OF),
            "b", new DagNode<>("b", "2", Set.of(), JoinType.ALL_OF),
            "c", new DagNode<>("c", "3", Set.of(), JoinType.ALL_OF),
            "join", new DagNode<>("join", "j", Set.of("a", "b", "c"), JoinType.ALL_OF)));
        var driver = new DagDriver<>(plan, DispatchMode.BARRIER);
        DagResult<String> result = driver.execute(t -> t);

        assertThat(result.allSucceeded()).isTrue();
        assertThat(result.completedResults()).hasSize(4);
    }

    @Test
    void allOfPartialFail_dependentSkipped_independentContinues() {
        var plan = new DagPlan<>(Map.of(
            "a", new DagNode<>("a", "root", Set.of(), JoinType.ALL_OF),
            "b", new DagNode<>("b", "fail", Set.of("a"), JoinType.ALL_OF),
            "c", new DagNode<>("c", "ok", Set.of("a"), JoinType.ALL_OF),
            "d", new DagNode<>("d", "join", Set.of("b", "c"), JoinType.ALL_OF)));
        var driver = new DagDriver<>(plan, DispatchMode.BARRIER);
        DagResult<String> result = driver.execute(task -> {
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
        var plan = DagPlan.sequence(List.of(
            new DagNode<>("a", "fail", Set.of(), JoinType.ALL_OF),
            new DagNode<>("b", "b", Set.of("a"), JoinType.ALL_OF),
            new DagNode<>("c", "c", Set.of("b"), JoinType.ALL_OF)));
        var driver = new DagDriver<>(plan, DispatchMode.BARRIER);
        DagResult<String> result = driver.execute(task -> {
            if ("fail".equals(task)) throw new RuntimeException("boom");
            return task;
        });

        assertThat(result.nodeStates().get("a")).isInstanceOf(NodeState.Failed.class);
        assertThat(result.nodeStates().get("b")).isInstanceOf(NodeState.Skipped.class);
        assertThat(result.nodeStates().get("c")).isInstanceOf(NodeState.Skipped.class);
    }

    @Test
    void independentPaths_failureIsolated() {
        var plan = new DagPlan<>(Map.of(
            "a", new DagNode<>("a", "fail", Set.of(), JoinType.ALL_OF),
            "b", new DagNode<>("b", "b", Set.of("a"), JoinType.ALL_OF),
            "c", new DagNode<>("c", "ok", Set.of(), JoinType.ALL_OF),
            "d", new DagNode<>("d", "d", Set.of("c"), JoinType.ALL_OF)));
        var driver = new DagDriver<>(plan, DispatchMode.BARRIER);
        DagResult<String> result = driver.execute(task -> {
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
        var plan = new DagPlan<>(Map.of(
            "a", new DagNode<>("a", "slow", Set.of(), JoinType.ALL_OF),
            "b", new DagNode<>("b", "fast", Set.of(), JoinType.ALL_OF),
            "c", new DagNode<>("c", "join", Set.of("a", "b"), JoinType.ANY_OF)));
        var driver = new DagDriver<>(plan, DispatchMode.BARRIER);
        DagResult<String> result = driver.execute(t -> t);

        assertThat(result.nodeStates().get("c")).isInstanceOf(NodeState.Completed.class);
    }

    @Test
    void anyOf_allPredecessorsFail_skipped() {
        var plan = new DagPlan<>(Map.of(
            "a", new DagNode<>("a", "f1", Set.of(), JoinType.ALL_OF),
            "b", new DagNode<>("b", "f2", Set.of(), JoinType.ALL_OF),
            "c", new DagNode<>("c", "join", Set.of("a", "b"), JoinType.ANY_OF)));
        var driver = new DagDriver<>(plan, DispatchMode.BARRIER);
        DagResult<String> result = driver.execute(task -> {
            throw new RuntimeException("fail");
        });

        assertThat(result.nodeStates().get("c")).isInstanceOf(NodeState.Skipped.class);
    }

    @Test
    void singleUse_secondExecuteThrows() {
        var plan = DagPlan.<String>singleton("a", "task");
        var driver = new DagDriver<>(plan, DispatchMode.BARRIER);
        driver.execute(t -> t);

        assertThatThrownBy(() -> driver.execute(t -> t))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void listenerNotifications_fireCorrectly() {
        var dispatched = new CopyOnWriteArrayList<String>();
        var completed = new CopyOnWriteArrayList<String>();
        var listener = new DagEventListener<String, String>() {
            @Override public void onNodeDispatched(String nodeId, String task) {
                dispatched.add(nodeId);
            }
            @Override public void onNodeCompleted(String nodeId, String task, String result) {
                completed.add(nodeId);
            }
        };
        var plan = DagPlan.sequence(List.of(
            new DagNode<>("a", "first", Set.of(), JoinType.ALL_OF),
            new DagNode<>("b", "second", Set.of("a"), JoinType.ALL_OF)));
        var driver = new DagDriver<>(plan, DispatchMode.BARRIER, List.of(listener));
        driver.execute(t -> t);

        assertThat(dispatched).containsExactly("a", "b");
        assertThat(completed).containsExactly("a", "b");
    }

    @Test
    void listenerException_doesNotCrashExecution() {
        var listener = new DagEventListener<String, String>() {
            @Override public void onNodeDispatched(String nodeId, String task) {
                throw new RuntimeException("listener crash");
            }
        };
        var plan = DagPlan.<String>singleton("a", "task");
        var driver = new DagDriver<>(plan, DispatchMode.BARRIER, List.of(listener));
        DagResult<String> result = driver.execute(t -> t);

        assertThat(result.allSucceeded()).isTrue();
    }

    @Test
    void customExecutor_usedForDispatch() {
        var pool = Executors.newFixedThreadPool(2);
        try {
            var threadNames = new CopyOnWriteArrayList<String>();
            var plan = DagPlan.parallel(List.of("a", "b"));
            var driver = new DagDriver<>(plan, DispatchMode.BARRIER);
            driver.execute(task -> {
                threadNames.add(Thread.currentThread().getName());
                return task;
            }, pool);

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
        var driver = new DagDriver<>(plan, DispatchMode.BARRIER);
        DagResult<Integer> result = driver.execute(t -> t * 2);

        assertThat(result.allSucceeded()).isTrue();
        assertThat(result.completedResults()).hasSize(101);
    }
}
```

- [ ] **Step 2: Run to verify failures**

Run: `mvn test -pl common -Dtest=DagDriverBarrierTest -DfailIfNoTests=false -Dcheckstyle.skip=true`
Expected: compilation failure — DagDriver doesn't exist

- [ ] **Step 3: Implement DagDriver**

```java
package io.casehub.engine.plan;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class DagDriver<T, R> {

    private static final System.Logger LOG = System.getLogger(DagDriver.class.getName());

    private final DagPlan<T> plan;
    private final DispatchMode mode;
    private final List<DagEventListener<T, R>> listeners;
    private final ConcurrentHashMap<String, NodeState<R>> states = new ConcurrentHashMap<>();
    private final AtomicBoolean executed = new AtomicBoolean(false);
    private volatile boolean cancelled = false;

    public DagDriver(DagPlan<T> plan) {
        this(plan, DispatchMode.STREAMING, List.of());
    }

    public DagDriver(DagPlan<T> plan, DispatchMode mode) {
        this(plan, mode, List.of());
    }

    public DagDriver(DagPlan<T> plan, DispatchMode mode, List<DagEventListener<T, R>> listeners) {
        this.plan = Objects.requireNonNull(plan);
        this.mode = Objects.requireNonNull(mode);
        this.listeners = List.copyOf(listeners);
    }

    public DagResult<R> execute(java.util.function.Function<T, R> taskExecutor) {
        return execute(taskExecutor, Executors.newVirtualThreadPerTaskExecutor());
    }

    public DagResult<R> execute(java.util.function.Function<T, R> taskExecutor, Executor threadPool) {
        if (!executed.compareAndSet(false, true)) {
            throw new IllegalStateException("DagDriver is single-use — construct a new instance for each execution");
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
        cancelled = true;
        for (var entry : states.entrySet()) {
            if (entry.getValue() instanceof NodeState.Pending) {
                states.replace(entry.getKey(), entry.getValue(), new NodeState.Cancelled<>());
                fireNodeCancelled(entry.getKey(), plan.nodes().get(entry.getKey()).task());
            }
        }
    }

    // --- BARRIER mode ---

    private void executeBarrier(java.util.function.Function<T, R> taskExecutor, Executor threadPool) {
        while (!cancelled) {
            Set<String> ready = computeReadySet();
            if (ready.isEmpty()) break;

            List<CompletableFuture<Void>> wave = new ArrayList<>();
            for (String nodeId : ready) {
                DagNode<T> node = plan.nodes().get(nodeId);
                states.put(nodeId, new NodeState.Dispatched<>());
                fireNodeDispatched(nodeId, node.task());
                wave.add(CompletableFuture.runAsync(() -> executeNode(nodeId, node, taskExecutor), threadPool));
            }
            CompletableFuture.allOf(wave.toArray(new CompletableFuture[0])).join();

            propagateFailures();
        }
    }

    // --- STREAMING mode ---

    private void executeStreaming(java.util.function.Function<T, R> taskExecutor, Executor threadPool) {
        CountDownLatch latch = new CountDownLatch(plan.nodes().size());

        Set<String> initialReady = computeReadySet();
        for (String nodeId : initialReady) {
            dispatchStreaming(nodeId, taskExecutor, threadPool, latch);
        }

        propagateFailures();
        markUnreachablePending(latch);

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void dispatchStreaming(String nodeId, java.util.function.Function<T, R> taskExecutor,
                                   Executor threadPool, CountDownLatch latch) {
        DagNode<T> node = plan.nodes().get(nodeId);
        if (!states.replace(nodeId, new NodeState.Pending<>(), new NodeState.Dispatched<>())) {
            return; // another thread already dispatched this node
        }
        fireNodeDispatched(nodeId, node.task());

        CompletableFuture.runAsync(() -> executeNode(nodeId, node, taskExecutor), threadPool)
            .whenComplete((v, ex) -> {
                latch.countDown();
                propagateFailures();

                if (!cancelled) {
                    Set<String> newlyReady = computeReadySet();
                    for (String readyId : newlyReady) {
                        dispatchStreaming(readyId, taskExecutor, threadPool, latch);
                    }
                }

                markUnreachablePending(latch);
            });
    }

    private void markUnreachablePending(CountDownLatch latch) {
        for (var entry : states.entrySet()) {
            if (entry.getValue() instanceof NodeState.Pending && isUnreachable(entry.getKey())) {
                if (states.replace(entry.getKey(), entry.getValue(), new NodeState.Skipped<>("predecessor failed"))) {
                    fireNodeSkipped(entry.getKey(), plan.nodes().get(entry.getKey()).task(), "predecessor failed");
                    latch.countDown();
                }
            }
        }
    }

    private boolean isUnreachable(String nodeId) {
        DagNode<T> node = plan.nodes().get(nodeId);
        return switch (node.joinType()) {
            case ALL_OF -> node.dependsOn().stream().anyMatch(dep -> {
                var s = states.get(dep);
                return s instanceof NodeState.Failed || s instanceof NodeState.Skipped || s instanceof NodeState.Cancelled;
            });
            case ANY_OF -> node.dependsOn().stream().allMatch(dep -> {
                var s = states.get(dep);
                return s instanceof NodeState.Failed || s instanceof NodeState.Skipped || s instanceof NodeState.Cancelled;
            });
        };
    }

    // --- Shared ---

    private void executeNode(String nodeId, DagNode<T> node, java.util.function.Function<T, R> taskExecutor) {
        try {
            R result = taskExecutor.apply(node.task());
            states.put(nodeId, new NodeState.Completed<>(result));
            fireNodeCompleted(nodeId, node.task(), result);
        } catch (Exception e) {
            states.put(nodeId, new NodeState.Failed<>(e.getMessage(), e));
            fireNodeFailed(nodeId, node.task(), e.getMessage(), e);
        }
    }

    private Set<String> computeReadySet() {
        Set<String> ready = new LinkedHashSet<>();
        for (var entry : plan.nodes().entrySet()) {
            String nodeId = entry.getKey();
            DagNode<T> node = entry.getValue();
            if (!(states.get(nodeId) instanceof NodeState.Pending)) continue;
            if (cancelled) continue;

            boolean satisfied = switch (node.joinType()) {
                case ALL_OF -> node.dependsOn().stream()
                    .allMatch(dep -> states.get(dep) instanceof NodeState.Completed);
                case ANY_OF -> node.dependsOn().isEmpty() ||
                    node.dependsOn().stream()
                        .anyMatch(dep -> states.get(dep) instanceof NodeState.Completed);
            };
            if (satisfied) ready.add(nodeId);
        }
        return ready;
    }

    private void propagateFailures() {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (var entry : plan.nodes().entrySet()) {
                String nodeId = entry.getKey();
                if (!(states.get(nodeId) instanceof NodeState.Pending)) continue;
                if (isUnreachable(nodeId)) {
                    states.put(nodeId, new NodeState.Skipped<>("predecessor failed"));
                    fireNodeSkipped(nodeId, entry.getValue().task(), "predecessor failed");
                    changed = true;
                }
            }
        }
    }

    private DagResult<R> buildResult(Instant start) {
        Map<String, R> completed = new LinkedHashMap<>();
        boolean allSucceeded = true;
        for (var entry : states.entrySet()) {
            if (entry.getValue() instanceof NodeState.Completed<R> c) {
                completed.put(entry.getKey(), c.result());
            } else if (!(entry.getValue() instanceof NodeState.Completed)) {
                if (entry.getValue() instanceof NodeState.Failed
                    || entry.getValue() instanceof NodeState.Skipped
                    || entry.getValue() instanceof NodeState.Cancelled) {
                    allSucceeded = false;
                }
            }
        }
        Duration elapsed = Duration.between(start, Instant.now());
        var result = new DagResult<>(Map.copyOf(states), Map.copyOf(completed), allSucceeded, elapsed);
        fireExecutionComplete(result);
        return result;
    }

    // --- Listener notification ---

    private void fireNodeDispatched(String nodeId, T task) {
        for (var l : listeners) { try { l.onNodeDispatched(nodeId, task); } catch (Exception e) { LOG.log(System.Logger.Level.WARNING, "Listener threw on dispatch", e); } }
    }
    private void fireNodeCompleted(String nodeId, T task, R result) {
        for (var l : listeners) { try { l.onNodeCompleted(nodeId, task, result); } catch (Exception e) { LOG.log(System.Logger.Level.WARNING, "Listener threw on complete", e); } }
    }
    private void fireNodeFailed(String nodeId, T task, String reason, Throwable cause) {
        for (var l : listeners) { try { l.onNodeFailed(nodeId, task, reason, cause); } catch (Exception e) { LOG.log(System.Logger.Level.WARNING, "Listener threw on fail", e); } }
    }
    private void fireNodeSkipped(String nodeId, T task, String reason) {
        for (var l : listeners) { try { l.onNodeSkipped(nodeId, task, reason); } catch (Exception e) { LOG.log(System.Logger.Level.WARNING, "Listener threw on skip", e); } }
    }
    private void fireNodeCancelled(String nodeId, T task) {
        for (var l : listeners) { try { l.onNodeCancelled(nodeId, task); } catch (Exception e) { LOG.log(System.Logger.Level.WARNING, "Listener threw on cancel", e); } }
    }
    private void fireExecutionComplete(DagResult<R> result) {
        for (var l : listeners) { try { l.onExecutionComplete(result); } catch (Exception e) { LOG.log(System.Logger.Level.WARNING, "Listener threw on execution complete", e); } }
    }
}
```

- [ ] **Step 4: Run BARRIER tests**

Run: `mvn test -pl common -Dtest=DagDriverBarrierTest -DfailIfNoTests=false -Dcheckstyle.skip=true`
Expected: all PASS

- [ ] **Step 5: Commit**

```
feat(#695): DagDriver with BARRIER mode, failure propagation, listeners

BARRIER dispatches in waves: all ready → await all → next wave.
Continue-by-default failure: failed node dependents are SKIPPED,
independent paths unaffected. Single-use enforcement. Listener
exceptions isolated from execution.

Refs #695
```

---

### Task 4: DagDriver — STREAMING mode + concurrency tests

**Files:**
- Modify: `common/src/main/java/io/casehub/engine/plan/DagDriver.java` (STREAMING already implemented in Task 3 skeleton — this task adds targeted tests)
- Test: `common/src/test/java/io/casehub/engine/plan/DagDriverStreamingTest.java`

**Interfaces:**
- Consumes: `DagDriver<T, R>` from Task 3
- Produces: verified STREAMING mode behaviour

- [ ] **Step 1: Write streaming-specific tests**

```java
package io.casehub.engine.plan;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class DagDriverStreamingTest {

    @Test
    void asymmetricDepth_streamingDispatchesEarly() throws Exception {
        // A→B→D, A→C (C is slow). D should dispatch before C finishes.
        var dDispatched = new CountDownLatch(1);
        var cStarted = new CountDownLatch(1);

        var plan = new DagPlan<>(Map.of(
            "a", new DagNode<>("a", "root", Set.of(), JoinType.ALL_OF),
            "b", new DagNode<>("b", "fast", Set.of("a"), JoinType.ALL_OF),
            "c", new DagNode<>("c", "slow", Set.of("a"), JoinType.ALL_OF),
            "d", new DagNode<>("d", "after-fast", Set.of("b"), JoinType.ALL_OF)));

        var driver = new DagDriver<>(plan, DispatchMode.STREAMING, List.of(
            new DagEventListener<String, String>() {
                @Override public void onNodeDispatched(String nodeId, String task) {
                    if ("d".equals(nodeId)) dDispatched.countDown();
                }
            }));

        CompletableFuture.runAsync(() -> driver.execute(task -> {
            if ("slow".equals(task)) {
                cStarted.countDown();
                try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return task;
        }));

        // D should dispatch while C is still running
        cStarted.await(5, TimeUnit.SECONDS);
        boolean dDispatchedBeforeCFinished = dDispatched.await(1, TimeUnit.SECONDS);
        assertThat(dDispatchedBeforeCFinished).isTrue();
    }

    @Test
    void anyOfSiblingContinuation_remainingPredecessorsComplete() {
        var completed = new CopyOnWriteArrayList<String>();
        var plan = new DagPlan<>(Map.of(
            "a", new DagNode<>("a", "fast", Set.of(), JoinType.ALL_OF),
            "b", new DagNode<>("b", "slow", Set.of(), JoinType.ALL_OF),
            "join", new DagNode<>("join", "join", Set.of("a", "b"), JoinType.ANY_OF)));
        var driver = new DagDriver<>(plan, DispatchMode.STREAMING);
        DagResult<String> result = driver.execute(task -> {
            if ("slow".equals(task)) {
                try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            completed.add(task);
            return task;
        });

        assertThat(result.nodeStates().get("join")).isInstanceOf(NodeState.Completed.class);
        // Both a and b should complete (b is not cancelled)
        assertThat(result.nodeStates().get("a")).isInstanceOf(NodeState.Completed.class);
        assertThat(result.nodeStates().get("b")).isInstanceOf(NodeState.Completed.class);
    }

    @Test
    void cancellation_pendingBecomesCancelled() throws Exception {
        var rootStarted = new CountDownLatch(1);
        var plan = new DagPlan<>(Map.of(
            "a", new DagNode<>("a", "slow-root", Set.of(), JoinType.ALL_OF),
            "b", new DagNode<>("b", "child", Set.of("a"), JoinType.ALL_OF)));
        var driver = new DagDriver<>(plan, DispatchMode.STREAMING);

        CompletableFuture<DagResult<String>> future = CompletableFuture.supplyAsync(() ->
            driver.execute(task -> {
                if ("slow-root".equals(task)) {
                    rootStarted.countDown();
                    try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
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
        var driver = new DagDriver<>(plan, DispatchMode.STREAMING);
        DagResult<Integer> result = driver.execute(t -> {
            try { Thread.sleep(ThreadLocalRandom.current().nextInt(10, 50)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return t * 2;
        });

        assertThat(result.allSucceeded()).isTrue();
        assertThat(result.completedResults()).hasSize(11);
        assertThat(result.nodeStates().values()).allMatch(NodeState::isTerminal);
    }

    @Test
    void complexDag_mixedJoinTypes() {
        // a → b (ALL_OF), a → c (ALL_OF), b,c → d (ANY_OF), d → e (ALL_OF)
        var plan = new DagPlan<>(Map.of(
            "a", new DagNode<>("a", "root", Set.of(), JoinType.ALL_OF),
            "b", new DagNode<>("b", "left", Set.of("a"), JoinType.ALL_OF),
            "c", new DagNode<>("c", "right", Set.of("a"), JoinType.ALL_OF),
            "d", new DagNode<>("d", "any-join", Set.of("b", "c"), JoinType.ANY_OF),
            "e", new DagNode<>("e", "final", Set.of("d"), JoinType.ALL_OF)));
        var driver = new DagDriver<>(plan, DispatchMode.STREAMING);
        DagResult<String> result = driver.execute(t -> t);

        assertThat(result.allSucceeded()).isTrue();
        assertThat(result.completedResults()).hasSize(5);
    }
}
```

- [ ] **Step 2: Run streaming tests**

Run: `mvn test -pl common -Dtest=DagDriverStreamingTest -DfailIfNoTests=false -Dcheckstyle.skip=true`
Expected: all PASS (STREAMING is already implemented in Task 3)

If any fail, fix the STREAMING algorithm in DagDriver and re-run.

- [ ] **Step 3: Run ALL plan tests together**

Run: `mvn test -pl common -Dtest="DagPlanTest+NodeStateTest+DagResultTest+DagDriverBarrierTest+DagDriverStreamingTest" -DfailIfNoTests=false -Dcheckstyle.skip=true`
Expected: all PASS

- [ ] **Step 4: Commit**

```
test(#695): STREAMING mode and concurrency tests for DagDriver

Asymmetric depth dispatch, ANY_OF sibling continuation, cancellation
with CANCELLED state, race condition coverage, mixed join types.

Refs #695
```

---

## Self-Review

**Spec coverage:**
- ✅ DagPlan, DagNode, JoinType — Task 1
- ✅ NodeState with toTaskStatus() — Task 2
- ✅ DispatchMode — Task 2
- ✅ DagResult with taskStatuses() — Task 2
- ✅ DagEventListener — Task 2
- ✅ DagDriver with both modes — Tasks 3-4
- ✅ Single-use enforcement — Task 3 test
- ✅ Listener exception isolation — Task 3 test
- ✅ Cancellation with CANCELLED state — Task 4 test
- ✅ ANY_OF sibling continuation — Task 4 test
- ✅ Ready-set computation (ALL_OF, ANY_OF) — Task 3
- ✅ Failure propagation — Task 3
- ✅ All 29 spec tests covered across Tasks 1-4

**Placeholder scan:** None found.
**Type consistency:** All signatures match across tasks.
**Tooling scan:** No bash file ops on source files.
