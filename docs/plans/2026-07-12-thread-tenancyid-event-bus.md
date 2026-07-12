# Thread tenancyId Through Event Bus Messages — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #680 — refactor: thread tenancyId through event bus messages for correct principal resolution  
**Issue group:** #680

**Goal:** Eliminate ambient `CurrentPrincipal` from the persistence layer and make tenant context explicit on every event bus message, so RLS enforcement is correct regardless of thread context.

**Architecture:** Three layers, root-first. Layer 1 parameterizes `TenantAwareRepository.withTenantTransaction()` to accept explicit `tenancyId` instead of reading `CurrentPrincipal`. Layer 2 adds `tenancyId` to 10 event records and reorders 2 existing ones. Layer 3 removes test workarounds that masked the problem.

**Tech Stack:** Java 21, Quarkus 3.32.2, Vert.x EventBus, Hibernate Reactive/Panache, PostgreSQL RLS

## Global Constraints

- Pre-release: breaking SPI changes are free — no backward-compat shims
- `tenancyId` position convention: immediately after `caseId` in all event records
- `tenancyId` is never optional on event records — no convenience constructors that omit it
- Cross-tenant operations must be explicitly named (`findDelegatedCrossTenant`, `withCrossTenantTransaction`)
- IntelliJ MCP required for all code navigation and editing — never bash grep on .java files

---

### Task 1: Parameterize `withTenantTransaction()` and update all JPA repos

**Files:**
- Modify: `casehub-persistence-hibernate/src/main/java/io/casehub/persistence/jpa/TenantAwareRepository.java`
- Modify: `casehub-persistence-hibernate/src/main/java/io/casehub/persistence/jpa/JpaReactiveCaseInstanceRepository.java`
- Modify: `casehub-persistence-hibernate/src/main/java/io/casehub/persistence/jpa/JpaReactiveEventLogRepository.java`
- Modify: `casehub-persistence-hibernate/src/main/java/io/casehub/persistence/jpa/JpaReactiveSubCaseGroupRepository.java`
- Modify: `casehub-persistence-hibernate/src/main/java/io/casehub/persistence/jpa/JpaReactivePlanItemStore.java`
- Modify: `casehub-persistence-hibernate/src/main/java/io/casehub/persistence/jpa/JpaReactiveCaseMetaModelRepository.java`
- Test: existing `src/test/java/io/casehub/persistence/jpa/JpaReactive*Test.java` suites

**Interfaces:**
- Produces: `withTenantTransaction(String tenancyId, Supplier<Uni<T>> work)` — new signature used by all JPA repos

- [ ] **Step 1: Write failing test for parameterized `withTenantTransaction`**

Add a test in `JpaReactiveCaseInstanceRepositoryTest` (or a new dedicated test class) that verifies the passed `tenancyId` is used for the SQL `SET LOCAL`, not an ambient principal. The existing test suite already calls repo methods with explicit tenancyId — after the change, those tests validate the new path.

Since the existing tests already pass tenancyId to every SPI method, the failing state is the compile error after changing the signature. This is a mechanical refactor — the "test" is compilation + existing suite green.

- [ ] **Step 2: Change `TenantAwareRepository.withTenantTransaction` signature**

Use `ide_edit_member` to replace `withTenantTransaction` in `TenantAwareRepository.java`:

```java
protected <T> Uni<T> withTenantTransaction(String tenancyId, Supplier<Uni<T>> work) {
    if (tenancyId == null || tenancyId.contains("'") || tenancyId.contains("\\")) {
        throw new IllegalStateException("Invalid tenancyId: " + tenancyId);
    }
    String sql = "SET LOCAL \"casehub.tenancy_id\" = '" + tenancyId + "'";
    return withSafeContext(
        () ->
            Panache.withTransaction(
                () ->
                    Panache.getSession()
                        .flatMap(
                            session ->
                                session
                                    .createNativeQuery(sql)
                                    .executeUpdate()
                                    .replaceWith(work.get()))));
}
```

Remove `@Inject CurrentPrincipal currentPrincipal;` and the `CurrentPrincipal` import.

