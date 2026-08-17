# SSE Execution-State Endpoint Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #921 — Add SSE execution-state endpoint at /api/v1/cases/{id}/state
**Issue group:** #921, #922

**Goal:** Add an SSE endpoint streaming `ExecutionStateSnapshot` on plan-item and context changes, backed by a persistent JPA `ExecutionSnapshotStore`.

**Architecture:** Add `tenancyId` to the `ExecutionSnapshotStore` SPI write methods, implement a JPA store with JSONB columns in `persistence-hibernate`, then wire an `ExecutionStateBroadcaster` observing CDI events to compose and push snapshots to a new SSE resource at `/api/v1/cases/{caseId}/state`.

**Tech Stack:** Quarkus, JAX-RS (RESTEasy Reactive), Mutiny `BroadcastProcessor`, JPA/Hibernate with JSONB, CDI `@ObservesAsync`

## Global Constraints

- Hibernate manages schema (`drop-and-create`) — no Flyway migrations
- All `@ConsumeEvent` handlers use `@RunOnVirtualThread` + void (PP-20260723-c4c1cf) — this work uses `@ObservesAsync` CDI events, not `@ConsumeEvent`
- SPI persistence pattern: `TenantAwareRepository` base, `setTenantContext(tenancyId)` before every query, `@Transactional` on every method
- `InMemoryExecutionSnapshotStore` stays as `@DefaultBean` — tests use it unchanged
- Tests named `*Test.java` (never `*IT.java`)
- Run `mvn install -DskipTests -q` before module-specific tests; include `TESTCONTAINERS_RYUK_DISABLED=true`

---

## Batch 1: SPI Evolution + JPA Store

### Task 1: Add tenancyId to ExecutionSnapshotStore SPI and update callers

**Files:**
- Modify: `common/src/main/java/io/casehub/engine/plan/execution/ExecutionSnapshotStore.java`
- Modify: `common/src/main/java/io/casehub/engine/plan/execution/InMemoryExecutionSnapshotStore.java`
- Modify: `common/src/main/java/io/casehub/engine/plan/execution/SnapshotCapturingDagEventListener.java`
- Modify: `common/src/test/java/io/casehub/engine/plan/execution/InMemoryExecutionSnapshotStoreTest.java`
- Modify: `common/src/test/java/io/casehub/engine/plan/execution/SnapshotCapturingDagEventListenerTest.java`

**Interfaces:**
- Consumes: existing `ExecutionSnapshotStore`, `SnapshotCapturingDagEventListener`
- Produces: `ExecutionSnapshotStore.storeDecomposition(UUID caseId, String tenancyId, DecompositionSnapshot)`, `storeDagPlan(UUID caseId, String tenancyId, DagPlanSnapshot)`, `storeDagResult(UUID caseId, String tenancyId, DagResultSnapshot)` — tenancyId added as second parameter to all store methods

- [ ] **Step 1: Update existing tests to pass tenancyId** — add `tenancyId` parameter to all `store*()` calls in `InMemoryExecutionSnapshotStoreTest` and update `SnapshotCapturingDagEventListenerTest` constructor call to include `tenancyId`
- [ ] **Step 2: Update the SPI interface** — add `String tenancyId` as second parameter on all three `store*()` methods
- [ ] **Step 3: Update InMemoryExecutionSnapshotStore** — accept and ignore the new `tenancyId` parameter
- [ ] **Step 4: Update SnapshotCapturingDagEventListener** — add `tenancyId` field and constructor parameter, thread through to store calls
- [ ] **Step 5: Run tests** — `mvn clean test -pl common` for the affected test classes
- [ ] **Step 6: Find and update any other callers** — `ide_find_references` on the old constructor to confirm no other callers
- [ ] **Step 7: Full build** — `mvn install -DskipTests -q`
- [ ] **Step 8: Commit** — `feat(#922): add tenancyId to ExecutionSnapshotStore store methods`

### Task 2: JPA ExecutionSnapshotStore entity and repository

**Files:**
- Create: `persistence-hibernate/src/main/java/io/casehub/persistence/jpa/ExecutionSnapshotEntity.java`
- Create: `persistence-hibernate/src/main/java/io/casehub/persistence/jpa/JpaExecutionSnapshotStore.java`
- Test: `persistence-hibernate/src/test/java/io/casehub/persistence/jpa/JpaExecutionSnapshotStoreTest.java`

**Interfaces:**
- Consumes: `ExecutionSnapshotStore` SPI (from Task 1), `TenantAwareRepository` base class
- Produces: `JpaExecutionSnapshotStore` (`@ApplicationScoped`, implements `ExecutionSnapshotStore`)

- [ ] **Step 1: Write test class** — 6 tests: store/retrieve dag plan, store/retrieve dag result, upsert overwrites single column, tenant isolation, evict removes all, get returns empty for unknown. Note: `DagPlanSnapshot` canonical constructor is `(Map<String, DagNodeSnapshot> nodes, Instant timestamp)`. `DagResultSnapshot` 5-arg convenience constructor is `(Map<String, NodeStateSnapshot> nodeStates, Map<String, Object> completedResults, boolean allSucceeded, Duration elapsed, Instant timestamp)`. Use these exact signatures in test code.
- [ ] **Step 2: Run test to verify fail** — class doesn't exist yet
- [ ] **Step 3: Create ExecutionSnapshotEntity** — `@Entity` with `case_id` (UUID PK), `tenancy_id`, three nullable JSONB `String` columns, `created_at`, `updated_at`
- [ ] **Step 4: Create JpaExecutionSnapshotStore** — extends `TenantAwareRepository`, `@ApplicationScoped`, `@Transactional` on all methods, `setTenantContext()` before queries, JSONB via Jackson `ObjectMapper`, find-or-create upsert pattern, cross-tenant evict
- [ ] **Step 5: Run tests** — all 6 pass
- [ ] **Step 6: Full build** — `mvn install -DskipTests -q`
- [ ] **Step 7: Commit** — `feat(#922): add JPA ExecutionSnapshotStore with JSONB persistence Closes #922`

