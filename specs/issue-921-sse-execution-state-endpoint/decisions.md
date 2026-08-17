## D1: Scope JPA ExecutionSnapshotStore into this branch

**Choice:** Include JPA persistence for `ExecutionSnapshotStore` in this branch alongside the SSE endpoint
**Alternatives:**
- File as separate issue and backlog — risks the gap staying unfixed as backlogs grow
**Rationale:** The SSE endpoint depends on the store data. Without persistence, the stream has nothing to push after a restart. Doing both together ensures end-to-end correctness.
**Trade-offs:** Larger branch scope
**Sources:** `ExecutionSnapshotStore` SPI (engine-common), `InMemoryExecutionSnapshotStore`, engine#910
**Exploration:** quick
**Status:** captured

## D2: Single entity with three JSONB columns for JPA store

**Choice:** One `ExecutionSnapshotEntity` per case with three nullable JSONB columns (decomposition, dag_plan, dag_result)
**Alternatives:**
- Separate entities per snapshot type — more normalized but three round-trips for the compose() call that reads all three together
**Rationale:** The three snapshots share lifecycle (evicted together), are always queried together by `ExecutionStateSnapshot.compose()`, and the in-memory model already treats them as a unit (`CaseSnapshots` inner class with three `AtomicReference` fields).
**Trade-offs:** Less normalized; a future snapshot type requires a schema change rather than a new table
**Sources:** `InMemoryExecutionSnapshotStore.CaseSnapshots`, `ExecutionStateSnapshot.compose()`
**Exploration:** quick
**Status:** captured

## D3: Eager composition in the observer

**Choice:** The broadcaster composes `ExecutionStateSnapshot` eagerly in the `@ObservesAsync` handler, then pushes the composed snapshot to the `BroadcastProcessor`
**Alternatives:**
- Lazy composition per subscriber — push just caseId trigger, compose in `stream()` map. Avoids composing for cases with no subscribers, but runs composition on the Mutiny I/O thread where blocking JPA calls are problematic.
**Rationale:** `@ObservesAsync` runs on a managed async thread where JPA calls are safe. The composition cost (a few queries) is low. The observer has access to `tenancyId` from the event, avoiding the need to thread tenancy through the broadcaster API.
**Trade-offs:** Composes snapshots for events even when no subscriber exists for that case. In practice, the workbench monitors active cases, so most compositions are consumed.
**Sources:** `CaseStreamBroadcaster` pattern, `PlanItemStateChangedEvent.tenancyId()`
**Exploration:** quick
**Status:** captured

## D4: Separate ExecutionStateBroadcaster

**Choice:** New `ExecutionStateBroadcaster` class, parallel to `CaseStreamBroadcaster`
**Alternatives:**
- Extend `CaseStreamBroadcaster` with composition logic — mixes thin notification concern with full snapshot composition, requires injecting multiple services
**Rationale:** `CaseStreamBroadcaster` is intentionally thin (wraps CDI events into notifications). The execution state broadcaster needs service injection for composition (CasePlanModelSnapshotProvider, ExecutionSnapshotStore, CaseDefinitionRegistry). Separate class keeps each broadcaster focused.
**Trade-offs:** Two broadcasters observing the same CDI events. No runtime cost since `@ObservesAsync` handles fan-out.
**Sources:** `CaseStreamBroadcaster`, `CaseStreamResource`
**Exploration:** quick
**Status:** captured

## D5: Keep existing GET /plan/state endpoint

**Choice:** Keep `PlanResource.getExecutionState()` at `/api/v1/cases/{caseId}/plan/state` as-is
**Alternatives:**
- Remove it — the new SSE endpoint supersedes it
- Move it to case-level path — breaking change
**Rationale:** It serves a different purpose (point-in-time polling vs. streaming). Removing is a breaking change. The `/plan/` prefix accurately reflects it as a plan diagnostic tool.
**Trade-offs:** Two endpoints serving similar data at different paths
**Sources:** engine#910, `PlanResource`
**Exploration:** quick
**Status:** captured
