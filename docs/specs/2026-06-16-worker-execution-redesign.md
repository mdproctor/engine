# Worker Execution Redesign — Separation of Scheduling and Execution

**Issue:** engine#463
**Branch:** `issue-463-function-worker-design`
**Date:** 2026-06-16

---

## Why This, Why Now

### Quartz is a scheduler, not an executor

Quartz's own FAQ states it is "not a job queue — though it is often used as one." eBay's production case study documents what happens when Quartz is pushed into high-volume task execution: misfired triggers, executor thread starvation, and hundreds of jobs stuck in the triggers table. The clustering mechanism relies on row-level database locks that degrade under load — TAV Technologies replaced Quartz across 60+ microservices after lock contention crippled their databases.

CaseHub uses Quartz as both scheduler (time-based triggers, retry delays, milestone SLAs) and executor (run this function now on a Quartz thread). The executor role is the misuse. Function workers call `startNow()` on a Quartz trigger — using a scheduling framework as a thread pool. Quarkus provides `ManagedExecutor` and `@VirtualThreads ExecutorService` for exactly this purpose, with proper lifecycle management, context propagation, and virtual thread support for I/O-bound work.

### The SPI boundary is in the wrong place

`WorkerExecutionManager.submit()` abstracts *when* to run work. Nothing abstracts *how* to run it. The execution logic — type dispatch, timeout enforcement, context setup, output evaluation, retry policy — lives inside `QuartzWorkerExecutionJob`, a Quartz `Job` implementation. This means:

- **Replacing Quartz requires reimplementing engine logic.** A db-scheduler adapter (or any future scheduler) must duplicate 500+ lines of engine concerns that have nothing to do with scheduling.
- **Engine logic is untestable without Quartz.** Testing timeout behaviour, retry policy, or type dispatch requires constructing a Quartz `JobExecutionContext`. These are engine concerns trapped behind a scheduler API.
- **The execution model is wrong for the workload.** Function workers are I/O-bound (LLM calls, HTTP, database). The current `CompletableFuture.supplyAsync()` runs on `ForkJoinPool` — a platform thread pool sized to CPU cores. Virtual threads are the right model for I/O-bound work but can't be used while execution is welded to Quartz's thread pool.

### Function workers need first-class status

Protocol PP-20260531 mandates `FuncWorkflowBuilder` for all production workers. For single-function workers, this wraps a simple function call in a Serverless Workflow object, changing the execution path from synchronous (Function, with timeout) to asynchronous (Workflow, via FlowWorkerExecutor). This is a semantic change masquerading as ceremony — users think they're adding observability, but they're actually switching execution models. The engine already provides retry, timeout, observability, and risk classification for function workers — the protocol just doesn't acknowledge it.

### What about durability?

The natural objection: "Quartz was chosen for durability — if the JVM crashes, Quartz JDBC store persists pending jobs and recovers them. A virtual thread executor is stateless."

This is a legitimate concern. CaseHub has pluggable persistence — `persistence-hibernate` configures `quarkus.quartz.store-type=jdbc-cmt` for production PostgreSQL deployments, giving JDBC-backed durable job storage. RAM store (`persistence-memory`) is for tests, demos, and specialised cases like QuarkMind. In production, Quartz JDBC provides real crash durability.

CaseHub also has the **EventLog** as a primary durability mechanism. Every worker scheduling writes a `WORKER_SCHEDULED` event *before* the Quartz job is created. On restart, `WorkerExecutionRecoveryService.recoverPendingScheduledWorkers()` replays the EventLog to find workers that were scheduled but never completed, and resubmits them.

Today these are belt-and-suspenders: Quartz JDBC can recover its own jobs, AND EventLog recovery can resubmit from the event-sourced log. This redesign changes the layering:

