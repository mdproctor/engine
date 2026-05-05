# casehub-blackboard Module Design
**Date:** 2026-04-18
**Status:** Approved — ready for implementation planning
**Issue:** casehubio/engine#76
**Epic:** casehubio/engine#30 (Layer CMMN/Blackboard features onto casehub-engine)
**Future evolution:** casehubio/engine#77 (Blackboard Architecture Evolution)

---

## Overview

`casehub-blackboard` is an optional Maven module that layers the
CMMN/Blackboard orchestration layer onto casehub-engine. Without it, the
engine runs pure choreography (`ChoreographyLoopControl` fires all eligible
bindings concurrently). Adding it enables deliberate orchestration:
`PlanningStrategy` selects which bindings fire, in what order, with full
access to async I/O via the reactive Vert.x Mutiny model.

### Design Principles

- **Opt-in, not intrusive.** Nothing in `engine/` or `api/` changes in
  behaviour. The only `api/` change is making `LoopControl.select()` return
  `Uni<List<Binding>>` — `ChoreographyLoopControl` wraps with
  `Uni.createFrom().item(eligible)`, zero behaviour delta.
- **Async throughout.** `PlanningStrategy.select()` returns
  `Uni<List<Binding>>`. Strategies can query the EventLog, call external
  systems, or use any non-blocking I/O without blocking the Vert.x event loop.
- **Stage lifecycle as first-class events.** Stage transitions publish to the
  Vert.x EventBus — observable, hookable, composable without coupling.
- **Domain state and control state are separate.** `CaseContext` (domain)
  and `CasePlanModel` (control) never mix. This is the separation
  Hayes-Roth described in the original BB1 architecture.
- **Future improvements tracked.** Research-identified improvements
  (meta-control, dual-space blackboard, memory stratification, hierarchical
  panels, dynamic agent selection, reason maintenance) are tracked in
  casehubio/engine#77 and its child issues #78–#83. Milestone/Goal/Stage
  conceptual alignment is tracked in casehubio/engine#84.

---

## Module Structure

### Dependency rules

```
api/                      ← LoopControl (async), PlanExecutionContext, Binding
engine/                   ← ChoreographyLoopControl, event handlers, EventBus
casehub-blackboard/       ← depends on api/ + engine/ only
```

Nothing in `engine/` gains a dependency on `casehub-blackboard/`. The
blackboard module plugs in via CDI `@Alternative @Priority`.

### Package layout

```
io.casehub.blackboard/
  control/
    PlanningStrategy.java
    DefaultPlanningStrategy.java
    PlanningStrategyLoopControl.java
    StageLifecycleEvaluator.java
  plan/
    CasePlanModel.java
    DefaultCasePlanModel.java
    PlanItem.java
  stage/
    Stage.java
    StageStatus.java
  event/
    StageActivatedEvent.java
    StageCompletedEvent.java
    StageTerminatedEvent.java
  handler/
    PlanItemCompletionHandler.java
    MilestoneAchievementHandler.java
```

---

## API Change: `LoopControl` → async

**File:** `api/src/main/java/io/casehub/api/engine/LoopControl.java`
**Tracking:** casehubio/engine#76

```java
/**
 * SPI for controlling which eligible bindings are selected for execution.
 *
 * <p>Returns a {@code Uni} to allow implementations to perform non-blocking
 * I/O during selection — e.g. querying EventLog history, calling an LLM
 * scorer, or checking external systems. See casehubio/engine#76.
 *
 * <p>The default implementation ({@link ChoreographyLoopControl}) wraps with
 * {@code Uni.createFrom().item(eligible)} — no behaviour change.
 */
public interface LoopControl {
    Uni<List<Binding>> select(PlanExecutionContext context, List<Binding> eligible);
}
```

`ChoreographyLoopControl` in `engine/`:

```java
@Override
public Uni<List<Binding>> select(PlanExecutionContext context, List<Binding> eligible) {
    return Uni.createFrom().item(eligible);
}
```

---

## Core Interfaces

### `PlanningStrategy`
**Tracking:** casehubio/engine#76

