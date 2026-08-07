# HTN/DAG REST Endpoints — Design Spec

**Issue:** casehubio/engine#873
**Date:** 2026-08-07
**Status:** Approved

## Summary

REST endpoints in `casehub-engine-rest` that serialize `TaskNode<T>` trees,
`DagPlan<T>`, `DagResult<R>`, and `CasePlanModel` as JSON matching the
blocks-ui `graph-stencil-htn` TypeScript contracts. Two model fixes at the
root (`CompoundTask.id`, `DecompositionMethod.guardLabel`), non-generic
snapshot types for serialization, two new SPIs for data access, and five
new endpoints on a `PlanResource`.

## Dependencies

- casehubio/blocks-ui#107 defines the TypeScript contracts these endpoints
  must conform to
- blocks#60 unified `DagPlan<LeafTask<T>>` with blocks

## Model Fixes (engine-api)

### CompoundTask gains `id`

```java
record CompoundTask<T>(String id, String name, List<DecompositionMethod<T>> methods)
    implements TaskNode<T> {
  public CompoundTask {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(name, "name");
    methods = List.copyOf(methods);
  }
}
```

Breaking change — all call sites must provide an ID. Follows the same
pattern as `DagNode.id` and `PlanItemDefinition.id()`. In-repo call sites
updated in this issue. Blocks call sites filed as a separate issue.

### DecompositionMethod gains `guardLabel`

```java
record DecompositionMethod<T>(Predicate<T> guard, DecompositionStrategy<T> strategy,
    String guardLabel) {
  public DecompositionMethod {
    // guardLabel is nullable — predicates without labels serialize as null
  }
}
```

The `Predicate<T>` is not serializable. `guardLabel` provides a
human-readable string for the UI (maps to TypeScript
`DecompositionMethodSnapshot.guardLabel?: string`). Compact constructor
allows null.

## Snapshot Types

Non-generic, Jackson-serializable records that mirror the engine's generic
types. Placed per `plan-type-module-boundary` protocol (PP-20260727-5267d2).

### engine-api (`io.casehub.engine.plan.snapshot`) — plan-definition snapshots

**TaskNodeSnapshot** — sealed interface with `@JsonTypeInfo(property = "kind")`:
- `LeafTaskSnapshot(String id, String description, String executorName)` — kind = `"leaf"`
- `CompoundTaskSnapshot(String id, String name, List<DecompositionMethodSnapshot> methods)` — kind = `"compound"`

**DecompositionMethodSnapshot** — record:
`(String guardLabel, String strategyId, List<TaskNodeSnapshot> children)`

**DecompositionSnapshot** — record:
`(TaskNodeSnapshot root, Instant timestamp)`

**DagNodeSnapshot** — record:
`(String id, String taskId, String taskDescription, String executorName, Set<String> dependsOn, JoinType joinType)`

**DagPlanSnapshot** — record:
`(Map<String, DagNodeSnapshot> nodes, Instant timestamp)`

**PlanItemDefinitionSnapshot** — sealed interface with `@JsonTypeInfo(property = "kind")`:
- `PrimitiveItemSnapshot(String id, String name, String executorName, String executorDescription, String entryCondition)` — kind = `"primitive"`
- `CompoundItemSnapshot(String id, String name, List<PlanItemDefinitionSnapshot> children, String planningStrategy, CompletionSemanticsSnapshot completion, String dispatchMode, String entryCondition, String exitCondition, boolean repeatable, Map<String, String> scopedBindings)` — kind = `"compound"`

**CompletionSemanticsSnapshot** — sealed interface with `@JsonTypeInfo(property = "kind")`:
- `AllSnapshot()` — kind = `"All"`
- `MOfNSnapshot(int m)` — kind = `"MOfN"`
- `FirstWinsSnapshot()` — kind = `"FirstWins"`

### engine-common (`io.casehub.engine.plan.execution`) — execution snapshots