**Scheduling remains durable.** `WorkerExecutionManager` (the scheduling SPI) continues to be backed by Quartz JDBC store. The Quartz trigger that fires the job is still persisted. What changes is what happens *when the trigger fires* — instead of executing the worker function inside the Quartz job thread, the Quartz job calls `WorkerExecutor.execute()` which runs on a virtual thread. If the JVM crashes during execution, the Quartz trigger has already fired (it's consumed), but the EventLog has no `WORKER_EXECUTION_FINISHED` — so `WorkerExecutionRecoveryService` resubmits on restart. Same recovery path as today.

**Execution is stateless by design.** A worker function running on a virtual thread is stateless — exactly like a worker function running on a Quartz executor thread today. Neither survives a JVM crash mid-execution. The durability boundary is the scheduling layer (Quartz JDBC) and the recovery layer (EventLog), not the execution layer.

**Future: db-scheduler migration.** If Quartz is replaced with db-scheduler, the scheduling durability moves from Quartz's 11 tables to db-scheduler's single table. The `WorkerExecutor` SPI is unaffected — it doesn't know or care what scheduled the work. The `WorkerExecutionManager` SPI implementation changes, but the execution and retry SPIs remain stable.

### Why now

- **casehub-life#25** (migrate workers to FuncWorkflowBuilder) is blocked pending this design decision — it's asking workers to adopt ceremony that this redesign eliminates
- **Layer 7** (OpenClaw as WorkerProvisioner) will introduce real non-stub workers — the execution model needs to be correct before external workers arrive
- **Quartz's last non-beta release was October 2019** — the ecosystem has moved on, and CaseHub needs the ability to replace it without reimplementing engine logic

---

## Problem

Worker execution logic is embedded inside `QuartzWorkerExecutionJob` (305 lines) — a Quartz `Job` implementation in the `scheduler-quartz` module. Five engine concerns live there that have nothing to do with scheduling:

| Concern | Where it lives | Should live |
|---------|---------------|-------------|
| Type dispatch (Workflow/Function/Agent) | `QuartzWorkerExecutionJob:157-204` | Engine |
| WorkerExecutionContext thread-local lifecycle | `QuartzWorkerExecutionJob:257-262` | Engine |
| Timeout enforcement via CompletableFuture | `QuartzWorkerExecutionJob:245-286` | Engine |
| JQ output schema evaluation | `QuartzWorkerExecutionJob:288-305` | Engine |
| Retry decision (backoff, attempt counting) | `QuartzWorkerExecutionJobListener:177-221` | Engine |

The scheduling SPI (`WorkerExecutionManager.submit()`) abstracts *when* to run work. Nothing abstracts *how* to run it. Replacing Quartz with db-scheduler (or any other scheduler) requires duplicating all five engine concerns in the new adapter.

Additionally, function and agent workers don't need a scheduler at all. They execute immediately — `startNow()` on a Quartz trigger is using a scheduler as a thread pool. Quarkus provides `ManagedExecutor` and `@VirtualThreads ExecutorService` for exactly this purpose.

### Type Safety Gap

`WorkerFunctionHolder<T>` is a generic wrapper that erases type information at the API boundary. `Worker.getFunction()` returns `WorkerFunctionHolder<?>` — the wildcard forces `instanceof` recovery in `QuartzWorkerExecutionJob`. The `File` type is accepted by the Builder but never handled in execution. No compiler error catches missing dispatch branches.

---

## Design

**Status:** Revised (rev 3) — second review feedback incorporated.

Two concerns separated cleanly: sealed type hierarchy for compile-time type safety, and a `WorkerExecutor` interface for scheduler-independent execution. Retry backoff is a static utility, not a CDI SPI.

### 1. WorkerFunction — Sealed Type Hierarchy

Replaces `WorkerFunctionHolder<T>` with compile-time exhaustive type safety.

Package: `io.casehub.api.model`

```java
public sealed interface WorkerFunction
    permits WorkerFunction.Sync, WorkerFunction.AgentExec, WorkerFunction.Flow {

  record Sync(Function<Map<String, Object>, WorkerResult> fn) implements WorkerFunction {}

  record AgentExec(Agent agent) implements WorkerFunction {}

  record Flow(Workflow workflow) implements WorkerFunction {}
}
```

**Why three variants, not two:**

Function and Agent share the same execution semantics (synchronous, timeout-bounded, on a worker thread). Unifying them into a single `Sync` variant by wrapping Agent in a lambda (`Sync(input -> agent.execute(input))`) is tempting but wrong:

- `Agent` carries rich metadata (`systemPrompt`, `responseSchema`, `ChatModel`, `AgentDescriptor`) that the engine uses for routing, observability, and risk classification. Wrapping it in a lambda loses all of this.
- `AgentRoutingStrategy` operates on `AgentDescriptor` extracted from the Worker — it needs to know this is an Agent, not an opaque function.
- Event log entries distinguish agent executions from function executions for operational visibility.

The sealed type preserves the API distinction (what the user defined) while the `WorkerExecutor` implementation can share execution logic for both sync variants.

**Worker changes (breaking):**

`Worker.getFunction()` returns `WorkerFunction` (sealed) instead of `WorkerFunctionHolder<?>` (wildcard generic). All `instanceof` dispatch sites (10 call sites) become exhaustive `switch` expressions.

Builder:
```java
// Before
Worker.Builder.function(Function<Map<String, Object>, WorkerResult> fn)  // → WorkerFunctionHolder<Function>
Worker.Builder.function(Workflow workflow)                                 // → WorkerFunctionHolder<Workflow>
Worker.Builder.function(Agent agent)                                      // → WorkerFunctionHolder<Agent>
Worker.Builder.function(File file)                                        // → WorkerFunctionHolder<File> (never executed)

// After
Worker.Builder.function(Function<Map<String, Object>, WorkerResult> fn)  // → WorkerFunction.Sync
Worker.Builder.function(Workflow workflow)                                 // → WorkerFunction.Flow
Worker.Builder.function(Agent agent)                                      // → WorkerFunction.AgentExec
// File overload removed — it was never handled in execution
```

Public constructors — all four change to create `WorkerFunction` variants:
```java
// Before (four public constructors creating WorkerFunctionHolder<?>)
public Worker(String name, List<Capability> capabilities,
    Function<CaseContext, Map<String, Object>> function)  // legacy type — removed
public Worker(String name, List<Capability> capabilities, Workflow workflow)
public Worker(String name, List<Capability> capabilities, File file)       // removed
public Worker(String name, List<Capability> capabilities, Agent agent)

// After (two public constructors + Builder)
public Worker(String name, List<Capability> capabilities, Workflow workflow)  // → WorkerFunction.Flow
public Worker(String name, List<Capability> capabilities, Agent agent)       // → WorkerFunction.AgentExec
```

The legacy `Function<CaseContext, Map<String, Object>>` constructor is removed — it's a different type signature than the Builder's `Function<Map<String, Object>, WorkerResult>` and creates a silent type mismatch. The `File` constructor is removed — it was never handled in execution. Function workers go through the Builder (`.function(lambda)`).

`WorkerFunctionHolder` is deleted entirely.

### 2. WorkerExecutor — Engine-Level Execution

Abstracts *how* to run a worker function. Independent of any scheduler. Called by scheduler modules, implemented by the engine — follows the `WorkflowExecutor` precedent (`common/internal/`, not `common/spi/`).

Package: `io.casehub.engine.common.internal.executor`

```java
public interface WorkerExecutor {

  Uni<WorkerResult> execute(
      WorkerFunction function,
      Map<String, Object> inputData,
      WorkerContext context,
      int timeoutMs,
      String outputSchema,
      ExecutionMetadata metadata);
}
```

**Supporting type:**

```java
// common/internal/executor/
public record ExecutionMetadata(String workerName, String inputDataHash) {}
```

`ExecutionMetadata` carries lineage fields needed by the flow path (`FlowExecutionRegistry`) but meaningless for sync/agent execution. Keeping them in a separate record avoids polluting `WorkerContext` (which is an API type visible to worker functions) with engine internals.

**Parameter choices:**
- `int timeoutMs` (resolved, non-nullable) — not `ExecutionPolicy`. The adapter resolves the effective timeout via `WorkerExecutionConfig.getEffectiveTimeout(policy.timeoutMs())` before calling the executor. The executor doesn't see `RetryPolicy` — retry is the scheduler's responsibility.
- `String outputSchema` (nullable) — JQ expression for output evaluation. The executor handles output schema uniformly for all worker types. The adapter publishes the evaluated `WorkerResult` — no JQ evaluation in the adapter or completion handler.
- `ExecutionMetadata metadata` — workerName and inputDataHash for flow lineage. Sync/agent paths ignore it.

`WorkerExecutionConfig` moves from `scheduler-quartz` to `common/internal/executor/` — timeout resolution is an engine concern that any scheduler adapter needs.

**Default implementation:** `DefaultWorkerExecutor` in `runtime/internal/executor/`

```java
@ApplicationScoped
public class DefaultWorkerExecutor implements WorkerExecutor {

  @Inject @VirtualThreads ExecutorService virtualThreads;
  @Inject WorkflowExecutor workflowExecutor;

  @Override
  public Uni<WorkerResult> execute(
      WorkerFunction function,
      Map<String, Object> inputData,
      WorkerContext context,
      int timeoutMs,
      String outputSchema,
      ExecutionMetadata metadata) {

    return switch (function) {
      case WorkerFunction.Sync sync -> executeSync(sync.fn(), inputData, context, timeoutMs);
      case WorkerFunction.AgentExec agent -> executeSync(
          agent.agent()::execute, inputData, context, timeoutMs);
      case WorkerFunction.Flow flow -> executeFlow(
          flow.workflow(), inputData, context, timeoutMs, metadata);
    }.map(result -> applyOutputSchema(result, outputSchema));
  }
}
```

**Sync execution** uses Quarkus-managed virtual threads — not `CompletableFuture.supplyAsync()` on ForkJoinPool:

```java
private Uni<WorkerResult> executeSync(
    Function<Map<String, Object>, WorkerResult> fn,
    Map<String, Object> inputData,
    WorkerContext context,
    int timeoutMs) {

  return Uni.createFrom().item(() -> {
        WorkerExecutionContext.set(context);
        try {
          return fn.apply(inputData);
        } finally {
          WorkerExecutionContext.clear();
        }
      })
      .runSubscriptionOn(virtualThreads)
      .ifNoItem().after(Duration.ofMillis(timeoutMs)).fail();
}
```

**Flow execution** delegates to the existing `WorkflowExecutor` interface. The flow path requires lineage metadata (`workerName`, `inputDataHash`) for `FlowExecutionRegistry`, plus `caseId` for event log entries. These come from `ExecutionMetadata` and `WorkerContext.caseId()`:

```java
private Uni<WorkerResult> executeFlow(
    Workflow workflow,
    Map<String, Object> inputData,
    WorkerContext context,
    int timeoutMs,
    ExecutionMetadata metadata) {

  // Flow execution delegates timeout to the workflow runtime — individual steps manage
  // their own timeouts via the workflow definition. No overall execution timeout applied.
  return Uni.createFrom().completionStage(
      () -> workflowExecutor.execute(
          workflow, inputData, context.caseId(),
          metadata.workerName(), metadata.inputDataHash()))
      .map(model -> WorkerResult.of(model.asMap().orElseThrow()));
}
```

**WorkflowExecutor signature change:** `CaseInstance caseInstance` → `UUID caseId`. Three implementors require the update:

| Class | Module | Change |
|-------|--------|--------|
| `FlowWorkerExecutor` | `flow/` | `CaseInstance` → `UUID caseId`; `FlowExecutionRegistry.register()` takes UUID |
| `NoOpWorkflowExecutor` | `runtime/` | Parameter type change only — still returns `failedFuture` |
| `ServerlessWorkflowExecutor` | `runtime/src/test/` | Test impl — parameter type change |

`FlowExecution` record changes from `FlowExecution(CaseInstance caseInstance, ...)` to `FlowExecution(UUID caseId, ...)`.

`CasehubDispatch` requires two changes:
1. Inject `CaseInstanceCache` (existing engine cache — `common/spi/cache/CaseInstanceCache`, implemented by `CaseInstanceCacheImpl` in `runtime/`, 80+ usage sites across the engine)
2. Look up `CaseInstance` via `caseInstanceCache.get(execution.caseId())` for `orchestrator.submit()` and `appendStepLog()`

The instance is guaranteed to be cached: `DefaultWorkerExecutionRecoveryService.loadOrRestoreCaseInstance()` populates the cache before the adapter calls `execute()`.

**Output schema** is applied uniformly after execution via `.map(result -> applyOutputSchema(result, outputSchema))`. This replaces `evalJqAsMap()` which was previously split between the Quartz job (sync) and the workflow completion path (flow).

**Why `@VirtualThreads ExecutorService`:** CaseHub workers are overwhelmingly I/O-bound (LLM API calls, HTTP to external services, database queries). Virtual threads are the right execution model. The current `CompletableFuture.supplyAsync()` uses ForkJoinPool — a platform thread pool sized to CPU cores, wrong for I/O work. Virtual threads provide per-task threads without pooling overhead, with Quarkus managing the lifecycle.

**No `@DefaultBean`:** `DefaultWorkerExecutor` is `@ApplicationScoped` — it's the engine's own implementation, not a consumer-replaceable SPI fallback.

### 3. Retry Backoff — Static Utility, Not SPI

The backoff computation is pure math — it takes `RetryPolicy` and `failureCount` and returns a duration. It has no dependencies and doesn't need CDI injection.

Package: `io.casehub.engine.common.internal.executor`

```java
public final class RetryPolicies {

  public static RetryDecision evaluate(int failureCount, RetryPolicy policy) {
    if (failureCount >= policy.maxAttempts()) {
      return new RetryDecision.Exhaust(
          "Max attempts exceeded: " + failureCount + "/" + policy.maxAttempts());
    }
    long delayMs = computeBackoffDelayMs(failureCount, policy);
    return new RetryDecision.Retry(Duration.ofMillis(delayMs));
  }

  // FIXED, EXPONENTIAL, EXPONENTIAL_WITH_JITTER — moved from
  // QuartzWorkerExecutionJobListener.computeBackoffDelayMs()
}

public sealed interface RetryDecision {
  record Retry(Duration delay) implements RetryDecision {}
  record Exhaust(String reason) implements RetryDecision {}
}
```

`RetryDecision` is a sealed type — `Exhaust(String reason)` carries why the retry was exhausted (useful for logging and observability), which `OptionalLong` cannot express.

The scheduler adapter calls `RetryPolicies.evaluate()` after counting attempts, then acts on the result: `Retry(delay)` → reschedule, `Exhaust(reason)` → publish `WORKER_RETRIES_EXHAUSTED`.

### 4. Unified Fire-and-Forget Execution Model

Currently, sync and flow workers have different execution models from the Quartz adapter's perspective:
- **Sync/Agent:** Quartz thread blocks via `CompletableFuture.get(timeout)`, throws `JobExecutionException` on failure → `jobWasExecuted()` in `JobListener` handles retry
- **Flow:** Quartz thread returns immediately, workflow runs async, failure published to `WORKFLOW_EXECUTION_FAILED` event bus → separate `onWorkflowExecutionFailed()` handler in the `JobListener` handles retry

This redesign converges all paths to fire-and-forget:

1. Quartz job calls `workerExecutor.execute()` — returns `Uni<WorkerResult>`
2. Job **subscribes** to the Uni and **returns immediately** (Quartz marks job complete)
3. On success callback: publishes `WORKER_EXECUTION_FINISHED` with evaluated output
4. On failure callback: persists `WORKER_EXECUTION_FAILED`, evaluates retry via `RetryPolicies.evaluate()`, reschedules or publishes `WORKER_RETRIES_EXHAUSTED`

**What changes:**
- `QuartzWorkerExecutionJobListener.jobWasExecuted()` no longer handles sync worker failures — the subscription callback handles all failures
- `QuartzWorkerExecutionJobListener.onWorkflowExecutionFailed()` (the `@ConsumeEvent` handler for `WORKFLOW_EXECUTION_FAILED`) is removed — flow failures are handled by the same subscription callback
- `WORKFLOW_EXECUTION_FAILED` event bus address is no longer needed — failure handling is unified
- `QuartzWorkerExecutionJobListener` simplifies to `jobToBeExecuted()` only (for `WORKER_EXECUTION_STARTED` events)

**Why convergence is correct:** The Quartz thread's only job is to trigger the execution, not to block waiting for it. Blocking on sync workers was the original Quartz-as-executor anti-pattern. With `WorkerExecutor` running on virtual threads, all worker types complete asynchronously — the adapter subscribes to completion uniformly.

### WorkerContextProvider.buildContext() Timing Contract

Two call sites exist today:
1. `WorkerScheduleEventHandler:108` — at schedule time (return value discarded, gives provider lead time)
2. `QuartzWorkerExecutionJob:152` — at execution time (real call, result passed to worker)

The schedule-time call stays in `WorkerScheduleEventHandler` (runtime module, unaffected). The execution-time call moves to the **adapter's setup phase** — the Quartz adapter calls `buildContext()` and passes the result as the `WorkerContext context` parameter to `WorkerExecutor.execute()`. The executor sets the thread-local (`WorkerExecutionContext.set(context)`) but does not call `buildContext()` itself. Both pre-build and execution-time calls are preserved — the timing contract is unchanged.

---

## What Changes in scheduler-quartz

`QuartzWorkerExecutionJob` shrinks from 305 lines to under 100. It becomes a fire-and-forget adapter:

```java
@Override
public void execute(JobExecutionContext ctx) {
  // 1. Extract metadata from Quartz JobDataMap
  // 2. Load EventLog, CaseInstance, Worker, Capability
  // 3. Build WorkerContext via workerContextProvider.buildContext()
  // 4. Resolve effective timeout via executionConfig.getEffectiveTimeout()
  // 5. Build ExecutionMetadata(workerName, inputDataHash)
  // 6. Subscribe to workerExecutor.execute(function, inputData, context, timeoutMs, outputSchema, metadata)
  //    - success: enrich PlannedAction with workerId/caseId, publish WORKER_EXECUTION_FINISHED
  //    - failure: call quartzRetryService.handleFailure(retryContext)
}
```

No `JobExecutionException` thrown — the job always completes normally. All failure handling is in the subscription callback.

**New `QuartzRetryService`** (`@ApplicationScoped` in `scheduler-quartz`): extracted from `QuartzWorkerExecutionJobListener`. Owns the retry mechanics:
- `handleFailure(WorkerRetryContext)` — persists `WORKER_EXECUTION_FAILED` event, counts attempts, calls `RetryPolicies.evaluate()`, reschedules or publishes `WORKER_RETRIES_EXHAUSTED`
- `resolveRetryPolicy()` — loads `RetryPolicy` from case definition
- `countFailedAttempts()` — queries EventLog for attempt count
- `rescheduleWorker()` — creates new Quartz trigger with backoff delay

Both the job's subscription callback and any remaining listener paths inject `QuartzRetryService`. The listener no longer owns retry logic directly.

`QuartzWorkerExecutionJobListener` simplifies to `jobToBeExecuted()` only — fires `WORKER_EXECUTION_STARTED` event. `jobWasExecuted()` becomes a no-op (all failure handling in the callback). `onWorkflowExecutionFailed()` (`@ConsumeEvent` handler) is removed.

`WORKFLOW_EXECUTION_FAILED` event bus address removed. `handleWorkflowFailure()` in `QuartzWorkerExecutionJob` removed. `WorkflowExecutionFailed` event type removed. Dead code cleaned up in the same branch.

---

## What Changes in the Protocol

Protocol PP-20260531-worker-func-exec updates:

**Before:** "Worker functions MUST use FuncWorkflowBuilder — never raw lambdas."

**After:** Two paths, matched to purpose:

| Worker type | Use | Why |
|-------------|-----|-----|
| Single function (I/O call, computation, CDI service) | `Worker.Builder.function(lambda)` | Direct execution on virtual threads. Full engine policy (retry, timeout, observability, risk classification). No workflow ceremony. |
| Multi-step composition (fetch → process → store) | `FuncWorkflowBuilder` or YAML workflow | Workflow runtime provides step-level tracing, branching, error recovery per step. |
| Agent (LLM-powered) | `Worker.Builder.function(agent)` | Agent metadata preserved for routing and observability. |

The key change: single-function workers are first-class. `FuncWorkflowBuilder` is reserved for genuine multi-step workflows. The protocol stops mandating workflow ceremony for what is conceptually a function call.

---

## Module Placement

| Type | Module | Rationale |
|------|--------|-----------|
| `WorkerFunction` (sealed) | `api/model/` | Part of the Worker model — consumer-visible |
| `WorkerExecutor` (interface) | `common/internal/executor/` | Called by scheduler modules — follows `WorkflowExecutor` precedent in `common/internal/worker/` |
| `ExecutionMetadata` (record) | `common/internal/executor/` | Lineage metadata for flow path — engine-internal |
| `RetryDecision` (sealed) | `common/internal/executor/` | Return type of `RetryPolicies` — engine-internal |
| `RetryPolicies` (static utility) | `common/internal/executor/` | Pure math — backoff calculation from `RetryPolicy` + `failureCount` |
| `WorkerExecutionConfig` | `common/internal/executor/` | Moved from `scheduler-quartz` — timeout resolution is engine-level |
| `DefaultWorkerExecutor` | `runtime/internal/executor/` | Engine's own implementation |
| `QuartzRetryService` | `scheduler-quartz` | Extracted from `QuartzWorkerExecutionJobListener` — Quartz-specific retry mechanics |

`DefaultWorkerExecutor` is `@ApplicationScoped` (not `@DefaultBean`) — it's the engine's own implementation, not a consumer-replaceable fallback. Per protocol PP-20260514.

---

## Migration

### Callers of `Worker.getFunction().getValue()`

10 call sites (4 in `QuartzWorkerExecutionJob`, 6 in tests). All `instanceof` checks become exhaustive `switch` on `WorkerFunction`. Compiler enforces completeness.

### Consumer apps using `FuncWorkflowBuilder` for single-step workers

Optional migration: `workflow().tasks(function(lambda)).build()` → `Worker.Builder.function(lambda)`. Both continue to work — the workflow path goes through `WorkerFunction.Flow`, the function path through `WorkerFunction.Sync`. The protocol recommends the simpler path.

### `WorkerFunctionHolder` removal

Deleted. No deprecation period — this platform has no external consumers.

### `File` overload and legacy constructor removal

`Worker.Builder.function(File)` removed — never handled in execution. `Worker(String, List<Capability>, Function<CaseContext, Map<String, Object>>)` removed — legacy type that doesn't match the Builder's `Function<Map<String, Object>, WorkerResult>`.

### `WORKFLOW_EXECUTION_FAILED` removal

`WORKFLOW_EXECUTION_FAILED` event bus address removed from `EventBusAddresses`. `WorkflowExecutionFailed` event record deleted. `handleWorkflowFailure()` in `QuartzWorkerExecutionJob` deleted. `onWorkflowExecutionFailed()` in `QuartzWorkerExecutionJobListener` deleted. All dead code after the fire-and-forget convergence — removed in the same branch, not deferred.

### `WorkflowExecutor` signature change

`CaseInstance caseInstance` → `UUID caseId`. `FlowExecutionRegistry.register()` and `FlowExecution` record updated correspondingly. `CasehubDispatch` injects `CaseInstanceCache` for `orchestrator.submit()`.

### PlannedAction enrichment

The success callback in the Quartz adapter enriches `PlannedAction` with `workerId` and `caseId` before publishing `WORKER_EXECUTION_FINISHED` — same as today, stays in the adapter.

---

## Tests

**Unit tests:**
- `WorkerFunction` sealed type — exhaustive switch coverage, each variant holds correct type
- `DefaultWorkerExecutor` — sync execution with timeout, agent execution, flow delegation, output schema evaluation
- `RetryPolicies.evaluate()` — FIXED/EXPONENTIAL/EXPONENTIAL_WITH_JITTER backoff, exhaust after max attempts
- `RetryDecision` sealed type coverage

**Integration tests:**
- `QuartzWorkerExecutionJob` thin adapter — subscribes to `WorkerExecutor`, publishes correct events on success/failure
- End-to-end: function worker → virtual thread execution → completion → event log
- End-to-end: function worker → failure → `RetryPolicies.evaluate()` → Quartz reschedule → retry → success
- Workflow fire-and-forget: flow worker → async completion → same callback path as sync

**Contract tests:**
- `WorkerExecutor` interface contract (timeout enforcement, context propagation, output schema evaluation, error handling)

---

## Future Work (tracked issues, not this branch)

- db-scheduler adapter implementing `WorkerExecutionManager` + calling `WorkerExecutor` + `RetryPolicies` — the entire point of this redesign
- Virtual thread pool sizing/monitoring for production workloads
- `WorkerFunction.Remote` variant for HTTP-dispatched external workers (casehub-workers integration)
- Structured concurrency (JEP 462) when Quarkus supports it — replaces manual timeout handling
