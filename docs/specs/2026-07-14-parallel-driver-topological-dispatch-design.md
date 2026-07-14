# DAG-Aware Parallel Execution Driver

**Date:** 2026-07-14
**Issue:** casehubio/engine#695
**Parent:** casehubio/blocks#44, casehubio/engine#700

## Related Issues

| Issue | Relationship |
|-------|-------------|
| engine#700 | Parent architecture — orchestration model unification. Defines `TaskStatus`, `TaskDescriptor`, `ExecutorRef` in engine-api. This spec implements Phase 2's parallel driver component. |
| engine#694 | Predecessor — created `ExecutionPlan<T>` DAG types in blocks. Paired with this issue: "DAG-aware parallel execution is #695." |
| blocks#44 | Agentic planning architecture epic. |
| blocks#51 | Gate for promoting `ExecutionPlan<T>` to engine-api: `LeafTask implements TaskDescriptor`. |

## Summary

Add a DAG execution driver to the engine that dispatches independent tasks concurrently
and gates dependent tasks on predecessor completion. Standard topological scheduling —
no LLM-specific behaviour, no blocks dependency.

This driver is the **scheduling algorithm layer** — it performs topological dispatch using
pure `java.util.concurrent`. A blocks-side `ParallelDriver<T>` (extending
`AbstractExecutionDriver<T>`, implementing `ExecutionDriver<T>`) would wrap this algorithm
to integrate with routing, activation, aggregation, and termination strategies. That wrapper
is a separate concern tracked under blocks#44.

### Relationship to blocks' ExecutionPlan\<T\>

`DagPlan<T>` is structurally equivalent to blocks' `ExecutionPlan<T>` (#694). The duplication
is intentional and temporary:

- `ExecutionPlan<T>` is parameterized on `TaskNode.LeafTask<T>` — a blocks-specific type.
  Engine cannot depend on blocks (dependency flows the other direction).
- `DagPlan<T>` is generic over `T` — any task type, no blocks coupling.
- **Convergence path:** When blocks#51 (`LeafTask implements TaskDescriptor`) ships,
  `ExecutionPlan<T>` can be reparameterized on `TaskDescriptor` and promoted to engine-api.
  At that point, `DagPlan<T>` merges into the promoted type. The algorithm and driver code
  are parameterized on the plan interface — the merge is mechanical.

## Motivation

When a plan decomposes into tasks with explicit dependencies, independent tasks should
run concurrently. Sequential execution leaves latency on the table. This is the same
pattern as LLMCompiler's Task Fetching Unit, Make's parallel build, or Gradle's task
graph — applied to CaseHub's worker execution.

## Location

Engine repo only. No cross-repo work. Blocks depends on engine-api (not the reverse),
so engine defines the DAG types and driver. If blocks needs them later, they're available
via engine-api.

**Package:** `io.casehub.engine.plan` in `casehub-engine-common` (shared across
runtime, blackboard, scheduler-quartz).

Why common, not runtime: blackboard and scheduler-quartz modules are likely consumers.
Placing in common avoids circular dependencies. The types are pure POJOs with no CDI —
no runtime-specific infrastructure needed.

## Types

### DagPlan\<T\>

