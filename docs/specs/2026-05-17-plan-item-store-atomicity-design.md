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

### 1. `PlanItemStatus`, `PlanItemRecord`, `PlanItemStore`, `ReactivePlanItemStore`

**`PlanItemStatus`** is a standalone enum in `casehub-engine-common` (`io.casehub.engine.internal.model`), extracted from the former nested `PlanItem.PlanItemStatus`. Placing it in `common` means the Store SPIs can reference it without depending on the `blackboard` module.

**`PlanItemRecord`** is a lightweight Java record (also in `common`) returned by `findByCaseId`:

```java
public record PlanItemRecord(UUID caseId, String planItemId, String bindingName,
                              PlanItemStatus status, Instant createdAt) {}
```

**`PlanItemStore`** (blocking) and **`ReactivePlanItemStore`** (`Uni<>` mirror) are SPI interfaces in `casehub-engine-common` (`io.casehub.engine.spi`), following the ledger dual-variant pattern (`LedgerEntryRepository` + `ReactiveLedgerEntryRepository`):

```java
public interface PlanItemStore {
    void save(UUID caseId, String planItemId, String bindingName, PlanItemStatus status, Instant createdAt);
    void updateStatus(String planItemId, PlanItemStatus status);
    List<PlanItemRecord> findByCaseId(UUID caseId);
}

public interface ReactivePlanItemStore {
    Uni<Void> save(UUID caseId, String planItemId, String bindingName, PlanItemStatus status, Instant createdAt);
    Uni<Void> updateStatus(String planItemId, PlanItemStatus status);
    Uni<List<PlanItemRecord>> findByCaseId(UUID caseId);
}
```

`PlanItemStore` is injected by the work-adapter (blocking). `ReactivePlanItemStore` is available for future reactive engine consumers.

### 2. Implementations

**No-op defaults — `blackboard` module:**

`NoOpPlanItemStore` and `NoOpReactivePlanItemStore` are `@DefaultBean @ApplicationScoped` beans in the `blackboard` module. They yield automatically to any consumer-provided implementation (e.g. `JpaPlanItemStore` from work-adapter). When no real store is deployed, PlanItem status is tracked in-memory only.

**JPA reactive — `persistence-hibernate` module:**

`PlanItemEntity` (`@Entity @Table(name="plan_item")`, extends reactive `PanacheEntity`) with indexed columns:

| Column | Type | Index |
|--------|------|-------|
| `plan_item_id` | `VARCHAR(36)`, unique | `idx_plan_item_plan_item_id` |
| `case_id` | `UUID` | `idx_plan_item_case_id` |
| `binding_name` | `VARCHAR(255)` | — |
| `status` | `VARCHAR(50)`, enum | — |
| `created_at` | `TIMESTAMP` | — |

`JpaReactivePlanItemStore` — reactive, uses `Panache.withTransaction()` and `Uni<>`.

**JPA blocking — `casehub-engine-work-adapter` module:**

`WorkAdapterPlanItemEntity` maps to the same `plan_item` table using standard blocking JPA (`EntityManager`). Placed in `work-adapter` because it must share the blocking persistence unit with `WorkItemService` for writes to participate in the same JTA transaction.

`JpaPlanItemStore` — `@ApplicationScoped`, injects `EntityManager`, `updateStatus()` uses `em.flush()` + JPQL bulk UPDATE + `em.clear()` to handle L1 cache/bulk-DML ordering.

**In-memory — `persistence-memory` module:**

`InMemoryPlanItemStore` and `InMemoryReactivePlanItemStore` — `@Alternative @ApplicationScoped`, backed by a `ConcurrentHashMap<String, PlanItemRecord>` keyed by `planItemId`. Activated in tests via `quarkus.arc.selected-alternatives`.

### 3. Handler wiring

`HumanTaskScheduleHandler` receives `@Inject PlanItemStore planItemStore` and is annotated `@Transactional`.

Execution order in both modes:

```
1. Validate PlanItem is PENDING              ← in-memory guard; fast-fail before any DB work
2. WorkItem creation (create or instantiate) ← joins handler @Transactional
3. planItemStore.save(caseId, planItemId, bindingName, RUNNING, createdAt)
                                             ← JPA write; joins same @Transactional
4. item.markRunning()                        ← in-memory sync; only reached on clean commit
```

If steps 2 or 3 throw, the transaction rolls back both DB writes atomically. `item.markRunning()` is never reached — PlanItem stays PENDING in memory and in the store. Protocol `PP-20260517-cbf836` is now enforced by the transaction boundary, not by convention.

`save()` is called with `RUNNING` status directly (not a two-step save-then-updateStatus), because the handler is the first point where the PlanItem's transition to RUNNING is safe to record — `addPlanItem()` runs on the reactive Vert.x IO thread and has no JTA context.

`@ConsumeEvent(blocking=true)` is already present on the handler. `@Transactional` on a blocking worker thread is safe and consistent with CLAUDE.md conventions.

### 4. Deferred

- **engine#274** — On restart, `BlackboardRegistry` should hydrate PlanItem status from `PlanItemStore.findByCaseId()`. Cases with RUNNING PlanItems but no matching WorkItem (zombie detection) should be flagged. Blocked on this issue.
- **engine#279** — `JpaReactivePlanItemStore.updateStatus()` needs flush before find (reactive L1 cache concern).
- **engine#280** — Contract test for `JpaReactivePlanItemStore`.
- **engine#281** — `FailingWorkItemStore` test double leaks across all `@QuarkusTest` classes in work-adapter.

---

## Module changes

| Module | Change |
|--------|--------|
| `casehub-engine-common` | Add `PlanItemStatus` enum, `PlanItemRecord` record, `PlanItemStore` + `ReactivePlanItemStore` SPI interfaces, `PlanItemStoreContractTest` (abstract) |
| `blackboard` | Extract `PlanItemStatus` from `PlanItem`; add `NoOpPlanItemStore` + `NoOpReactivePlanItemStore` (`@DefaultBean`) |
| `persistence-hibernate` | Add `PlanItemEntity`, `JpaReactivePlanItemStore` |
| `persistence-memory` | Add `InMemoryPlanItemStore`, `InMemoryReactivePlanItemStore` (`@Alternative`) |
| `casehub-engine-work-adapter` | Add `WorkAdapterPlanItemEntity`, `JpaPlanItemStore`; update `HumanTaskScheduleHandler` |

---

## Testing

- `PlanItemStoreContractTest` — abstract contract test in `casehub-engine-common`; `MemoryPlanItemStoreContractTest` is the concrete subclass
- `JpaPlanItemStoreTest` — `@QuarkusTest` for the blocking JPA store in `work-adapter`
- `HumanTaskScheduleHandlerTest`: new atomicity test — WorkItem creation fails → PlanItem stays PENDING in memory and store not updated to RUNNING
- Existing handler tests updated to assert store state after success
