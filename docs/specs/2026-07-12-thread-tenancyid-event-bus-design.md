# Thread tenancyId through event bus messages

**Issue:** engine#680  
**Date:** 2026-07-12  
**Status:** Design

## Problem

Event bus handlers that receive `UUID caseId` often need to call tenant-scoped repository methods. Two design flaws make this unreliable:

1. **`TenantAwareRepository.withTenantTransaction()`** reads `CurrentPrincipal.tenancyId()` — an ambient thread-local — instead of using the explicit `tenancyId` parameter every SPI method already carries. When a handler runs on a Vert.x event bus thread, the `CurrentPrincipal` may be the system principal or a different tenant's principal, causing the PostgreSQL RLS `SET LOCAL "casehub.tenancy_id"` to set the wrong tenant. With RLS enabled, operations silently fail or see the wrong rows. **This is the root cause — Layer 1 fixes it.**

2. **10 event records** carry `UUID caseId` without `tenancyId`. Handlers currently resolve correct tenant context via `CaseInstanceCache.get(caseId)` (which has `instance.tenancyId`) or `WorkerExecutionRecoveryService.loadOrRestoreCaseInstance(caseId)` (cross-tenant recovery), so this is not the cause of the RLS bug. However, it leaves tenant context implicit — dependent on cache state and recovery paths rather than explicit in the event payload. **Layer 2 is defense-in-depth: making tenant context explicit removes this dependency and makes the system more robust.**

`TestCaseInstanceRepository` and `TestReactiveCaseInstanceRepository` mask the problem by overriding `findByUuid(UUID, String)` to ignore the `tenancyId` parameter.

## Design

Three layers, root-first.

### Layer 1 — Parameterize `withTenantTransaction()`

**`TenantAwareRepository`** changes from:

```java
@Inject CurrentPrincipal currentPrincipal;

protected <T> Uni<T> withTenantTransaction(Supplier<Uni<T>> work) {
    String tenancyId = currentPrincipal.tenancyId();
    // ...
}
```

To:

```java
// No CurrentPrincipal injection

protected <T> Uni<T> withTenantTransaction(String tenancyId, Supplier<Uni<T>> work) {
    if (tenancyId == null || tenancyId.contains("'") || tenancyId.contains("\\")) {
        throw new IllegalStateException("Invalid tenancyId: " + tenancyId);
    }
    String sql = "SET LOCAL \"casehub.tenancy_id\" = '" + tenancyId + "'";
    // ... same Panache.withTransaction() chain
}
```

**Call site updates (27 sites across 5 JPA repos):**

Every SPI method already receives `String tenancyId` — each `withTenantTransaction()` call becomes `withTenantTransaction(tenancyId, () -> ...)`. Mechanical.

**Previously tenant-agnostic methods** — two methods previously omitted `tenancyId` from their SPI signatures because they use globally unique keys. Both gain `tenancyId` for defense-in-depth — RLS guards against wrong-ID bugs even when keys are globally unique:

| Method | Current | Fix |
|--------|---------|-----|
| `updateStatus(planItemId, status)` | `withTenantTransaction()` (ambient) | Add `String tenancyId` to SPI → `withTenantTransaction(tenancyId, ...)` |
| `findDelegated(caseId)` | `withTenantTransaction()` (ambient) | Add `String tenancyId` overload → `withTenantTransaction(tenancyId, ...)`; rename no-tenancyId variant to `findDelegatedCrossTenant` |

`updateStatus(String planItemId, TaskStatus status, String tenancyId)` — added to both `PlanItemStore` and `ReactivePlanItemStore`. Callers have `CaseInstance` or `PlanItemRecord` in scope, both of which carry tenancyId.

`findDelegated(UUID caseId, String tenancyId)` — new tenant-scoped overload for callers that have tenancyId (e.g. `BlackboardRegistry.get(UUID, String)`). The existing no-tenancyId variant is renamed to `findDelegatedCrossTenant(UUID caseId)` — JPA implementation uses `withCrossTenantTransaction()`. The rename makes the RLS bypass explicit, matching the platform convention (`withCrossTenantTransaction`, `CrossTenantCaseInstanceRepository`, `@CrossTenant`). This is necessary for `BlackboardRegistry.get(UUID)` which bootstraps tenancyId from query results (chicken-and-egg: must query to discover tenant, so cannot provide one).

