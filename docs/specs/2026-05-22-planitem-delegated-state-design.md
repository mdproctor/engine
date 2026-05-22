# PlanItem DELEGATED State and SubCase Lifecycle — Design Spec

**Date:** 2026-05-22  
**Issues:** casehubio/engine#322, #323 (openChannel contract), plus HumanTask fix and error-path hardening  
**Branch:** issue-321-324-blackboard-fixes

---

## Problem

`PlanItemStatus.RUNNING` currently conflates two semantically distinct execution states:

1. **A Quartz job is actively computing** (CapabilityTarget)
2. **The engine has delegated to an external actor and is waiting** (SubCase, HumanTask, Extension)

An LLM reading `RUNNING` on a SubCase PlanItem would infer active local computation. The engine
is actually idle, waiting for a child case to return a signal. This ambiguity undermines the
normative layer — a consumer cannot distinguish "work is running here" from "work is delegated
and we are waiting."

Additionally, SubCase and HumanTask PlanItems can get permanently stuck in PENDING or DELEGATED
(once introduced) when handler error paths return early without updating the PlanItem state.

---

## State Machine

```
PENDING → RUNNING    CapabilityTarget only — Quartz job submitted
PENDING → DELEGATED  SubCaseTarget, HumanTaskTarget, ExtensionTarget — handed to external actor
PENDING → FAULTED    Pre-dispatch error (spawn failure, guard block, template not found, etc.)

RUNNING   → COMPLETED | FAULTED | CANCELLED
DELEGATED → COMPLETED | FAULTED | CANCELLED
```

**DELEGATED:** "Work has been initiated and control has passed to an external actor.
The engine is waiting for a completion signal." Unambiguous to any consumer — human or LLM.

**RUNNING** retains its original meaning: a Quartz thread is actively executing this binding.

All terminal transitions (COMPLETED, FAULTED, CANCELLED) accept both RUNNING and DELEGATED
as source states. FAULTED also accepts PENDING (pre-dispatch errors).

---

## Changes

### 1. `PlanItemStatus` (common module)
Add `DELEGATED` to the enum.

### 2. `PlanItem` (blackboard module)
- Add `markDelegated()`: PENDING → DELEGATED
- `markDelegated()` is idempotent-safe at call sites: callers guard with `status == PENDING` check
  before calling (M-of-N subsequent spawns skip it, not throw)
- Update `markCompleted()`, `markFaulted()`, `markCancelled()` to accept DELEGATED as source state

### 3. `BlackboardRegistry` — rename for semantic accuracy
- `indexWorkerForCompletion(caseId, workerName, planItemId)` → `indexForCompletion(caseId, trackingKey, planItemId)`
- `getPlanItemId(caseId, workerName)` → `getPlanItemId(caseId, trackingKey)`

The "tracking key" is the external identifier that routes completion events to a PlanItem:
- CapabilityTarget: `workerName`
- SubCaseTarget: `childCaseId.toString()`
- HumanTaskTarget: workItemId (when completion path is implemented)

### 4. `PlanItemCompletedEvent` (blackboard module)
Rename field `workerName` → `trackingKey`.

### 5. `SubCaseScheduleEvent` (common module)
Add `String bindingName` field. `CaseContextChangedEventHandler` passes `binding.getName()`
at the call site (already in scope).

### 6. `SubCaseExecutionHandler` (blackboard module)

After `startCase()` returns `childCaseId`:

**Success path:**
1. Find PlanItem by `event.bindingName()` in `registry.get(parentCaseId)`
2. If `status == PENDING`: call `markDelegated()`
3. Call `registry.indexForCompletion(parentCaseId, childCaseId.toString(), planItemId)` — ALL spawns
   (M-of-N subsequent spawns skip markDelegated but still index, so any completing child routes completion)
4. For `waitForCompletion=false`: immediately call `markCompleted()` after `markDelegated()`
   (fire-and-forget — plan item is done once child is spawned)

