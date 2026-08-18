# SSE Execution-State Endpoint and JPA ExecutionSnapshotStore

Refs engine#921, engine#922, engine#910

## Problem

The orchestration workbench subscribes to SSE at `${endpoint}/${id}/state` where `endpoint=/api/v1/cases`. The engine has the data — `ExecutionSnapshotStore` + `CasePlanModelSnapshotProvider` — but serves it via GET at `/plan/state` (wrong path, wrong protocol). Additionally, `ExecutionSnapshotStore` has only an in-memory implementation that loses data on JVM restart.

## Scope

Two changes on one branch:

1. **JPA `ExecutionSnapshotStore`** — persistent implementation in `persistence-hibernate` following the existing tenant-aware repository pattern. The SPI interface is already clean; this adds the missing implementation.

2. **SSE endpoint** at `GET /api/v1/cases/{caseId}/state` — pushes `ExecutionStateSnapshot` events via Server-Sent Events on plan-item transitions and context updates.

The existing GET endpoint at `/api/v1/cases/{caseId}/plan/state` stays as-is (diagnostic/polling).

## Part 1: JPA ExecutionSnapshotStore

### Entity

`ExecutionSnapshotEntity` in `persistence-hibernate`:

| Column | Type | Notes |
|--------|------|-------|
| `id` | `UUID` | PK, equals `caseId` — one row per case |
| `tenancy_id` | `VARCHAR` | RLS policy column |
| `decomposition_snapshot` | `JSONB` | Nullable, serialized `DecompositionSnapshot` |
| `dag_plan_snapshot` | `JSONB` | Nullable, serialized `DagPlanSnapshot` |
| `dag_result_snapshot` | `JSONB` | Nullable, serialized `DagResultSnapshot` |
| `created_at` | `TIMESTAMP` | Set on first store |
| `updated_at` | `TIMESTAMP` | Updated on every store |

Table name: `execution_snapshot`. Hibernate manages the schema (`drop-and-create`). No Flyway migration per project convention.

Single entity with three nullable JSONB columns because the three snapshots share lifecycle (evicted together) and are always queried together by `ExecutionStateSnapshot.compose()`.

### Repository

`JpaExecutionSnapshotStore` in `persistence-hibernate`:

- Extends `TenantAwareRepository` (same base as `JpaEventLogRepository`, `JpaCaseInstanceRepository`)
- Uses `withTenantTransaction(tenancyId, work)` for all reads and writes
- JSONB serialization via Jackson `ObjectMapper` (injected)
- `store*()` methods: upsert (find by caseId, create or update the relevant column)
- `get*()` methods: find by caseId within tenant transaction, deserialize from JSONB
- `evict()`: delete by caseId (cross-tenant via `withCrossTenantTransaction`)

The `InMemoryExecutionSnapshotStore` remains `@DefaultBean` — tests and deployments without `persistence-hibernate` continue to work unchanged.

### SPI interface

No changes to `ExecutionSnapshotStore`. The interface already has `tenancyId` on all read methods. The `store*()` methods do not take `tenancyId` — the implementation resolves it from the entity or the caller's context. This matches the existing pattern: `SnapshotCapturingDagEventListener` calls `store*()` during DAG execution where tenancy context is available from the execution path.

**Gap: `store*()` methods lack `tenancyId` parameter.** The in-memory implementation ignores tenancy, but the JPA implementation needs it for `withTenantTransaction()`. Two options:

- Add a `tenancyId` parameter to all `store*()` methods on the SPI (breaking change to the interface, but only `SnapshotCapturingDagEventListener` calls them)
- Resolve `tenancyId` inside the JPA implementation from the case entity

Since `SnapshotCapturingDagEventListener` is the sole caller of `store*()` and it already has access to the caseId, the cleanest fix is to add `tenancyId` to the SPI methods. The in-memory implementation ignores it. The single caller site in `SnapshotCapturingDagEventListener` threads it from the execution context.

## Part 2: SSE Endpoint

### ExecutionStateBroadcaster

`@ApplicationScoped` in `rest/`:

```
Observes: PlanItemStateChangedEvent, CaseContextUpdatedEvent (same as CaseStreamBroadcaster)
Injects: CasePlanModelSnapshotProvider, ExecutionSnapshotStore, CaseDefinitionRegistry, CaseInstanceRepository
Produces: BroadcastProcessor<CaseExecutionStateEvent>
```

`CaseExecutionStateEvent` is an internal record `(UUID caseId, ExecutionStateSnapshot snapshot)` — carries the caseId for per-subscriber filtering.

