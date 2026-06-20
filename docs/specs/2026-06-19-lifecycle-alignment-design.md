# Design: Lifecycle Alignment — FAULTED/OBSOLETE at Engine Layer

**Issue:** casehubio/engine#539
**Date:** 2026-06-19
**Revision:** 3 — post-review rounds 1 (10 findings) and 2 (2 findings)
**Upstream spec:** casehubio/work specs/2026-06-18-lifecycle-alignment-design.md (Sections 9a–9f, 10, 11)

---

## Goal

Align the engine work-adapter module with casehub-work#240's addition of FAULTED and OBSOLETE to WorkItemStatus. Propagate OBSOLETE as a first-class PlanItemStatus (diverging from the upstream spec's CANCELLED collapse). Add `isTerminal()` to `PlanItemStatus` to eliminate hardcoded terminal-state enumeration across the codebase. Fix PlanItemFaultedEvent field semantics and parity gaps. Audit all guidance for coherence.

---

## Deviation from Upstream Spec

The upstream spec maps `WorkItemStatus.OBSOLETE` → `PlanItem.markCancelled()` with rationale: "No other layer has OBSOLETE." This design rejects that mapping.

**Why:** PlanItems ARE the engine's representation of human/agent tasks — the exact layer where OBSOLETE semantics matter. Collapsing OBSOLETE into CANCELLED loses a distinction that trust scoring, case outcome analysis, and repeatable stage cleanup all need. The cost is one enum value and one method.

---

## Section 1 — PlanItemStatus: OBSOLETE + isTerminal()

Add `OBSOLETE` to `PlanItemStatus` and add `isTerminal()` / `isActive()` methods on the enum itself — eliminating the root fragility of hardcoded terminal-state enumeration scattered across consumers.

```java
public enum PlanItemStatus {
  PENDING, RUNNING, DELEGATED, COMPLETED, FAULTED, REJECTED, OBSOLETE, CANCELLED;

  public boolean isTerminal() {
    return this == COMPLETED || this == FAULTED || this == REJECTED
        || this == OBSOLETE || this == CANCELLED;
  }

  public boolean isActive() {
    return this == PENDING || this == RUNNING || this == DELEGATED;
  }
}
```

**Javadoc — semantic definitions for all terminal states:**

- **COMPLETED** — work finished successfully
- **FAULTED** — work did not complete successfully: system failure (worker exception, retry exhaustion), deadline breach (WorkItem EXPIRED), or gate resolution preventing the action from being applied (gate rejected or expired). Distinct from REJECTED (actor refused the work itself) and OBSOLETE (work became irrelevant)
- **REJECTED** — external actor deliberately refused the work
- **OBSOLETE** — case context changed, making this work irrelevant. Not stopped by anyone — it stopped mattering
- **CANCELLED** — deliberate stop by a human or system

**Javadoc for DELEGATED** (cross-system note):
"Control passed to external actor (HumanTask, SubCase, Extension) — engine waiting for completion signal. Non-terminal. Distinct from `WorkItemStatus.DELEGATED` (pre-acceptance hold within the task) and `CommitmentState.DELEGATED` (terminal obligation transfer)."

**Cascade — consumers that currently hardcode terminal-state checks:**

| Consumer | Current pattern | After |
|---|---|---|
| `StageAutocompleteEvaluator.isTerminal()` | Explicit 4-value check | Delete method — callers use `status.isTerminal()` directly |
| `PlanItem.markFaulted()` guard | Explicit 4-value `if` | `if (status.isTerminal()) throw ...` |
| `PlanItem.markCancelled()` guard | Explicit 4-value `if` | `if (status.isTerminal()) throw ...` |
| `PlanItem.markObsolete()` guard | New method | `if (status.isTerminal()) throw ...` |
| `PlanItemFaultHandler.FAULTABLE` EnumSet | Positive set (PENDING, RUNNING, DELEGATED) | `!item.getStatus().isTerminal()` — delete FAULTABLE constant |
| `ActionGateExpiredPlanItemHandler.FAULTABLE` | Positive set (RUNNING, DELEGATED) | `!item.getStatus().isTerminal()` — delete FAULTABLE constant |
| `ActionGateRejectedPlanItemHandler.FAULTABLE` | Positive set (RUNNING, DELEGATED) | `!item.getStatus().isTerminal()` — delete FAULTABLE constant |

