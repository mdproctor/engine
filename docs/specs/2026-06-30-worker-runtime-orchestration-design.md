# Hybrid Orchestration — Making the Engine Truly Hybrid

**Epic:** #490 — QuarkMind-driven engine API expansion
**Covers:** #483, #484, #485 (note: #482 already implemented and closed)
**Date:** 2026-06-30

---

## Problem Statement

The engine claims to be a "hybrid choreography+orchestration" system. The choreography half is strong — bindings, ContextChangeTrigger, goals, stage autocomplete. The orchestration half is hollow.

Two separate gaps create this:

**Gap 1 — Plan-level orchestration is empty.** The blackboard module's `PlanningStrategyLoopControl` delegates to `PlanningStrategy.select()`, but `DefaultPlanningStrategy` is a pass-through that returns all eligible bindings. No real deliberative strategy exists. The infrastructure was ported from casehub-poc; the intelligence was not. A developer who wants "run binding A, then B, then C" has no strategy implementation to express that.

**Gap 2 — Workers are isolated from the engine.** Workers are black-box functions: `Map<String,Object> → WorkerResult`. They cannot call other worker functions, spawn sub-cases, or express any control flow beyond "compute and return." A developer who wants to compose three functions with a conditional branch between them must scatter it across binding chains (choreography pretending to be orchestration) or bring in Serverless Workflow (overkill for simple composition).

These are different levels of the same symptom. Gap 1 is about **which work fires** (plan decisions). Gap 2 is about **what a unit of work can do** (execution capabilities).

---

## Architectural Model — Four Tiers of Orchestration

The engine supports orchestration at four tiers. Each tier has different durability, complexity, and observability properties. The consumer picks the lightest tier that meets their needs.

| Tier | Mechanism | Decides | Durability | Build Now? |
|------|-----------|---------|-----------|------------|
| 1. Execution | WorkerRuntime | What happens inside a worker | None (outer Quartz retry only) | Yes |
| 2. Simple plan | SequentialPlanningStrategy | Which binding fires next (linear) | Natural (PlanItem state) | Yes |
| 3. Complex plan | WorkflowPlanningStrategy | Which binding fires next (branching, compensation) | Full (SW state) | Future |
| 4. Multi-case | blocks patterns | How cases coordinate | Depends on driver | Future |

### Tier relationships

```
Tier 4: blocks (Supervisor, Voting, HTN)
  └── coordinates multiple cases via CaseHubRuntime

Tier 3: WorkflowPlanningStrategy
  └── SW workflow controls which bindings fire (durable, compensation)

Tier 2: SequentialPlanningStrategy
  └── selects one binding at a time (lightweight, natural durability)

Tier 1: WorkerRuntime
  └── in-worker function composition (direct calls, full Java control flow)
```

Tiers 2 and 3 sit at the same architectural seam — `PlanningStrategy.select()`:

```
LoopControl
├── ChoreographyLoopControl         → fires all eligible (pure choreography)
└── PlanningStrategyLoopControl     → delegates to PlanningStrategy
      ├── DefaultPlanningStrategy          → pass-through (current, to be replaced)
      ├── SequentialPlanningStrategy       → one at a time, lightweight
      └── WorkflowPlanningStrategy        → SW-backed, durable, compensation (future)
```

WorkflowPlanningStrategy preserves this symmetry even though its implementation may internally be a FlowWorker started at stage entry. The consumer sees a PlanningStrategy — the implementation detail is irrelevant.

### Tier 1 vs Tier 2 — when to use which

| Need | Tier 1 (WorkerRuntime) | Tier 2 (SequentialPlanningStrategy) |
|------|----------------------|-------------------------------------|
| Steps are implementation details | Yes | No — each step is a PlanItem |
| Per-step retry via Quartz | No | Yes |
| Per-step event log | No | Yes |
| Per-step timeout | No | Yes (ExecutionPolicy per worker) |
| Stage autocomplete | No (one PlanItem) | Yes (stage sees each step complete) |
| Conditional branching | Yes (Java if/else) | No (strategy only filters eligible) |
| Loops | Yes (Java while) | No |
| Survives restart mid-sequence | No (retries from scratch) | Yes (re-derives position from PlanItem state) |
| Latency | Minimal (direct function call) | Higher (Quartz + event bus per step) |

