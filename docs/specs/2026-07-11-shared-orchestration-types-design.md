# Shared Orchestration Types — Design Spec

**Issue:** #700  
**Date:** 2026-07-11  
**Status:** Draft  
**Scope:** Task + TaskLifecycle + TaskSnapshot. Plan deferred to #694.

## Problem

CaseHub has three coordination models (blackboard, workflow, agentic) that evolved independently with parallel type hierarchies. The same concepts — unit of work, executor, lifecycle — have different names, different shapes, and different module homes. This makes cross-model orchestration impossible without adapters and unified monitoring impossible without per-model glue code.

## Prior Work (this branch)

- `RoutingResult` sealed interface — replaces `AgentAssignment` across all routing strategies
- `Assignment` record — unified selection result
- `ExecutorRef` interface — shared executor identity (`name()`, `description()`)
- `OutcomeKind` enum — shared outcome taxonomy
- `PlanItem.description` field — description thread from `PlannedTask` through `PlanItem` to `PlannedAction`

## Design

### Principle: unify at the root

The shared types are not wrappers or adapters — they ARE the types. Both coordination models use them directly. Engine's internal types (`PlanItemStatus`) are replaced, not wrapped.

### Type 1: TaskStatus (enum, replaces PlanItemStatus)

**Location:** `io.casehub.api.model.TaskStatus`

```java
public enum TaskStatus {
  PENDING, RUNNING, DELEGATED, SUSPENDED,
  COMPLETED, FAULTED, REJECTED, OBSOLETE, CANCELLED;

  public boolean isTerminal() { ... }
  public boolean isActive() { ... }
}
```

**Rationale for enum over sealed variants:** Status is a phase label. Contextual data (which agent, what iteration, why it faulted) belongs on the task or in events, not on the status. Sealed variants fight CAS transitions (`compareAndSet` needs identity equality — sealed records with data fields break this) and JPA persistence (`@Enumerated(STRING)`).

**All 9 states are universal, not engine-specific:**

| State | Meaning | Adoption |
|-------|---------|----------|
| PENDING | Work defined, not yet started | Engine: PlanItemStatus.PENDING. Blocks: ExecutionState.Idle, SubTaskStatus.PENDING |
| RUNNING | Actively executing | Engine: PlanItemStatus.RUNNING. Blocks: ExecutionState.Running |
| DELEGATED | Control passed to external actor; waiting for completion signal | Engine: PlanItemStatus.DELEGATED. Blocks: ExecutionState.WaitingForAgent |
| SUSPENDED | Execution paused; slot occupied, resumes without re-dispatch | Engine: PlanItemStatus.SUSPENDED. Blocks: ExecutionState.WaitingForEvent (anticipated) |
| COMPLETED | Finished successfully | Engine: PlanItemStatus.COMPLETED. Blocks: ExecutionState.Complete, SubTaskStatus.COMPLETE |
| FAULTED | Failed — system failure, deadline breach, or gate rejection | Engine: PlanItemStatus.FAULTED. Blocks: ExecutionState.Faulted, SubTaskStatus.ERROR |
| REJECTED | Actor deliberately refused the work | Engine: PlanItemStatus.REJECTED. Blocks: anticipated (agent decline, consensus rejection) |
| OBSOLETE | Context changed, work became irrelevant | Engine: PlanItemStatus.OBSOLETE. Blocks: anticipated (HTN replanning invalidation) |
| CANCELLED | Deliberate stop by human or system | Engine: PlanItemStatus.CANCELLED. Blocks: ExecutionState.Cancelled |

States marked "anticipated" for blocks are not implemented in `ExecutionState` today but represent coordination concepts that any model involving actors or replanning will encounter.

**Blocks ExecutionState mapping:** `Idle` → PENDING, `Running` → RUNNING, `WaitingForAgent` → DELEGATED, `WaitingForEvent` → SUSPENDED, `Complete` → COMPLETED, `Faulted` → FAULTED, `Cancelled` → CANCELLED.

**Blocks SubTaskStatus mapping:** `PENDING` → PENDING, `COMPLETE` → COMPLETED, `ERROR` → FAULTED.