Immutable DAG of execution nodes. Validated at construction time (no cycles, valid
references, at least one entry node). Structurally equivalent to blocks' `ExecutionPlan<T>`
(#694) — see §Relationship to blocks' ExecutionPlan\<T\> for convergence path.

```java
public record DagPlan<T>(Map<String, DagNode<T>> nodes) {
    // Construction validates: no cycles, all references exist, at least one entry node

    public Set<String> entryNodeIds()       // nodes with no predecessors
    public Set<String> exitNodeIds()        // nodes no other node depends on
    public List<DagNode<T>> topologicalSort()

    // Factories
    public static <T> DagPlan<T> singleton(T task)
    public static <T> DagPlan<T> sequence(List<T> tasks)
    public static <T> DagPlan<T> parallel(List<T> tasks)
}
```

### DagNode\<T\>

```java
public record DagNode<T>(String id, T task, Set<String> dependsOn, JoinType joinType) {
    // dependsOn defaults to empty, joinType defaults to ALL_OF
}
```

### JoinType

```java
public enum JoinType {
    ALL_OF,  // fire when every predecessor completes (conjunction)
    ANY_OF   // fire when any predecessor succeeds (disjunction)
}
```

### NodeState

Internal execution tracking type — sealed for pattern matching with contextual data.
Each variant maps to a `TaskStatus` value (engine-api, #700).

```java
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

**Why sealed interface over bare `TaskStatus`:** `TaskStatus` is a flat enum — correct for
status tracking and persistence. But `NodeState<R>` carries execution data: `Completed` holds
the result `R`, `Failed` holds reason and cause. The sealed interface enables exhaustive pattern
matching on completion callbacks. `toTaskStatus()` bridges to the shared taxonomy for external
consumers and monitoring.

| NodeState variant | TaskStatus | Rationale |
|---|---|---|
| Pending | PENDING | Not yet dispatched |
| Dispatched | RUNNING | Actively executing |
| Completed(R) | COMPLETED | Finished successfully |
| Failed(reason, cause) | FAULTED | System failure |
| Skipped(reason) | OBSOLETE | Unreachable due to predecessor failure |
| Cancelled | CANCELLED | Deliberate stop |

### DispatchMode

```java
public enum DispatchMode {
    STREAMING,  // dispatch each task as predecessors satisfy — default
    BARRIER     // batch all ready, await all, repeat
}
```

**When to use BARRIER:** Wave-based processing where all wave-N results are needed before
computing wave-N+1. Examples: multi-round LLM planning where each wave's outputs inform the
next wave's prompts; checkpoint/audit workflows where all tasks in a level must be verified
before proceeding; resource-bounded execution where concurrent task count must be bounded
to wave size.

### DagResult\<R\>

```java
public record DagResult<R>(
    Map<String, NodeState<R>> nodeStates,
    Map<String, R> completedResults,  // node ID → result, for completed nodes
    boolean allSucceeded,
    Duration elapsed
) {
    public Map<String, TaskStatus> taskStatuses() {
        return nodeStates.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().toTaskStatus()));
    }
}
```

### DagEventListener\<T, R\>

Callback interface for **observation** of dispatch and completion events. Provides the
composition seam for accountability listeners, monitoring, and audit logging.

```java
public interface DagEventListener<T, R> {
    default void onNodeDispatched(String nodeId, T task) {}
    default void onNodeCompleted(String nodeId, T task, R result) {}
    default void onNodeFailed(String nodeId, T task, String reason, Throwable cause) {}
    default void onNodeSkipped(String nodeId, T task, String reason) {}
    default void onNodeCancelled(String nodeId, T task) {}
    default void onExecutionComplete(DagResult<R> result) {}
}
```

**Composition model — what composes where:**

| Concern | Composition point | Mechanism |
|---------|------------------|-----------|
| Oversight gates | Executor function | `awaitApproval(task)` blocks the virtual thread; independent tasks continue on their own threads |
| Trust routing | Plan construction or executor function | Tasks are pre-assigned in the plan, or the executor applies trust filtering before dispatch |
| Accountability / audit | `DagEventListener` | Observer callbacks fire on each state transition — no control flow influence |
| Monitoring / metrics | `DagEventListener` | Same — observation only |

The listener **observes but does not control**. Gate integration and trust routing compose
through the executor function (blocking the calling virtual thread) or through pre-plan
construction, not through void callbacks.

**Exception isolation:** Listener callback exceptions are caught and logged. A faulty
listener never affects DAG execution — it cannot crash the scheduling algorithm, mark a
node as failed, or prevent dispatch. This is standard observer pattern practice.

A blocks-side `ParallelDriver<T>` wrapper would implement this listener to bridge events
into `ExecutionEventListener` callbacks (agent dispatch, result, aggregation notifications).
The DAG driver doesn't embed agentic concerns — they belong in the orchestration layer.

## DagDriver\<T, R\>

The driver is generic over task type `T` (what goes into a node) and result type `R`
(what each task produces). No inheritance from blocks. Pure Java with
`CompletableFuture` for concurrency.

```java
public class DagDriver<T, R> {

    private final DagPlan<T> plan;
    private final DispatchMode mode;
    private final List<DagEventListener<T, R>> listeners;

    public DagDriver(DagPlan<T> plan)
    public DagDriver(DagPlan<T> plan, DispatchMode mode)
    public DagDriver(DagPlan<T> plan, DispatchMode mode, List<DagEventListener<T, R>> listeners)

    public DagResult<R> execute(Function<T, R> taskExecutor)
    public DagResult<R> execute(Function<T, R> taskExecutor, Executor threadPool)