**QuarkMind game tick** — four plugin steps in <500ms, no per-step audit needed → Tier 1.
**Clinical case workflow** — multi-day stages, each step independently auditable → Tier 2.

---

## Tier 1: WorkerRuntime

### The orchestration surface

A handle available to running workers that provides engine interaction. Scoped to the current case — each worker invocation gets its own instance.

```java
package io.casehub.api.engine;

public interface WorkerRuntime {

    UUID caseId();

    WorkerResult execute(WorkerFunction function, Map<String, Object> input);

    WorkerResult execute(String workerName, Map<String, Object> input);

    UUID spawnCase(String caseType, Map<String, Object> input);

    CaseContext awaitCase(UUID childCaseId, Duration timeout);

    CaseContext spawnAndAwaitCase(
        String caseType, Map<String, Object> input, Duration timeout);
}
```

**`execute(function, input)`** — runs a WorkerFunction synchronously on the current virtual thread. The orchestrating worker gets the result immediately and decides what to do next. No Quartz scheduling, no event bus dispatch. Direct function call. Supports `WorkerFunction.Sync` and `AgentWorkerFunction`. `FlowWorkerFunction` is not supported — flow workers have their own handler infrastructure and durability concerns that belong at Tier 3, not Tier 1.

Execution semantics:
- Saves/restores the parent's `WorkerExecutionContext` (stack semantics for nested orchestration)
- Sets a fresh `WorkerContext` for the inner function (inherits caseId, channels from parent)
- Timeout is NOT enforced per-step — the outermost worker's timeout governs the entire orchestration
- Output schema evaluation is NOT applied per-step — owned by `DefaultWorkerExecutor` on the outermost result
- **Exception contract:** `execute()` never throws. If the inner function throws a runtime exception, `execute()` catches it and returns `WorkerResult.failed(exception.getMessage())`. Callers can always assume a non-null `WorkerResult` return — no try/catch needed around `execute()` calls. This is consistent with the `sequence()` combinator, which checks `result.outcome() instanceof WorkerOutcome.Success` without exception handling.

**What inner executions bypass:** Tier 1 inner calls are direct function invocations that produce zero audit trail. Specifically, inner steps do NOT generate: WORKER_SCHEDULED or WORKER_EXECUTION_COMPLETED event log entries, CaseLedgerEntry records (EU AI Act Art.12 audit), WorkerDecisionEvent (trust scoring input), WorkerStatusListener notifications, or worker provisioning. The outer PlanItem captures the orchestrating worker's aggregate result. Consumers in regulated environments (EU AI Act Art.12, GDPR Art.22) where per-step auditability is required should use Tier 2 (SequentialPlanningStrategy), where each step is a separate PlanItem with full event log, ledger entries, and trust scoring.

**`execute(workerName, input)`** — resolves the named worker's function by looking up the `Worker` by name in the case's `CaseDefinition` (via `CaseDefinitionRegistry`) and extracting its `function()`. Executes it with the same direct-call semantics as `execute(function, input)`. Throws `IllegalArgumentException` if the worker name is not found in the definition. This decouples orchestrating workers from function implementation details — they reference steps by name rather than holding function references. (`WorkerFunctionProviderRegistry` is a YAML-node-to-function construction-time factory — it cannot resolve by name at runtime.)

**`spawnCase(caseType, input)`** — starts a child case via `CaseHubRuntime.startCase()`. Returns the child's UUID immediately. The child runs through normal choreographic flow. Does NOT create a PlanItem — this is a runtime-scoped operation. Resolution: `caseType` is matched by the definition's `name` field (e.g., `"economy-expansion"`). `DefaultWorkerRuntime` scans `CaseDefinitionRegistry` for a definition with that name. If zero matches, throws `IllegalArgumentException`. If multiple matches exist (same name across different namespaces), throws `IllegalArgumentException` — callers in multi-namespace environments must use a qualified `namespace:name` form, which the runtime splits on `:` to resolve unambiguously. Version: the latest registered version is used.

