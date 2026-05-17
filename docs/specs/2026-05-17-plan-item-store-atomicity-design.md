# Design Spec: PlanItemStore — Durable PlanItem Status and Handler Atomicity

**Date:** 2026-05-17  
**Issue:** casehubio/engine#273  
**Deferred:** casehubio/engine#274 (BlackboardRegistry hydration on restart)

---

## Problem

`HumanTaskScheduleHandler` calls `item.markRunning()` (in-memory blackboard state) then creates a WorkItem via `workItemService.create()` or `workItemTemplateService.instantiate()` — each operating in its own `@Transactional` boundary. If WorkItem creation fails after `markRunning()`, the PlanItem is stuck RUNNING with no WorkItem. The engine will not re-schedule it (PlanItem is no longer PENDING), and the case is permanently blocked.

This affects both inline and template modes. Protocol `PP-20260517-cbf836` formalises the ordering rule; this design enforces it structurally.

The deeper issue: PlanItem status is purely in-memory, so it cannot participate in the JPA transaction that creates the WorkItem. The fix is to make PlanItem status durable — following the platform's Store SPI pattern — and wire the handler to write both records atomically.

---

## Design

### 1. `PlanItemStore` and `ReactivePlanItemStore` SPIs

Two new SPI interfaces in the `blackboard` module (Tier 2 — CDI allowed, no JPA), following the ledger dual-variant pattern (`LedgerEntryRepository` + `ReactiveLedgerEntryRepository`).

**Blocking** — for the work-adapter and any blocking consumer:

```java
// blackboard/src/main/java/io/casehub/blackboard/store/PlanItemStore.java
public interface PlanItemStore {
    void save(UUID caseId, PlanItem item);
    void updateStatus(String planItemId, PlanItem.PlanItemStatus status);
    List<PlanItem> findByCaseId(UUID caseId);
}
```

**Reactive** — for engine runtime handlers on Vert.x IO threads:

```java
// blackboard/src/main/java/io/casehub/blackboard/store/ReactivePlanItemStore.java
public interface ReactivePlanItemStore {
    Uni<Void> save(UUID caseId, PlanItem item);
    Uni<Void> updateStatus(String planItemId, PlanItem.PlanItemStatus status);
    Uni<List<PlanItem>> findByCaseId(UUID caseId);
}
```

`PlanItemStore` is injected by the work-adapter (blocking). `ReactivePlanItemStore` is available for future reactive engine consumers.

### 2. Implementations

**JPA — `persistence-hibernate` module:**

New `PlanItemEntity` JPA entity:

| Column | Type | Notes |
|--------|------|-------|
| `plan_item_id` | `VARCHAR(36)` | PK — UUID string from `PlanItem.getPlanItemId()` |
| `case_id` | `UUID` | FK-like grouping key |
| `binding_name` | `VARCHAR(255)` | |
| `status` | `VARCHAR(50)` | Enum string (`PENDING`, `RUNNING`, etc.) |
| `created_at` | `TIMESTAMP` | |

`JpaPlanItemStore` — blocking, uses `EntityManager` directly (no Panache static methods; same pattern as `JpaLedgerEntryRepository`).

`JpaReactivePlanItemStore` — reactive, uses `Panache.withTransaction()` and `Uni<>`.

**In-memory — `persistence-memory` module:**

`MemoryPlanItemStore` and `MemoryReactivePlanItemStore` — `@Alternative @Priority(1)`, backed by a `ConcurrentHashMap<String, PlanItem>` keyed by `planItemId`. The reactive variant wraps results with `Uni.createFrom().item()`. Activated in tests via `selected-alternatives`; no JPA or datasource required.

The `BlackboardRegistry` is unchanged — it continues to hold live `PlanItem` objects. The store provides the durable backing record.

### 3. Handler wiring

`HumanTaskScheduleHandler` receives `@Inject PlanItemStore planItemStore` and is annotated `@Transactional`.

Execution order in both modes:

```
1. Validate PlanItem is PENDING             ← in-memory guard; fast-fail before any DB work
2. planItemStore.updateStatus(planItemId, RUNNING)   ← JPA write; joins handler @Transactional
3. workItemService.create() or instantiate()          ← joins same @Transactional
4. item.markRunning()                                 ← in-memory sync; only reached on clean commit
```

If steps 2 or 3 throw, the transaction rolls back both DB writes atomically. `item.markRunning()` is never reached — PlanItem stays PENDING in memory and in the store. Protocol `PP-20260517-cbf836` is now enforced by the transaction boundary, not by convention.

`@ConsumeEvent(blocking=true)` is already present on the handler. Adding `@Transactional` on a blocking worker thread is safe and consistent with CLAUDE.md conventions.

**`DefaultCasePlanModel.addPlanItem()`** calls `planItemStore.save(caseId, item)` when a PlanItem first enters the system, so every PlanItem has a durable record from creation, not just from its first status transition.

`DefaultCasePlanModel` takes `PlanItemStore` as a constructor argument. `BlackboardRegistry.getOrCreate()` injects `PlanItemStore` via CDI and passes it through `DefaultCasePlanModel`'s constructor.

### 4. Deferred

**engine#274** — On restart, `BlackboardRegistry` should hydrate PlanItem status from `PlanItemStore.findByCaseId()` rather than starting all PlanItems as PENDING. Cases with RUNNING PlanItems but no matching WorkItem (zombie detection) should be flagged. Blocked on this issue.

---

## Module changes

| Module | Change |
|--------|--------|
| `blackboard` | Add `PlanItemStore` + `ReactivePlanItemStore` interfaces; `DefaultCasePlanModel` takes `PlanItemStore` constructor arg |
| `persistence-hibernate` | Add `PlanItemEntity`, `JpaPlanItemStore`, `JpaReactivePlanItemStore` |
| `persistence-memory` | Add `MemoryPlanItemStore`, `MemoryReactivePlanItemStore` |
| `casehub-work-adapter` | `HumanTaskScheduleHandler`: add `@Transactional`, inject `PlanItemStore`, invert execution order |

---

## Testing

- `PlanItemStore` contract test (abstract test class, one concrete test per implementation)
- `HumanTaskScheduleHandlerTest`: new case — WorkItem creation fails → PlanItem stays PENDING in memory and in store
- Existing handler tests continue to pass unchanged
