# EventLog Enrichment — ContextDiffStrategy Design
**Date:** 2026-04-15
**Status:** Approved — ready for implementation
**Issue:** casehubio/engine#51 (Phase 2 — EventLog enrichment)

---

## Problem

When a worker completes, the engine records a `WORKER_EXECUTION_COMPLETED` EventLog entry with
the worker's raw output in `payload` and only `inputDataHash` in `metadata`. There is no record
of *what changed* in the CaseContext — which keys were added, updated, or removed, and what the
values were before.

The original casehub design used `CaseFileItem` to carry this provenance (writtenBy, writtenAt,
value). The migration plan re-evaluates that: enrich the `WORKER_EXECUTION_COMPLETED` EventLog
entry with a key-level before/after diff instead of adding a dual-map to `CaseContextImpl`.

---

## Design

### SPI: `ContextDiffStrategy` (api module)

```java
public interface ContextDiffStrategy {
    /**
     * Computes the diff between the CaseContext before and after a worker execution.
     * Returns null to omit contextChanges from the EventLog metadata entirely.
     */
    JsonNode compute(JsonNode before, JsonNode after);
}
```

Placed in `api` alongside the other SPIs (`WorkerExecutionGuard`, `LoopControl`,
`ExpressionEngine`). The engine injects it into `WorkflowExecutionCompletedHandler` and calls it
inside the existing Panache transaction — no new DB columns, no schema migration.

### Three Implementations (engine module)

All three live in the `engine` module. `zjsonpatch` is already a dependency of `engine`
(used by `CaseContextImpl.diff()` and `applyDiff()`), so `JsonPatchContextDiffStrategy` adds
zero new dependencies.

| Bean | CDI | Output |
|---|---|---|
| `TopLevelContextDiffStrategy` | Default `@ApplicationScoped` | Object keyed by top-level key name |
| `JsonPatchContextDiffStrategy` | `@Alternative @Priority(10)` | RFC 6902 array |
| `NoOpContextDiffStrategy` | `@Alternative @Priority(10)` | `null` — omits `contextChanges` |

Users switch via `quarkus.arc.selected-alternatives` in `application.properties` — same
mechanism as `PoisonPillWorkerExecutionGuard`.

### Data Shape

**Current `WORKER_EXECUTION_COMPLETED` metadata:**
```json
{ "inputDataHash": "abc123" }
```

**After enrichment (TopLevelContextDiffStrategy — default):**
```json
{
  "inputDataHash": "abc123",
  "contextChanges": {
    "status":  { "before": "processing", "after": "done" },
    "result":  { "after": "summary text" },
    "tempKey": { "before": "old" }
  }
}
```

Rules:
- Key added by worker: `{ "after": value }` — no `before`
- Key updated: `{ "before": oldValue, "after": newValue }`
- Key removed: `{ "before": oldValue }` — no `after` field (distinguishes removal from writing null)
- Unchanged keys: **omitted**
- Worker produces no changes: `contextChanges` is an empty object `{}`

**After enrichment (JsonPatchContextDiffStrategy):**
```json
{
  "inputDataHash": "abc123",
  "contextChanges": [
    { "op": "replace", "path": "/status", "value": "done" },
    { "op": "add",     "path": "/result", "value": "summary text" },
    { "op": "remove",  "path": "/tempKey" }
  ]
}
```

**NoOpContextDiffStrategy:** `contextChanges` is omitted entirely.

### Handler Change

`WorkflowExecutionCompletedHandler.onWorkflowExecutionCompletedHandler()` is the only
production code change. Sequence inside the existing Panache transaction:

```
1. before = caseInstance.getCaseContext().snapshot()   // capture state before
2. caseInstance.getCaseContext().setAll(rawOutput)      // apply worker output
3. diff = strategy.compute(before.asJsonNode(),
                           caseInstance.getCaseContext().asJsonNode())
4. metadata = { "inputDataHash": idempotency,
                "contextChanges": diff }                // enrich (omit if null)
5. eventLog.persist() + publish CONTEXT_CHANGED         // unchanged
```

`CaseContextImpl.snapshot()` already exists and produces a deep copy. The diff computation
is pure (no I/O) and runs synchronously inside the transaction lambda before the persist.

### Why Top-Level Only for the Default

Workers declare `outputSchema` as flat projections (`{ status: .status, result: .result }`).
The CaseContext is primarily a flat key-value workspace. Deeply nested worker writes are
uncommon in practice. Top-level key granularity matches the old `CaseFileItem` model and
answers the primary provenance question: "which worker wrote which key and what was the value
before?"

`JsonPatchContextDiffStrategy` is available for users who need full path-level precision (e.g.
for future replay support, issues #10–#13).

---

## Implementation Scope

**Files added:**
- `api/src/main/java/io/casehub/api/spi/ContextDiffStrategy.java`
- `engine/src/main/java/io/casehub/engine/internal/diff/TopLevelContextDiffStrategy.java`
- `engine/src/main/java/io/casehub/engine/internal/diff/JsonPatchContextDiffStrategy.java`
- `engine/src/main/java/io/casehub/engine/internal/diff/NoOpContextDiffStrategy.java`

**Files modified:**
- `engine/src/main/java/io/casehub/engine/internal/engine/handler/WorkflowExecutionCompletedHandler.java`

No schema migration. No new Maven modules. No new library dependencies.

---

## Testing

### Unit Tests (pure, no CDI)

**`TopLevelContextDiffStrategyTest`:**
- Key added → `{ "after": value }`, no `before`
- Key updated → `{ "before": old, "after": new }`
- Key removed → `{ "before": old }` — no `after` field
- Unchanged key → omitted from output
- No changes at all → empty object `{}`
- Multiple changes in one execution → all captured
- Null value written → treated as removal

**`JsonPatchContextDiffStrategyTest`:**
- Same scenarios, verified as RFC 6902 operations (`add`, `replace`, `remove`)
- Empty diff → empty array `[]`

**`NoOpContextDiffStrategyTest`:**
- Returns `null` for any input

### Integration Test (@QuarkusTest)

**`WorkflowExecutionCompletedHandlerTest`:**
- Publish `WORKER_EXECUTION_FINISHED` event with known before-state and output
- Await `WORKER_EXECUTION_COMPLETED` EventLog entry
- Assert `metadata.contextChanges` contains the expected before/after entries
- Assert `inputDataHash` is still present alongside `contextChanges`

### End-to-End Test (@QuarkusTest, real case run)

**`ContextDiffEndToEndTest`:**
- Start a case with known initial context (e.g. `{ "status": "start" }`)
- Worker runs and writes `{ "status": "done", "result": "ok" }`
- Query EventLog for `WORKER_EXECUTION_COMPLETED`
- Assert `contextChanges.status.before = "start"`, `contextChanges.status.after = "done"`
- Assert `contextChanges.result` has no `before`, `after = "ok"`
- Assert unchanged keys are absent from `contextChanges`

---

## Out of Scope

- Schema migration (no new columns)
- `casehub-resilience` changes (JSON Patch strategy lives in `engine`)
- Replay engine (issues #10–#13 — future work that will build on this)
- `IdempotencyService` (separate concern)
- `TimeoutEnforcer` (aligns with co-owner issue #22)