**`awaitCase(childCaseId, timeout)`** — blocks until the child case reaches a terminal state (COMPLETED, FAULTED, CANCELLED). Returns the child's final CaseContext. Implementation: registers a listener on `CASE_STATUS_CHANGED` events, uses a `CompletableFuture` to bridge to the blocking caller. Throws `SettlementTimeoutException` on timeout.

Timeout cleanup: when timeout fires, the event bus listener is deregistered, the future is completed exceptionally with `SettlementTimeoutException`, and late case completion events are silently ignored (no side effects). The listener holds a `WeakReference` to the future to prevent GC leaks if the calling thread is interrupted before timeout.

**`spawnAndAwaitCase(caseType, input, timeout)`** — convenience: `spawnCase()` + `awaitCase()`.

### What WorkerRuntime deliberately excludes

**`signal(path, value)`** — a worker signaling its own case's context during execution creates recursion: context change → binding evaluation → potentially re-dispatches the same or overlapping workers. Workers write output; the engine applies it. Clean separation.

**`eventLog()`** — workers should decide from their input data, not by reading system internals.

### Access pattern

WorkerRuntime is available through the existing `WorkerExecutionContext` thread-local. No changes to `casehub-worker-api`.

```java
public final class WorkerExecutionContext {

    private static final ThreadLocal<WorkerContext> CONTEXT_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<WorkerRuntime> RUNTIME_HOLDER = new ThreadLocal<>();

    public static WorkerContext current() { return CONTEXT_HOLDER.get(); }
    public static WorkerRuntime currentRuntime() { return RUNTIME_HOLDER.get(); }

    public static void set(WorkerContext context) { CONTEXT_HOLDER.set(context); }
    public static void setRuntime(WorkerRuntime runtime) { RUNTIME_HOLDER.set(runtime); }

    public static void clear() {
        CONTEXT_HOLDER.remove();
        RUNTIME_HOLDER.remove();
    }
}
```

`SyncAgentWorkerFunctionHandler` sets both `WorkerContext` AND `WorkerRuntime` before invoking any function. Workers that need orchestration call `WorkerExecutionContext.currentRuntime()`. Workers that don't need it ignore it. Zero overhead for leaf workers. Zero changes to casehub-worker-api.

### Module placement

| Type | Module | Rationale |
|------|--------|-----------|
| `WorkerRuntime` (interface) | `api/engine/` | Consumer-facing API, alongside `CaseHubRuntime`. Does not reference `CaseInstance`. |
| `WorkerRuntimeFactory` | `runtime/internal/executor/` | `@ApplicationScoped` CDI bean. Injected into `SyncAgentWorkerFunctionHandler`. Creates per-invocation `DefaultWorkerRuntime` instances scoped to the current case. Dependencies: `CaseHubRuntime`, `CaseDefinitionRegistry`, Vert.x event bus. |
| `DefaultWorkerRuntime` (impl) | `runtime/internal/executor/` | Per-invocation instance, NOT a CDI bean. Created by `WorkerRuntimeFactory` with the current caseId. `caseId()` is a simple field access. |
| `WorkerExecutionContext` changes | `api/model/` | Already there. Adding one ThreadLocal. |
| `SettlementTimeoutException` | `api/engine/` | Unchecked exception. |

### Orchestration patterns enabled

**Sequential execution:**
```java
var rt = WorkerExecutionContext.currentRuntime();
var a = rt.execute(fnA, input);
var b = rt.execute(fnB, merge(input, a.output()));
return WorkerResult.of(b.output());
```