```java
/**
 * Selects which eligible {@link Binding}s to fire and in what order,
 * optionally reading and writing {@link CasePlanModel} control state.
 *
 * <p>Returns a {@code Uni} — implementations may perform non-blocking I/O
 * (e.g. EventLog queries) before returning. See casehubio/engine#76.
 * Async strategy use cases (EventLog-based learning, LLM scoring) are
 * tracked in casehubio/engine#82.
 *
 * <p>Implementations must:
 * <ul>
 *   <li>Never return bindings not present in {@code eligible}
 *   <li>Never return null — return an empty list to suppress all firing
 *   <li>Handle an empty {@code eligible} list gracefully
 * </ul>
 */
public interface PlanningStrategy {
    String getId();
    String getName();
    Uni<List<Binding>> select(CasePlanModel plan,
                              PlanExecutionContext context,
                              List<Binding> eligible);
}
```

### `CasePlanModel`
**Tracking:** casehubio/engine#76
**Future milestone/goal alignment:** casehubio/engine#84

```java
/**
 * The control blackboard — Hayes-Roth's BB1 "control board" — paired 1:1
 * with each running {@link io.casehub.engine.internal.model.CaseInstance}.
 *
 * <p>Holds the scheduling agenda, current focus of attention, resource budget,
 * stage tracking, milestone lifecycle state, and extensible key-value state.
 * Written to by {@link PlanningStrategy}; read by
 * {@link PlanningStrategyLoopControl} to determine which bindings to return.
 *
 * <p>Domain state ({@code CaseContext}) and control state ({@code CasePlanModel})
 * are deliberately separate — see casehubio/engine#76 and the Hayes-Roth (1985)
 * BB1 architecture.
 *
 * <p>Milestone lifecycle tracking (PENDING → ACHIEVED) on this model is an
 * interim approach. Full alignment of Milestone, Stage, and Goal as a
 * consistent family is tracked in casehubio/engine#84.
 */
public interface CasePlanModel {

    UUID getCaseId();

    // --- Scheduling agenda ---

    void addPlanItem(PlanItem planItem);
    void removePlanItem(String planItemId);
    Optional<PlanItem> getPlanItem(String planItemId);
    /** Returns PENDING PlanItems only, sorted highest-priority first. */
    List<PlanItem> getAgenda();
    List<PlanItem> getTopPlanItems(int maxCount);

    // --- Stage management ---

    void addStage(Stage stage);
    Optional<Stage> getStage(String stageId);
    List<Stage> getPendingStages();
    List<Stage> getActiveStages();
    List<Stage> getAllStages();

    // --- Milestone lifecycle tracking ---
    // Full Milestone/Goal/Stage alignment tracked in casehubio/engine#84.

    void trackMilestone(String milestoneName);
    void achieveMilestone(String milestoneName);
    boolean isMilestoneAchieved(String milestoneName);

    // --- Focus of attention (written by PlanningStrategy) ---

    void setFocus(String focusArea);
    Optional<String> getFocus();
    void setFocusRationale(String rationale);

    // --- Resource budget (written by PlanningStrategy) ---

    void setResourceBudget(Map<String, Object> budget);
    Map<String, Object> getResourceBudget();

    // --- Extensible key-value (custom PlanningStrategy state) ---

    void put(String key, Object value);
    <T> Optional<T> get(String key, Class<T> type);
}
```

### `PlanItem`
**Tracking:** casehubio/engine#76

```java
/**
 * Activation record for a {@link Binding} on the {@link CasePlanModel}
 * scheduling agenda. Priority is assigned by {@link PlanningStrategy};
 * status is updated by {@link PlanItemCompletionHandler} on worker
 * completion.
 *
 * <p>Maps 1:1 to a {@link Binding} — one PlanItem per eligible binding
 * per evaluation cycle. Implements {@link Comparable} for priority-ordered
 * sorting (higher priority first; FIFO for equal priority).
 */
public class PlanItem implements Comparable<PlanItem> {

    private final String planItemId;       // UUID
    private final String bindingName;      // the Binding that triggered this
    private final String workerName;       // the worker that will execute
    private int priority;                  // 0 = default; higher fires first
    private PlanItemStatus status;
    private final Instant createdAt;
    private Optional<String> parentStageId;

    /**
     * Lifecycle states aligned with CNCF Serverless Workflow (OWL) phases.
     * See casehubio/engine#76.
     */
    public enum PlanItemStatus {
        PENDING, RUNNING, COMPLETED, FAULTED, CANCELLED
    }
}
```

### `Stage`
**Tracking:** casehubio/engine#76
**Containment and lifecycle alignment:** casehubio/engine#84