On each CDI event:
1. Look up `CaseInstance` by caseId to get `CaseMetaModel` (needed for definition lookup)
2. Compose `ExecutionStateSnapshot` using `ExecutionStateSnapshot.compose()` — same logic as `PlanResource.getExecutionState()`
3. Push to `BroadcastProcessor`, catching `BackPressureFailure`

Composition is eager (in the `@ObservesAsync` handler). The observer runs on a managed async thread where blocking calls (JPA) are safe. The event carries `tenancyId`.

Failures in composition (case not found, store errors) are caught and logged — never crash the broadcaster.

### ExecutionStateResource

New JAX-RS resource:

```
@Path("/api/v1/cases/{caseId}/state")
@Tag(name = "Execution State", description = "SSE stream for execution state updates")
```

Single endpoint:

```
@GET
@Produces(MediaType.SERVER_SENT_EVENTS)
@RestStreamElementType(MediaType.APPLICATION_JSON)
Multi<ExecutionStateSnapshot> stream(@PathParam("caseId") UUID caseId)
```

ACL enforcement: calls `CaseService.requireCaseAccess(caseId, AclAction.READ)` before subscribing to the broadcaster. This is a one-time check at subscription time — subsequent events are not individually authorized (same pattern as `CaseStreamResource`, which does no ACL check at all; this is strictly better).

The `stream()` method:
1. Validates access
2. Composes and emits an initial snapshot immediately (so the client gets current state on connect, not just deltas)
3. Concatenates with the broadcaster's filtered stream

Initial snapshot on connect is important — without it, a client connecting mid-execution sees nothing until the next state change.

### Event flow

```
PlanItemStateChangedEvent ──@ObservesAsync──> ExecutionStateBroadcaster
                                                    │
CaseContextUpdatedEvent  ──@ObservesAsync──>        │
                                                    ▼
                                             compose(caseId, tenancyId)
                                                    │
                                                    ▼
                                        BroadcastProcessor<CaseExecutionStateEvent>
                                                    │
                                           filter(caseId) per subscriber
                                                    │
                                                    ▼
                                    ExecutionStateResource (SSE to client)
```

## Testing

### JPA store tests
- Contract tests: extend the existing `ExecutionSnapshotStore` test pattern from `InMemoryExecutionSnapshotStoreTest`
- Tenant isolation: store for tenant A, query as tenant B → empty
- JSONB round-trip: store and retrieve each snapshot type, verify field fidelity
- Upsert: store decomposition, then store dag_plan → both present on read
- Evict: store all three, evict, verify empty

### SSE endpoint tests
- `ExecutionStateBroadcasterTest`: inject broadcaster, fire CDI events directly, verify snapshot composition and caseId filtering
- `ExecutionStateResourceTest`: HTTP-level test using RestAssured — verify SSE content type, verify initial snapshot on connect, verify events flow through after plan-item state change
- No-state case: connect to a case with no execution data → initial snapshot is empty/404 or IDLE state

## Files changed

| File | Change |
|------|--------|
| `common/.../ExecutionSnapshotStore.java` | Add `tenancyId` to `store*()` methods |
| `common/.../InMemoryExecutionSnapshotStore.java` | Accept and ignore new `tenancyId` param |
| `common/.../SnapshotCapturingDagEventListener.java` | Thread `tenancyId` to `store*()` calls |
| `persistence-hibernate/.../ExecutionSnapshotEntity.java` | New entity |
| `persistence-hibernate/.../JpaExecutionSnapshotStore.java` | New repository |
| `rest/.../ExecutionStateBroadcaster.java` | New broadcaster |
| `rest/.../dto/CaseExecutionStateEvent.java` | New internal record |
| `rest/.../ExecutionStateResource.java` | New SSE resource |
| `rest/test/.../ExecutionStateBroadcasterTest.java` | New test |
| `rest/test/.../ExecutionStateResourceTest.java` | New test |
| `persistence-hibernate/test/.../JpaExecutionSnapshotStoreTest.java` | New test |

## References

- `rest/.../CaseStreamBroadcaster.java` — existing SSE broadcaster pattern
- `rest/.../CaseStreamResource.java` — existing SSE resource pattern
- `rest/.../dto/ExecutionStateSnapshot.java` — snapshot composition logic (engine#910)
- `rest/.../PlanResource.java:146-162` — existing GET endpoint composition
- `common/.../ExecutionSnapshotStore.java` — SPI interface
- `common/.../InMemoryExecutionSnapshotStore.java` — in-memory implementation
- `common/.../SnapshotCapturingDagEventListener.java` — sole store*() caller
- `docs/protocols/casehub/virtual-thread-handler-convention.md` — PP-20260723-c4c1cf
- engine#910 — GET execution-state endpoint
- engine#873 — HTN/DAG REST endpoints design