**Conditional branching:**
```java
var scout = rt.execute(scoutFn, input);
if (threatDetected(scout.output())) {
    return rt.execute(defenseFn, scout.output());
} else {
    return rt.execute(expansionFn, scout.output());
}
```

**Loop until condition:**
```java
var state = input;
while (!goalReached(state)) {
    state = rt.execute(refineFn, state).output();
}
return WorkerResult.of(state);
```

**Sub-case with result:**
```java
var childCtx = rt.spawnAndAwaitCase("economy-expansion", input, Duration.ofMinutes(5));
return WorkerResult.of(Map.of("expansion.status", childCtx.get("status")));
```

### Optional convenience combinator

```java
public final class WorkerFunctions {

    public static WorkerFunction.Sync sequence(WorkerFunction... steps) {
        return new WorkerFunction.Sync(input -> {
            var rt = WorkerExecutionContext.currentRuntime();
            var acc = input;
            for (var step : steps) {
                var result = rt.execute(step, acc);
                if (!(result.outcome() instanceof WorkerOutcome.Success)) return result;
                acc = merge(acc, result.output());
            }
            return WorkerResult.of(acc);
        });
    }
}
```

**`merge(base, overlay)`** — shallow map merge, last-write-wins. `new HashMap<>(base)` then `putAll(overlay)`. Lives alongside `sequence()` in `WorkerFunctions`.

Lives in `api/model/`. Returns a plain `WorkerFunction.Sync`. No new handler, no new variant.

### YAML support

A `sequence:` key in YAML worker definitions, mapped by `CaseDefinitionYamlMapper` to `WorkerFunctions.sequence()`:

```yaml
workers:
  - name: tick-orchestrator
    capabilities: [game-tick]
    sequence:
      - scouting
      - strategy
      - tactics
      - economics
```

Each step name references another worker in the same case definition. The mapper resolves the worker's function and passes it to `WorkerFunctions.sequence()`.

---

## Tier 2: SequentialPlanningStrategy

### Purpose

A PlanningStrategy that selects one binding at a time from the eligible set, in a declared order. When that binding's worker completes, context changes, re-evaluation fires, and the strategy selects the next binding.

### Why this is durable without any new infrastructure

Each step is a separate PlanItem dispatched through the normal engine pipeline:
- Quartz schedules the worker → per-step retry via `QuartzRetryService`
- `ExecutionPolicy` per worker → per-step timeout
- PlanItem lifecycle tracking → per-step event log, stage autocomplete
- Worker outcome handling → per-step REROUTE/FAULT

If the JVM crashes after step 2 of 4, on restart:
- CasePlanModel shows steps 1-2 as COMPLETED
- Context change triggers re-evaluation
- Strategy sees what's done, selects step 3
- The sequence resumes from where it left off

No new durability mechanism. The existing PlanItem + Quartz stack provides it naturally.

### Interface

Already exists — `PlanningStrategy.select()`:

```java
public interface PlanningStrategy {
    String getId();
    String getName();
    Uni<List<Binding>> select(
        CasePlanModel plan, PlanExecutionContext context, List<Binding> eligible);
}
```

### Implementation sketch

```java
@ApplicationScoped
public class SequentialPlanningStrategy implements PlanningStrategy {

    @Override
    public Uni<List<Binding>> select(
            CasePlanModel plan, PlanExecutionContext context, List<Binding> eligible) {

        // Find the first eligible binding whose PlanItem is not yet COMPLETED.
        // Note: PlanningStrategyLoopControl pre-creates PlanItems for all eligible
        // bindings (status PENDING) before calling select(). This strategy returns
        // the first PENDING binding; filterToDispatchable() downstream confirms
        // the PENDING status before actual dispatch.
        for (Binding binding : eligible) {
            Optional<PlanItem> item = plan.getPlanItemByBindingName(binding.getName());

            if (item.isEmpty()) {
                // Defensive: not expected (LoopControl pre-creates), but safe to dispatch
                return Uni.createFrom().item(List.of(binding));
            }

            PlanItemStatus status = item.get().getStatus();

            if (status == PlanItemStatus.COMPLETED) {
                // Successfully completed — advance to next step
                continue;
            }

            if (status.isTerminal()) {
                // FAULTED, REJECTED, OBSOLETE, CANCELLED — halt the sequence.
                // Do not advance past a failed step. Return empty so stage
                // autocomplete or case-level fault handling takes over.
                return Uni.createFrom().item(List.of());
            }

            if (status == PlanItemStatus.PENDING) {
                // Ready for dispatch — this is the next step to fire.
                // filterToDispatchable() downstream confirms PENDING before dispatch.
                return Uni.createFrom().item(List.of(binding));
            }

            // Active (RUNNING, DELEGATED, SUSPENDED) — wait for it
            return Uni.createFrom().item(List.of());
        }

        // All steps COMPLETED
        return Uni.createFrom().item(List.of());
    }
}
```