**isActive() consumers:**

| Consumer | Current pattern | After |
|---|---|---|
| `DefaultCasePlanModel.addPlanItemIfAbsent()` | `s == PENDING \|\| s == RUNNING \|\| s == DELEGATED` | `s.isActive()` |
| `DefaultCasePlanModel.getPlanItemByBindingName()` | `status == PENDING \|\| status == RUNNING \|\| status == DELEGATED` | `status.isActive()` |
| `DefaultCasePlanModel.hasActivePlanItem()` | `status == PENDING \|\| status == RUNNING \|\| status == DELEGATED` | `status.isActive()` |

**Deliberately excluded:** `PlanItemCompletionHandler.COMPLETABLE = EnumSet.of(RUNNING, DELEGATED)` — this is a semantic subset (states from which completion is valid), not "all active states." `isActive()` would incorrectly allow PENDING→COMPLETED.

**Implementation note:** Replacing the ActionGate handlers' `FAULTABLE = EnumSet.of(RUNNING, DELEGATED)` with `!isTerminal()` broadens acceptance to include PENDING. In practice this is safe — a gate-associated PlanItem is always RUNNING (the worker must complete and produce a `PlannedAction` before the gate WorkItem exists). A PENDING PlanItem receiving a gate fault event would be a bug elsewhere, and faulting it is more correct than silently dropping the event. Consistency of the `!isTerminal()` pattern across all three handlers outweighs the theoretical broadening.

---

## Section 2 — PlanItemFaultedEvent: workerId → bindingName

Breaking change to the record signature:

```java
// Before
public record PlanItemFaultedEvent(UUID caseId, String planItemId, String workerId, String tenancyId) {}

// After
public record PlanItemFaultedEvent(UUID caseId, String planItemId, String bindingName, String tenancyId) {}
```

**Rationale:** `workerId` only makes sense for worker-originated faults. For human-task-originated faults (EXPIRED, FAULTED from WorkItem layer), there is no worker. `bindingName` is the architectural connector — it identifies which case definition binding faulted, regardless of target type. Achieves parity with `PlanItemRejectedEvent` which already uses `bindingName`.

All fire sites update — blackboard handlers (`PlanItemFaultHandler`, `ActionGateExpiredPlanItemHandler`, `ActionGateRejectedPlanItemHandler`, `WorkerOutcomeResolvedHandler`) all have `PlanItem` in scope with `getBindingName()`.

All observers across the platform need updating (find-references sweep during implementation).

---

## Section 3 — WorkItemLifecycleAdapter.onWorkItemLifecycle() (9a)

Replace explicit status filter with `isTerminal()`:

```java
// Before:
if (status != WorkItemStatus.COMPLETED
    && status != WorkItemStatus.REJECTED
    && status != WorkItemStatus.CANCELLED
    && status != WorkItemStatus.EXPIRED) return;

// After:
if (!status.isTerminal()) return;
```

ESCALATED pre-check at line 75 returns before this guard. Future terminal statuses flow through automatically.

---

## Section 4 — PlanItemCompletionApplier.applyStatus() (9b)

**4a. Complete switch with logging on default:**

```java
switch (status) {
  case COMPLETED -> item.markCompleted();
  case REJECTED  -> item.markRejected();
  case FAULTED   -> item.markFaulted();
  case EXPIRED   -> item.markFaulted();
  case OBSOLETE  -> item.markObsolete();
  case CANCELLED -> item.markCancelled();
  default -> {
    LOG.warnf("Unhandled WorkItemStatus %s for PlanItem %s — no transition applied",
        status, item.getPlanItemId());
    return false;
  }
}
```

**4b. PlanItemFaultedEvent parity — fire for FAULTED and EXPIRED:**

