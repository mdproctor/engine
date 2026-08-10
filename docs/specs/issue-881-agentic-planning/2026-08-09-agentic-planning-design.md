# Agentic Planning — Engine-Hosted Pattern Execution

**Epic:** engine#881
**Children:** #882 (re-planning), #883 (checkpointing), #884 (constraints), #886 (backends)
**Depends on:** engine#802 (hierarchical planning — landed), blocks#60 Phase 5 (HTN SPI — landed), engine#823–826 (scoped worker lifecycle — landed)

## Problem

The agentic pattern infrastructure (blocks `io.casehub.blocks.agentic`) has a rich
builder DSL (`debate().debaters(a,b,c).judge(j).build()`) and a five-strategy
composition model (routing × decomposition × activation × aggregation × termination).
But execution is in-process only — `OrchestratedDriver` runs a while loop in memory
with no persistence, no recovery, no re-planning on failure, and no constraint
awareness.

Without durability and adaptive planning, the DSL looks like "just another workflow
DSL." The value proposition is the composition model — but it needs an execution
layer that provides production-grade durability without rebuilding what the engine
already provides.

## Architecture

**Core principle:** Blocks owns the DSL and composition model. The engine owns
execution and durability. The `WorkerFunctionHandler` SPI is the interface.

```
┌─ Blocks (DSL layer) ─────────────────────────────┐
│  debate().debaters(a,b,c).judge(j).build()        │
│  → ExecutionModel (5 SPIs + config)               │
│  → ExecutionBackend selects runtime               │
└───────────────────────────────────────────────────┘
         │                           │
    reactive()                  engineHosted()
         │                           │
    ┌────▼────┐              ┌───────▼───────────────────┐
    │ In-proc │              │ Engine Worker Boundary     │
    │ Driver  │              │  ┌─────────────────────┐  │
    │ (tests, │              │  │ PatternWorkerFn     │  │
    │  atomic)│              │  │  → Driver.execute() │  │
    │         │              │  │  → EngineInvoker    │  │
    └─────────┘              │  │    → WorkerRuntime  │  │
                             │  └─────────────────────┘  │
                             │  PlanItem lifecycle        │
                             │  EventLog audit            │
                             │  Retry via RetryPolicies   │
                             │  Recovery on restart       │
                             └───────────────────────────┘
```

### Why this architecture

Four options were evaluated from first principles:

1. **Blocks owns execution** — requires rebuilding persistence, retry, recovery in
   blocks. Duplicates engine infrastructure. AgentInvoker can't dispatch to engine
   workers without heavy coupling.

2. **Engine owns execution (blocks compiles to engine primitives)** — impedance
   mismatch between blocks' iterative 5-SPI model and engine's reactive binding
   dispatch. Aggregation (MajorityVote, CollectAll) has no engine equivalent.
   Modeling DEBATE as compounds + bindings is painfully verbose.

3. **Clean split by pattern shape** — correct insight (workflow-shaped vs agentic
   patterns have different needs) but incomplete: doesn't specify how agentic
   patterns get durability without custom checkpointing.

4. **Engine as host, blocks driver as worker (chosen)** — the engine provides the
   durability envelope. The blocks driver runs inside a worker function. Sub-agents
   dispatch via `WorkerRuntime.execute()` through the full engine pipeline. No
   duplication, minimal changes to both layers, existing tests unchanged.

### Module: casehub-engine-agentic

New optional module (directory: `agentic-engine/`). Follows the same pattern as
`casehub-engine-flow` — activated by adding it to the consumer's classpath.

**Compile dependencies:**
- `casehub-blocks` (ExecutionModel, drivers, AgentRef)
- `casehub-engine-common` (WorkerFunctionHandler SPI, WorkerExecutor)
- `casehub-engine-api` (WorkerFunction, DecompositionStrategy, WorkerRuntime)
- `casehub-engine` (runtime) — for `WorkerRuntimeFactory` (same pattern as
  `PersistentWorkerFunctionHandler`)
- `casehub-worker-api`

## Phase 1: Configurable Execution Backends (#886)

### ExecutionBackend extension

`ExecutionBackend` already exists as a `@FunctionalInterface` in blocks with a
single method: `Uni<ExecutionResult> execute(ExecutionModel<T>, T)`.