The ordering comes from the binding declaration order in the CaseDefinition. The strategy iterates eligible bindings in order, finds the first one that is not yet COMPLETED. If a step's PlanItem is PENDING, return it (it's the next step to dispatch — `filterToDispatchable()` downstream confirms the PENDING status). If it's active (RUNNING, DELEGATED, SUSPENDED), wait. If it's a non-success terminal (FAULTED, REJECTED, OBSOLETE, CANCELLED), halt the sequence — do not advance past a failed step. If all steps are COMPLETED, return empty (stage autocomplete takes over).

### Location

`blackboard/src/main/java/io/casehub/blackboard/control/SequentialPlanningStrategy.java`

### Activation

Case definitions opt in by referencing the strategy:

```java
CaseDefinition.builder()
    .planningStrategy("sequential")
    // ...
    .build();
```

**Required changes to existing types:**

1. **`CaseDefinition`** — add `planningStrategy` field (String, nullable). Builder gets `planningStrategy(String id)` method. Null means "default" (backward compatible). `CaseDefinitionYamlMapper` maps `planningStrategy:` YAML key.

2. **`PlanningStrategyLoopControl`** — currently injects a single `PlanningStrategy` via CDI constructor. Change to inject `Instance<PlanningStrategy>` (CDI `jakarta.enterprise.inject.Instance`), iterate to build a `Map<String, PlanningStrategy>` keyed by `getId()`. On each `select()` call, look up the strategy ID from `ctx.definition().getPlanningStrategy()`. Fall back to `"default"` when null.

3. **CDI ambiguity resolution** — `DefaultPlanningStrategy` remains `@ApplicationScoped`. `SequentialPlanningStrategy` is also `@ApplicationScoped`. No ambiguity because `PlanningStrategyLoopControl` injects `Instance<PlanningStrategy>` (all implementations), not a single `PlanningStrategy`.

---

## Tier 3: WorkflowPlanningStrategy (Future — Design Acknowledged)

A PlanningStrategy backed by a Serverless Workflow definition. The SW engine controls which bindings fire, with full durability, compensation, conditional branching, and parallel joins. Implements the same `PlanningStrategy.select()` interface as SequentialPlanningStrategy.

Implementation may internally be a FlowWorker started at stage entry. The consumer sees a PlanningStrategy — the symmetry of the PlanningStrategy seam is preserved.

### Semantic difference from FlowWorker

| Dimension | FlowWorker | WorkflowPlanningStrategy |
|-----------|-----------|-------------------------|
| Who owns execution | SW workflow (direct dispatch) | Engine pipeline (PlanItems, Quartz, event log) |
| Per-step PlanItem | No (one PlanItem for entire flow) | Yes (each step is a binding → PlanItem) |
| Target types | Workers only (via casehub:dispatch) | All binding targets (capability, subCase, humanTask) |
| Scope | Single worker's lifetime | Stage or case lifetime |

Both use the same SW engine. The distinction is whether SW controls execution (FlowWorker) or just decisions (WorkflowPlanningStrategy). This is a future design decision — tiers 1-2 built now are compatible with either path.

**Not built in #490.** Acknowledged here so tiers 1-2 don't close off the path.