Inject `Event<PlanItemFaultedEvent>` into `PlanItemCompletionApplier`. Fire after status transition:

```java
if (status == WorkItemStatus.FAULTED || status == WorkItemStatus.EXPIRED) {
  planItemFaultedEvents.fireAsync(
      new PlanItemFaultedEvent(caseId, planItemId, item.getBindingName(), instance.tenancyId));
}
```

**4c. PlanItemObsoleteEvent — fire for OBSOLETE:**

New CDI event record:

```java
public record PlanItemObsoleteEvent(UUID caseId, String planItemId, String bindingName, String tenancyId) {}
```

Inject `Event<PlanItemObsoleteEvent>` into `PlanItemCompletionApplier`. Fire after status transition:

```java
if (status == WorkItemStatus.OBSOLETE) {
  planItemObsoleteEvents.fireAsync(
      new PlanItemObsoleteEvent(caseId, planItemId, item.getBindingName(), instance.tenancyId));
}
```

All three CDI events (`PlanItemRejectedEvent`, `PlanItemFaultedEvent`, `PlanItemObsoleteEvent`) use fire-and-forget per protocol PP-20260529-3237bd — audit events, must not gate case state progression. All three carry `bindingName` (not `workerId`) and `tenancyId` per protocol PP-20260611-d4e5cf.

---

## Section 5 — HumanTaskRecoveryService (9c)

Replace `TERMINAL_STATUSES` EnumSet with `isTerminal()`:

```java
// Before:
private static final Set<WorkItemStatus> TERMINAL_STATUSES =
    EnumSet.of(COMPLETED, REJECTED, CANCELLED, EXPIRED);
// ...
if (!TERMINAL_STATUSES.contains(workItem.status)) { ... }

// After — delete constant, replace usage:
if (!workItem.status.isTerminal()) { ... }
```

---

## Section 6 — ActionGateCompletionApplier (9d)

Handle FAULTED and OBSOLETE in the gate switch:

```java
switch (status) {
  case COMPLETED -> handleApproved(gateRef, workItem);
  case REJECTED, CANCELLED, OBSOLETE -> handleRejected(gateRef, workItem);
  case EXPIRED, FAULTED -> handleExpired(gateRef);
  default -> LOG.debugf("...");
}
```

- **FAULTED → handleExpired()** — system failure prevented human decision; no approval given
- **OBSOLETE → handleRejected()** — context changed, gated action no longer relevant

---

## Section 7 — WorkerOutcomeResolvedHandler — PlanItemFaultedEvent parity

`WorkerOutcomeResolvedHandler` marks PlanItems FAULTED for non-success worker outcomes (DECLINED, FAILED, EXPIRED with FAULT/EXHAUSTED/REROUTE disposition) but does not fire `PlanItemFaultedEvent`. Every other fault path fires the event.

Add `Event<PlanItemFaultedEvent>` injection and fire after `markFaulted()`:

```java
item.markFaulted();
planItemFaultedEvents.fireAsync(
    new PlanItemFaultedEvent(
        event.caseInstance().getUuid(), item.getPlanItemId(),
        item.getBindingName(), event.caseInstance().tenancyId));
```

Fires for all dispositions (REROUTE, EXHAUSTED, FAULT) — the PlanItem is faulted regardless of whether a replacement is created.

---

## Section 8 — Javadoc fixes (10, 11)

**WorkItemLifecycleAdapter class javadoc:** Fix ESCALATED description. Current says "ESCALATED is not terminal" — wrong. Replace with: "ESCALATED is terminal — all SLA breach policy branches have been exhausted. The adapter writes a `workItemEscalated` signal to the case context. Note: SLA breach policies that re-route the WorkItem to new groups (the `EscalateTo` decision) do not set ESCALATED — the WorkItem stays PENDING with updated candidate groups, so the adapter's terminal filter skips it entirely."

**PlanItemStatus javadoc:** See Section 1 for all terminal state definitions and DELEGATED cross-system note.

**No changes to WorkItemStatus.DELEGATED or CommitmentState.DELEGATED** — those live in casehub-work and casehub-qhorus. Filed as cross-repo follow-ups.

