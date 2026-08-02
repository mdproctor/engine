# Scoped Worker Output Application — Design Spec

**Issue:** casehubio/engine#825 (partial — REINVOKED path only; PERSISTENT `emit()` path deferred to #824)
**Parent epic:** #821
**Date:** 2026-08-01

## Problem

When a non-TRANSIENT worker returns `WorkerOutcome.Success` (interim output, not `Completed`),
`QuartzWorkerExecutionJob.onSuccess()` publishes to a dead event bus address
(`casehub.engine.scoped-worker-output`) with only `caseId` and `bindingName` — no actual output
data. No handler consumes this event. Worker output is silently discarded.

Downstream bindings never re-evaluate because `CONTEXT_CHANGED` is never published.

## Solution

New event type and handler for scoped worker interim output. Keeps the existing completion path
(`WORKER_EXECUTION_FINISHED` → `WorkflowExecutionCompletedHandler` + `PlanItemCompletionHandler`)
untouched. The handler applies output to case context with conflict resolution, writes an EventLog
entry, and publishes `CONTEXT_CHANGED`.

The output application logic (binding lookup, conflict strategy resolution, per-key application
with `ConflictResolver`, before/after diff computation) is extracted into a shared
`ContextOutputApplier` service to avoid duplicating `WorkflowExecutionCompletedHandler`'s private
methods.

### Design rationale — event bus handler vs direct mutation

Issue #825 originally proposed applying output directly in `onSuccess()` and removing the event
bus address. This spec takes the opposite approach — keeping the address and creating a handler —
for three reasons:

1. **Reuse by #824:** `PersistentScope.emit()` needs the same output application path. A shared
   handler on the event bus is naturally reusable — `emit()` publishes to the same address.
2. **Separation of concerns:** `QuartzWorkerExecutionJob` is a scheduler adapter in the
   `scheduler-quartz` module. It should not own context mutation, EventLog writing, or
   `CONTEXT_CHANGED` publishing — those belong in `runtime`.
3. **Consistency:** All other output application flows use the event bus pattern
   (`WORKER_EXECUTION_FINISHED` → `WorkflowExecutionCompletedHandler`).

### Cross-spec coordination

This spec supersedes §4 ("Scoped Worker Output Application") of the sibling spec for #823
(`scoped-worker-lifecycle-wiring-design.md`). That spec's §4 defines an incompatible
`ScopedWorkerOutputEvent` shape and an incomplete handler that:

- References non-existent `CaseDefinition.findBindingByName()` (only exists as a private method
  on `WorkflowExecutionCompletedHandler`)
- Calls a non-existent `ConflictResolver.resolve(Map, Map, String)` overload (the actual API is
  `resolve(String strategy, String key, Object existing, Object incoming)` — per-key, not bulk)
- Omits EventLog writing, settlement tracking, and context diff computation
- Passes `ContextLayer.WORKING.name()` (invalid — `ContextLayer.WORKING` is already a `String`
  constant, not an enum)
- Constructs `CaseContextChangedEvent` without the required `CaseContext contextSnapshot` parameter