**New factories:**
- `ExecutionBackend.reactive()` — renamed from `orchestrated()`. In-process,
  immediate. For tests and atomic single-shot patterns. `orchestrated()` stays as
  a deprecated alias. Lives in blocks (no new dependency).
- `EngineHostedBackend` — new class in `casehub-engine-agentic` implementing
  `ExecutionBackend`. NOT a static factory on `ExecutionBackend` (that would
  create a blocks→engine dependency). Wraps `ExecutionModel` in
  `PatternWorkerFunction`, dispatches through the engine's worker pipeline.

**Auto-selection:** `AbstractPatternBuilder.execute()` currently defaults to
`new OrchestratedDriver<>()` when no backend is set. New default: query
`ServiceLoader<ExecutionBackend>` for a non-reactive implementation. If found
(engine-agentic module on classpath, which registers `EngineHostedBackend` via
`META-INF/services`), use it. If absent, fall back to reactive. This means:
- Blocks standalone (tests, no engine) → reactive automatically
- Inside engine runtime (production) → engine-hosted automatically
- Explicit `.backend()` call overrides either

**ServiceLoader lifecycle:** The `ServiceLoader` discovery happens at
`AbstractPatternBuilder.execute()` time, not at class-load time. In a CDI
environment (Quarkus), the `EngineHostedBackend` can also be discovered as a
CDI bean — `PatternWorkerFunctionProvider` injects it directly when
constructing functions from YAML. The `ServiceLoader` path is for the
programmatic builder API (non-CDI).

Builder API:
```java
// Programmatic — auto-selects engine-hosted if on classpath
Patterns.debate()
    .debaters(a, b, c)
    .judge(j)
    .maxRounds(5)
    .execute(initialContext);

// Explicit — for tests or when auto-selection is wrong
Patterns.debate()
    .debaters(a, b, c)
    .backend(ExecutionBackend.reactive())
    .execute(initialContext);
```

### PatternWorkerFunction

Record implementing `WorkerFunction<Map, Map>`. Holds the `ExecutionModel`
reference (constructed at definition build time, not serialized).

```java
public record PatternWorkerFunction(
    ExecutionModel<?> model,
    PatternType patternType,
    boolean checkpointingEnabled
) implements WorkerFunction<Map, Map> {
    @Override public Class<Map> inputType() { return Map.class; }
    @Override public Class<Map> outputType() { return Map.class; }
}
```

### PatternWorkerFunctionHandler

`@ApplicationScoped`, implements `WorkerFunctionHandler`. Injects
`WorkerRuntimeFactory` to create a per-invocation `WorkerRuntime` — same
pattern as `SyncAgentWorkerFunctionHandler`. Runs the driver on
`@VirtualThreads ExecutorService` with timeout enforcement.

```java
@ApplicationScoped
public class PatternWorkerFunctionHandler implements WorkerFunctionHandler {

    private final WorkerRuntimeFactory workerRuntimeFactory;

    // constructor injection

    @Override
    public boolean supports(WorkerFunction<?, ?> function) {
        return function instanceof PatternWorkerFunction;
    }

    @Override
    public HandlerResult execute(WorkerFunction<?, ?> function, Object inputData,
                                  WorkerContext context, int timeoutMs,
                                  ExecutionMetadata metadata) {
        var patternFn = (PatternWorkerFunction) function;
        var runtime = workerRuntimeFactory.create(
            context.caseId(), metadata.workerName(), context);
        var invoker = new EngineAgentInvoker<>(runtime);
        var driver = new OrchestratedDriver<>(invoker);
        var result = driver.execute(patternFn.model(), inputData)
            .await().atMost(Duration.ofMillis(timeoutMs));
        return toHandlerResult(result, patternFn.patternType());
    }
}
```

**Timeout and cancellation:** If the handler timeout fires (`atMost()`
throws `TimeoutException`), the driver's in-flight agents may still be
executing. The handler calls `driver.cancel()` in a `finally` block to
mark pending nodes `Cancelled` and prevent new dispatches. In-flight
agent workers complete independently (the engine owns their lifecycle).