    public void cancel()
}
```

**Single-use:** `execute()` throws `IllegalStateException` if called more than once. The
driver holds mutable execution state (node states, cancellation flag) that is not reset
between calls. Construct a new `DagDriver` for each execution. This aligns with the
immutable plan / immutable result design — the driver is a one-shot executor, not a
reusable service.

**Why `Function<T, R>` and not an SPI?** The executor is a function, not an interface with
lifecycle. The caller provides the mapping from task → result. Composition with richer
concerns (oversight gates, trust routing) happens **inside the executor function** — the
function blocks on a gate, applies trust filtering, then executes. The listener observes
but does not control. A blocks-side wrapper would supply a function that internally uses
`AgentInvoker<T>` and bridges events via `DagEventListener`.

### Algorithm — STREAMING mode

```
1. Initialize: all nodes PENDING
2. Dispatch entry nodes (no predecessors) → each on a virtual thread
3. On each completion callback:
   a. Update node state to COMPLETED or FAILED
   b. Notify listeners (onNodeCompleted / onNodeFailed)
   c. If FAILED: propagate — mark dependent nodes SKIPPED (per join rules), notify listeners
   d. Compute newly-ready nodes (predecessors now satisfied)
   e. Dispatch each newly-ready node, notify listeners (onNodeDispatched)
4. When all nodes are terminal → build DagResult, notify listeners (onExecutionComplete), return
```

Implemented via a `CompletableFuture` per dispatched node. Each future's `whenComplete`
callback checks for newly-ready nodes and dispatches them. A `CountDownLatch` (or
`Phaser`) tracks when all nodes are terminal.

### Algorithm — BARRIER mode

```
1. Initialize: all nodes PENDING
2. While there are PENDING nodes:
   a. Compute ready set
   b. If empty → break (stuck or done)
   c. Dispatch all ready tasks concurrently (CompletableFuture.allOf), notify listeners
   d. Await entire wave
   e. Update states, propagate failures, notify listeners
3. Build DagResult, notify listeners (onExecutionComplete), return
```

### Thread safety

STREAMING mode has concurrent completions. State tracking uses:
- `ConcurrentHashMap<String, NodeState<R>>` for node states
- CAS on node state transitions (PENDING → DISPATCHED is the critical one — only one
  thread dispatches a node)
- The ready-set computation reads only COMPLETED predecessors — no TOCTOU because
  transitions are monotonic (a node never goes from COMPLETED back to PENDING)

## Ready-Set Computation

A node is **ready** when:
- State is PENDING, AND
- Join condition satisfied:
  - `ALL_OF`: every predecessor is COMPLETED
  - `ANY_OF`: at least one predecessor is COMPLETED (or no predecessors)

Failure propagation:
- `ALL_OF` node: if ANY predecessor is FAILED or SKIPPED → node is SKIPPED
- `ANY_OF` node: only SKIPPED when ALL predecessors are FAILED or SKIPPED

### ANY_OF sibling behaviour

When an ANY_OF join fires (first predecessor completes), remaining in-flight predecessors
**continue executing to completion**. Their results are recorded in `nodeStates` but do not
trigger the successor again (the successor dispatches exactly once).

Rationale: cancelling sibling sub-trees requires cooperative cancellation propagation and
introduces complexity disproportionate to the gain for typical CaseHub task durations. The
resource cost of redundant completion is bounded by the number of ANY_OF predecessors (small
in practice — typically 2-3 alternatives). Sub-tree cancellation for ANY_OF predecessors is
a valid future optimisation — file as a separate issue if workloads demonstrate the need.

## Failure Handling

**Continue-by-default.** A failed node's dependents are transitively SKIPPED per join
rules. Independent paths continue unaffected. The plan completes when all reachable
nodes are terminal.

`DagResult.allSucceeded` tells the caller whether any node failed. The caller decides
policy (retry, escalate, fault the case).

## Cancellation

`cancel()` sets a volatile flag. No new nodes are dispatched after cancellation. In-flight
tasks run to completion (no thread interruption — tasks may hold resources). Pending nodes
at cancellation time transition to **CANCELLED** (not PENDING) — this distinguishes
deliberate cancellation from failure-induced SKIPPED and from nodes not yet reached by
the scheduler.

The `Cancelled` variant maps to `TaskStatus.CANCELLED`, while `Skipped` maps to
`TaskStatus.OBSOLETE` — callers can distinguish "stopped by request" from "unreachable
due to predecessor failure."

## What DagDriver Does NOT Do

- **No routing**: tasks are pre-assigned in the plan
- **No retry**: caller's responsibility
- **No aggregation**: result is per-node — caller aggregates
- **No timeout**: timeouts are compositional — the caller wraps the executor function with
  a timeout (`CompletableFuture.orTimeout`) or schedules a `cancel()` from a timer. Building
  timeout into the driver conflates scheduling with policy.
- **No Mutiny/Uni**: pure `java.util.concurrent` — no reactive framework dependency in common

## Engine Integration

### Integration with existing types

The DAG driver uses engine-api's shared types:
- `TaskStatus` (via `NodeState.toTaskStatus()`) — for monitoring and cross-model status reporting
- `DagResult.taskStatuses()` — projects `NodeState` map into `TaskStatus` map for external consumers

The driver is generic over `T` and `R` — it does not depend on any specific task type. Engine
consumers parameterize it with their domain types:

```java
// Example: PlanItem-based execution
DagPlan<PlanItem> plan = buildPlanFromCasePlanModel(casePlanModel);
var driver = new DagDriver<>(plan, DispatchMode.STREAMING, List.of(auditListener));
DagResult<OutcomeKind> result = driver.execute(planItem ->
    dispatchAndAwait(planItem));