**NodeStateSnapshot** — flat record (not sealed):
`(String kind, String reason)` — kind is the sealed variant name
("Pending", "Dispatched", "Completed", "Failed", "Skipped", "Cancelled")

**DagResultSnapshot** — record:
`(Map<String, NodeStateSnapshot> nodeStates, Map<String, Object> completedResults, boolean allSucceeded, Duration elapsed, Instant timestamp)`

### engine-common (`io.casehub.engine.plan.execution`) — plan model snapshots

**AgendaItemSnapshot** — record:
`(String planItemId, String bindingName, String status, String description)`

**SubCaseSnapshotRecord** — record:
`(String caseDefinition, String namespace, String status)`

**CompoundStatusSnapshot** — record:
`(String id, String name, String status, int childCount, int completedCount, CompletionSemanticsSnapshot completion)`

**CasePlanModelSnapshot** — record:
`(UUID caseId, List<AgendaItemSnapshot> agenda, String focus, String focusRationale, Map<String, Object> resourceBudget, List<SubCaseSnapshotRecord> subCases, List<CompoundStatusSnapshot> compounds, Instant timestamp)`

### Static `from()` factories

Each snapshot type provides a static `from()` factory for converting
generic engine types to flat snapshots:

- `DagPlanSnapshot.from(DagPlan<?>, Instant)` — iterates nodes, extracts
  `TaskDescriptor` fields when `T instanceof TaskDescriptor`
- `DagResultSnapshot.from(DagResult<?>, Instant)` — maps `NodeState<R>`
  variants to `NodeStateSnapshot(kind, reason)`, serializes `R` results
  as `Object`
- `DecompositionSnapshot.from(TaskNode<?>, Instant)` — recursively walks
  the tree
- `NodeStateSnapshot.from(NodeState<?>)` — pattern-matches the sealed
  variants

`ExpressionEvaluator` conditions serialize to string: `JQExpressionEvaluator`
extracts the expression, lambda shows `"<lambda>"`.

## SPIs (engine-common)

### ExecutionSnapshotStore

Read/write store for captured execution snapshots (decomposition, DAG plan,
DAG result).

```java
public interface ExecutionSnapshotStore {
    void storeDecomposition(UUID caseId, DecompositionSnapshot snapshot);
    Optional<DecompositionSnapshot> getDecomposition(UUID caseId, String tenancyId);

    void storeDagPlan(UUID caseId, DagPlanSnapshot snapshot);
    Optional<DagPlanSnapshot> getDagPlan(UUID caseId, String tenancyId);

    void storeDagResult(UUID caseId, DagResultSnapshot snapshot);
    Optional<DagResultSnapshot> getDagResult(UUID caseId, String tenancyId);

    void evict(UUID caseId);
}
```

Read methods take `tenancyId` for consistency with `CasePlanModelSnapshotProvider`.
Write methods omit it — the caller context already validated tenancy.

**Default implementation:** `InMemoryExecutionSnapshotStore`
(`@DefaultBean @ApplicationScoped`). `ConcurrentHashMap<UUID, CaseSnapshots>`
keyed by caseId. `CaseSnapshots` uses `AtomicReference<>` per snapshot
field for thread-safe concurrent writes from different execution paths.

### CasePlanModelSnapshotProvider

Live plan model data computed on-demand from `BlackboardRegistry`.

```java
public interface CasePlanModelSnapshotProvider {
    Optional<CasePlanModelSnapshot> getSnapshot(UUID caseId, String tenancyId);
    List<PlanItemDefinitionSnapshot> getDefinitions(UUID caseId, String tenancyId);
}
```

**Default implementation:** `NoOpCasePlanModelSnapshotProvider`
(`@DefaultBean @ApplicationScoped`) returns `Optional.empty()` and
`List.of()`.

Both SPIs follow the `@DefaultBean` pattern per
`PP-20260514-engine-spi-noops-defaultbean`.

## Implementations

### PlanningCasePlanModelSnapshotProvider (planning module)