- [ ] **Step 3: Update `JpaReactiveCaseInstanceRepository` (7 call sites)**

Every method already has `String tenancyId` as a parameter. Use `ide_replace_member` on each method body to change `withTenantTransaction(` to `withTenantTransaction(tenancyId,`. Methods: `save`, `update`, `findByUuid`, `updateStateAndAppendEvent`, `findByStatus`, `findAll`, `findByNamespaceAndName`.

- [ ] **Step 4: Update `JpaReactiveEventLogRepository` (8 call sites)**

Same mechanical change. Methods: `append`, `appendAndReturnId`, `findById`, `findSchedulingEvents`, `findByCaseAndTypes`, `findByCaseAndWorkerAndType`, `findByWorkerAndType`, `findByCaseWithFilters`.

- [ ] **Step 5: Update `JpaReactiveSubCaseGroupRepository` (6 call sites)**

Methods: `getOrCreate`, `registerChild`, `incrementCompleted`, `incrementRejected`, `markPolicyTriggered`, `findByChildCaseId`.

- [ ] **Step 6: Update `JpaReactiveCaseMetaModelRepository` (2 call sites)**

Methods: `findByKey`, `save`.

- [ ] **Step 7: Update `JpaReactivePlanItemStore` — tenant-scoped methods only (2 call sites)**

Change `save` and `findByCaseId` to pass tenancyId. Leave `updateStatus` and `findDelegated` for Task 2. Leave `findAllDelegated` unchanged (already uses `withCrossTenantTransaction`).

- [ ] **Step 8: Build and run persistence-hibernate tests**

```bash
mvn install -DskipTests -q && TESTCONTAINERS_RYUK_DISABLED=true mvn clean test -pl casehub-persistence-hibernate
```

Expected: all existing tests pass — the tenancyId was always correct in test contexts.

- [ ] **Step 9: Commit**

```bash
git add -A && git commit -m "refactor(#680): parameterize withTenantTransaction with explicit tenancyId

Remove ambient CurrentPrincipal from TenantAwareRepository. All 27 call sites
across 5 JPA repos now pass tenancyId explicitly. RLS SET LOCAL uses the correct
tenant regardless of thread context.

Refs #680"
```

---

### Task 2: PlanItemStore SPI evolution — `updateStatus` tenancyId + `findDelegatedCrossTenant`

**Files:**
- Modify: `casehub-engine-common/src/main/java/io/casehub/engine/common/spi/PlanItemStore.java`
- Modify: `casehub-engine-common/src/main/java/io/casehub/engine/common/spi/ReactivePlanItemStore.java`
- Modify: `casehub-persistence-memory/src/main/java/io/casehub/persistence/memory/InMemoryPlanItemStore.java`
- Modify: `casehub-persistence-memory/src/main/java/io/casehub/persistence/memory/InMemoryReactivePlanItemStore.java`
- Modify: `casehub-persistence-hibernate/src/main/java/io/casehub/persistence/jpa/JpaReactivePlanItemStore.java`
- Modify: `casehub-blackboard/src/main/java/io/casehub/blackboard/store/NoOpPlanItemStore.java`
- Modify: `casehub-blackboard/src/main/java/io/casehub/blackboard/store/NoOpReactivePlanItemStore.java`
- Modify: `casehub-blackboard/src/main/java/io/casehub/blackboard/registry/BlackboardRegistry.java`
- Modify: `casehub-engine-common/src/test/java/io/casehub/engine/common/spi/PlanItemStoreContractTest.java`
- Modify: `casehub-engine-common/src/test/java/io/casehub/engine/common/spi/ReactivePlanItemStoreContractTest.java`
- Test: contract tests + `BlackboardRegistryTest`, `BlackboardRegistryTenancyTest`

**Interfaces:**
- Consumes: `withTenantTransaction(String tenancyId, ...)` from Task 1
- Produces: `updateStatus(String planItemId, TaskStatus status, String tenancyId)` on both SPIs; `findDelegatedCrossTenant(UUID caseId)` + `findDelegated(UUID caseId, String tenancyId)` on both SPIs

