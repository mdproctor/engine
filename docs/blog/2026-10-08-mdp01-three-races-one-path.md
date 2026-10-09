---
layout: post
title: "Three Races, One Path"
date: 2026-10-08
entry_type: note
subtype: diary
projects: [casehubio/engine]
tags: [concurrency, ConcurrentHashMap, coalescing, registry, virtual-threads]
---

# Three Races, One Path

AML's CI had been flaking for weeks. The failures migrated — a different test class every run, always a timeout, always somewhere in the context-change evaluation path. The kind of bug where the symptom has almost no correlation with the cause.

Tracing it back, the failures share one execution path: worker completion fires a `CaseContextChangedEvent`, which the `CaseEvaluationSerializer` gates (one evaluation per case at a time), which calls `evaluateAndDispatch`, which looks up the case definition from the registry. Three distinct concurrency defects stacked along that path, each making the others harder to diagnose.

## The registry that wasn't thread-safe enough

`DefaultCaseDefinitionRegistry` stores definitions in a `ConcurrentHashMap`. When a lookup finds the map empty — a stale CDI bean instance after a Quarkus test restart — it re-registers everything by iterating over `CaseHub` beans and calling `put()` for each one. ConcurrentHashMap guarantees per-entry atomicity. It does not guarantee batch atomicity. A concurrent reader calling `get()` mid-loop sees whatever entries have been inserted so far. If the definition it needs hasn't been inserted yet, the lookup returns null and the case faults.

The fix is a volatile snapshot swap. Build the entire registry in a local `HashMap`, then publish via a single volatile write:

```java
private volatile Map<CaseKey, RegistryEntry> registry = Map.of();

void registerKnownDefinitions() {
    Map<CaseKey, RegistryEntry> snapshot = new HashMap<>();
    for (CaseHub hub : caseHubInstance) {
        registerSingle(hub.getDefinition(), snapshot);
    }
    this.registry = Map.copyOf(snapshot);
}
```

Readers see either the old snapshot or the fully populated new one. No lock contention on the read path. `Map.copyOf()` produces an optimised immutable implementation that outperforms ConcurrentHashMap for read-heavy workloads — which a definition registry very much is.

## The coalescing that ate your signals

`CaseEvaluationSerializer` ensures at most one evaluation runs per case at a time. When a submission arrives while an evaluation is active, the new evaluator overwrites the previous pending one. Single slot, latest wins. The existing tests explicitly validate this: three submissions produce two evaluations.

The problem: each submission carries a `signalId` for settlement tracking. When a pending evaluator gets overwritten, its signal ID vanishes. `settlementTracker.markFullyDispatched()` is never called for the dropped signal. Under parallel worker completions — which is exactly when coalescing happens — settlement tracking quietly breaks.

The insight is that coalescing conflates two concerns. The *work* (the evaluator Runnable) can be safely replaced — the latest version reads the most current context. But the *metadata* (who asked for this evaluation) must be preserved across all coalesced submissions. The fix: a `Set<UUID>` on the gate that accumulates signal IDs, returned to the caller after the drain loop completes. The caller iterates and settles them all.

## The reset that didn't wait

`CaseEvaluationSerializer.reset()` — called between Quarkus test lifecycle boundaries — was a single line: `gates.clear()`. No coordination with in-flight evaluations. If reset fires while an evaluation is running, the old evaluation continues with a gate that's been ripped out of the map. A new submission creates a fresh gate for the same case. Two evaluations for the same case run concurrently, violating the one-at-a-time invariant.

The fix adds a `closed` flag and a drain with timeout. Reset sets `closed` (new submissions return immediately), waits up to five seconds for active evaluations to finish via a `CountDownLatch`, then clears the gates. The `activeCount` + `drainLatch` coordination is lightweight — no Phaser needed when the only question is "has everyone finished?"

## What ties them together

Individually, each bug looks minor. The registry race is a stale-bean edge case. The signal loss is a metadata accounting error. The reset overlap is a test-lifecycle boundary problem. But they share a single execution path — and when two workers complete within milliseconds of each other, the path exercises all three defects in one pass. The registry returns null for one case, the signal for the other is lost, and if a test restart happens to coincide, the reset collides with both. The symptom — a timeout in a completely unrelated AML test — gives you nothing to work with.

The concurrency test that validates the registry fix is worth noting: ten virtual threads calling `getCaseDefinition()` while a writer re-registers two hundred times. Before the volatile swap, the failure rate was high enough to reproduce in a few iterations. After — zero partial-state observations across the entire run.