`@ApplicationScoped` in `io.casehub.engine.planning.snapshot`. Injects
`BlackboardRegistry`. Maps live `CasePlanModel` state:

| CasePlanModel method | Snapshot field |
|---|---|
| `getAgenda()` | `List<AgendaItemSnapshot>` |
| `getFocus()` / `getFocusRationale()` | nullable strings |
| `getResourceBudget()` | map pass-through |
| `getSubCases()` | `List<SubCaseSnapshotRecord>` |
| `getAllCompounds()` + `getDefinitionStatus()` | `List<CompoundStatusSnapshot>` |

`getDefinitions()` walks the `PlanItemDefinition` hierarchy (Primitive /
Compound) via `CasePlanModel.getDefinition()` and maps to
`PlanItemDefinitionSnapshot`. Expression conditions (`ExpressionEvaluator`)
serialize: `JQExpressionEvaluator` → expression string, lambda → `"<lambda>"`.

### SnapshotCapturingDagEventListener (engine-common)

Convenience `DagEventListener<T, R>` implementation in
`io.casehub.engine.plan.snapshot`. Constructor takes `UUID caseId`,
`ExecutionSnapshotStore`, `DagPlan<T>`. On construction, stores
`DagPlanSnapshot.from(plan)`. On `onExecutionComplete()`, stores
`DagResultSnapshot.from(result)`.

Blocks passes this listener when constructing `DagDriver` — one line of
wiring at the call site.

### Eviction

Captured snapshots (decomposition, DAG plan, DAG result) are NOT evicted on
case terminal state — they are most valuable for post-mortem analysis after
a case completes or faults. `CasePlanModel` eviction from `BlackboardRegistry`
continues as before (live state no longer needed). The in-memory store grows
until JVM restart. A TTL-based or explicit cleanup mechanism is future work
(filed as issue).

## REST Endpoints

### PlanResource

New JAX-RS resource in `casehub-engine-rest`:

```java
@Path("/api/v1/cases/{caseId}/plan")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Plan Snapshots", description = "HTN decomposition, DAG execution, and plan model snapshots")
public class PlanResource {

    @Inject CaseService caseService;
    @Inject CasePlanModelSnapshotProvider planModelProvider;
    @Inject ExecutionSnapshotStore snapshotStore;
    @Inject CurrentPrincipal currentPrincipal;
}
```

| Method | Path | Returns | Source |
|---|---|---|---|
| GET | `/model` | `CasePlanModelSnapshot` | Live from `CasePlanModelSnapshotProvider` |
| GET | `/definitions` | `List<PlanItemDefinitionSnapshot>` | Live from `CasePlanModelSnapshotProvider` |
| GET | `/decomposition` | `DecompositionSnapshot` | Captured in `ExecutionSnapshotStore` |
| GET | `/dag` | `DagPlanSnapshot` | Captured in `ExecutionSnapshotStore` |
| GET | `/dag/result` | `DagResultSnapshot` | Captured in `ExecutionSnapshotStore` |

All endpoints:
- `@RunOnVirtualThread`
- Pre-check ACL via `caseService.requireCaseAccess(caseId, AclAction.READ)`
- Return 404 `ProblemDetail` when snapshot not available for the case
- OpenAPI `@Operation` and `@APIResponse` annotations

### No separate DTO layer

Snapshot types carry `@JsonTypeInfo` for sealed interface discriminators.
Jackson serializes records directly. `Instant` → ISO-8601, `UUID` → string,
`JoinType.ALL_OF` → `"ALL_OF"`. Field names align with TypeScript contracts.
OpenAPI generates schema from record structure.

### REST module dependencies

No new compile dependencies. `engine-api` and `engine-common` (already
present) carry snapshot types and SPIs. No dependency on `planning`.

## Capture Points Summary

