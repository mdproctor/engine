# Concurrent Registry and Evaluation Safety — Design Spec

**Issue:** casehubio/engine#1205
**Branch:** `issue-1205-concurrent-registry-reregistration`
**Date:** 2026-10-08

## Problem Statement

Three interrelated concurrency defects share a single execution path through `CaseContextChangedEventHandler.handle()` → `CaseEvaluationSerializer.submit()` → `evaluateAndDispatch()` → `CaseDefinitionRegistry.getCaseDefinition()`. They produce intermittent CI failures in AML and any integration test suite with parallel worker completions.

1. **Partial registry state** — concurrent lazy re-registration in `DefaultCaseDefinitionRegistry` exposes a partially populated `ConcurrentHashMap` to readers
2. **Lost context-change evaluation** — `CaseEvaluationSerializer` single-slot coalescing overwrites pending evaluators, discarding context changes from near-simultaneous worker completions
3. **Unsafe reset/restart** — `CaseEvaluationSerializer.reset()` clears gates without draining in-flight evaluations, allowing stale and new evaluations to overlap

## Fix 1: Registry Atomic Snapshot Swap

### Current Behavior

`DefaultCaseDefinitionRegistry` stores definitions in a `ConcurrentHashMap<CaseKey, RegistryEntry>` (line 80). When `getCaseDefinition()` finds the map empty, it calls `registerKnownDefinitions()` which inserts definitions one at a time. Concurrent readers can observe the map mid-population.

### Design

Replace the mutable `ConcurrentHashMap` field with a `volatile` reference to an immutable `Map`:

```java
private volatile Map<CaseKey, RegistryEntry> registry = Map.of();
```

`registerKnownDefinitions()` builds a local `HashMap`, then publishes it via a single volatile write:

```java
void registerKnownDefinitions() {
    // ... validation (unchanged) ...
    Map<CaseKey, RegistryEntry> snapshot = new HashMap<>();
    for (CaseHub hub : caseHubInstance) {
        RegistryEntry entry = registerSingle(hub.getDefinition(), snapshot);
        snapshot.put(CaseKey.of(entry.metaModel()), entry);
    }
    this.registry = Map.copyOf(snapshot);
}
```

The `getCaseDefinition()` lazy fallback remains, but re-registration is now atomic — readers see either the old snapshot or the fully populated new one.

`registerCaseDefinition()` (the public SPI method for single-definition registration after startup) needs to create a new snapshot that includes the new entry:

```java
public CaseMetaModel registerCaseDefinition(CaseDefinition model) {
    RegistryEntry entry = registerSingle(model, registry);
    Map<CaseKey, RegistryEntry> updated = new HashMap<>(registry);
    updated.put(CaseKey.of(entry.metaModel()), entry);
    this.registry = Map.copyOf(updated);
    return entry.metaModel();
}
```

This is safe because single-definition registration is rare (only startup and dynamic registration) and does not need to be lock-free.

### Impact

- All read methods (`getCaseDefinition`, `findByIdentity`, `findByName`, `findByType`, `findByLabel`, `allDefinitions`) work unchanged — they read from a `Map` via the same API
- No lock contention on the hot lookup path
- The `ConcurrentHashMap` import is removed

## Fix 2: Re-read Context with Signal Accumulation

### Current Behavior

`CaseEvaluationSerializer.submit(UUID, Runnable)` stores a single `pendingEvaluator` in `CaseGate`. When multiple events arrive while an evaluation is running, each overwrites the previous pending evaluator. The second event's evaluator (and its captured context snapshot / signalId) is lost.

### Design

Change the coalescing to preserve correctness while keeping the single-slot performance:

**2a. Re-read context at evaluation time.** The `CaseContextChangedEventHandler` currently submits `() -> evaluateAndDispatch(event)` which closes over the event's context snapshot. `evaluateAndDispatch()` passes this snapshot to `rules()`, `goals()`, `observations()`, and `localRules()` for condition evaluation. When two workers complete concurrently, each event's snapshot may miss the other's changes — but `caseInstance.getCaseContext()` reflects both because the case instance is shared and mutable.

Change the coalesced evaluator to read context from `caseInstance.getCaseContext()` at execution time instead of using the captured event snapshot. When coalescing occurs, pass `null` for `changedLayer` (multiple layers may have changed) — this skips the `listenLayer` filter in `rules()` and evaluates all bindings, which is correct because we cannot know which layers were affected by the coalesced-away events.