```java
/**
 * CMMN Stage — a container for {@link PlanItem}s, Milestones, and nested
 * Stages that activates and completes as a unit.
 *
 * <p>Entry and exit conditions use {@link ExpressionEvaluator} (JQ or Lambda)
 * — the same expression system used by Bindings and Milestones.
 *
 * <p>Milestone containment ({@code containedMilestoneIds}) is present but
 * Stage exit criteria referencing contained milestone achievement requires
 * the full alignment work in casehubio/engine#84. For PR4, Stage exit
 * criteria use expression evaluation against {@code CaseContext} only.
 *
 * <p>See CMMN 1.1 §5.4.4 for Stage semantics. Full CMMN audit is tracked
 * in casehubio/engine#84.
 */
public class Stage {

    private final String stageId;          // UUID
    private String name;
    private StageStatus status;

    // Conditions — ExpressionEvaluator (JQ or Lambda), optional
    private ExpressionEvaluator entryCondition;
    private ExpressionEvaluator exitCondition;

    // Containment
    private final List<String> containedPlanItemIds;
    private final List<String> containedMilestoneIds;  // casehubio/engine#84
    private final List<String> containedStageIds;
    private final List<String> requiredItemIds;        // for autocomplete

    // Behaviour
    private boolean autocomplete;       // default true
    private boolean manualActivation;   // default false
    private Optional<String> parentStageId;

    // Timestamps, metadata
}
```

---

## Data Flow

```
CONTEXT_CHANGED
  → CaseContextChangedEventHandler
      ├── evaluateTriggers()  →  eligible List<Binding>
      ├── loopControl.select(ctx, eligible)           [Uni — awaited]
      │     └── PlanningStrategyLoopControl.select()
      │           ├── getOrCreate CasePlanModel(caseId)
      │           ├── eligible → PlanItems → plan.addPlanItem()
      │           ├── StageLifecycleEvaluator.evaluate(plan, ctx)
      │           │     ├── activate pending stages (entry condition met)
      │           │     ├── terminate active stages (exit condition met)
      │           │     └── publish StageActivatedEvent / StageTerminatedEvent
      │           └── planningStrategy.select(plan, ctx, eligible)  [Uni]
      ├── publish WorkerScheduleEvent per selected Binding
      │     └── record (caseId, workerName) → planItemId
      ├── evaluateMilestones()
      └── evaluateGoals()

WORKER_EXECUTION_FINISHED  [eventBus.publish — fan-out, two consumers]
  → WorkflowExecutionCompletedHandler  (engine/ — existing)
  → PlanItemCompletionHandler          (casehub-blackboard/ — new)
        ├── look up (caseId, workerName) → planItemId
        ├── planItem.setStatus(COMPLETED)
        ├── for each active Stage containing this planItemId:
        │     if all requiredItemIds COMPLETED or ACHIEVED → stage.complete()
        │     publish StageCompletedEvent
        └── (nested stage completion re-evaluated by parent Stage)

CONTEXT_CHANGED ← cycle repeats
```

`MilestoneAchievementHandler` listens to `MILESTONE_REACHED` (already
published by `CaseContextChangedEventHandler`) and calls
`plan.achieveMilestone(name)` — keeping milestone lifecycle state current
in the `CasePlanModel` for Stage autocomplete checks.

---

## Stage Lifecycle

```
         [entry condition met]
PENDING ──────────────────────► ACTIVE
                                   │
              [exit condition met] │ [all requiredItems done]
                       ┌───────────┤──────────────────────┐
                       ▼           │                       ▼
                  TERMINATED    SUSPENDED             COMPLETED
                                   │
                          [resume] │
                                   ▼
                                ACTIVE
```

**Autocomplete** fires in `PlanItemCompletionHandler` when:
- All IDs in `Stage.requiredItemIds` are either:
  - A `PlanItem` with status `COMPLETED`, or
  - A milestone name for which `plan.isMilestoneAchieved(name)` is true, or
  - A nested Stage ID with status `COMPLETED`
- `stage.isAutocomplete()` is true

**Manual activation** (`stage.isManualActivation() == true`): entry condition
is evaluated but activation requires an explicit `stage.activate()` call —
i.e. from a `PlanningStrategy` that decides to activate it deliberately.

---

## `PlanningStrategyLoopControl`