| Snapshot | Capture point | Who | When |
|---|---|---|---|
| CasePlanModel | Live read from BlackboardRegistry | Planning module (this issue) | On request |
| PlanItemDefinition | Live read from CasePlanModel | Planning module (this issue) | On request |
| DagResult | `SnapshotCapturingDagEventListener.onExecutionComplete()` | Engine-common provides listener; blocks wires it (blocks issue) | Execution end |
| DagPlan | `SnapshotCapturingDagEventListener` constructor | Same — blocks wires it (blocks issue) | Execution start |
| Decomposition tree | After `DecompositionStrategy.decompose()` returns | Blocks call site (blocks issue) | After decomposition |

## Testing

### Unit tests (engine-api, engine-common)

- Snapshot `from()` factories: verify correct mapping from generic engine
  types to flat snapshots
- `InMemoryExecutionSnapshotStore`: store/retrieve/evict round-trip
- `SnapshotCapturingDagEventListener`: verify DagPlan stored on
  construction, DagResult stored on completion
- Jackson serialization: verify `@JsonTypeInfo` discriminators produce
  correct `kind` fields matching TypeScript contracts

### Integration tests (planning module)

- `PlanningCasePlanModelSnapshotProvider`: verify mapping from live
  `CasePlanModel` to snapshot, including agenda items, compounds, and
  PlanItemDefinition hierarchy

### REST integration tests (rest module)

- `@QuarkusTest` with `casehub-persistence-memory`
- Verify HTTP 200 with correct JSON structure for all 5 endpoints
- Verify HTTP 404 when no snapshot available
- Verify `kind` discriminators in serialized JSON
- Verify ACL enforcement (`requireCaseAccess`)

## Issues to File

1. **blocks — Wire snapshot capture at decomposition/DagDriver call sites.**
   Inject `ExecutionSnapshotStore`, store `DecompositionSnapshot` after
   decomposition, pass `SnapshotCapturingDagEventListener` to `DagDriver`.
   Scale: S, Complexity: Low.

2. **blocks — Update call sites for CompoundTask.id and
   DecompositionMethod.guardLabel.** Mechanical — add arguments to all
   constructors. Scale: S, Complexity: Low.

3. **engine — Snapshot TTL-based cleanup.**
   In-memory execution snapshots are not evicted on case terminal state
   (post-mortem value). Add a TTL-based cleanup or bounded cache to prevent
   unbounded growth in long-running JVMs. Scale: S, Complexity: Low.

4. **blocks-ui — Remove `rationale` from `LeafTaskSnapshot` and
   `selectedMethodIndex` from `CompoundTaskSnapshot`.**
   These TypeScript fields have no source in the Java model. Remove from
   the TS contracts to stay aligned. Scale: XS, Complexity: Low.

## Review Resolutions

Light review (1 round × 3 dimensions). Key findings and resolutions:

| # | Finding | Resolution |
|---|---------|------------|
| 1 | `selectedMethodIndex` unpopulable from `TaskNode` | **Accepted** — dropped from `CompoundTaskSnapshot` |
| 2 | `LeafTaskSnapshot.rationale` has no source | **Accepted** — dropped; file blocks-ui issue |
| 3 | Split package across modules | **Accepted** — `snapshot` in api, `execution` in common |
| 4 | Eviction destroys post-mortem data | **Accepted** — no eviction on terminal; TTL future issue |
| 5 | Concurrent write race on CaseSnapshots | **Accepted** — `AtomicReference<>` per field |
| 6 | CasePlanModelSnapshot not point-in-time | **Deferred** — acceptable for v1 REST read |
| 7 | `tenancyId` asymmetry between SPIs | **Accepted** — added to read methods |
| 8 | 3/5 endpoints 404 until blocks ships | **Acknowledged** — expected, documented |
| 9 | `scopedBindings` type mapping | **Accepted** — `Map<String, String>` (enum name) |

## Out of Scope

- `DagDispatchMode` (STREAMING/BARRIER) on `DagPlanSnapshot` — not in the
  TypeScript contract; add later if the UI needs it
- Persistent snapshot store (JPA) — in-memory is sufficient for v1
- SSE/WebSocket for live DAG state updates — future if needed
- Blocks-side capture implementation — separate issue
