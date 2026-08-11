# Design: #461 Follow-ups — WorkerFunction.None, Precise Routing, Recovery, Trigger Audit

**Date:** 2026-06-29
**Issues:** #586, #587, #588, #589
**Branch:** issue-586-461-followups

## Problem

The composite WorkerExecutionManager (#461) introduced multi-backend routing but left four
deferred concerns:

1. External workers carry placeholder `WorkerFunction.Sync` functions that mask misconfiguration
   — Quartz catches the capability and silently returns wrong output (#586)
2. Quartz `supports()` returns `true` unconditionally — no mechanism for a backend to express
   function-type constraints (#587)
3. `QuartzWorkerExecutionManager.onStart()` blocks for 30 seconds during startup recovery,
   and the composite routing may run before all backends are initialized (#588)
4. Four trigger scheduling methods on `QuartzWorkerExecutionManager` have zero callers (#589)

The root cause is that `WorkerFunction` has no type for "no in-process function" and the
routing predicate `supports()` has no mechanism for function-type gating.

## Design

### A. WorkerFunction.None — foundation tier model fix (#586)

Add `None` as an inner record of `WorkerFunction` in `casehub-worker-api`:

```java
public interface WorkerFunction {
    record Sync(Function<Map<String, Object>, WorkerResult> fn) implements WorkerFunction {}
    record None() implements WorkerFunction {}

    WorkerFunction NONE = new None();
}
```

`None` models external workers that have no in-process execution logic. `Worker.function()`
stays non-null — `None` IS a valid function type, it means "dispatch is handled by an external
backend."

`NONE` is a singleton constant — idiomatic for stateless marker types. All usage sites
reference the constant, not `new None()`. Java records provide structural equality, so
`WorkerFunction.NONE.equals(new None())` is `true`, but the singleton avoids unnecessary
allocations and communicates intent.

**Worker.Builder** gains a convenience method:

```java
public Builder noFunction() {
    this.function = WorkerFunction.NONE;
    return this;
}
```

**CaseDefinitionYamlMapper** (line 273) changes from:

```java
function = new WorkerFunction.Sync(input -> WorkerResult.of(input));
```

to:

```java
function = WorkerFunction.NONE;
```

This is the only production site that creates placeholder functions. The fallback triggers when
`providerRegistry.createFunction()` returns null AND `sw.getAgent()` is null — i.e., the worker
has no agent block, no flow block, no provider-handled function. These are external workers.

**Cross-repo:** Requires publishing a new `casehub-worker-api` SNAPSHOT. No changes to the
workers repo — external backends don't interact with `WorkerFunction` at the SPI layer.

### B. canExecute(WorkerFunction) — additive routing predicate (#587)

Add a default method to `WorkerExecutionManager`:

```java
default boolean canExecute(WorkerFunction function) {
    return true;
}
```

This follows the SPI evolution protocol: additive default method with safe no-op return.
Existing implementations (all 5 external backends) inherit `true` without changes.

**Quartz overrides** with positive handler delegation:

```java
@Inject Instance<WorkerFunctionHandler> functionHandlers;

@Override
public boolean canExecute(WorkerFunction function) {
    for (WorkerFunctionHandler handler : functionHandlers) {
        if (handler.supports(function)) return true;
    }
    return false;
}
```

This delegates to the actual in-process handler chain — the authoritative source for "can the
execution pipeline handle this function type?" The check is positive (returns `true` only when
a handler explicitly supports the function) and fail-safe (unknown `WorkerFunction` types
without a registered handler are correctly rejected).

Verified handler behavior:
- `SyncAgentWorkerFunctionHandler.supports()`: positive check —
  `function instanceof WorkerFunction.Sync || function instanceof AgentWorkerFunction`.
  Rejects `None`.
- `FlowWorkerFunctionHandler.supports()`: positive check —
  `function instanceof FlowWorkerFunction`. Rejects `None`.

**CompositeWorkerExecutionManager overrides** to delegate to backends:

```java
@Override
public boolean canExecute(WorkerFunction function) {
    for (WorkerExecutionManager backend : backends) {
        if (backend.canExecute(function)) return true;
    }
    return false;
}
```

This mirrors the existing `supports()` delegation pattern. Without this override, the
composite inherits the default `return true`, which would be incorrect — `canExecute(None)`
on the composite should return `false` when no backend can handle it.

**FirstSupportedRoutingStrategy** adds the `canExecute()` check:

```java
@Override
public Optional<WorkerExecutionManager> select(
    List<WorkerExecutionManager> candidates,
    Worker worker,
    Capability capability,
    String tenancyId) {
  for (WorkerExecutionManager candidate : candidates) {
    if (candidate.supports(capability.name(), tenancyId)
        && candidate.canExecute(worker.function())) {
      return Optional.of(candidate);
    }
  }
  return Optional.empty();
}
```

**Two-layer defense:**

| Layer | Guard | Fires when | Verification |
|-------|-------|------------|--------------|
| Routing | `canExecute(None) → false` | At submit — prevents Quartz job creation | Quartz delegates to handler chain; no handler's `supports()` returns `true` for `None` |
| Execution | `WorkerFunctionHandler.supports(None) → false` | Defense-in-depth — if routing is bypassed | `SyncAgentWorkerFunctionHandler`: positive `instanceof Sync \|\| instanceof AgentWorkerFunction` — rejects `None`. `FlowWorkerFunctionHandler`: positive `instanceof FlowWorkerFunction` — rejects `None`. |

`DefaultWorkerExecutor` already throws `UnsupportedOperationException` when no handler supports
the function. With `canExecute()`, this becomes unreachable in normal operation for `None`
functions — it remains as defense-in-depth against custom routing strategies that omit the
`canExecute()` check.

**`WorkerExecutionRoutingStrategy` Javadoc correction:** The current Javadoc (lines 26–29)
incorrectly states that `CompositeWorkerExecutionManager` pre-filters backends via `supports()`
before passing them to the strategy. In reality, the composite passes ALL backends and the
strategy is responsible for calling `supports()` and `canExecute()` on each candidate. Updated
Javadoc:

> "Called by `CompositeWorkerExecutionManager` with all discovered backends sorted by priority
> (highest first). The strategy is responsible for selecting the appropriate backend, typically
> by checking `supports()` and `canExecute()` on each candidate. Returns the selected manager,
> or `Optional.empty()` if none are suitable."

**Why not change `supports()` signature:** `supports(String, String)` serves capability+tenant
routing — information external backends need. Function-type gating is a separate concern that
only in-process backends care about. Merging them into one method would leak domain objects
(`Worker`, `Capability`) into the scheduler tier. Two methods, each with a single responsibility,
is the cleaner decomposition. This also keeps the recovery path unaffected (see §C).

**Design decision: `canExecute()` on WEM vs `supports()` on `WorkerExecutor` (#587)**

Issue #587 proposed `boolean supports(WorkerFunction)` on `WorkerExecutor`, with Quartz
delegating: `workerExecutor.supports(function)`. This spec chose `canExecute(WorkerFunction)`
on `WorkerExecutionManager` instead, with Quartz's implementation delegating to the handler
chain via `Instance<WorkerFunctionHandler>`. The trade-offs:

| Concern | #587 (on WorkerExecutor) | This spec (on WEM) |
|---------|--------------------------|---------------------|
| Abstraction level | Execution-level predicate | Routing-level predicate |
| Who decides | Executor declares capability | Backend declares capability |
| External backends | N/A — they don't use WorkerExecutor | Inherit `true` via default method |
| Quartz answer source | WorkerExecutor (indirect) | Handler chain (direct, same source) |
| Routing strategy deps | Must access WorkerExecutor | Only talks to WEM |

`canExecute()` on WEM is the better layering: it's a routing concern, so it belongs on the
routing participant. The routing strategy only needs `WorkerExecutionManager` — it shouldn't
depend on `WorkerExecutor`. Quartz's implementation delegates to `Instance<WorkerFunctionHandler>`
for an authoritative answer, getting #587's correctness benefit at the right abstraction level.

### C. Recovery — non-blocking startup (#588)

**`onStart()` becomes non-blocking with observable status:**

Current code blocks for 30 seconds:

```java
void onStart(@Observes @Priority(20) StartupEvent ev) throws SchedulerException {
    scheduler.getListenerManager().addJobListener(workflowExecutionJobListener);
    workerExecutionRecoveryService.recoverPendingScheduledWorkers()
        .await().atMost(Duration.ofSeconds(30));
}
```

Changed to fire-and-forget with recovery status tracking:

```java
private volatile RecoveryStatus recoveryStatus = RecoveryStatus.PENDING;

enum RecoveryStatus { PENDING, COMPLETED, FAILED }

void onStart(@Observes @Priority(20) StartupEvent ev) throws SchedulerException {
    scheduler.getListenerManager().addJobListener(workflowExecutionJobListener);
    workerExecutionRecoveryService.recoverPendingScheduledWorkers()
        .subscribe().with(
            v -> { recoveryStatus = RecoveryStatus.COMPLETED;
                   LOG.info("Worker execution recovery completed"); },
            t -> { recoveryStatus = RecoveryStatus.FAILED;
                   LOG.errorf(t, "Worker execution recovery failed"); });
}

public RecoveryStatus getRecoveryStatus() {
    return recoveryStatus;
}
```

This eliminates the startup blocking. Recovery runs asynchronously after the `onStart` observer
yields to the Vert.x event loop. In practice, the subscribed `Uni` executes after all
synchronous startup observers complete, since the Vert.x event loop processes it after the
synchronous startup sequence returns. No formal happens-before guarantee exists between the
async recovery execution and other startup observers at lower priority levels.

The `RecoveryStatus` field makes recovery outcome observable — queryable by monitoring endpoints
or future health checks. Wiring this to a `@Liveness` or `@Readiness` health check is a
follow-up concern (tracked separately).

**`schedulePersistedEvent()` routing is unchanged.** The current routed implementation in
`CompositeWorkerExecutionManager` correctly routes recovery events via `supports()`:

```java
for (WorkerExecutionManager backend : backends) {
    if (backend.supports(capabilityName, tenancyId)) {
        return backend.schedulePersistedEvent(scheduledEventLog);
    }
}
```

External backends have higher priority (sorted first) and their `schedulePersistedEvent()` is a
no-op (inherits the interface default). Quartz only receives recovery events when no external
backend supports the capability — which is correct, since those events were Quartz-originated.

Broadcasting to all backends was considered and rejected: it would cause Quartz to create
spurious jobs for external workers whose recovery events should be silently consumed by their
external backend. The `canExecute()` guard is not needed on the recovery path because the
existing `supports()` routing already prevents cross-backend leakage.

**Pre-existing gap (not addressed by this branch):** External-backend events that were scheduled
but never dispatched (JVM crash between EventLog write and external dispatch) are not recovered.
`schedulePersistedEvent()` is a no-op for external backends. This gap predates the composite and
is tracked separately.

### D. Trigger methods audit (#589)

Four public methods on `QuartzWorkerExecutionManager` not on the WEM interface:

- `scheduleScheduledTrigger(UUID, Binding, ScheduleTrigger, Worker)`
- `scheduleConditionalTrigger(UUID, Binding, ScheduleTrigger, Worker)`
- `cancelScheduledTrigger(UUID, String)`
- `cancelAllScheduledTriggers(UUID)`

**Decision: keep as planned API.** These are staged work for the binding/trigger scheduling
feature. The implementations are complete and well-tested. They don't create maintenance
burden and are architecturally sound.

**Action:** Add Javadoc noting they are unwired planned API. When the trigger scheduling feature
is wired, callers will inject `QuartzWorkerExecutionManager` via `@WorkerBackend` qualifier.

**Note:** The TODO at `QuartzWorkerExecutionManager` line 91 (`"yes, here is id of event object,
because later it can be splitted into multiple jobs on diff jvms"`) is pre-existing design debt
from the original Quartz implementation. It suggests a future multi-JVM fan-out design for the
`submit()` method. This predates the composite (#461) and is unrelated to the changes in this
spec. Tracked for cleanup.

## Protocol coherence

| Protocol | Status |
|----------|--------|
| `spi-evolution-default-methods.md` | ✅ `canExecute()` is a default method with safe `true` return; `WorkerExecutionManagerContractTest` covers both `supports()` and `canExecute()` contracts |
| `engine-spi-noops-defaultbean.md` | ✅ No new default beans — Quartz overrides, others inherit |
| `module-tier-structure.md` | ✅ `None` in worker-api (Tier 1 pure Java); `canExecute()` in common SPI |
| `casehub-engine-flow-module-isolation.md` | ✅ Flow module unaffected — `FlowWorkerFunction` is not `None` |

## Scope

| File | Module | Action |
|------|--------|--------|
| `WorkerFunction.java` | casehub-worker-api (cross-repo) | Add `None` record, `NONE` singleton constant |
| `Worker.java` | casehub-worker-api (cross-repo) | Add `noFunction()` builder method |
| `WorkerExecutionManager.java` | common | Add `canExecute()` default method |
| `QuartzWorkerExecutionManager.java` | scheduler-quartz | Override `canExecute()` (handler delegation), inject `WorkerFunctionHandler`, make `onStart()` non-blocking with `RecoveryStatus` |
| `CompositeWorkerExecutionManager.java` | runtime | Override `canExecute()` to delegate to backends |
| `FirstSupportedRoutingStrategy.java` | runtime | Add `canExecute()` to routing |
| `CaseDefinitionYamlMapper.java` | api | Use `WorkerFunction.NONE` instead of placeholder Sync |
| `WorkerExecutionManagerContractTest.java` | common (test) | New abstract contract test — `supports()` consistent/no-throw, `canExecute()` default returns `true`, `canExecute(None)` rejected by Quartz |
| `WorkerExecutionRoutingStrategy.java` | common | Fix stale Javadoc — document that composite passes all backends, strategy checks `supports()` + `canExecute()` |
| Tests | multiple | New + updated tests for all changes |
| Trigger method Javadoc | scheduler-quartz | Document as unwired planned API |

## Out of scope

- External-backend recovery (pre-existing gap — not introduced by this branch) → #592
- Workers repo changes (all 5 backends inherit `canExecute() → true`) — not deferred work, no issue needed
- Health check integration for `RecoveryStatus` → #593
- Line 91 multi-JVM TODO cleanup (pre-existing, unrelated) → #594