**Module dependency note:** `WorkerRuntimeFactory` lives in
`casehub-engine` (runtime). This means `casehub-engine-agentic` needs
a runtime dependency for the factory. This follows the same pattern as
`PersistentWorkerFunctionHandler` which also lives outside runtime but
injects runtime beans.

### EngineAgentInvoker

Bridges `AgentRef` to the engine's worker dispatch via `WorkerRuntime`.
The invoker receives a `WorkerRuntime` from the handler (created via
`WorkerRuntimeFactory`) — not `WorkerScope`.

| AgentRef variant | Dispatch path |
|-----------------|---------------|
| `ExternalAgent` | Call function directly (no engine overhead) |
| `WorkerAgent` | `runtime.execute(worker.name(), input)` |
| `ComposedAgent` | Recursive via same backend |
| `ChannelAgent` | v1: `UnsupportedOperationException` (see v1 limitations) |
| `HumanAgent` | v1: `UnsupportedOperationException` (see v1 limitations) |

`ExternalAgent` stays direct — it's a lambda, no retry/audit needed, and it
preserves blocks' independent testability.

**Per-agent retry:** When `runtime.execute()` returns a failed result, the
engine's own retry infrastructure (`RetryPolicies`) has already retried the
sub-agent worker. The invoker does not add a second retry layer. The failed
result is returned to the driver, which consults `FailurePolicy` (or
`ReplanPolicy` for HTN patterns).

**v1 limitation — ChannelAgent and HumanAgent:** These require Qhorus
channel posting and WorkItem creation respectively. `WorkerRuntime` exposes
`execute()` and `spawnCase()` but not `openChannel()` or
`createWorkItem()`. These variants throw `UnsupportedOperationException`
in v1. Support requires extending `WorkerRuntime` or adding dedicated SPIs
on the invoker — tracked as future work.

### PatternWorkerFunctionProvider

`@ApplicationScoped`, implements `WorkerFunctionProvider`. Detects `pattern:`
YAML blocks on worker definitions, constructs `PatternWorkerFunction`.

YAML schema:
```yaml
workers:
  - name: analyst-debate
    capabilities: [analysis]
    pattern:
      type: debate
      maxRounds: 5
      agents: [analyst-a, analyst-b, analyst-c]
      judge: senior-analyst
      backend: engine-hosted
```

### CaseDefinition integration

`CaseDefinition` gains `executionBackend` (nullable String). Resolved via
`EngineStrategyResolver`. YAML: `executionBackend:` under `spec:`. When null,
auto-select per the `ServiceLoader` mechanism.

## Phase 2: Planning Under Constraints (#884)

### PlanningConstraints

New record in `engine-api` (`io.casehub.engine.plan`):

```java
public record PlanningConstraints(
    Duration timeBudget,
    Integer resourceLimit,
    Map<String, Double> weights
) {
    public static PlanningConstraints unconstrained() {
        return new PlanningConstraints(null, null, Map.of());
    }
}
```

v1 scope: `timeBudget` (max duration for the pattern execution) and
`resourceLimit` (max concurrent agents per iteration). Cost budget (LLM tokens,
API quotas) deferred — requires token tracking infrastructure.

### DecompositionContext extension

```java
public interface DecompositionContext<T> {
    T state();
    int depth();
    default PlanningConstraints constraints() {
        return PlanningConstraints.unconstrained();
    }
}
```

Default method — backward compatible. `GoalDecompositionContext` carries
constraints from the `CaseDefinition`.

### LLM prompt integration

`LlmDecompositionStrategy` includes constraints in the user prompt when non-null:

> "You have 30 minutes and 3 available agents. Decompose this goal into steps
> that can complete within these constraints. Prefer parallelism when resource
> limits allow."

### Driver-side enforcement

`PatternWorkerFunctionHandler` tracks constraint consumption during execution:

- **Time budget:** Checks elapsed time before each iteration. If exceeded, the
  driver terminates with `ExecutionResult.Failed("time budget exceeded")`. The
  remaining time is threaded to `EngineAgentInvoker`, which passes it as the
  worker timeout — agents dispatched late get proportionally less time.
- **Resource limit:** Caps concurrent agent dispatch within an iteration. If the
  routing strategy selects more agents than `resourceLimit`, only the first N are
  dispatched; the rest are deferred to the next iteration.