---

## Cross-Cutting: signalAndAwait()

### Bulk signal (new)

Today `signal(UUID, String, Object)` updates one context path. Bulk signal updates multiple paths atomically and fires a single `CONTEXT_CHANGED`:

```java
// CaseHubRuntime addition (default method per SPI evolution protocol)
default CompletionStage<Void> signal(UUID caseId, Map<String, Object> updates) {
    throw new UnsupportedOperationException();
}
```

Implementation: `CaseContext.setAll(updates)` → single `CONTEXT_UPDATED` event log entry → single `CONTEXT_CHANGED` event.

### signalAndAwait (new)

```java
default CompletionStage<CaseContext> signalAndAwait(
        UUID caseId, Map<String, Object> updates, Duration timeout) {
    throw new UnsupportedOperationException();
}

default CaseContext signalAndAwaitSync(
        UUID caseId, Map<String, Object> updates, Duration timeout) {
    return signalAndAwait(caseId, updates, timeout).toCompletableFuture().join();
}
```

### Settlement model

signalAndAwait needs to know when all work triggered by this specific signal has completed.

**Implementation — signal generation tagging:**

1. `signalAndAwait()` generates a unique `signalId` (UUID)
2. Attaches it to the `CaseContextChangedEvent`
3. `CaseContextChangedEventHandler` threads `signalId` through to all dispatched `WorkerScheduleEvent`s
4. A `SignalSettlementTracker` (per-case, in-memory, `@ApplicationScoped`) tracks:
   - `signalId → expectedCount` (incremented during binding dispatch)
   - `signalId → completedCount` (incremented on WORKER_EXECUTION_FINISHED)
   - `signalId → CompletableFuture<CaseContext>` (resolved when counts match)