## Batch 2: SSE Endpoint

### Task 3: ExecutionStateBroadcaster and ExecutionStateResource

**Files:**
- Create: `rest/src/main/java/io/casehub/engine/rest/ExecutionStateBroadcaster.java`
- Create: `rest/src/main/java/io/casehub/engine/rest/ExecutionStateResource.java`
- Test: `rest/src/test/java/io/casehub/engine/rest/ExecutionStateBroadcasterTest.java`
- Test: `rest/src/test/java/io/casehub/engine/rest/ExecutionStateResourceTest.java`

**Interfaces:**
- Consumes: `CasePlanModelSnapshotProvider`, `ExecutionSnapshotStore`, `CaseDefinitionRegistry`, `CaseInstanceRepository` (method: `findByUuid(UUID, String)`), `CaseService`, `CurrentPrincipal`, `ExecutionStateSnapshot.compose()`
- Produces: `ExecutionStateBroadcaster.stream(UUID caseId)` returning `Multi<ExecutionStateSnapshot>`, `ExecutionStateBroadcaster.composeInitial(UUID caseId, String tenancyId)` returning nullable `ExecutionStateSnapshot`, SSE endpoint at `GET /api/v1/cases/{caseId}/state` with initial snapshot on connect

- [ ] **Step 1: Write ExecutionStateBroadcasterTest** — 3 tests: plan-item event triggers snapshot, context event triggers snapshot, filters by caseId
- [ ] **Step 2: Write ExecutionStateResourceTest** — HTTP-level HEAD test verifying SSE content type
- [ ] **Step 3: Run tests to verify fail**
- [ ] **Step 4: Create ExecutionStateBroadcaster** — `@ApplicationScoped`, inner `CaseSnapshotEvent(UUID, ExecutionStateSnapshot)` record, `BroadcastProcessor`, `@ObservesAsync` on `PlanItemStateChangedEvent` and `CaseContextUpdatedEvent`, eager composition via injected providers. `resolveDefinition()` uses `caseInstanceRepository.findByUuid(caseId, tenancyId)` (not `findById`). `stream(UUID, String tenancyId)` filters by caseId and maps to snapshot. `composeInitial(UUID, String)` composes a single snapshot for initial delivery on connect.
- [ ] **Step 5: Create ExecutionStateResource** — `@Path("/api/v1/cases/{caseId}/state")`, `@Produces(SERVER_SENT_EVENTS)`, `@RestStreamElementType(APPLICATION_JSON)`. The `stream()` method: (1) calls `caseService.requireCaseAccess(caseId, AclAction.READ)`, (2) composes an initial snapshot via `broadcaster.composeInitial(caseId, tenancyId)`, (3) returns `Multi.createFrom().item(initial).onCompletion().switchTo(() -> broadcaster.stream(caseId))` — client gets current state on connect, then live updates. If initial snapshot is null (no execution state yet), skip the prepend and return just the live stream.
- [ ] **Step 6: Run tests** — all pass
- [ ] **Step 7: Run full rest module tests** — no regressions
- [ ] **Step 8: ide_diagnostics** — verify no compilation errors
- [ ] **Step 9: Commit** — `feat(#921): add SSE execution-state endpoint at /api/v1/cases/{id}/state Closes #921`

## Batch 3: Documentation

### Task 4: Update CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update Plan Snapshot Infrastructure section** — document JPA store, `tenancyId` on store methods, `ExecutionSnapshotEntity`
- [ ] **Step 2: Add SSE endpoint documentation** — `GET /api/v1/cases/{caseId}/state` (SSE), note existing GET at `/plan/state` stays
- [ ] **Step 3: Commit** — `docs: update CLAUDE.md for SSE execution-state and JPA snapshot store`

## References

- `specs/issue-921-sse-execution-state-endpoint/2026-08-18-sse-execution-state-design.md` — design spec
- `rest/src/main/java/io/casehub/engine/rest/CaseStreamBroadcaster.java` — existing SSE pattern
- `rest/src/main/java/io/casehub/engine/rest/CaseStreamResource.java` — existing SSE resource
- `rest/src/main/java/io/casehub/engine/rest/dto/ExecutionStateSnapshot.java` — composition logic
- `rest/src/main/java/io/casehub/engine/rest/PlanResource.java:146-162` — existing GET
- `common/src/main/java/io/casehub/engine/plan/execution/ExecutionSnapshotStore.java` — SPI
- `common/src/main/java/io/casehub/engine/plan/execution/InMemoryExecutionSnapshotStore.java`
- `common/src/main/java/io/casehub/engine/plan/execution/SnapshotCapturingDagEventListener.java`
- `persistence-hibernate/src/main/java/io/casehub/persistence/jpa/JpaEventLogRepository.java` — JPA pattern
- `persistence-hibernate/src/main/java/io/casehub/persistence/jpa/EventLogEntity.java` — entity pattern
- `docs/protocols/casehub/virtual-thread-handler-convention.md` — PP-20260723-c4c1cf
- GitHub #921, #922, #910, #873