### CaseDefinition config

```yaml
spec:
  planningConstraints:
    timeBudget: PT30M
    resourceLimit: 3
```

Builder: `definition.planningConstraints(PlanningConstraints.of(Duration.ofMinutes(30), 3))`.

### Module placement

`PlanningConstraints` in `engine-api` (consumers inspect it, plan-definition
type per PP-20260727-5267d2). Driver enforcement in `casehub-engine-agentic`
(execution infrastructure).

## Phase 3: Dynamic Re-Planning on Step Failure (#882)

### Scope: HTN patterns only

Re-planning applies to patterns that have a decomposition step — specifically
HTN, where `HybridDecomposition` or `LlmDecomposition` produces a `DagPlan`
that is flattened into an ordered sequence of agents. For non-HTN patterns
(DEBATE, VOTING, SUPERVISOR), there is no "plan" to revise — failure handling
uses the existing `FailurePolicy` actions (FAIL, RETRY_BROADER, ESCALATE).

The blocks driver is iteration-based (route → activate → dispatch → aggregate
→ terminate). HTN's `HtnBuilder` bridges the two models: it decomposes a
compound task into a `DagPlan`, topologically sorts it, creates candidates from
leaf executors, and feeds them to the iteration loop with iteration count =
agent count. Re-planning revises the `DagPlan` and re-initializes the candidate
list and iteration count.

### ReplanPolicy — new field on FailurePolicy

Re-planning is NOT a new value in `RoutingFailureAction` or
`AggregationFailureAction` — those enums govern per-iteration decisions.
Re-planning is a plan-level recovery that sits above the iteration loop.

`FailurePolicy` gains a new field:

```java
public record FailurePolicy(
    RoutingFailureAction onRoutingFailure,
    AggregationFailureAction onAggregationFailure,
    AgentRetryPolicy agentRetryPolicy,
    ReplanPolicy replanPolicy               // NEW — nullable, null = no re-planning
) {
    public record ReplanPolicy(
        int maxReplans,                      // default 2
        RoutingFailureAction fallbackAction  // when replan itself fails, default FAIL
    ) {}
}
```

### Retry layering (clarified)

Two retry layers exist, operating at different levels:

1. **Engine-level retry (per sub-agent):** When `EngineAgentInvoker` calls
   `runtime.execute(workerName, input)`, the engine dispatches a worker.
   If that worker fails, the engine's own `RetryPolicies` retries it
   (configurable per worker via `ExecutionPolicy`). This is transparent
   to the blocks driver — it sees a single `runtime.execute()` call that
   either succeeds or fails after all engine retries are exhausted.

2. **Blocks-level failure handling (per iteration):** When the driver
   receives a failed `AgentResult`, it consults `FailurePolicy`
   (`onRoutingFailure`, `onAggregationFailure`). For non-HTN patterns,
   this is the terminal failure decision. For HTN patterns with
   `replanPolicy` set, the driver additionally checks the replan path.

Blocks' `AgentRetryPolicy` (backoff strategies) remains not wired in v1 —
engine retry handles per-agent retry, blocks re-planning handles plan-level
recovery.

### DecompositionStrategy.replan()

Default method on the SPI interface — opt-in:

```java
default Uni<DagPlan<TaskNode.LeafTask<T>>> replan(
        TaskNode<T> task,
        DecompositionContext<T> context,
        ReplanContext<T> replanContext) {
    return Uni.createFrom().failure(
        new UnsupportedOperationException("Re-planning not supported by " + id()));
}
```

### ReplanContext

New record in `casehub-engine-agentic` (not engine-api — this is execution
infrastructure, not a plan-definition type that consumers inspect):

```java
public record ReplanContext<T>(
    List<CompletedStep<T>> completedSteps,
    FailedStep<T> failedStep,
    DagPlan<TaskNode.LeafTask<T>> originalPlan,
    int replanCount
) {}

public record CompletedStep<T>(String stepId, Object result, Duration elapsed) {}
public record FailedStep<T>(String stepId, String errorMessage,
                             Throwable cause, int retryAttempts) {}
```