5. After `CaseContextChangedEventHandler` finishes, it marks the signal as "fully dispatched"
6. When `completedCount == expectedCount AND fullyDispatched` → future resolves and the entry is removed from the tracker. **Both `markFullyDispatched()` and `recordCompletion()` are resolution trigger points** — each must atomically check the dual condition and resolve the future if both are satisfied. This handles the race where all workers complete before dispatch finishes (completion fires first) and the normal path where dispatch finishes first. **Cleanup:** entries are removed from the `ConcurrentHashMap` when the future resolves (success) or when the timeout fires (the caller's `signalAndAwait()` implementation completes the future exceptionally and removes the entry). No entry survives past resolution — the tracker's memory footprint is bounded by the number of in-flight signals, not cumulative calls.

**Interaction with SequentialPlanningStrategy:** If a signal triggers a sequential strategy, only the FIRST step is dispatched (expectedCount=1). That step's completion triggers re-evaluation, which dispatches the next step — but that's a new context change cycle, not part of the original signal's generation. signalAndAwait() resolves after step 1. If the caller wants to await the entire sequence, they need to await case quiescence or use an orchestrating worker (tier 1) instead.

**Scope:** Only CapabilityTarget bindings count toward settlement. SubCaseTarget and HumanTaskTarget have unbounded completion times and are excluded. If no bindings fire, the future resolves immediately.

**Single-node limitation:** SignalSettlementTracker is per-JVM (ConcurrentHashMap). Same constraint as CaseInstanceCache.

### SignalSettlementTracker

```java
// runtime/internal/engine/SignalSettlementTracker.java
@ApplicationScoped
class SignalSettlementTracker {

    UUID registerSignal(UUID caseId);
    void incrementExpected(UUID signalId);
    void markFullyDispatched(UUID signalId);
    void recordCompletion(UUID signalId);
    CompletableFuture<CaseContext> getFuture(UUID signalId);
}
```

Integration points:
- `CaseHubReactor.signalAndAwait()` → `tracker.registerSignal()`
- `CaseContextChangedEventHandler.scheduleWorker()` → `tracker.incrementExpected(signalId)` immediately before/after the `eventBus.publish(WORKER_SCHEDULE, ...)` call. Must be in `scheduleWorker()`, not `publishWorkerSchedule()` — the latter has multiple exit paths (no workers, no candidates, all excluded, unresolvable, escalation) that do not produce a `WorkerScheduleEvent`. Calling `incrementExpected()` at entry to `publishWorkerSchedule()` would over-count, causing the settlement future to never resolve.
- `CaseContextChangedEventHandler` completion → `tracker.markFullyDispatched(signalId)`
- `WorkflowExecutionCompletedHandler` → `tracker.recordCompletion(signalId)` for workers carrying a signalId

The signalId threads through: `CaseContextChangedEvent` → `WorkerScheduleEvent` → Quartz job data → `WorkflowExecutionCompleted`.

---

## Changes to Existing Types

### New types

| Type | Module | Kind |
|------|--------|------|
| `WorkerRuntime` | `api/engine/` | Interface (6 methods) |
| `WorkerRuntimeFactory` | `runtime/internal/executor/` | @ApplicationScoped CDI bean |
| `DefaultWorkerRuntime` | `runtime/internal/executor/` | Per-invocation instance (not CDI) |
| `SequentialPlanningStrategy` | `blackboard/control/` | @ApplicationScoped CDI bean |
| `SignalSettlementTracker` | `runtime/internal/engine/` | @ApplicationScoped CDI bean |
| `SettlementTimeoutException` | `api/engine/` | Unchecked exception |
| `WorkerFunctions` | `api/model/` | Static utility (optional) |

### Modified types

| Type | Change |
|------|--------|
| `CaseHubRuntime` | Add `signal(UUID, Map)`, `signalAndAwait()`, `signalAndAwaitSync()` as default methods |
| `WorkerExecutionContext` | Add `RUNTIME_HOLDER` ThreadLocal, `currentRuntime()`, `setRuntime()` |
| `SyncAgentWorkerFunctionHandler` | Set WorkerRuntime before function invocation |
| `CaseContextChangedEvent` | Add optional `signalId` field (nullable UUID) |
| `WorkerScheduleEvent` | Add optional `signalId` field |
| `WorkflowExecutionCompleted` | Add optional `signalId` field |
| `CaseContextChangedEventHandler` | Thread signalId, call tracker on dispatch |
| `WorkflowExecutionCompletedHandler` | Call tracker on completion when signalId present |
| `CaseHubRuntimeImpl` | Implement bulk signal and signalAndAwait |
| `CaseHubReactor` | Add bulk signal and signalAndAwait methods |
| `CaseDefinition` | Add `planningStrategy` field (String, nullable) and Builder method |
| `PlanningStrategyLoopControl` | Change from single `PlanningStrategy` injection to `Instance<PlanningStrategy>` with ID-based lookup |
| `CaseDefinitionYamlMapper` | Map `planningStrategy:` YAML key and `sequence:` worker key |

---

## What Is NOT Built

| Concept | Status | Rationale |
|---------|--------|-----------|
| `SequenceWorkerFunction` type | Not needed | WorkerRuntime.execute() in a loop replaces it |
| `SequenceWorkerFunctionHandler` | Not needed | No new WorkerFunction variant to handle |
| `Step`, `StepFailurePolicy` | Not needed | Standard Java control flow replaces them |
| New `WorkerFunction` variants | Not needed | Thread-local access avoids foundation-tier changes |
| Changes to `casehub-worker-api` | Not needed | WorkerRuntime accessed via WorkerExecutionContext |
| `WorkflowPlanningStrategy` | Future | Acknowledged in design, not built in #490 |
| blocks integration | Future | Multi-case coordination is a separate concern |

## Issue Mapping

| Original Issue | Disposition |
|----------------|-------------|
| #485 WorkerRuntime | Implement as Tier 1 (different scope than original proposal). Original issue proposed `signal()` and `eventLog()` on WorkerRuntime — both deliberately excluded. `signal()` creates recursion (context change → binding evaluation → re-dispatch). `eventLog()` is excluded because workers should decide from their input data, not system internals; cross-case event inspection is available via `CaseHubRuntime.eventLog(UUID)` for callers that have runtime access. |
| #484 SequenceWorker | Addressed by Tier 1 (WorkerRuntime pattern) + Tier 2 (SequentialPlanningStrategy). No dedicated type. Optional `WorkerFunctions.sequence()` combinator. **Divergence from original acceptance criteria:** #484 proposed `FAIL_FAST`/`CONTINUE_ON_FAILURE` step failure policies and per-step span events (`SEQUENCE_STEP_STARTED`, etc.). This spec replaces both with standard Java control flow at Tier 1 (where steps are implementation details, not observable plan items) and existing PlanItem lifecycle at Tier 2 (where each step IS a PlanItem with full event log, retry, and timeout). Per-step observability belongs at Tier 2, not Tier 1 — the tier separation is the design answer to this. |
| #483 signalAndAwait | Implement: bulk `signal(Map)` + `signalAndAwait()` + `SignalSettlementTracker` |

---

## Testing Strategy

### Tier 1 — WorkerRuntime

- `execute()` with Sync function — verifies result passing
- `execute()` with nested orchestration — verifies WorkerExecutionContext stack semantics
- `execute()` with failed inner function — verifies outcome propagation
- `execute()` with throwing inner function — verifies exception wrapping into WorkerResult.failed()
- `spawnAndAwaitCase()` — verifies child case lifecycle
- `awaitCase()` timeout — verifies SettlementTimeoutException
- Sequential pattern: 4-step sequence verifies order and result accumulation
- Conditional pattern: different paths based on intermediate results
- Mixed: sequence with sub-case step

### Tier 2 — SequentialPlanningStrategy

- 3 bindings, strategy selects one at a time, verifies ordering
- Step FAULTED — verifies sequence halts (does not advance past failed step)
- Step failure with REROUTE — verifies retry then next step
- JVM restart simulation — verifies sequence resumes from PlanItem state
- Stage autocomplete — verifies stage completes when all steps terminal
- Interaction with non-sequential bindings (choreographic bindings alongside strategy-controlled ones)

### signalAndAwait

- Bulk signal triggers one binding → resolves when worker completes
- Bulk signal triggers multiple bindings → resolves when all complete
- Bulk signal triggers no bindings → resolves immediately
- Timeout → throws SettlementTimeoutException
- Cascading (worker output triggers more bindings) → original signal settles without waiting for cascade
- Interaction with SequentialPlanningStrategy → settles after first step only

---

## Platform Coherence

- **Module tier structure**: WorkerRuntime interface is pure Java (tier 1 api). SequentialPlanningStrategy in blackboard. Implementation in runtime. Correct per `module-tier-structure` protocol.
- **SPI evolution**: New CaseHubRuntime methods are default methods throwing UnsupportedOperationException. Contract tests added. Per `spi-evolution-default-methods` protocol.
- **SPI placement**: WorkerRuntime does not reference CaseInstance → lives in api/, not common/spi/. Per `spi-placement-caseinstance-goes-in-common` protocol.
- **Flow module isolation**: No changes to casehub-engine-flow. FlowWorkerFunction continues unchanged. Per `casehub-engine-flow-module-isolation` protocol.
- **Worker-api stability**: Zero changes to casehub-worker-api.
- **PlanningStrategy seam**: SequentialPlanningStrategy uses the same `select()` interface as DefaultPlanningStrategy. WorkflowPlanningStrategy (future) will slot in as a peer. No interface changes needed.
- **Blocking-reactive parity**: WorkerRuntime is blocking (virtual threads). No ReactiveWorkerRuntime needed — orchestrating workers are inherently synchronous. `awaitCase()` blocks on a `CompletableFuture.join()` waiting for child case completion, which IS blocking I/O in a practical sense. This is justified by Loom (virtual threads make the blocking cheap), not by absence of blocking. A Uni return type is not required because the blocking is the design intent — callers use imperative sequential flow.