---

## Section 9 — Guidance audit

All documentation referencing PlanItemStatus, terminal state semantics, or PlanItemFaultedEvent must be updated for coherence.

**9a. Engine CLAUDE.md:**
1. `casehub-work-adapter Module` section — add FAULTED→markFaulted() and OBSOLETE→markObsolete() to the terminal status mapping table. Add PlanItemFaultedEvent firing for FAULTED and EXPIRED. Add PlanItemObsoleteEvent firing for OBSOLETE.
2. All `PlanItemFaultedEvent` references — update signature from `(caseId, planItemId, workerId, tenancyId)` to `(caseId, planItemId, bindingName, tenancyId)`.
3. `ActionRiskClassifier SPI` section — update PlanItemFaultedEvent field name in blackboard handler descriptions.
4. ESCALATED description in `casehub-work-adapter Module` section — fix "ESCALATED is not terminal" statement.

**9b. DESIGN.md:** Update any sections referencing PlanItemStatus terminal states or WorkItem→PlanItem mapping.

**9c. Cross-repo issues to file:**
1. `casehubio/work` — WorkItemStatus.DELEGATED javadoc cross-reference
2. `casehubio/qhorus` — CommitmentState.DELEGATED javadoc cross-reference
3. `casehubio/parent` — PLATFORM.md update: PlanItemStatus gains OBSOLETE (8 states); lifecycle protocol note about `isTerminal()` as the single source of truth for terminal-state checks

**9d. engine#540:** Add a comment noting OBSOLETE was added in #539.

**9e. Find-references sweep:** During implementation, run IntelliJ find-references on `PlanItemFaultedEvent`, `PlanItemStatus`, and `StageAutocompleteEvaluator.isTerminal` to catch every consumer. Any site switching on PlanItemStatus values needs an OBSOLETE case or default branch. Any site calling `StageAutocompleteEvaluator.isTerminal()` must migrate to `status.isTerminal()`.

---

## Files Changed

| File | Change |
|---|---|
| `common/.../PlanItemStatus.java` | Add OBSOLETE, isTerminal(), isActive(), javadoc for all states |
| `common/.../event/PlanItemFaultedEvent.java` | workerId → bindingName |
| `common/.../event/PlanItemObsoleteEvent.java` | New CDI event record |
| `blackboard/.../plan/PlanItem.java` | Add markObsolete(); all terminal guards use status.isTerminal() |
| `blackboard/.../plan/DefaultCasePlanModel.java` | Migrate 3 explicit active-state checks to status.isActive() |
| `blackboard/.../handler/StageAutocompleteEvaluator.java` | Delete isTerminal() method; callers migrate to status.isTerminal() |
| `blackboard/.../handler/PlanItemFaultHandler.java` | Delete FAULTABLE; use !isTerminal(); bindingName in PlanItemFaultedEvent |
| `blackboard/.../handler/ActionGateExpiredPlanItemHandler.java` | Delete FAULTABLE; use !isTerminal(); bindingName in PlanItemFaultedEvent |
| `blackboard/.../handler/ActionGateRejectedPlanItemHandler.java` | Delete FAULTABLE; use !isTerminal(); bindingName in PlanItemFaultedEvent |
| `blackboard/.../handler/WorkerOutcomeResolvedHandler.java` | Add PlanItemFaultedEvent injection + firing |
| `work-adapter/.../WorkItemLifecycleAdapter.java` | isTerminal() + javadoc fix |
| `work-adapter/.../PlanItemCompletionApplier.java` | FAULTED/OBSOLETE cases + PlanItemFaultedEvent + PlanItemObsoleteEvent injection + firing + default logging |
| `work-adapter/.../recovery/HumanTaskRecoveryService.java` | isTerminal() replaces EnumSet |
| `work-adapter/.../ActionGateCompletionApplier.java` | FAULTED/OBSOLETE in gate switch |
| All affected test files | Updated constructors, assertions, and terminal-state checks |
| `CLAUDE.md` | Guidance audit updates (9a) |