- `CompletedStep<T>` — inputs to the replanner (it knows what succeeded)
  but never re-executed.
- `FailedStep<T>` — `retryAttempts` reflects how many engine-level retries
  were exhausted before the failure surfaced to the driver.

### Scoped re-planning

The replanner produces a plan for the *remaining* work only. Completed steps are
preserved — the driver splices the new plan into execution. Completed step states
stay terminal. The failed step and all downstream steps are replaced by the new
plan.

### Driver integration

Re-planning hooks into `HtnBuilder.execute()`, not
`AbstractExecutionDriver.executeIteration()`. HTN already overrides `execute()`
to decompose, sort, and run. The replan hook is added after the driver returns
a failed result:

1. Driver returns `ExecutionResult.Failed`
2. If `replanPolicy` is null or `replanCount >= maxReplans` → return failed result
3. Build `ReplanContext` from completed agent results + failed agent
4. Call `decomposition.replan(task, context, replanContext)`
5. If replan succeeds: rebuild candidates from new plan, reset driver, re-execute
6. If replan fails: apply `replanPolicy.fallbackAction()`

### LlmDecompositionStrategy.replan()

LLM-backed implementation:

- System prompt: "You are revising a plan after a step failure. Produce a revised
  plan that achieves the same goal using the remaining capabilities."
- User prompt includes: original goal, completed steps with results, failed step
  with error, available capabilities, current constraints
- Response schema: same as `decompose()` — `{ "steps": [...] }`
- The LLM can reorder remaining steps, substitute capabilities, add intermediate
  steps, or skip optional steps

### YAML configuration

```yaml
workers:
  - name: research-agent
    pattern:
      type: htn
      rootTask: comprehensive-analysis
      replan:
        maxReplans: 2
        fallback: escalate
```

Re-planning configuration lives on the `pattern:` block (not at the top-level
`failurePolicy:`), since it only applies to patterns with decomposition.

## Phase 4: Partial Plan Execution with Checkpointing (#883)

### Phase 4a (MVP): No checkpointing

Pattern runs as a worker function. If the process crashes, the engine's retry
infrastructure re-dispatches the worker. The driver re-executes from the start.

Acceptable for short-lived patterns (single-shot VOTING, shallow PARALLEL).

### Phase 4b: Iteration-level checkpointing

#### Checkpoint granularity

Per iteration. After each complete iteration of the driver loop, the driver
persists its state. Within an iteration, agent dispatches go through
`WorkerRuntime.execute()` (synchronous, blocking on virtual thread). If the
process crashes mid-iteration, the iteration is re-run from scratch on
recovery (see Mid-iteration crash recovery below).

#### PatternExecutionCheckpoint

New record in `engine-common`:

```java
public record PatternExecutionCheckpoint(
    UUID caseId,
    String patternId,
    int completedIterations,
    List<AgentResultRecord> results,
    Set<String> excludedAgents,
    DagPlanSnapshot currentPlan,     // nullable — only present for HTN patterns
    int replanCount,                 // 0 when Phase 3 not active
    Map<String, Object> driverState
) {}
```

`AgentResultRecord` is the serializable projection of blocks' `AgentResult` —
agent ID (String), output (as `JsonNode`), success/failure, duration. No
function references or lambdas.

**Serialization constraint:** `driverState` stores activation counts and
consecutive-idle counts keyed by **agent ID (String)**, NOT by `AgentRef`.
`AgentRef` contains function references and lambdas (`ExternalAgent.function`,
`ComposedAgent.model`) which are not serializable. The driver must maintain a
mapping from `AgentRef` to stable string IDs at construction time.

**Phase 3 dependency:** `replanCount` and `currentPlan` fields are populated
only when Phase 3 (re-planning) is active. When Phase 3 is not implemented,
`replanCount` is always 0 and `currentPlan` is null. The checkpoint record
is forward-compatible — adding Phase 3 later does not change the schema.

#### Storage: EventLog-backed

Checkpoints serialize as EventLog metadata with a `PATTERN_CHECKPOINT` event
type. No new JPA entity, no new table.

On recovery, the handler queries for the latest `PATTERN_CHECKPOINT` event for
the case/pattern.