**Affected JPA repos:**

| Repository | `withTenantTransaction` call sites |
|------------|------------------------------------|
| `JpaReactiveCaseInstanceRepository` | 7 |
| `JpaReactiveEventLogRepository` | 8 |
| `JpaReactiveSubCaseGroupRepository` | 6 |
| `JpaReactivePlanItemStore` | 4 (save, updateStatus, findByCaseId, findDelegated) |
| `JpaReactiveCaseMetaModelRepository` | 2 |

**CurrentPrincipal removal safety:** `TenantAwareRepository` has 7 subclasses. The 2 cross-tenant repos (`JpaReactiveCrossTenantCaseInstanceRepository`, `JpaReactiveCrossTenantEventLogRepository`) inherit the `currentPrincipal` field but never access it — they only call `withCrossTenantTransaction()`. The 5 tenant-scoped repos access `currentPrincipal` only through `withTenantTransaction()`, which is the method being changed to accept an explicit parameter. No subclass accesses `currentPrincipal` directly. Removal is safe.

### Layer 2 — Add `tenancyId` to 10 Vert.x event bus records

**Scope:** This layer covers Vert.x event bus messages only. CDI events (e.g. `SubCaseGroupLifecycleEvent`) are out of scope — filed as engine#709 for consistency.

Events that carry `UUID caseId` but no tenant context:

| Event record | Module | Current fields (relevant) |
|---|---|---|
| `SignalReceivedEvent` | common | `UUID caseId, String path, Object value, String triggerChannelId, String triggerCorrelationId` |
| `BulkSignalReceivedEvent` | common | `UUID caseId, Map updates, String triggerChannelId, String triggerCorrelationId, UUID signalId` |
| `ActionGateApprovedEvent` | common | `UUID caseId, long gateId, String workItemResolution, String approvedBy` |
| `ActionGateRejectedEvent` | common | `UUID caseId, long gateId, String workItemResolution, String rejectedBy` |
| `ActionGateExpiredEvent` | common | `UUID caseId, long gateId` |
| `ActionGateCancelledEvent` | common | `UUID caseId, long gateId` |
| `AgentRoutingEscalationEvent` | common | `UUID caseId, String capabilityName, String bindingName, EscalationReason reason` |
| `StageActivatedEvent` | blackboard | `UUID caseId, Stage stage, int instanceIndex` |
| `StageCompletedEvent` | blackboard | `UUID caseId, Stage stage, int instanceIndex` |
| `StageTerminatedEvent` | blackboard | `UUID caseId, Stage stage` |

Each gets `String tenancyId` added as a record component. Position: immediately after `caseId`, establishing a consistent convention. Two of the four existing events with explicit `tenancyId` already place it after `caseId` (`WorkerRetriesExhaustedEvent`, `ActionGateScheduleEvent`). The other two place it last (`ActionGateWorkerFaultedEvent` 4th of 4, `HumanTaskScheduleEvent` 9th of 9). This spec establishes "after caseId" as the convention — it groups identity fields (which case, which tenant) for readability. `ActionGateWorkerFaultedEvent` and `HumanTaskScheduleEvent` are migrated to match.

**Convenience constructors:** where existing convenience constructors omit optional trailing fields, they pass `null` for those fields. `tenancyId` is NOT optional — no convenience constructors that omit it. This prevents accidental creation of events without tenant context.

New signatures after inserting `tenancyId` after `caseId`:

- `SignalReceivedEvent(UUID caseId, String tenancyId, String path, Object value, String triggerChannelId, String triggerCorrelationId)` — canonical
- `SignalReceivedEvent(UUID caseId, String tenancyId, String path, Object value)` — convenience (two consecutive `String` params: `tenancyId` and `path` have distinct semantics; IDE parameter hints make this unambiguous at call sites)
- `BulkSignalReceivedEvent(UUID caseId, String tenancyId, Map<String, Object> updates, String triggerChannelId, String triggerCorrelationId, UUID signalId)` — canonical
- `BulkSignalReceivedEvent(UUID caseId, String tenancyId, Map<String, Object> updates)` — convenience (no ambiguity: `String` followed by `Map`)