This spec (#825) is authoritative for the output application path. See casehubio/engine#850 for
updating #823's §4 to defer here.

## Components

### 1. `ScopedWorkerOutputEvent` (common/internal/event/)

```java
public record ScopedWorkerOutputEvent(
    CaseInstance caseInstance,
    String workerName,
    Map<String, Object> output,
    String bindingName,
    UUID signalId) {}
```

Carries full output and worker name (not the full `Worker` object — the handler only needs
the name for EventLog metadata, and `PersistentScope.emit()` from #824 does not have a
`Worker` reference). No idempotency key (worker is still running). No outcome field (always
interim Success). Lives in `common` so both `scheduler-quartz` (publisher) and `runtime`
(consumer) can access it.

Cross-module Vert.x delivery uses the same pattern as `WorkflowExecutionCompleted` — Quarkus
handles the local codec automatically via `@ConsumeEvent`. No explicit codec registration needed.

### 2. `EventBusAddresses.SCOPED_WORKER_OUTPUT`

```java
public static final String SCOPED_WORKER_OUTPUT = "casehub.engine.scoped-worker-output";
```

Reuses the existing (dead) string literal. Placed after the `WORKER_RETRIES_EXHAUSTED` constant
in the worker lifecycle section.

### 3. `CaseHubEventType.SCOPED_WORKER_OUTPUT`

New enum value for EventLog entries. Placed after `WORKER_OUTCOME_EXPIRED`.

### 4. `ContextOutputApplier` (runtime/internal/engine/handler/)

`@ApplicationScoped` service that encapsulates the shared output-application contract: given
raw output and a binding name, look up the conflict resolution strategy, apply output
key-by-key with `ConflictResolver`, and return the computed context diff. Serializes output
application per case via a per-case `ReentrantLock` to prevent concurrent `apply()` calls
from producing incorrect diffs or lost updates.

```java
@ApplicationScoped
public class ContextOutputApplier {

    @Inject CaseDefinitionRegistry caseDefinitionRegistry;
    @Inject ContextDiffStrategy contextDiffStrategy;

    private final ConcurrentHashMap<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * Applies conflict-resolved output to the case context and returns the context diff.
     * Returns null if output is empty (no-op).
     * Serialized per case — concurrent calls for the same case block until the previous completes.
     */
    public JsonNode apply(CaseInstance instance, Map<String, Object> output, String bindingName) {
        if (output == null || output.isEmpty()) {
            return null;
        }
        ReentrantLock lock = locks.computeIfAbsent(instance.getUuid(), k -> new ReentrantLock());
        lock.lock();
        try {
            JsonNode contextBefore = instance.getCaseContext().snapshot().asJsonNode();

            Binding binding = findBindingByName(instance, bindingName);
            String strategy = binding != null ? binding.getConflictResolverStrategy() : null;
            CaseContext context = instance.getCaseContext();
            if ("FAIL".equals(strategy)) {
                for (String key : output.keySet()) {
                    if (context.get(key) != null) {
                        throw new IllegalStateException(
                            "FAIL strategy: key '" + key + "' already exists — rejecting entire output");
                    }
                }
            }
            for (Map.Entry<String, Object> entry : output.entrySet()) {
                String key = entry.getKey();
                Object incoming = entry.getValue();
                Object existing = context.get(key);
                Object resolved = existing != null
                    ? ConflictResolver.resolve(strategy, key, existing, incoming)
                    : incoming;
                context.set(key, resolved);
            }

            JsonNode contextAfter = instance.getCaseContext().asJsonNode();
            return contextDiffStrategy.compute(contextBefore, contextAfter);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Evicts the per-case lock when the case reaches a terminal state.
     * Follows the CaseEvaluationSerializer.evict() pattern.
     */
    public void evict(UUID caseId) {
        locks.remove(caseId);
    }

    Binding findBindingByName(CaseInstance instance, String bindingName) {
        CaseDefinition definition =
            caseDefinitionRegistry.getCaseDefinition(instance.getCaseMetaModel());
        if (definition == null || definition.getBindings() == null || bindingName == null) {
            return null;
        }
        return definition.getBindings().stream()
            .filter(b -> b.getName().equals(bindingName))
            .findFirst()
            .orElse(null);
    }
}
```

Extracts the duplicated private logic from `WorkflowExecutionCompletedHandler`:
- `findBindingByName()` (line 659) — binding lookup from `CaseDefinitionRegistry`
- `resolveConflictStrategy()` (line 694) — inlined as `binding.getConflictResolverStrategy()`
- `applyOutputWithConflictResolution()` (line 643) — key-by-key iteration with `ConflictResolver`
- Context before/after snapshot and diff computation (lines 129–136)

The per-case lock map grows proportionally to cases that produce scoped worker output. Entries
are evicted via `evict(UUID)` when the case reaches a terminal state, following the same pattern
as `CaseEvaluationSerializer.evict()`. `ReentrantLock` is virtual-thread-friendly — it unmounts
the carrier thread on contention rather than pinning (JEP 444).

`ScopedWorkerOutputHandler` delegates to this service. `WorkflowExecutionCompletedHandler` will
be refactored to delegate here as a follow-on (casehubio/engine#849).

**Diff semantics note for #849:** `ContextOutputApplier.apply()` returns a diff covering only
the output application — it does not include episodic layer or diagnostics changes that WECH
currently folds into its diff. This narrower diff is intentionally more correct (the diff
should reflect what the output application changed, not collateral mutations). When #849
migrates WECH to this applier, the EventLog `contextChanges` metadata will narrow accordingly.
Downstream consumers that depend on the broader diff must be identified and updated as part of
#849.

### 5. `ScopedWorkerOutputHandler` (runtime/internal/engine/handler/)

`@ApplicationScoped`, `@ConsumeEvent(SCOPED_WORKER_OUTPUT)`, `@RunOnVirtualThread`.

Steps:
1. **Case status guard:** if `caseInstance.getState()` is not `RUNNING` or `WAITING`, log a
   debug message and return. Scoped workers may produce output after a case reaches a terminal
   state (the output event was already in flight when `ScopedWorkerTerminationHandler` cancelled
   the worker). Applying output to a dead case would write a spurious EventLog entry and publish
   a `CONTEXT_CHANGED` that `CaseContextChangedEventHandler` would discard anyway.
2. Call `ContextOutputApplier.apply(caseInstance, output, bindingName)` — this handles binding
   lookup, conflict strategy resolution, per-key output application, before/after snapshot, and
   diff computation. Returns the context diff (`JsonNode`), or null if output was empty.
3. If diff is null → return (no-op, no EventLog, no `CONTEXT_CHANGED`)
4. Write EventLog entry:
   - `caseId`: `caseInstance.getUuid()`
   - `workerId`: `event.workerName()`
   - `streamType`: `EventStreamType.CASE`
   - `timestamp`: `Instant.now()`
   - `eventType`: `CaseHubEventType.SCOPED_WORKER_OUTPUT`
   - `payload`: raw output (serialized via ObjectMapper)
   - `metadata`:
     - `bindingName`: binding name (may be null)
     - `contextChanges`: the computed diff
     - `producedKeys`: array of top-level keys from the diff (matching
       `WorkflowExecutionCompletedHandler.buildMetadata()` pattern)
   - No `inputDataHash` — the worker is still running, there is no idempotency key.
5. Publish `CONTEXT_CHANGED` using the 3-arg convenience constructor:
   ```java
   new CaseContextChangedEvent(caseInstance, caseInstance.getCaseContext().snapshot(), ContextLayer.WORKING)
   ```
   This sets `triggerChannelId`, `triggerCorrelationId`, and `signalId` all to null — matching
   `WorkflowExecutionCompletedHandler`'s CONTEXT_CHANGED publish. No Qhorus trigger context
   exists in the scoped worker path. The signalId is not forwarded — downstream dispatches
   triggered by the context change are independently tracked if needed, consistent with how
   `WorkflowExecutionCompletedHandler` handles this.

No `SignalSettlementTracker.recordCompletion()` call — settlement is deferred to the final
`Completed` outcome via `WorkflowExecutionCompletedHandler`. Calling `recordCompletion()` on
interim output would prematurely resolve the settlement future when `completed >= expected`,
causing `signalAndAwait()` callers to proceed before the worker finishes.

Output application is serialized per case by `ContextOutputApplier`'s internal per-case lock
(see §4). No output is dropped — concurrent outputs wait for the previous `apply()` call to
complete. Virtual threads make this blocking wait effectively free.

`CaseEvaluationSerializer` is **not** used here. Its coalescing design (single
`pendingEvaluator` per case, overwritten on each new submission) would silently drop
concurrent outputs — each scoped output carries unique data that must be applied. The
existing `CaseContextChangedEventHandler` already uses `CaseEvaluationSerializer` for
downstream binding evaluation, where coalescing is correct (stateless re-evaluation of
the latest context).

**Inter-handler concurrency with `WorkflowExecutionCompletedHandler`:** When a REINVOKED
worker returns its final `Completed` outcome, both this handler (processing the last interim
`Success`) and `WorkflowExecutionCompletedHandler` (processing the `Completed`) may run
concurrently on different virtual threads for the same case. Until #849 refactors WECH to
delegate to `ContextOutputApplier`, the two handlers operate on independent code paths —
WECH's `applyOutputWithConflictResolution()` is not serialized by the applier's lock.

This race is accepted because:
1. **Narrow window:** The interim handler completes in microseconds (apply + EventLog +
   publish). The next Quartz invocation cannot even start until the current one returns
   from `onSuccess()`, so the scoped handler has already processed the event by the time
   WECH receives its event.
2. **Authoritative final output:** The `Completed` outcome carries the worker's full final
   output. With LAST_WRITER_WINS (default), WECH's output supersedes any interim values.
3. **#849 resolves it:** When WECH migrates to `ContextOutputApplier`, both handlers
   serialize through the same per-case lock.

Injections:
- `ContextOutputApplier`
- `EventLogRepository` (tenant-scoped, not cross-tenant — scoped worker output is always
  within the owning case's tenancy)
- `EventBus`

No episodic update, no `workerStatusListener.onWorkerCompleted()`, no CDI lifecycle events,
no routing outcome recording. Those belong to final `Completed` only.

Error handling: catch-and-log at the top level (same pattern as
`WorkflowExecutionCompletedHandler`). Output application failure must not crash the case.

### 6. Fix `QuartzWorkerExecutionJob.onSuccess()`

Replace the dead publish block:

**Before:**
```java
if (executionMode != null && executionMode != ExecutionMode.TRANSIENT) {
    if (!(workerResult.outcome() instanceof WorkerOutcome.Completed)) {
        Map<String, Object> output = toMap(workerResult.output());
        if (output != null && !output.isEmpty()) {
            vertx.eventBus().publish("casehub.engine.scoped-worker-output",
                JsonObject.of("caseId", instance.getUuid().toString(), "bindingName", bindingName));
        }
        LOG.debugf("Scoped worker %s returned Success — output applied, PlanItem stays RUNNING", bindingName);
        return;
    }
}
```

**After:**
```java
if (executionMode != null && executionMode != ExecutionMode.TRANSIENT) {
    if (workerResult.outcome() instanceof WorkerOutcome.Success) {
        Map<String, Object> output = toMap(workerResult.output());
        if (output != null && !output.isEmpty()) {
            eventBus.publish(SCOPED_WORKER_OUTPUT,
                new ScopedWorkerOutputEvent(instance, worker.name(), output, bindingName, signalId));
        }
        LOG.debugf("Scoped worker %s returned Success — interim output published", bindingName);
        return;
    }
}
```

Key changes:
- **Guard changed** from `!(instanceof Completed)` to `instanceof WorkerOutcome.Success` —
  ensures `Declined`, `Failed`, and `Expired` outcomes fall through to
  `WORKER_EXECUTION_FINISHED` and route to
  `WorkflowExecutionCompletedHandler.handleSemanticFailure()` for proper outcome policy
  handling, rerouting, and PlanItem lifecycle management
- Uses `eventBus` (Mutiny) instead of `vertx.eventBus()` (raw Vert.x)
- Publishes `ScopedWorkerOutputEvent` with full output, not a `JsonObject` stub
- Uses the `SCOPED_WORKER_OUTPUT` constant instead of a string literal
- Passes `worker.name()` (not the full `Worker` object) and `signalId` for traceability

### 7. Future integration point — PERSISTENT `emit()` (#824)

When #824 implements `PersistentScope.emit(output)`, it publishes to the same
`SCOPED_WORKER_OUTPUT` address with a `ScopedWorkerOutputEvent`. The handler is already in
place — no changes needed. Using `String workerName` (not `Worker`) in the event makes
construction straightforward from the persistent scope, which has the worker name but not
the `Worker` object.

**Partial delivery note:** This spec delivers two of #825's three acceptance criteria:
(1) REINVOKED worker Success output appears in case context, and (3) downstream bindings
re-evaluate after scoped worker output is applied. Criterion (2) — PERSISTENT worker `emit()`
output appears in case context — is gated on #824 landing. Issue #825 cannot be closed until
both this spec and #824 are implemented.

## What this does NOT change

- `WorkflowExecutionCompletedHandler` — untouched, handles final `Completed` outcomes.
  Follow-on: refactor to delegate output application to `ContextOutputApplier`
  (casehubio/engine#849).
- `PlanItemCompletionHandler` — untouched, PlanItem stays RUNNING because
  `WORKER_EXECUTION_FINISHED` is never published for interim Success
- `ScopedWorkerRegistry` — untouched, session tracking is separate from output application

## Testing

### Unit: `ScopedWorkerOutputHandlerTest`

1. Output applied with default conflict resolution (LAST_WRITER_WINS)
2. Output applied with DEEP_MERGE conflict resolution
3. Empty output is no-op (no EventLog, no CONTEXT_CHANGED) — defensive guard: the REINVOKED
   publisher filters empty output, but the #824 PERSISTENT `emit()` path may not
4. EventLog entry written with correct type and metadata
5. `CONTEXT_CHANGED` published after output application with `ContextLayer.WORKING`
6. Settlement tracker NOT called regardless of signalId (deferred to final completion)
7. Handler exception → caught and logged, does not propagate
9. Case in terminal state (COMPLETED/FAULTED/CANCELLED) → no-op, no output applied
10. Null `bindingName` → output applied with LAST_WRITER_WINS default

### Unit: `ContextOutputApplierTest`

11. Output applied with all conflict resolution strategies (LAST_WRITER_WINS, FIRST_WRITER_WINS,
    DEEP_MERGE, FAIL)
12. Returns null for empty/null output
13. Returns correct context diff reflecting applied changes
14. Missing binding → defaults to LAST_WRITER_WINS (null strategy)
15. Missing CaseDefinition → defaults to LAST_WRITER_WINS (null strategy)
16. FAIL strategy with conflicting key → entire output rejected, no partial application
17. Concurrent `apply()` calls for the same case are serialized — no lost updates or incorrect diffs
18. `evict()` removes the per-case lock entry

### Unit: `QuartzWorkerExecutionJob` onSuccess changes

16. Non-TRANSIENT + Success → publishes `ScopedWorkerOutputEvent` with full output and `worker.name()`
17. Non-TRANSIENT + Success + empty output → no event published
18. Non-TRANSIENT + Completed → publishes `WORKER_EXECUTION_FINISHED` (existing path, unchanged)
19. TRANSIENT + Success → publishes `WORKER_EXECUTION_FINISHED` (existing path, unchanged)
20. Non-TRANSIENT + Declined → publishes `WORKER_EXECUTION_FINISHED` (not misrouted as interim)
21. Non-TRANSIENT + Failed → publishes `WORKER_EXECUTION_FINISHED` (not misrouted as interim)

### Integration: `ScopedWorkerOutputIntegrationTest`

20. REINVOKED worker Success output appears in case context after handler runs
21. Downstream binding re-evaluates after scoped worker output is applied