The existing `InMemoryExecutionSnapshotStore` stays as `@DefaultBean` for
observability. Checkpointing uses the EventLog directly — it's the durable
audit trail that already exists.

#### Driver checkpoint protocol

```
Iteration N completes
  → Driver serializes state to PatternExecutionCheckpoint
  → Writes PATTERN_CHECKPOINT EventLog entry
  → Continues to iteration N+1
```

#### Recovery protocol

```
Worker retry after crash
  → PatternWorkerFunctionHandler.execute()
  → Query EventLog for latest PATTERN_CHECKPOINT for this case/pattern
  → If found:
      → Reconstruct driver state from checkpoint
      → Skip iterations 0..N (already completed)
      → Resume from iteration N+1
  → If not found:
      → Start from scratch (Phase 4a behavior)
```

#### Mid-iteration crash recovery

On recovery, the driver skips completed iterations (restored from checkpoint).
For the iteration that was in-flight during the crash, the driver **re-runs
it entirely**. Agents dispatched via `WorkerRuntime.execute()` in the crashed
iteration may have already completed — `WorkerRuntime.execute()` creates a
fresh execution each time (no deduplication key), so re-dispatched agents
will execute again.

**v1 limitation:** Agent side effects from the crashed iteration may be
duplicated. Agent workers that perform external side effects (API calls,
database writes) should be idempotent or should use `PlannedAction` with
`ActionRiskClassifier` to gate consequential actions. This is consistent
with the general engine recovery model where worker idempotency is the
worker's responsibility.

#### PERSISTENT lifecycle scope

Patterns expected to run for hours (DEBATE with human judges, SUPERVISOR with
escalation chains) use `LifecycleScope.PERSISTENT` + `ExecutionMode.PERSISTENT`.
The driver runs on a virtual thread, waits for external events via the
`PersistentScope` mailbox, and `ScopedWorkerRegistry` tracks the session.

Checkpointing still happens at iteration boundaries — the mailbox handles the
wait, checkpointing handles the crash.

#### CaseDefinition config

```yaml
workers:
  - name: analyst-debate
    pattern:
      type: debate
      maxRounds: 5
      agents: [analyst-a, analyst-b, analyst-c]
      judge: senior-analyst
      checkpointing: true
    lifecycleScope: case
    executionMode: persistent
```

#### What is NOT checkpointed (deliberate)

- In-flight agent execution state — agents are full engine workers with their own
  retry/recovery
- LLM conversation history — the LLM is called fresh on each replan, with
  completed steps as context
- The `ExecutionModel` itself — reconstructed from `CaseDefinition` at recovery
  time, not serialized

## Testing

### Unit tests (blocks, unchanged)

Existing blocks tests continue to use `ExternalAgent` + `reactive()` backend.
No modifications needed. These test the DSL, composition model, and driver
logic in isolation.

### Unit tests (new, casehub-engine-agentic)

1. **`PatternWorkerFunctionHandlerTest`** — handler supports PatternWorkerFunction,
   runs driver, returns HandlerResult with pattern metadata.
2. **`EngineAgentInvokerTest`** — dispatch table: ExternalAgent direct,
   WorkerAgent via scope, ComposedAgent recursive.
3. **`PatternWorkerFunctionProviderTest`** — detects `pattern:` YAML, constructs
   PatternWorkerFunction with correct config.

### Unit tests (re-planning, blocks)

4. **`ReplanContextTest`** — record validation, completed/failed step construction.
5. **`ReplanDriverTest`** — driver calls replan on REPLAN action, splices new plan,
   respects maxReplans guard, falls back on replan failure.
6. **`LlmReplanTest`** — LLM receives failure context, returns revised plan,
   handles LLM errors gracefully.

### Unit tests (constraints, engine-api)

7. **`PlanningConstraintsTest`** — record validation, unconstrained factory.
8. **`ConstraintEnforcementTest`** — driver terminates on time budget exceeded,
   caps agent dispatch on resource limit.

### Unit tests (checkpointing, engine-common)

9. **`PatternExecutionCheckpointTest`** — serialization round-trip, AgentResultRecord
   projection from AgentResult.
10. **`CheckpointRecoveryTest`** — driver resumes from checkpoint, skips completed
    iterations, handles missing checkpoint (fresh start).