**Publish site updates:**

All publish sites are in handlers that already have `CaseInstance` (from the event they received, or from `CaseInstanceCache`). The tenancyId comes from `instance.tenancyId` at every site.

For the three `ActionGate*Event` types published by the work-adapter (separate repo): the event records gain the field here; the work-adapter must populate it from the `ActionGateScheduleEvent.tenancyId()` it received when creating the gate WorkItem. Filed as engine#710.

### Layer 3 — Remove test workarounds

| Class | Workaround | Fix |
|-------|-----------|-----|
| `TestCaseInstanceRepository` | Overrides `findByUuid(UUID, String)` to delegate to `findByUuid(UUID)`, ignoring tenancyId | Remove override; parent `InMemoryCaseInstanceRepository.findByUuid(UUID, String)` enforces tenancyId correctly |
| `TestReactiveCaseInstanceRepository` | Same pattern | Remove override |

With Layer 1 in place, the JPA repos use the explicit tenancyId from the SPI call, not from `CurrentPrincipal`. The in-memory repos already filter correctly by tenancyId. The test workarounds become unnecessary.

## Events already carrying tenant context (unchanged)

For reference — these 14 event types already carry tenant context and are not modified:

**Via `CaseInstance` (12):** `CaseContextChangedEvent`, `CaseStartedEvent`, `CaseStatusChanged`, `GoalReachedEvent`, `WorkerScheduleEvent`, `WorkflowExecutionCompleted`, `WorkerOutcomeResolvedEvent`, `MilestoneReachedEvent`, `MilestoneActivatedEvent`, `MilestoneCompletedEvent`, `MilestoneSLAViolatedEvent`, `SubCaseScheduleEvent`

**Via explicit `String tenancyId` (2):** `WorkerRetriesExhaustedEvent`, `ActionGateScheduleEvent`

## Migrated existing events (component reorder)

Two events already carry `tenancyId` but place it last instead of after `caseId`. They are reordered to match the convention established in Layer 2:

| Event | Current signature | Change | Blast radius |
|-------|------------------|--------|-------------|
| `ActionGateWorkerFaultedEvent` | `(UUID caseId, String workerId, String idempotency, String tenancyId)` | tenancyId moves from 4th to 2nd | 2 construction sites (`ActionGateRejectedHandler:162`, `ActionGateExpiredHandler:146`), 2 consumer parameter lists (`ActionGateExpiredPlanItemHandler`, `ActionGateRejectedPlanItemHandler`) |
| `HumanTaskScheduleEvent` | `(UUID caseId, String bindingName, HumanTaskTarget target, ..., String tenancyId)` | tenancyId moves from 9th to 2nd | 1 construction site (`CaseContextChangedEventHandler:544`), test sites in `HumanTaskTargetDispatchTest` |

## Cross-repo follow-up (engine#710)

`ActionGateApprovedEvent`, `ActionGateRejectedEvent`, `ActionGateExpiredEvent` are published by `ActionGateCompletionApplier` in the `casehub-work-engine-adapter` module (work repo). The event records are defined in this repo (`casehub-engine-common`) and gain the `tenancyId` field here. The work-adapter must be updated to populate it — tracked as engine#710.

## Test strategy

- **Layer 1 tests:** existing `JpaReactive*RepositoryTest` suites verify RLS behaviour. Add a test confirming `withTenantTransaction` uses the passed tenancyId, not an ambient principal.
- **Layer 2 tests:** update all test sites that construct event records (add tenancyId parameter). Verify handlers receive and use the correct tenancyId.
- **Layer 3 tests:** remove `TestCaseInstanceRepository`/`TestReactiveCaseInstanceRepository` overrides. Existing test suites must pass with `InMemoryCaseInstanceRepository`'s native tenancyId enforcement.