**2b. Accumulate signal IDs.** The `CaseGate` gains a `Set<UUID> pendingSignalIds` field. When a submission is coalesced (stored as pending), its signalId is added to the set. After the coalesced evaluation completes, all accumulated signalIds are marked as dispatched via `settlementTracker.markFullyDispatched()`.

The `submit()` signature changes:

```java
public void submit(UUID caseId, Runnable evaluator, UUID signalId)
```

`CaseGate` structure:

```java
private static final class CaseGate {
    final UUID caseId;
    final ReentrantLock lock = new ReentrantLock();
    boolean evaluating;
    Runnable pendingEvaluator;
    Set<UUID> pendingSignalIds;    // accumulates across coalesced submissions
    // ...
}
```

`drainPending()` collects accumulated signalIds and returns them to the caller, who passes them to the settlement tracker.

### Impact

- The existing test `coalescesMultiplePendingEvents` still passes — 3 submissions still produce 2 evaluations
- New test: verify that all signalIds from coalesced submissions are marked as dispatched
- `CaseContextChangedEventHandler` changes its `handle()` method to pass `signalId` separately

## Fix 3: Drain-with-Timeout Reset

### Current Behavior

`CaseEvaluationSerializer.reset()` calls `gates.clear()`. In-flight evaluations continue with stale references. New submissions create fresh gates that may overlap with old evaluations.

### Design

Add lifecycle state tracking to `CaseEvaluationSerializer`:

```java
private volatile boolean closed;
private final Phaser activeEvaluations = new Phaser(1); // self-registered
```

**`reset()` becomes:**

1. Set `closed = true` — `submit()` returns immediately without queuing
2. `activeEvaluations.arriveAndAwaitAdvance()` with 5s timeout — waits for all in-flight evaluations to complete
3. `gates.clear()`
4. Reset the `Phaser` for the next lifecycle
5. Set `closed = false`

**`submit()` checks `closed`:**

```java
public void submit(UUID caseId, Runnable evaluator, UUID signalId) {
    if (closed) return;
    // ... existing logic ...
}
```

**Evaluation tracking:**

Each evaluation registers with the `Phaser` before starting and arrives when complete. `drainPending()` handles the arrive on the final iteration (when no more pending work exists).

### Impact

- Reset is now safe across test lifecycle boundaries — no overlapping evaluations
- New test: reset during active evaluation blocks until evaluation completes, then clears state cleanly
- New test: submissions during reset are silently dropped

## Regression Tests

Per the issue's five requested regression tests:

1. **Concurrent registry lookups during re-registration** — multiple threads call `getCaseDefinition()` while `registerKnownDefinitions()` is running; no partial state is observed
2. **Two workers complete concurrently** — both outputs are visible in the case context and the dependent binding is activated (tests the re-read-context approach)
3. **Three context changes during blocked evaluation** — no semantically distinct transition is lost; all signalIds are settled
4. **Reset during active evaluation** — evaluations do not overlap; no stale executor/CDI reference is used after reset completes
5. **Stress test** — concurrent submissions across multiple cases with interleaved resets, run 100+ iterations to exercise timing windows

## Module Boundaries

| Change | Module | File |
|--------|--------|------|
| Atomic snapshot swap | `runtime` | `DefaultCaseDefinitionRegistry.java` |
| Registry tests | `runtime` | `DefaultCaseDefinitionRegistryTest.java` (new concurrent tests) |
| Re-read context + signal accumulation | `runtime-core` | `CaseEvaluationSerializer.java` |
| Submit API change (add signalId) | `runtime-core` | `CaseEvaluationSerializer.java` |
| Drain-with-timeout reset | `runtime-core` | `CaseEvaluationSerializer.java` |
| Serializer tests | `runtime` | `CaseEvaluationSerializerTest.java` (extended) |
| Handler adaptation | `runtime-core` | `CaseContextChangedEventHandler.java` |

## References

- `DefaultCaseDefinitionRegistry.java:181-185` — lazy re-registration fallback (defect 1)
- `CaseEvaluationSerializer.java:35-55` — submit with single-slot coalescing (defect 2)
- `CaseEvaluationSerializer.java:62-64` — reset without coordination (defect 3)
- `CaseContextChangedEventHandler.java:239-257` — handle() submission path
- `QuiescenceTracker.java` — existing lifecycle tracking (analogous pattern for drain)
- casehubio/engine#424 — prior partial fix for registry race
- `CaseEvaluationSerializerTest.java:86-128` — existing coalescing test
