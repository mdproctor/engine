# Milestone and Goal — Full Conceptual Alignment

**Issue:** [#84](https://github.com/casehubio/engine/issues/84)
**Closes:** #84 — all four areas of the epic are addressed (Goal.terminal cleanup via prior #581/#582, milestone stage containment via S1, milestone lifecycle state via S3, CMMN 1.1 audit via S5). The approach diverges from the epic's original vision (which envisioned adding `parentStageId`; this spec removes it) but the design rationale in S1 and S5 explains why expression-based composition supersedes structural containment.
**Date:** 2026-07-31
**Supersedes:** `2026-06-28-milestone-goal-cmmn-alignment.md` (partially implemented via #581, #582)

## Prior work

#581 and #582 addressed several of the original gaps:

- `Goal.terminal` field removed (no longer exists in codebase)
- `GoalBasedCompletion` generalized to support custom `GoalKind` beyond success/failure
- `CasePlanModel` milestone tracking upgraded from `Boolean` to `MilestoneLifecycleStatus`
- `DefaultCaseDefinitionRegistry` warns on unreferenced goals
- `MilestoneLifecycleManager` is the sole milestone evaluation path (PENDING→ACTIVE→COMPLETED)

Since then, Stage was fully retired (blocks#60 Phase 3C.3), replaced by
`PlanItemDefinition.Compound`. The Stage containment work in #581 is now dead code.

## What remains

Five gaps identified from first-principles code investigation:

| Gap | Evidence |
|-----|----------|
| `Milestone.parentStageId` is dead code | 0 production references; only `MilestoneParentStageTest` |
| Unreferenced Goals allowed (warn-only) | `DefaultCaseDefinitionRegistry` logs WARNING but permits registration |
| Milestone state tracked in 3 places | EventLog (queried by `MilestoneLifecycleManager`), CasePlanModel (ConcurrentHashMap), CaseContext (`milestones.<name>.*`) |
| CasePlanModel milestone tracking has zero readers | `getMilestoneStatus()`, `isMilestoneAchieved()`, `trackMilestone()` — 0 production callers |
| Dead enum values / unimplemented SLA modes | `MilestoneLifecycleStatus.FAILED`/`CANCELLED` never set; `SlaStartFrom.PREVIOUS_MILESTONE_COMPLETED`/`EVENT_OCCURRED` throw `UnsupportedOperationException` |

## Design principle

The platform's composition model is `ExpressionEvaluator` on `CaseContext` — pluggable
across JQ (YAML path), MVEL, and Java lambdas (DSL path). Milestone conditions, goal
conditions, binding triggers, and compound entry/exit criteria all evaluate through
`ExpressionEvaluator`. This is the universal evaluation surface.

Structural containment relationships (like `parentStageId`) are a different composition
model — one that CMMN needed because it lacks a general-purpose expression language.
casehub chose pluggable expression evaluation. Structural containment fights that choice.

## S1: Remove `parentStageId` — don't replace it

### Problem

`Milestone.parentStageId` was added for Stage containment. Stage is retired. The field
has zero production references — only `MilestoneParentStageTest` uses it.

### Decision

Remove `parentStageId` entirely. Do not replace with `parentCompoundId`.

Compound conditions already reference milestone state via `ExpressionEvaluator` on
CaseContext — e.g., `.milestones["phase-1-complete"].lifecycleStatus == "COMPLETED"`.
The expression evaluation surface provides the integration. Structural back-pointers
add a second, redundant composition mechanism.

### Extension point

If compound-scoped milestone evaluation becomes necessary (repeatable compounds with
milestone reset), the extension point is `Compound.scopedMilestones: Set<String>` —
following the existing `scopedBindings` pattern. File a follow-on issue when the need
arises; do not build it now.

### Changes

| File | Change |
|------|--------|
| `api/.../model/Milestone.java` | Remove `parentStageId` field, getter, constructor parameter, builder method. Remove from `equals()`/`hashCode()`. Update Javadoc — remove Stage references. |
| `api/.../model/Milestone.java` Javadoc | Replace "Completed milestones can be referenced in stage exit criteria" with "Milestone state is written to CaseContext at `milestones.<name>.*` and can be referenced by any `ExpressionEvaluator` — compound conditions, binding triggers, goal conditions." |
| `api/src/test/.../MilestoneParentStageTest.java` | Delete entirely |

### Tests

- Verify `Milestone.builder()` no longer exposes `parentStageId(String)`
- Existing milestone lifecycle tests remain unchanged

## S2: Reject unreferenced Goals at registration

### Problem

`DefaultCaseDefinitionRegistry` logs a WARNING when a Goal is not referenced in any
`GoalExpression` within the `completion:` block. The goal is still registered. This
permits the conceptual overlap identified in #84 — a non-terminal goal is functionally
a Milestone.

### Decision

Upgrade from WARNING to a hard rejection. A Goal not referenced in any `GoalExpression`
is an error at registration time.

The boundary: Goals are terminal — they drive case completion. Milestones are
non-terminal — they mark progress. A non-terminal checkpoint is a Milestone, not a Goal.

### Changes

| File | Change |
|------|--------|
| `runtime/.../engine/DefaultCaseDefinitionRegistry.java` | Change WARNING to `IllegalArgumentException` at registration time. Message: "Goal '<name>' is not referenced in any completion expression. Goals must drive case completion — use Milestone for non-terminal checkpoints." |

### Tests

- `warns_when_goal_not_referenced_in_any_goal_expression` → rename and update to expect `IllegalArgumentException`
- Verify that goals referenced in completion expressions register successfully (existing tests)
- Verify that definitions with no `completion:` block AND no goals register successfully (no false positives)
- Verify that definitions with `PredicateBasedCompletion` (`doneWhen:`) AND goals reject the unreferenced goals
- Verify that definitions with `GoalBasedCompletion` where some goals are referenced and some are not reject at registration (partial reference — the most common real-world scenario for accidentally forgetting to wire a goal)

### Migration concern

Existing definitions with unreferenced goals will fail registration. This is
intentional — the warning was the migration period. Any definition with an unreferenced
goal should either: reference it in a completion expression, or convert it to a Milestone.

## S3: Consolidate milestone state — CaseContext is canonical

### Problem

Milestone lifecycle state is tracked in three places:

1. **EventLog** — source of truth, queried by `MilestoneLifecycleManager.getCurrentLifecycleStatus()` on every `CONTEXT_CHANGED` for every milestone (linear scan of milestone events)
2. **CasePlanModel** — `ConcurrentHashMap<String, MilestoneLifecycleStatus>` in `DefaultCasePlanModel`, updated by `MilestoneAchievementHandler`
3. **CaseContext** — written by `MilestoneActivatedEventHandler` and `MilestoneCompletedEventHandler` at `milestones.<name>.lifecycleStatus`

The CasePlanModel tracking is dead infrastructure: `getMilestoneStatus()`,
`isMilestoneAchieved()`, and `trackMilestone()` have zero production callers. Only
`MilestoneAchievementHandler` writes to them, and nothing reads the values.

The EventLog query on every `CONTEXT_CHANGED` is the expensive path:
`findByCaseAndTypes()` + stream filter + max comparator — per milestone, per context
change. This is O(milestones * milestone_events) per context change.

### Decision

**CaseContext is the canonical runtime state for event-driven consumers.** This follows the platform's own pattern: workers write output to CaseContext, and downstream consumers read from CaseContext. Milestones should work the same way.

1. `MilestoneLifecycleManager` reads lifecycle status from CaseContext (`milestones.<name>.lifecycleStatus`) instead of querying EventLog
2. `MilestoneSLATimeoutJob` retains its EventLog-based status query — it is a Quartz job that may fire after a JVM restart when CaseContext is not populated (see Recovery section)
3. CasePlanModel milestone tracking is removed entirely
4. EventLog continues recording all milestone events for audit, SLA timeout queries, and as the reconstruction source for future persistent `CaseContextStore` implementations

### Changes

**MilestoneLifecycleManager:**

| Method | Change |
|--------|--------|
| `getCurrentLifecycleStatus()` | Read from CaseContext: `milestones.<name>.lifecycleStatus`. If absent, return `PENDING`. Remove EventLog query. |
| `getCurrentSlaStatus()` | Read from CaseContext: `milestones.<name>.slaStatus`. If absent, return `NOT_STARTED`. Remove EventLog query via `findLastMilestoneEvent()`. |
| `findLastMilestoneEvent()` | Delete — no longer called by any method |
| `MILESTONE_LIFECYCLE_EVENTS` | Delete — used only by `findLastMilestoneEvent()` |

Note: `eventLogRepository` injection is retained — still used by `calculateSlaDeadline()` to query `CASE_STARTED` events.

**MilestoneSLATimeoutJob — no changes to status reading:**

`MilestoneSLATimeoutJob` continues reading lifecycle status from EventLog. This is intentional: the timeout job is a Quartz scheduled job that fires outside the reactive event loop. After a JVM restart, Quartz replays persisted jobs, but the `CaseInstance` loaded from the database does not have `CaseContext` populated (CaseContext is in-memory, not persisted in `CaseInstanceEntity`). The EventLog-based query is the correct approach for this job because it works regardless of whether the case is in the in-memory cache.

**CasePlanModel interface:**

| Method | Change |
|--------|--------|
| `trackMilestone(String)` | Remove |
| `activateMilestone(String)` | Remove |
| `completeMilestone(String)` | Remove |
| `getMilestoneStatus(String)` | Remove |
| `isMilestoneAchieved(String)` | Remove |
| `achieveMilestone(String)` | Remove (already deprecated) |

**DefaultCasePlanModel:**

| Change |
|--------|
| Remove `ConcurrentHashMap<String, MilestoneLifecycleStatus> milestones` field |
| Remove all milestone method implementations |

**MilestoneAchievementHandler:**

| Change |
|--------|
| Delete the entire class — the bridge from runtime events to CasePlanModel is no longer needed |

**Event bus addresses (EventBusAddresses.java):**

| Address | Change |
|---------|--------|
| `MILESTONE_REACHED` | Remove — deprecated, no publishers |

**MilestoneReachedEvent:**

| Change |
|--------|
| Delete — no publishers, deprecated handler |

**MilestoneReachedEventHandler:**

| Change |
|--------|
| Delete — deprecated, no publishers |

**CaseHubEventType enum:**

| Value | Change |
|-------|--------|
| `MILESTONE_REACHED` | Retain — existing EventLog rows reference this value. Removing the enum constant would break deserialization of historical entries. The event bus address and event class are deleted (no publishers), but the enum value stays for backwards compatibility. |

### Deferred: SLA violation deactivation

`MilestoneLifecycleManager.getCurrentLifecycleStatus()` contains a TODO:
"maybe it must be configurable whether SLA violation deactivates the milestone
or not?" The current behavior (both EventLog-based and the new CaseContext-based
read) keeps the milestone ACTIVE after SLA violation — `MilestoneSLAViolatedEventHandler`
updates `slaStatus` to `BREACHED` but does not change `lifecycleStatus`. This spec
preserves that behavior. File a follow-on issue: "Design: configurable SLA
violation response (deactivate vs. continue)" (refs `MilestoneLifecycleManager.java`
TODO at line 198).

### Recovery and persistence model

CaseContext is in-memory — `CaseInstanceEntity` does not persist context data.
The `CaseContextStore` SPI allows pluggable backends (in-memory, Redis, database),
but the default `InMemoryCaseContextStore` is a `LinkedHashMap` that does not
survive JVM restarts.

**MilestoneLifecycleManager** reads CaseContext from the `CaseInstance` carried
by `CaseContextChangedEvent`. These events only fire for actively-running cases
whose CaseContext is already populated in memory. No recovery concern — if the
case is processing context changes, its CaseContext exists.

**MilestoneSLATimeoutJob** is a Quartz job that fires outside the event loop.
After a JVM restart, Quartz replays persisted jobs but the `CaseInstance` loaded
from the database has no CaseContext. This is why the timeout job retains its
EventLog-based status query — it must work without CaseContext.

**EventLog** continues recording all milestone events for audit, SLA timeout
queries, and as the reconstruction source if a persistent `CaseContextStore`
implementation is added in the future.

### Tests

- `MilestoneLifecycleManager` reads lifecycle and SLA status from CaseContext, not EventLog
- `MilestoneSLATimeoutJob` continues reading status from EventLog (no test changes — retains existing behavior)
- Milestone lifecycle integration tests continue to pass (they assert on CaseContext and EventLog, both of which are still populated)
- `MilestoneAchievementHandlerTest` — delete (handler deleted)
- `DefaultCasePlanModelTest` milestone tests — delete milestone-specific tests (methods removed from interface)
- CasePlanModel tests for PlanItem, Compound, focus, etc. remain unchanged

## S4: Remove dead enum values and unimplemented SLA modes

### MilestoneLifecycleStatus

| Value | Status | Action |
|-------|--------|--------|
| `PENDING` | Used | Keep |
| `ACTIVE` | Used | Keep |
| `COMPLETED` | Used | Keep |
| `FAILED` | Never set — no code path produces it | Remove |
| `CANCELLED` | Never set — no code path produces it | Remove. Re-add with clear semantics if compound-scoped milestones are built. |

### SlaStartFrom

| Value | Status | Action |
|-------|--------|--------|
| `CASE_CREATED` | Implemented | Keep |
| `MILESTONE_ACTIVATED` | Implemented (default) | Keep |
| `PREVIOUS_MILESTONE_COMPLETED` | Throws `UnsupportedOperationException` | Remove |
| `EVENT_OCCURRED` | Throws `UnsupportedOperationException` | Remove |

### SlaStatus

No changes — `NOT_STARTED`, `ON_TRACK`, `BREACHED` are all used and correct.

### MilestoneActivatedEventHandler

`isTerminalLifecycleStatus()` references `FAILED` and `CANCELLED` — update to check
only `COMPLETED` after enum cleanup.

### MilestoneSLAViolatedEventHandler

Unaffected — does not reference `MilestoneLifecycleStatus` at all. Uses only
`SlaStatus.BREACHED` and `CaseHubEventType.MILESTONE_SLA_VIOLATED`. No changes needed.

### Changes

| File | Change |
|------|--------|
| `api/.../model/MilestoneLifecycleStatus.java` | Remove `FAILED`, `CANCELLED` |
| `api/.../model/SlaStartFrom.java` | Remove `PREVIOUS_MILESTONE_COMPLETED`, `EVENT_OCCURRED` |
| `runtime/.../handler/MilestoneActivatedEventHandler.java` | Update `isTerminalLifecycleStatus()` to check `COMPLETED` only |
| `api/.../model/Milestone.java` builder | Remove validation for removed `SlaStartFrom` values (if any) |

### Tests

- Verify `MilestoneLifecycleStatus` has exactly 3 values: PENDING, ACTIVE, COMPLETED
- Verify `SlaStartFrom` has exactly 2 values: CASE_CREATED, MILESTONE_ACTIVATED
- Existing lifecycle tests remain unchanged (they only use PENDING, ACTIVE, COMPLETED)

## S5: Platform design documentation

Write a design alignment section in `docs/DESIGN.md` under a "Milestone and Goal
Alignment" heading. Not a CMMN compliance document — a deliberate platform design record.

### Content

**Composition model:** `ExpressionEvaluator` on `CaseContext` is the platform's universal
evaluation surface. Milestone conditions, goal conditions, binding triggers, and compound
entry/exit criteria all compose through pluggable expression evaluation (JQ, MVEL, lambda).
Structural containment relationships (CMMN Stage→Milestone) are replaced by expression-based
composition — any condition can reference any context data.

**Milestone/Goal boundary:**

| Concept | Question | Nature | State | Scope |
|---------|----------|--------|-------|-------|
| Milestone | Where are we? | Neutral progress marker | PENDING→ACTIVE→COMPLETED in CaseContext | Case-level |
| Goal | What outcome? | Terminal condition with GoalKind | Drives CaseStatus via GoalBasedCompletion | Case-level |

Goals are always terminal. Unreferenced goals are rejected at registration. A
non-terminal checkpoint is a Milestone.

**Milestone lifecycle state:** CaseContext (`milestones.<name>.*`) is the canonical
runtime state. EventLog records events for audit and recovery. Single source of runtime
truth — no parallel tracking in CasePlanModel.

**CMMN deviations (deliberate):**

| CMMN concept | casehub equivalent | Rationale |
|-------------|-------------------|-----------|
| Stage containment of Milestones | Expression-based composition via CaseContext | More flexible — any condition can reference any milestone |
| Milestones as PlanItemDefinitions | Separate definition-time concept | Milestones are evaluated (condition-driven), not dispatched (execution-driven) |
| Exit criteria on Case/Stage for completion | Explicit `Goal` + `GoalBasedCompletion` | Clearer intent — goals are named, typed, and composed via GoalExpressions |

**Extension points:**

- `Compound.scopedMilestones: Set<String>` — for future compound-scoped milestone evaluation (follows `scopedBindings` pattern)
- `SlaStartFrom` — for future SLA start modes (milestone chaining, event correlation)

## Files affected (summary)

| File | Section | Change |
|------|---------|--------|
| `api/.../model/Milestone.java` | S1, S4 | Remove `parentStageId`. Update Javadoc. |
| `api/test/.../MilestoneParentStageTest.java` | S1 | Delete |
| `runtime/.../engine/DefaultCaseDefinitionRegistry.java` | S2 | Unreferenced goal: warn → reject |
| `runtime/.../engine/DefaultCaseDefinitionRegistryGoalWarningTest.java` | S2 | Update to expect exception |
| `runtime/.../milestone/MilestoneLifecycleManager.java` | S3 | Read lifecycle and SLA status from CaseContext, delete `findLastMilestoneEvent()`, delete `MILESTONE_LIFECYCLE_EVENTS` EnumSet. Retain `eventLogRepository` for `calculateSlaDeadline()`. |
| `scheduler-quartz/.../MilestoneSLATimeoutJob.java` | S3 | No changes — retains EventLog-based status query (CaseContext unavailable after JVM restart) |
| `planning/.../plan/CasePlanModel.java` | S3 | Remove 6 milestone methods |
| `planning/.../plan/DefaultCasePlanModel.java` | S3 | Remove milestone ConcurrentHashMap and implementations |
| `planning/.../handler/MilestoneAchievementHandler.java` | S3 | Delete |
| `planning/test/.../MilestoneAchievementHandlerTest.java` | S3 | Delete |
| `planning/test/.../DefaultCasePlanModelTest.java` | S3 | Remove milestone-specific tests |
| `common/.../event/MilestoneReachedEvent.java` | S3 | Delete |
| `common/.../event/EventBusAddresses.java` | S3 | Remove `MILESTONE_REACHED` address |
| `runtime/.../handler/MilestoneReachedEventHandler.java` | S3 | Delete |
| `api/.../model/event/CaseHubEventType.java` | S3 | Retain `MILESTONE_REACHED` enum value (EventLog backwards compatibility) |
| `api/.../model/MilestoneLifecycleStatus.java` | S4 | Remove FAILED, CANCELLED |
| `api/.../model/SlaStartFrom.java` | S4 | Remove PREVIOUS_MILESTONE_COMPLETED, EVENT_OCCURRED |
| `runtime/.../handler/MilestoneActivatedEventHandler.java` | S4 | Update `isTerminalLifecycleStatus()` |
| `runtime/.../handler/MilestoneSLAViolatedEventHandler.java` | S4 | No changes — does not reference `MilestoneLifecycleStatus` |
| `docs/DESIGN.md` | S5 | Add Milestone and Goal Alignment section |
