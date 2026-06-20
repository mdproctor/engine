# Design: PlanItemStatus.SUSPENDED — observability parity

**Issue:** casehubio/engine#540
**Date:** 2026-06-20

---

## Goal

Add SUSPENDED to PlanItemStatus so the engine can distinguish active work from paused work when a WorkItem or SubCase is suspended.

---

## Design

### PlanItemStatus

Add SUSPENDED as an **active** state — a suspended PlanItem still occupies its binding slot.

```java
PENDING, RUNNING, DELEGATED, SUSPENDED, COMPLETED, FAULTED, REJECTED, OBSOLETE, CANCELLED;

public boolean isActive() {
    return this == PENDING || this == RUNNING || this == DELEGATED || this == SUSPENDED;
}
```

Javadoc: "SUSPENDED — external actor has paused work. The PlanItem slot remains occupied; work resumes without re-dispatch. Only reachable from DELEGATED."

### PlanItem transitions

- `markSuspended()` — from DELEGATED only. Workers (RUNNING) don't pause; unscheduled items (PENDING) don't pause.
- `markResumed()` — from SUSPENDED → DELEGATED. Matches WorkItem layer naming.

### WorkItemLifecycleAdapter

Add a SUSPENDED pre-check before the terminal guard (same pattern as ESCALATED):

```java
if (status == WorkItemStatus.SUSPENDED) {
    handleSuspension(event);
    return;
}
```

`handleSuspension()` finds the PlanItem via callerRef and calls `markSuspended()`. No CONTEXT_CHANGED — suspension is observability only.

For resume detection: when the PlanItem is currently SUSPENDED and the incoming WorkItem status is active (ASSIGNED/IN_PROGRESS via a non-terminal, non-SUSPENDED lifecycle event), call `markResumed()`.

### Not in scope

- No CDI event for suspension — no consumer needs to react to a pause
- No stage autocomplete impact — SUSPENDED is active, not terminal

---

## Files Changed

| File | Change |
|---|---|
| `common/.../PlanItemStatus.java` | Add SUSPENDED, update isActive() javadoc |
| `common/.../PlanItemStatusTest.java` | EXPECTED_ACTIVE gains SUSPENDED |
| `blackboard/.../plan/PlanItem.java` | Add markSuspended(), markResumed() |
| `blackboard/.../plan/PlanItemTest.java` | Transition tests |
| `work-adapter/.../WorkItemLifecycleAdapter.java` | SUSPENDED pre-check + resume detection |
| `work-adapter/.../WorkItemLifecycleAdapterTest.java` | Suspension/resume tests |