```java
/**
 * {@link LoopControl} implementation that delegates selection to a
 * {@link PlanningStrategy}, managing a {@link CasePlanModel} per case.
 *
 * <p>Activated via {@code @Alternative @Priority(10)} — replaces
 * {@link ChoreographyLoopControl} when {@code casehub-blackboard} is on
 * the classpath and a {@link PlanningStrategy} CDI bean is present.
 * See casehubio/engine#76.
 */
@Alternative
@Priority(10)
@ApplicationScoped
public class PlanningStrategyLoopControl implements LoopControl {

    @Inject EventBus eventBus;
    @Inject PlanningStrategy planningStrategy;
    @Inject StageLifecycleEvaluator stageLifecycleEvaluator;

    // keyed by caseId
    private final ConcurrentHashMap<UUID, CasePlanModel> planModels = new ConcurrentHashMap<>();
    // (caseId → (workerName → planItemId)) — for PlanItem completion correlation
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, String>> completionIndex
        = new ConcurrentHashMap<>();

    @Override
    public Uni<List<Binding>> select(PlanExecutionContext ctx, List<Binding> eligible) {
        UUID caseId = ctx.getCaseId();
        CasePlanModel plan = planModels.computeIfAbsent(caseId, DefaultCasePlanModel::new);

        List<PlanItem> newItems = toPlanItems(eligible, ctx);
        newItems.forEach(plan::addPlanItem);

        return stageLifecycleEvaluator.evaluate(plan, ctx)
            .chain(() -> planningStrategy.select(plan, ctx, eligible))
            .invoke(selected -> indexForCompletion(caseId, selected, plan));
    }
}
```

---

## Testing Requirements (RDD)

Tests are organised into three layers. All test class comments reference the
relevant issue or epic.

### Layer 1 — Unit Tests (pure Java, no CDI, no Vert.x)

**`DefaultCasePlanModelTest`** — casehubio/engine#76
- Agenda returns only PENDING PlanItems, sorted by priority descending, FIFO for equal priority
- `getTopPlanItems(n)` respects limit; handles n > agenda size gracefully
- `trackMilestone` / `achieveMilestone` / `isMilestoneAchieved` lifecycle
- Stage management: add, retrieve by ID, pending/active filtering

**`PlanItemTest`** — casehubio/engine#76
- `compareTo`: higher priority sorts first; equal priority → earlier createdAt sorts first
- Status transitions: PENDING → RUNNING → COMPLETED; PENDING → CANCELLED

**`StageTest`** — casehubio/engine#76, casehubio/engine#84
- `activate()` from PENDING → ACTIVE; noop from any other state
- `complete()` from ACTIVE → COMPLETED; `terminate()` from ACTIVE → TERMINATED
- `suspend()` / `resume()` round-trip
- `isTerminal()` true for COMPLETED, TERMINATED, FAULTED
- Containment: `addPlanItem`, `addNestedStage`, `addRequiredItem`

**`StageLifecycleEvaluatorTest`** — casehubio/engine#76
- Pending stage with satisfied entry condition → activates, returns activated list
- Pending stage with unsatisfied entry condition → remains PENDING
- Active stage with satisfied exit condition → terminates, returns terminated list
- Active stage with unsatisfied exit condition → remains ACTIVE
- `manualActivation = true` → entry condition met but stage stays PENDING

**`DefaultPlanningStrategyTest`** — casehubio/engine#76
- Returns all eligible bindings unchanged
- Empty eligible → returns empty list (not null)
- Does not modify plan focus or budget

**`PlanningStrategyContractTest` (abstract)** — casehubio/engine#76
Extend to verify any custom strategy honours the contract:
- Never returns bindings not in the eligible list
- Never returns null
- Handles empty eligible
- Returns a non-null `Uni`

### Layer 2 — Integration Tests (`@QuarkusTest`, in-memory SPI, no Docker)

Follow PR3 pattern: copy in-memory SPI impls into `src/test/java/` to avoid
Maven cycle. No Testcontainers — all persistence is in-memory.

**Happy Path — choreography unchanged** — casehubio/engine#76
- Start a case without `casehub-blackboard` activated → verify
  `ChoreographyLoopControl` fires all eligible bindings; no `CasePlanModel`
  created; no stage events published.