### Integration tests (@QuarkusTest, casehub-engine-agentic)

11. **`PatternExecutionIntegrationTest`** — full flow: case with pattern worker →
    start → driver runs → agents dispatch via engine pipeline → result.
    Mock `ChatModelProvider` with canned responses. Uses
    `casehub-persistence-memory`.
12. **`ReplanIntegrationTest`** — agent fails → retries exhausted → replan fires →
    revised plan executes → case completes.
13. **`CheckpointRecoveryIntegrationTest`** — pattern starts → checkpoint written →
    simulate crash (kill handler) → retry fires → resumes from checkpoint.

## Scope Boundaries

**In scope:**
- `casehub-engine-agentic` module with PatternWorkerFunction, handler, invoker, provider
- `ExecutionBackend.reactive()` rename + `EngineHostedBackend` class
- Auto-selection via ServiceLoader (programmatic) and CDI (YAML)
- `PlanningConstraints` on `DecompositionContext`
- Driver-side time budget and resource limit enforcement
- `ReplanPolicy` on `FailurePolicy` for HTN patterns
- `DecompositionStrategy.replan()` default method + `ReplanContext`
- `LlmDecompositionStrategy.replan()` implementation
- `PatternExecutionCheckpoint` with EventLog storage (string-keyed driver state)
- Driver checkpoint/recovery protocol with mid-iteration re-run
- YAML `pattern:` block on worker definitions

**v1 limitations (deliberate):**
- Cost budget constraints deferred (requires token tracking infrastructure)
- SWF compilation for workflow-shaped patterns deferred (`ExecutionBackend.swf()`)
- Only `OrchestratedDriver` supported in engine-hosted mode (ChoreographedDriver
  needs full event-bus integration per its own deferred-work comment)
- Checkpointing is opt-in per pattern (`checkpointing: true`)
- No cross-pattern coordination (each pattern is an independent worker)
- `ChannelAgent` and `HumanAgent` throw `UnsupportedOperationException` —
  require Qhorus/WorkItem SPIs not available on `WorkerRuntime`
- Re-planning applies to HTN patterns only — non-HTN patterns use existing
  `FailurePolicy` actions
- Mid-iteration crash recovery re-runs the entire iteration — agent workers
  should be idempotent for consequential side effects
- Blocks' `AgentRetryPolicy` backoff strategies remain not wired — engine
  retry handles per-agent retry at the worker execution level

**Out of scope (future work):**
- `ExecutionBackend.swf()` — compile workflow-shaped patterns to SWF definitions
- ChoreographedDriver engine integration (requires Vert.x EventBus bridging)
- Cost budget constraints and LLM token tracking
- Cross-pattern coordination and multi-case orchestration
- Pattern observability REST endpoints (extends existing PlanResource)
- `ChannelAgent`/`HumanAgent` support via extended `WorkerRuntime` or
  dedicated invoker SPIs
- Wiring blocks' `FailurePolicy.AgentRetryPolicy` (backoff strategies defined
  but not connected in the driver — separate from engine-level retry)
- Re-planning for non-HTN patterns (would require defining what "plan" means
  for DEBATE/VOTING/SUPERVISOR iteration loops)

## Implementation Phases

| Phase | Issue | Deliverables | Dependencies |
|-------|-------|-------------|--------------|
| 1 | #886 | `casehub-engine-agentic` module, `PatternWorkerFunction`, handler (with `WorkerRuntimeFactory`), invoker, provider, `EngineHostedBackend`, auto-selection, YAML | None |
| 2 | #884 | `PlanningConstraints`, `DecompositionContext.constraints()`, LLM prompt, driver enforcement | Phase 1 |
| 3 | #882 | `ReplanPolicy` on `FailurePolicy`, `replan()` SPI method, `ReplanContext`, `LlmDecompositionStrategy.replan()`, HTN replan loop in `HtnBuilder` | Phase 1 |
| 4 | #883 | `PatternExecutionCheckpoint` (string-keyed), EventLog storage, checkpoint/recovery with mid-iteration re-run, PERSISTENT scope config | Phase 1 |

Phases 2 and 3 are independent of each other. Phase 4 depends on Phase 1 only.