**Transition invariants:** TaskStatus defines the state universe. Each adopter defines its own valid transitions (PlanItem's CAS state machine is one such definition). Universal invariants that all adopters must respect:

1. Terminal states (COMPLETED, FAULTED, REJECTED, OBSOLETE, CANCELLED) are final — no transitions out
2. PENDING is always the initial state
3. `isTerminal()` and `isActive()` partitions are exhaustive and non-overlapping

PlanItem's specific transition graph (for reference, not as a requirement for other adopters):
- PENDING → RUNNING | DELEGATED
- RUNNING | DELEGATED → COMPLETED
- DELEGATED → REJECTED | SUSPENDED
- SUSPENDED → DELEGATED (resume)
- Any active state → FAULTED | OBSOLETE | CANCELLED

**OutcomeKind → TaskStatus mapping:** `OutcomeKind` (shared outcome taxonomy) determines the terminal status after task completion:

| OutcomeKind | TaskStatus | Notes |
|-------------|------------|-------|
| SUCCESS | COMPLETED | |
| DECLINED | REJECTED | |
| FAILED | FAULTED | |
| EXPIRED | FAULTED | Deadline breach is a fault |
| ESCALATED | (context-dependent) | May remain DELEGATED (new actor) or FAULTED (unresolvable) |

**Migration:** `PlanItemStatus` is deleted. All references migrate to `TaskStatus`. IntelliJ rename refactoring handles the mechanical change.

### Type 2: ExecutorRef on PlanItem

**`ExecutorRef`** (existing, `api/model/`) stays unchanged: `name()`, `description()`, factory methods.

**PlanItem stores `ExecutorRef`** instead of bare `String workerName`:

- `PlanItem.create(bindingName, executorRef, priority, target, description)` — `ExecutorRef` replaces `String workerName`
- `PlanItem.restore(planItemId, bindingName, executorRef, target, status, createdAt, description)` — new `ExecutorRef` parameter
- `PlanItem.getExecutor()` — returns `ExecutorRef`
- `PlanItem.executorName()` — derived convenience: `executor != null ? executor.name() : null`
- `PlanItem.getPlanItemId()` — deprecated, callers migrate to `id()` (from `TaskDescriptor`). Both return `planItemId`. Migration is mechanical (IntelliJ inline + rename)

**Persistence threading:**

| Type | Change |
|------|--------|
| `PlanItemRecord` | `executorName` + `executorDescription` fields (flat, no interface) |
| `PlanItemSaveRequest` | `executorName` + `executorDescription` fields |
| `PlanItemEntity` (JPA) | `executor_name` + `executor_description` columns |
| `PlanItemRestorer` | Creates `ExecutorRef.of(name, description)` from persisted columns |
| `InMemoryPlanItemStore` | Extracts name/description from `ExecutorRef` |

**Downstream compatibility:** Events and handlers (`WorkerScheduleEvent`, `QuartzWorkerExecutionJob`, EventLog metadata) continue using `executorName()` string. They migrate to full `ExecutorRef` incrementally — not in this scope.

**Blocks integration (deferred):** `AgentRef extends ExecutorRef` is a blocks repo change. Each sealed variant provides `name()`/`description()`: `WorkerAgent` → `worker.name()`, `ChannelAgent` → `"channel:" + channelId`, `HumanAgent` → task type name. At that point, PlanItem can store any `AgentRef` variant as its executor.

### Type 3: TaskDescriptor (behavioral interface)

**Location:** `io.casehub.api.model.TaskDescriptor`

```java
public interface TaskDescriptor {
    String id();
    @Nullable String description();
    @Nullable ExecutorRef executor();
    TaskStatus status();
    Instant createdAt();

    default TaskSnapshot snapshot() {
        return new TaskSnapshot(id(), description(),
            executor() != null ? executor().name() : null,
            executor() != null ? executor().description() : null,
            status(), createdAt());
    }
}
```

**PlanItem implements TaskDescriptor:**

| Method | Delegation |
|--------|-----------|
| `id()` | `planItemId` |
| `description()` | `description` field |
| `executor()` | `executor` field (new `ExecutorRef`) |
| `status()` | `getStatus()` (now returns `TaskStatus`) |
| `createdAt()` | `createdAt` field |

**Why no `parentId()` on the interface:** Stage containment is a blackboard-specific concept. Plan structure belongs in the deferred Plan type (#694).

**Blocks adoption (deferred):** `PlannedTask implements TaskDescriptor` — `status()` returns `TaskStatus.PENDING`, `executor()` returns its `AgentRef` (extends `ExecutorRef`).

### Type 4: TaskSnapshot (read model)

**Location:** `io.casehub.api.model.TaskSnapshot`

```java
public record TaskSnapshot(
    String id,
    @Nullable String description,
    @Nullable String executorName,
    @Nullable String executorDescription,
    TaskStatus status,
    Instant createdAt
) {}
```

**Why flat Strings not `ExecutorRef`:** Snapshots cross serialization boundaries — event logs, dashboards, JSON APIs. Flat strings are universally serializable with no deserializer dependency. Both `executorName` and `executorDescription` are included because dashboards consume snapshots directly and need human-readable executor identity without a separate lookup.

**Projection:** `TaskDescriptor.snapshot()` is a default method. Any task descriptor produces a snapshot with zero glue code.

## Module dependency

```
worker-api (foundation)
    ↑
api (TaskStatus, TaskDescriptor, TaskSnapshot, ExecutorRef)
    ↑
common (PlanItemRecord, PlanItemSaveRequest, stores)
    ↑
blackboard (PlanItem implements TaskDescriptor)
```

No new module dependencies. `common` already depends on `api`.

## Migration scope (this branch)

1. Create `TaskStatus` in `api/model/`, delete `PlanItemStatus` from `common/internal/model/`
2. Create `TaskDescriptor` and `TaskSnapshot` in `api/model/`
3. PlanItem: add `ExecutorRef executor` field, implement `TaskDescriptor`, remove `workerName`
4. PlanItem: deprecate `getPlanItemId()`, migrate all callers to `id()`
5. Flyway migration: add `executor_name` (VARCHAR 255, nullable) and `executor_description` (VARCHAR 1000, nullable) columns to `plan_item` table
6. Thread through persistence: `PlanItemRecord`, `PlanItemSaveRequest`, `PlanItemEntity`, `PlanItemRestorer`, `InMemoryPlanItemStore`, `JpaReactivePlanItemStore`
7. Update `PlanItem.restore()` signature to accept `ExecutorRef`
8. Update contract tests (`PlanItemStoreContractTest`, `ReactivePlanItemStoreContractTest`)
9. Update all `PlanItemStatus` references across the codebase
10. Update all PlanItem creation sites (pass `ExecutorRef` instead of `String workerName`)

## Not in scope

- Blocks repo changes (`AgentRef extends ExecutorRef`, `PlannedTask implements TaskDescriptor`, `SubTaskStatus` → `TaskStatus`) — tracked as issues to file (see below)
- Shared Plan type (deferred to #694)
- Downstream event/handler migration from `executorName()` to `ExecutorRef` (incremental, driven by cross-model dispatch needs)

## Issues to file

These deferred items must be captured as GitHub issues before this branch merges:

| Repo | Issue | Description |
|------|-------|-------------|
| casehubio/blocks | `AgentRef extends ExecutorRef` | Each AgentRef variant implements `name()`/`description()` from ExecutorRef |
| casehubio/blocks | `PlannedTask implements TaskDescriptor` | PlannedTask gains `id` (UUID, generated at construction) and `createdAt` (Instant) fields — record shape change cascading to all construction sites (`DecompositionStrategy` impls, `HtnBuilder`, pattern builders, tests). `status()` returns PENDING; `executor()` requires `AgentRef extends ExecutorRef` (separate issue) |
| casehubio/blocks | `SubTaskStatus` → `TaskStatus` | Replace conversation SubTaskStatus with shared TaskStatus |
| casehubio/engine | Event/handler `ExecutorRef` migration | Migrate `WorkerScheduleEvent`, `QuartzWorkerExecutionJob`, EventLog from `executorName()` string to full `ExecutorRef` |

## Related issues

- #700 — this issue (unify orchestration model)
- #694 — DAG plan structure (natural vehicle for shared Plan type)
- #567 — remove serverlessworkflow SDK (completed — no naming conflict with TaskDescriptor)
- casehubio/blocks#44 — agentic planning architecture
- casehubio/blocks#13 — PlannedTask + LeafTask