```

### Blocks-side ParallelDriver (future, tracked under blocks#44)

A `ParallelDriver<T>` in blocks would wrap `DagDriver` and implement `ExecutionDriver<T>`:

```java
public class ParallelDriver<T> extends AbstractExecutionDriver<T> {
    @Override
    protected Uni<ExecutionResult> runLoop(ExecutionModel<T> model, T context) {
        // Convert ExecutionPlan<T> to DagPlan
        // Create DagEventListener that bridges to ExecutionEventListener
        // Run DagDriver.execute() with AgentInvoker-backed function
    }
}
```

This separation keeps the scheduling algorithm free of Mutiny, routing, activation,
aggregation, and termination concerns — those are agentic orchestration concerns, not
scheduling concerns.

## Test Plan

### Unit tests (pure, no CDI)

1. **Single node**: dispatches and completes
2. **Linear chain**: A→B→C, executes sequentially
3. **Full parallel**: A, B, C independent — all dispatch concurrently
4. **Diamond**: A→{B,C}→D — A first, B+C parallel, D after both
5. **Wide fan-out**: A→{B,C,D,E,F} — A then all 5 in parallel
6. **Wide fan-in**: {A,B,C,D,E}→F — all 5 parallel, F after all
7. **Asymmetric depth (STREAMING)**: A→B→D, A→C (C slow) — D dispatches before C finishes
8. **Asymmetric depth (BARRIER)**: same DAG — D waits for C (barrier penalty)
9. **ANY_OF fires early**: A→{B,C}→D(ANY_OF) — D fires when first of B/C completes
10. **ANY_OF all fail**: both B and C fail → D SKIPPED
11. **ALL_OF partial fail**: B fails → D SKIPPED, C continues
12. **Transitive failure**: A→B→C, A fails → B and C both SKIPPED
13. **Independent paths with failure**: {A→B, C→D}, A fails → B SKIPPED, C→D unaffected
14. **Cancellation marks pending as CANCELLED**: cancel mid-execution → in-flight completes, pending becomes CANCELLED (not PENDING)
15. **Custom executor**: provided thread pool is used for dispatch
16. **Complex DAG**: multi-level mixed ALL_OF/ANY_OF
17. **Large fan-out (100 nodes)**: no deadlock, correct completion
18. **Empty plan**: construction rejects with IAE
19. **Cycle detection**: construction rejects with IAE
20. **Self-referencing node**: construction rejects with IAE
21. **TaskStatus mapping**: every NodeState variant maps to correct TaskStatus
22. **DagResult.completedResults**: Map keyed by node ID, not unkeyed list
23. **Listener notifications**: all listener callbacks fire at correct points
24. **ANY_OF sibling continuation**: remaining predecessors complete after join fires

25. **Single-use enforcement**: second `execute()` call throws `IllegalStateException`
26. **Listener exception isolation**: faulty listener does not crash DAG execution or affect node states
27. **Listener receives task on skip/cancel**: `onNodeSkipped` and `onNodeCancelled` callbacks include the task `T`

### Concurrency tests

28. **Race condition**: 10 parallel paths complete simultaneously — state transitions are clean
29. **Slow node doesn't block independent path**: timed assertion on STREAMING mode