- [ ] **Step 1: Write failing contract test for `updateStatus` with tenancyId**

Add to `PlanItemStoreContractTest`:

```java
@Test
void updateStatus_withTenancyId_changes_stored_status() {
    String planItemId = UUID.randomUUID().toString();
    store().save(new PlanItemSaveRequest(UUID.randomUUID(), planItemId, "binding",
        TaskStatus.PENDING, Instant.now(), "CapabilityTarget", null, null, null, null), tenancyId());
    store().updateStatus(planItemId, TaskStatus.RUNNING, tenancyId());
    List<PlanItemRecord> records = store().findByCaseId(/* need caseId */, tenancyId());
    assertThat(records).extracting(PlanItemRecord::status).containsExactly(TaskStatus.RUNNING);
}
```

Add mirror in `ReactivePlanItemStoreContractTest`.

- [ ] **Step 2: Run tests to verify they fail (method doesn't exist yet)**

Expected: compile error — `updateStatus(String, TaskStatus, String)` not found.

- [ ] **Step 3: Add `updateStatus(String, TaskStatus, String)` to SPIs**

Add as a `default` method to `PlanItemStore` and `ReactivePlanItemStore` (SPI evolution protocol — backward-compatible default delegates to the old 2-arg version):

In `PlanItemStore`:
```java
default void updateStatus(String planItemId, TaskStatus status, String tenancyId) {
    updateStatus(planItemId, status);
}
```

In `ReactivePlanItemStore`:
```java
default Uni<Void> updateStatus(String planItemId, TaskStatus status, String tenancyId) {
    return updateStatus(planItemId, status);
}
```

- [ ] **Step 4: Implement in `InMemoryPlanItemStore` and `InMemoryReactivePlanItemStore`**

Override the 3-arg version. The in-memory implementation doesn't need tenancyId for the operation (it uses planItemId as key), but accepting it fulfills the SPI contract.

- [ ] **Step 5: Implement in `JpaReactivePlanItemStore`**

The 3-arg `updateStatus` uses `withTenantTransaction(tenancyId, ...)`. The old 2-arg version switches to `withCrossTenantTransaction()` (it has no tenancyId to pass).

- [ ] **Step 6: Implement in NoOp stores**

`NoOpPlanItemStore` and `NoOpReactivePlanItemStore` — add empty 3-arg override.

- [ ] **Step 7: Rename `findDelegated(UUID)` → `findDelegatedCrossTenant(UUID)` on both SPIs**

Use `ide_refactor_rename` on `PlanItemStore.findDelegated` (the 1-arg version) to `findDelegatedCrossTenant`. This updates all implementations and callers automatically.

- [ ] **Step 8: Add tenant-scoped `findDelegated(UUID, String)` to both SPIs**

Add as `default` methods:

In `PlanItemStore`:
```java
default List<PlanItemRecord> findDelegated(UUID caseId, String tenancyId) {
    return findDelegatedCrossTenant(caseId);
}
```

In `ReactivePlanItemStore`:
```java
default Uni<List<PlanItemRecord>> findDelegated(UUID caseId, String tenancyId) {
    return findDelegatedCrossTenant(caseId);
}
```

- [ ] **Step 9: Implement tenant-scoped `findDelegated` in JPA**

`JpaReactivePlanItemStore.findDelegated(UUID caseId, String tenancyId)`:
```java
return withTenantTransaction(tenancyId, () ->
    PlanItemEntity.<PlanItemEntity>find(
            "caseId = ?1 AND status = ?2 AND tenancyId = ?3",
            caseId, TaskStatus.DELEGATED, tenancyId)
        .list()
        .map(list -> list.stream().map(this::toRecord).collect(Collectors.toList())));
```

Verify `findDelegatedCrossTenant` now uses `withCrossTenantTransaction`.

- [ ] **Step 10: Update `BlackboardRegistry.get(UUID, String)` to use tenant-scoped `findDelegated`**

Change `planItemStore.findDelegated(caseId)` → `planItemStore.findDelegated(caseId, tenancyId)` in the `get(UUID, String)` method. The `get(UUID)` method continues using `findDelegatedCrossTenant(caseId)`.

- [ ] **Step 11: Build and run tests**

```bash
mvn install -DskipTests -q && TESTCONTAINERS_RYUK_DISABLED=true mvn clean test -pl casehub-engine-common,casehub-persistence-memory,casehub-persistence-hibernate,casehub-blackboard
```

- [ ] **Step 12: Commit**

```bash
git add -A && git commit -m "refactor(#680): add tenancyId to PlanItemStore updateStatus, rename findDelegated cross-tenant

SPI evolution: updateStatus gains 3-arg overload with tenancyId.
findDelegated(UUID) renamed to findDelegatedCrossTenant(UUID) for explicit
RLS bypass signaling. New findDelegated(UUID, String) for tenant-scoped callers.
BlackboardRegistry.get(UUID, String) uses the tenant-scoped variant.

Refs #680"
```

---

### Task 3: Add tenancyId to 10 event records + reorder 2 existing events

**Files:**
- Modify (common/internal/event/): `SignalReceivedEvent.java`, `BulkSignalReceivedEvent.java`, `ActionGateApprovedEvent.java`, `ActionGateRejectedEvent.java`, `ActionGateExpiredEvent.java`, `ActionGateCancelledEvent.java`, `AgentRoutingEscalationEvent.java`, `ActionGateWorkerFaultedEvent.java`, `HumanTaskScheduleEvent.java`
- Modify (blackboard/event/): `StageActivatedEvent.java`, `StageCompletedEvent.java`, `StageTerminatedEvent.java`
- Modify: all publish sites (handlers) that construct these events
- Modify: all test files that construct these events
- Test: existing handler tests must compile and pass with updated constructors

**Interfaces:**
- Produces: all 12 event records with `tenancyId` as 2nd component (after `caseId`)

This task changes the event records AND their construction sites in one pass — the record signature change breaks all callers immediately, so they must be updated together.

- [ ] **Step 1: Update `SignalReceivedEvent` — add tenancyId after caseId**

New canonical record:
```java
public record SignalReceivedEvent(
    UUID caseId, String tenancyId, String path, Object value,
    String triggerChannelId, String triggerCorrelationId) {
```

Convenience constructor (tenancyId required):
```java
public SignalReceivedEvent(UUID caseId, String tenancyId, String path, Object value) {
    this(caseId, tenancyId, path, value, null, null);
}
```

Update compact constructor validation to include tenancyId null check.

Then update ALL construction sites — use `ide_find_references` on `SignalReceivedEvent` to find every construction site and update each one. Publish sites in handlers use `instance.tenancyId`. Test sites use `TenancyConstants.DEFAULT_TENANT_ID` or the test tenant.

- [ ] **Step 2: Update `BulkSignalReceivedEvent` — add tenancyId after caseId**

New canonical:
```java
public record BulkSignalReceivedEvent(
    UUID caseId, String tenancyId, Map<String, Object> updates,
    String triggerChannelId, String triggerCorrelationId, UUID signalId) {
```

Convenience:
```java
public BulkSignalReceivedEvent(UUID caseId, String tenancyId, Map<String, Object> updates) {
    this(caseId, tenancyId, updates, null, null, null);
}
```

Update all construction sites via `ide_find_references`.

- [ ] **Step 3: Update 5 ActionGate events — add tenancyId after caseId**

`ActionGateApprovedEvent(UUID caseId, String tenancyId, long gateId, String workItemResolution, String approvedBy)`

`ActionGateRejectedEvent(UUID caseId, String tenancyId, long gateId, String workItemResolution, String rejectedBy)`

`ActionGateExpiredEvent(UUID caseId, String tenancyId, long gateId)`

`ActionGateCancelledEvent(UUID caseId, String tenancyId, long gateId)`

Update all construction sites. The three events published from the work-adapter (`Approved`, `Rejected`, `Expired`) — use a placeholder `tenancyId` value at construction sites within this repo's tests. The work-adapter will populate it from `ActionGateScheduleEvent.tenancyId()` per engine#710.

`ActionGateWorkerFaultedEvent` — **reorder**: move tenancyId from 4th to 2nd position:
```java
public record ActionGateWorkerFaultedEvent(
    UUID caseId, String tenancyId, String workerId, String idempotency) {}
```

Update 2 construction sites: `ActionGateRejectedHandler` and `ActionGateExpiredHandler`.

- [ ] **Step 4: Update `AgentRoutingEscalationEvent` — add tenancyId after caseId**

```java
public record AgentRoutingEscalationEvent(
    UUID caseId, String tenancyId, String capabilityName,
    String bindingName, EscalationReason reason) {}
```

Update construction site in `CaseContextChangedEventHandler`.

- [ ] **Step 5: Update `HumanTaskScheduleEvent` — reorder tenancyId from 9th to 2nd**

```java
public record HumanTaskScheduleEvent(
    UUID caseId, String tenancyId, String bindingName, HumanTaskTarget target,
    Map<String, Object> inputData, Set<String> resolvedCandidateGroups,
    Set<String> resolvedCandidateUsers, Instant caseBudgetDeadline,
    Instant expiresAtDeadline) {}
```

Update construction site in `CaseContextChangedEventHandler` and test sites in `HumanTaskTargetDispatchTest`.

- [ ] **Step 6: Update 3 blackboard Stage events — add tenancyId after caseId**

`StageActivatedEvent(UUID caseId, String tenancyId, Stage stage, int instanceIndex)`

`StageCompletedEvent(UUID caseId, String tenancyId, Stage stage, int instanceIndex)`

`StageTerminatedEvent(UUID caseId, String tenancyId, Stage stage)`

Update construction sites in `StageAutocompleteEvaluator`, `StageLifecycleEvaluator`, and any test files.

- [ ] **Step 7: Build full project to catch any missed construction sites**

```bash
mvn install -DskipTests -q && TESTCONTAINERS_RYUK_DISABLED=true mvn clean test -pl casehub-engine-common,engine,casehub-blackboard
```

Use `ide_diagnostics` with `includeBuildErrors: true` to identify any remaining compile errors.

- [ ] **Step 8: Commit**

```bash
git add -A && git commit -m "refactor(#680): thread tenancyId through all event bus messages

Add tenancyId to 10 event records (after caseId, per convention).
Reorder tenancyId in ActionGateWorkerFaultedEvent and HumanTaskScheduleEvent
from trailing position to after caseId. All publish sites populate from
CaseInstance.tenancyId.

Refs #680"
```

---

### Task 4: Remove test workarounds

**Files:**
- Modify: `engine/src/main/java/io/casehub/testing/TestCaseInstanceRepository.java`
- Modify: `engine/src/main/java/io/casehub/testing/TestReactiveCaseInstanceRepository.java`
- Test: full test suite must pass without the workaround

**Interfaces:**
- Consumes: correct tenancyId threading from Tasks 1-3

- [ ] **Step 1: Remove `findByUuid` override from `TestCaseInstanceRepository`**

Remove the entire `findByUuid(UUID, String)` method override. The class becomes an empty subclass (just the `@Alternative @Priority(1) @ApplicationScoped` annotations and `extends InMemoryCaseInstanceRepository`).

The parent `InMemoryCaseInstanceRepository.findByUuid(UUID, String)` now correctly enforces tenancyId filtering — and all callers pass the correct tenancyId because of Tasks 1-3.

- [ ] **Step 2: Remove `findByUuid` override from `TestReactiveCaseInstanceRepository`**

Same — remove the method override. Empty subclass.

- [ ] **Step 3: Run full test suite**

```bash
mvn install -DskipTests -q && TESTCONTAINERS_RYUK_DISABLED=true mvn clean test
```

If any test fails, the failure indicates a call site that was relying on the workaround and still passes incorrect tenancyId. Fix the call site, not the test — the workaround is gone for good.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "refactor(#680): remove TestCaseInstanceRepository tenancyId workaround

TestCaseInstanceRepository and TestReactiveCaseInstanceRepository no longer
override findByUuid to ignore tenancyId. The parent InMemoryCaseInstanceRepository
enforces tenancyId correctly, and all callers now pass the correct value.

Closes #680"
```