**Error paths** (circular dependency, no CaseDefinition, `startCase()` throws):
Fault the PlanItem: `registry.get(parentCaseId)` → find by bindingName → `markFaulted()`

**Ordering constraint:** `markDelegated()` and `indexForCompletion()` are called synchronously
in the same block before any async continuation. No window between them.

### 7. New event: `SubCaseExecutionCompleted` (blackboard module)
```java
record SubCaseExecutionCompleted(UUID parentCaseId, UUID childCaseId) {}
```
New address in `BlackboardEventBusAddresses.SUBCASE_EXECUTION_COMPLETED`.

### 8. `SubCaseCompletionService` (blackboard module)
Inject `EventBus`. After parent case is resumed:

- **Ungrouped completion**: publish `SubCaseExecutionCompleted(parentCaseId, childCaseId)`
- **Grouped COMPLETED** (`won == true`): publish `SubCaseExecutionCompleted(parentCaseId, childCaseId)`
- **Grouped REJECTED**: before cancelling parent, mark PlanItem CANCELLED via
  `registry.getPlanItemId(parentCaseId, anyChildId)` → `plan.getPlanItem(planItemId)` → `markCancelled()`

### 9. `PlanItemCompletionHandler` (blackboard module)
Extract private `completePlanItemByKey(UUID caseId, String trackingKey)`:
- Looks up planItemId in registry
- Calls `markCompleted()` on PlanItem
- Evaluates stage autocomplete
- Fires `PlanItemCompletedEvent`

Existing `onWorkerFinished()` delegates to `completePlanItemByKey(caseId, workerName)`.

New `@ConsumeEvent(SUBCASE_EXECUTION_COMPLETED)`:
```java
public Uni<Void> onSubCaseFinished(SubCaseExecutionCompleted event) {
    return completePlanItemByKey(event.parentCaseId(), event.childCaseId().toString());
}
```

All completion paths (worker, subcase) now flow through `PlanItemCompletionHandler`:
stage autocomplete and `PlanItemCompletedEvent` fire for every target type.

### 10. `HumanTaskScheduleHandler` (work-adapter module)
Replace `item.markRunning()` with `item.markDelegated()`. The WorkItem was created (delegated
to a human), not "running" locally.

---

## Tracking Key Summary

| Target type | RUNNING/DELEGATED set by | Tracking key | Completion routed by |
|---|---|---|---|
| CapabilityTarget | `indexSelectedForCompletion()` | `workerName` | `PlanItemCompletionHandler.onWorkerFinished()` |
| SubCaseTarget | `SubCaseExecutionHandler` | `childCaseId.toString()` | `PlanItemCompletionHandler.onSubCaseFinished()` |
| HumanTaskTarget | `HumanTaskScheduleHandler` | (future — workItemId via WorkItemLifecycleAdapter) | (future) |

---

## Error Path Coverage

All error cases that previously left PlanItems stuck in PENDING are now handled:

| Error | Handler | Resolution |
|---|---|---|
| `startCase()` throws | `SubCaseExecutionHandler` | PENDING → FAULTED |
| Circular dependency | `SubCaseExecutionHandler` | PENDING → FAULTED |
| No CaseDefinition | `SubCaseExecutionHandler` | PENDING → FAULTED |
| Invalid `totalInGroup` | `SubCaseExecutionHandler` | PENDING → FAULTED |
| Template not found (HumanTask) | `HumanTaskScheduleHandler` | PENDING → FAULTED |
| M-of-N REJECTED | `SubCaseCompletionService` | DELEGATED → CANCELLED |

**Out of scope** (filed as engine#331): CapabilityTarget RUNNING → FAULTED not wired when
retries are exhausted (`WorkerRetriesExhaustedEventHandler` does not call `PlanItemCompletionHandler`).

---

## Protocol Updates Required

- `blackboard-registry-call-order.md`: update method names, add SubCase indexing pattern
- `engine-spi-noops-defaultbean.md`: no changes (SubCase/HumanTask no-ops not affected)
- New protocol: `plan-item-delegated-state.md` — DELEGATED state semantics and transition rules