**Happy Path — `PlanningStrategyLoopControl` activates** — casehubio/engine#76
- Register `PlanningStrategyLoopControl` as `@Alternative @Priority(10)`
- Start a case with two eligible bindings
- `DefaultPlanningStrategy` returns both → both workers scheduled
- `PlanItemCompletionHandler` marks both COMPLETED
- Verify `CasePlanModel` agenda is empty after completion

**Happy Path — Stage activates on context change** — casehubio/engine#76
- Define a Stage with an entry condition `".phase == \"analysis\""`
- Signal case context with `phase = "analysis"`
- Verify `StageActivatedEvent` published on EventBus
- Verify stage status is ACTIVE in `CasePlanModel`

**Happy Path — Stage autocomplete** — casehubio/engine#76
- Define a Stage containing two bindings as required items
- Both workers complete
- Verify `StageCompletedEvent` published after second completion
- Verify stage status is COMPLETED

**Happy Path — Stage terminated on exit condition** — casehubio/engine#76
- Define an active Stage with exit condition `".abort == true"`
- Signal `abort = true`
- Verify `StageTerminatedEvent` published; stage status TERMINATED

**Happy Path — Milestone tracked in CasePlanModel** — casehubio/engine#76, casehubio/engine#84
- Case definition includes a Milestone `"documents-received"`
- Signal context satisfying milestone condition
- Verify `MilestoneAchievementHandler` calls `plan.achieveMilestone("documents-received")`
- Verify `plan.isMilestoneAchieved("documents-received")` is true

**Happy Path — Custom PlanningStrategy enforces sequential execution** — casehubio/engine#76
- Strategy returns only the highest-priority PlanItem per cycle
- First worker completes → CONTEXT_CHANGED → second binding now selected
- Verify workers executed sequentially, not concurrently

**Happy Path — Nested stage completion propagates to parent** — casehubio/engine#76
- Parent stage requires one nested child stage
- Child stage autocompletes
- Verify parent stage also autocompletes and `StageCompletedEvent` fires for parent

### Layer 3 — End-to-End Tests (`@QuarkusTest`, full Hibernate Reactive stack)

Run against the full persistence stack (PostgreSQL via Testcontainers, or
H2 in Hibernate Reactive compat mode). Verify the complete reactive chain
from signal to stage completion is correctly persisted in EventLog.

**E2E Happy Path — document analysis with stages** — casehubio/engine#76
- Loan application case: two stages (`intake`, `underwriting`)
- `intake` stage: bindings for OCR + entity extraction, autocompletes
- `underwriting` stage: entry condition `".intakeComplete == true"`, binding
  for credit-check, autocompletes
- Verify EventLog contains: WORKER_EXECUTION_COMPLETED × 3, MILESTONE_REACHED
  × 1 (`intake-complete`), STAGE events × 4
- Verify final CaseStatus is COMPLETED (GoalBasedCompletion on `decision`)

**E2E Happy Path — stage termination on abort signal** — casehubio/engine#76
- Active stage receives abort signal mid-execution
- Verify StageTerminatedEvent persisted in EventLog
- Verify case moves to FAILED via Goal `kind(GoalKind.FAILURE)`

---

## What Is NOT in Scope for PR4

| Item | Tracked in |
|------|-----------|
| Async PlanningStrategy querying EventLog | casehubio/engine#82 |
| Meta-level control / stall detection | casehubio/engine#78 |
| Private agent scratchpad | casehubio/engine#79 |
| Memory stratification / panels | casehubio/engine#80, #81 |
| Milestone stage containment (parentStageId) | casehubio/engine#84 |
| Goal.terminal cleanup | casehubio/engine#84 |
| CasePlanModel persistence SPI | casehubio/engine#84 (or later) |
| TaskBroker / TaskScheduler subsystem | migration plan Phase 3 |

---

## References

| Resource | Location |
|----------|----------|
| Migration plan | `docs/superpowers/specs/2026-04-14-casehub-engine-migration-plan.md` |
| Article: reactive blackboard | `docs/_posts/2026-04-18-mdp02-reactive-blackboard-control-shell.md` |
| Epic: CMMN/Blackboard features | casehubio/engine#30 |
| PR4 issue | casehubio/engine#76 |
| Future evolution epic | casehubio/engine#77 |
| Research improvements #78–#83 | casehubio/engine#78, #79, #80, #81, #82, #83 |
| Milestone/Goal/Stage alignment | casehubio/engine#84 |
| casehub source (control layer) | `casehub-core/src/main/java/io/casehub/control/` |
