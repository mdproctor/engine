# Milestone and Goal Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #84 — Epic: Milestone, Stage, and Goal — Full Conceptual Alignment
**Issue group:** #84

**Goal:** Remove dead code from Stage retirement, enforce the Goal/Milestone boundary, consolidate milestone state to CaseContext, and clean up dead enum values.

**Architecture:** Five independent cleanup sections from the design spec. Tasks are ordered by dependency: S1 (parentStageId removal) and S2 (goal rejection) are independent. S3 removes CasePlanModel tracking, deletes deprecated infrastructure, and refactors MilestoneLifecycleManager. S4 cleans up dead enum values. S5 is documentation.

**Tech Stack:** Java 21, Quarkus 3.32.2, Vert.x event bus, Quartz RAM store

## Global Constraints

- All `@QuarkusTest` classes must be named `*Test.java` (never `*IT.java`)
- `casehub-persistence-memory` for in-memory test dependencies
- Follow existing `@DefaultBean` / `@Alternative @Priority` test patterns
- Use `ide_edit_member` / `ide_replace_member` for code changes, `ide_refactor_safe_delete` for deletions
- Commit messages: `feat(#84): <description>` or `refactor(#84): <description>`

---

### Task 1: Remove `parentStageId` from Milestone

**Files:**
- Modify: `api/src/main/java/io/casehub/api/model/Milestone.java`
- Delete: `api/src/test/java/io/casehub/api/model/MilestoneParentStageTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: `Milestone` without `parentStageId` — all downstream tasks see the cleaned API

- [ ] **Step 1: Delete `MilestoneParentStageTest.java`**

Use `ide_refactor_safe_delete` on `api/src/test/java/io/casehub/api/model/MilestoneParentStageTest.java`.

- [ ] **Step 2: Remove `parentStageId` from Milestone class**

Using `ide_edit_member` on `Milestone`:

Remove the field `parentStageId` (line 101).

Remove the constructor parameter `parentStageId` and its assignment in the constructor body.

Remove `getParentStageId()` method.

Remove `parentStageId` from Builder: the field (line 162), the builder method `parentStageId(String)` (lines 223-226).

Update `build()` to not pass `parentStageId` to the constructor.

Remove `parentStageId` from `equals()` and `hashCode()`.

The constructor becomes:
```java
public Milestone(
    String name,
    ExpressionEvaluator entryCriteria,
    ExpressionEvaluator completionCriteria,
    Duration slaDuration,
    SlaStartFrom slaStartFrom) {
  this.name = name;
  this.entryCriteria = entryCriteria;
  this.completionCriteria = completionCriteria;
  this.slaDuration = slaDuration;
  this.slaStartFrom = slaStartFrom;
}
```

- [ ] **Step 3: Update Milestone Javadoc**

Replace the class-level Javadoc. Key change: remove "stage exit criteria" reference (line 89), replace with:

```
 * Milestone state is written to CaseContext at {@code milestones.<name>.*} and can be
 * referenced by any {@link io.casehub.platform.api.expression.ExpressionEvaluator} —
 * compound conditions, binding triggers, goal conditions.
```

- [ ] **Step 4: Build and verify**

Run: `mvn compile -pl api -q`
Expected: BUILD SUCCESS (no compilation errors)

- [ ] **Step 5: Run existing Milestone tests**

Run: `TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl api -Dtest="MilestoneTest" -q`
Expected: All tests pass

- [ ] **Step 6: Commit**

```bash
git add api/src/main/java/io/casehub/api/model/Milestone.java
git add api/src/test/java/io/casehub/api/model/MilestoneParentStageTest.java
git commit -m "refactor(#84): remove dead parentStageId from Milestone

Stage was retired in blocks#60 Phase 3C.3. The field had zero
production references. Compound conditions reference milestone state
via ExpressionEvaluator on CaseContext — structural containment is
not needed.

Refs #84"
```

---

### Task 2: Reject unreferenced Goals at registration

**Files:**
- Modify: `runtime/src/main/java/io/casehub/engine/internal/engine/DefaultCaseDefinitionRegistry.java`
- Modify: `runtime/src/test/java/io/casehub/engine/internal/engine/DefaultCaseDefinitionRegistryGoalWarningTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: `DefaultCaseDefinitionRegistry.registerCaseDefinitionBlocking()` throws `IllegalArgumentException` for unreferenced goals

- [ ] **Step 1: Write failing test — unreferenced goal throws**

In `DefaultCaseDefinitionRegistryGoalWarningTest.java`, rename `warns_when_goal_not_referenced_in_any_goal_expression` to `rejects_goal_not_referenced_in_any_goal_expression` and change assertion from log-capture to exception:

```java
@Test
void rejects_goal_not_referenced_in_any_goal_expression() {
  var unreferencedGoal =
      Goal.builder()
          .name("orphan-goal")
          .condition(".orphan == true")
          .kind(GoalKind.SUCCESS)
          .build();

  var referencedGoal =
      Goal.builder().name("real-goal").condition(".done == true").kind(GoalKind.SUCCESS).build();

  var definition =
      CaseDefinition.builder()
          .namespace("test")
          .name("reject-test")
          .version("1.0")
          .goals(List.of(unreferencedGoal, referencedGoal))
          .completion(GoalExpression.allOf(referencedGoal), null)
          .build();

  assertThatThrownBy(
          () ->
              registry
                  .registerCaseDefinition(definition)
                  .subscribe()
                  .asCompletionStage()
                  .toCompletableFuture()
                  .join())
      .hasRootCauseInstanceOf(IllegalArgumentException.class)
      .hasRootCauseMessage(
          "Goal 'orphan-goal' is not referenced in any completion expression. "
              + "Goals must drive case completion — use Milestone for non-terminal checkpoints.");
}
```

- [ ] **Step 2: Write failing test — goals with PredicateBasedCompletion**

```java
@Test
void rejects_goal_with_predicate_based_completion() {
  var goal =
      Goal.builder()
          .name("orphan")
          .condition(".x == true")
          .kind(GoalKind.SUCCESS)
          .build();

  var definition =
      CaseDefinition.builder()
          .namespace("test")
          .name("predicate-reject-test")
          .version("1.0")
          .goals(List.of(goal))
          .completion("(.done == true)")
          .build();

  assertThatThrownBy(
          () ->
              registry
                  .registerCaseDefinition(definition)
                  .subscribe()
                  .asCompletionStage()
                  .toCompletableFuture()
                  .join())
      .hasRootCauseInstanceOf(IllegalArgumentException.class);
}
```

- [ ] **Step 3: Write failing test — no goals, no completion is valid**

```java
@Test
void accepts_definition_with_no_goals_and_no_completion() {
  var definition =
      CaseDefinition.builder()
          .namespace("test")
          .name("no-goal-test")
          .version("1.0")
          .build();

  var result =
      registry
          .registerCaseDefinition(definition)
          .subscribe()
          .asCompletionStage()
          .toCompletableFuture()
          .join();

  assertThat(result).isNotNull();
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl runtime -Dtest="DefaultCaseDefinitionRegistryGoalWarningTest" -q`
Expected: `rejects_goal_not_referenced_in_any_goal_expression` and `rejects_goal_with_predicate_based_completion` FAIL (warning instead of exception). `accepts_definition_with_no_goals_and_no_completion` should PASS.

- [ ] **Step 5: Upgrade warning to exception in DefaultCaseDefinitionRegistry**

In `DefaultCaseDefinitionRegistry.validateExpressions()` (lines 296-310), replace the WARNING block with:

```java
// Reject goals not referenced in any GoalExpression
if (definition.getGoals() != null && !definition.getGoals().isEmpty()) {
  if (definition.getCompletion() instanceof GoalBasedCompletion<?> gbc) {
    var referencedGoals = new HashSet<String>();
    for (var entry : gbc.getGoals().entrySet()) {
      GoalExpression expr = entry.getValue();
      if (expr != null) {
        referencedGoals.addAll(expr.goalNames());
      }
    }
    for (Goal goal : definition.getGoals()) {
      if (!referencedGoals.contains(goal.getName())) {
        throw new IllegalArgumentException(
            "Goal '" + goal.getName()
                + "' is not referenced in any completion expression. "
                + "Goals must drive case completion — use Milestone for non-terminal checkpoints.");
      }
    }
  } else {
    // Goals defined but completion is not GoalBasedCompletion (e.g. PredicateBasedCompletion or null)
    for (Goal goal : definition.getGoals()) {
      throw new IllegalArgumentException(
          "Goal '" + goal.getName()
              + "' is not referenced in any completion expression. "
              + "Goals must drive case completion — use Milestone for non-terminal checkpoints.");
    }
  }
}
```

Keep the kind mismatch warning block (lines 321-337) unchanged.

- [ ] **Step 6: Run tests**

Run: `TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl runtime -Dtest="DefaultCaseDefinitionRegistryGoalWarningTest" -q`
Expected: All tests PASS

- [ ] **Step 7: Remove log-capture test infrastructure**

The log capture fields (`logRecords`, `testHandler`, `logger`) and `@BeforeEach`/`@AfterEach` methods in `DefaultCaseDefinitionRegistryGoalWarningTest` are no longer needed if no remaining tests use them. Check — if `warns_kind_mismatch_when_goal_referenced_in_wrong_completion_entry` still uses log capture, keep it. Otherwise remove the infrastructure.

- [ ] **Step 8: Commit**

```bash
git add runtime/src/main/java/io/casehub/engine/internal/engine/DefaultCaseDefinitionRegistry.java
git add runtime/src/test/java/io/casehub/engine/internal/engine/DefaultCaseDefinitionRegistryGoalWarningTest.java
git commit -m "feat(#84): reject unreferenced Goals at registration

Goals must drive case completion. A Goal not referenced in any
GoalExpression is now an IllegalArgumentException at registration
time. Non-terminal checkpoints should use Milestone.

Refs #84"
```

---

### Task 3: Remove CasePlanModel milestone tracking and deprecated infrastructure

**Files:**
- Modify: `planning/src/main/java/io/casehub/engine/planning/plan/CasePlanModel.java`
- Modify: `planning/src/main/java/io/casehub/engine/planning/plan/DefaultCasePlanModel.java`
- Delete: `planning/src/main/java/io/casehub/engine/planning/handler/MilestoneAchievementHandler.java`
- Delete: `planning/src/test/java/io/casehub/engine/planning/handler/MilestoneAchievementHandlerTest.java`
- Modify: `planning/src/test/java/io/casehub/engine/planning/plan/DefaultCasePlanModelTest.java`
- Delete: `common/src/main/java/io/casehub/engine/common/internal/event/MilestoneReachedEvent.java`
- Delete: `runtime/src/main/java/io/casehub/engine/internal/engine/handler/MilestoneReachedEventHandler.java`
- Modify: `common/src/main/java/io/casehub/engine/common/internal/event/EventBusAddresses.java`

**Interfaces:**
- Consumes: nothing
- Produces: `CasePlanModel` without milestone methods; no `MILESTONE_REACHED` event infrastructure

- [ ] **Step 1: Delete MilestoneAchievementHandler and its test**

Use `ide_refactor_safe_delete` on:
- `planning/src/main/java/io/casehub/engine/planning/handler/MilestoneAchievementHandler.java`
- `planning/src/test/java/io/casehub/engine/planning/handler/MilestoneAchievementHandlerTest.java`

- [ ] **Step 2: Remove milestone methods from CasePlanModel interface**

Remove these 6 methods from `CasePlanModel.java` (lines 94-105):
- `trackMilestone(String milestoneName)`
- `activateMilestone(String milestoneName)`
- `completeMilestone(String milestoneName)`
- `getMilestoneStatus(String milestoneName)`
- `achieveMilestone(String milestoneName)`
- `isMilestoneAchieved(String milestoneName)`

Also remove the `MilestoneLifecycleStatus` import.

- [ ] **Step 3: Remove milestone implementations from DefaultCasePlanModel**

Remove the `milestones` ConcurrentHashMap field (line 46-47).

Remove all milestone method implementations:
- `trackMilestone(String name)` (lines 163-165)
- `activateMilestone(String name)` (lines 168-184)
- `completeMilestone(String name)` (lines 186-203)
- `getMilestoneStatus(String name)` (lines 205-207)
- `achieveMilestone(String name)` (lines 211-213)
- `isMilestoneAchieved(String name)` (lines 216-218)

Remove the `MilestoneLifecycleStatus` import.

- [ ] **Step 4: Remove milestone tests from DefaultCasePlanModelTest**

Remove these test methods:
- `achieveMilestone_delegates_to_completeMilestone`
- `milestone_tracks_as_pending`
- `activate_milestone_transitions_to_active`
- `complete_milestone_transitions_to_completed`
- `complete_from_pending_handles_out_of_order_delivery`
- `activate_when_already_active_is_noop`
- `activate_when_completed_is_noop`
- `complete_when_already_completed_is_noop`

- [ ] **Step 5: Delete MilestoneReachedEvent**

Use `ide_refactor_safe_delete` on `common/src/main/java/io/casehub/engine/common/internal/event/MilestoneReachedEvent.java`.

- [ ] **Step 6: Delete MilestoneReachedEventHandler**

Use `ide_refactor_safe_delete` on `runtime/src/main/java/io/casehub/engine/internal/engine/handler/MilestoneReachedEventHandler.java`.

- [ ] **Step 7: Remove MILESTONE_REACHED from EventBusAddresses**

Remove line 42: `public static final String MILESTONE_REACHED = "casehub.milestone.reached";`

Note: retain `CaseHubEventType.MILESTONE_REACHED` — historical EventLog rows reference it.

- [ ] **Step 8: Build to verify compilation**

Run: `mvn compile -pl planning,common,runtime -q`
Expected: BUILD SUCCESS

- [ ] **Step 9: Run planning tests**

Run: `TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl planning -q`
Expected: All tests pass

- [ ] **Step 10: Commit**

```bash
git add planning/ common/ runtime/
git commit -m "refactor(#84): remove CasePlanModel milestone tracking and deprecated infrastructure

CasePlanModel milestone methods had zero production callers —
MilestoneAchievementHandler wrote to them but nothing read the values.
CaseContext at milestones.<name>.* is the canonical runtime state.

Also removes deprecated MilestoneReachedEvent, MilestoneReachedEventHandler,
and MILESTONE_REACHED event bus address (no publishers).
Retains CaseHubEventType.MILESTONE_REACHED for EventLog backward compat.

Refs #84"
```

---

### Task 4: Refactor MilestoneLifecycleManager to read from CaseContext

**Files:**
- Modify: `runtime/src/main/java/io/casehub/engine/internal/milestone/MilestoneLifecycleManager.java`
- Modify: `runtime/src/test/java/io/casehub/engine/MilestoneLifecycleTest.java`

**Interfaces:**
- Consumes: Task 3 complete (CasePlanModel milestone methods removed)
- Produces: `MilestoneLifecycleManager` reads lifecycle/SLA status from CaseContext instead of EventLog

- [ ] **Step 1: Write a test verifying CaseContext-based status reading**

In `MilestoneLifecycleTest.java`, add a test that verifies the lifecycle manager reads from CaseContext rather than EventLog. The existing integration tests should already cover this because MilestoneActivatedEventHandler writes to CaseContext before CONTEXT_CHANGED re-fires, but verify the existing tests pass after the refactor.

- [ ] **Step 2: Refactor `getCurrentLifecycleStatus()` to read from CaseContext**

Replace the EventLog-based method with:

```java
private MilestoneLifecycleStatus getCurrentLifecycleStatus(CaseInstance caseInstance, String milestoneName) {
  CaseContext context = caseInstance.getCaseContext();
  Object statusObj = context.layer("working").get("milestones." + milestoneName + ".lifecycleStatus");
  if (statusObj == null) {
    return MilestoneLifecycleStatus.PENDING;
  }
  try {
    return MilestoneLifecycleStatus.valueOf(statusObj.toString());
  } catch (IllegalArgumentException e) {
    LOG.warnf("Unknown milestone lifecycle status '%s' for milestone '%s', treating as PENDING",
        statusObj, milestoneName);
    return MilestoneLifecycleStatus.PENDING;
  }
}
```

Update the call site in `evaluateMilestone()` to pass `caseInstance` instead of `(caseInstance.getUuid(), milestoneName, caseInstance.tenancyId)`.

- [ ] **Step 3: Refactor `getCurrentSlaStatus()` to read from CaseContext**

Replace the EventLog-based method with:

```java
private SlaStatus getCurrentSlaStatus(CaseInstance caseInstance, String milestoneName) {
  CaseContext context = caseInstance.getCaseContext();
  Object statusObj = context.layer("working").get("milestones." + milestoneName + ".slaStatus");
  if (statusObj == null) {
    return SlaStatus.NOT_STARTED;
  }
  try {
    return SlaStatus.valueOf(statusObj.toString());
  } catch (IllegalArgumentException e) {
    LOG.warnf("Unknown SLA status '%s' for milestone '%s', treating as NOT_STARTED",
        statusObj, milestoneName);
    return SlaStatus.NOT_STARTED;
  }
}
```

Update the call site in `evaluateCompletionCriteria()` to pass `caseInstance` instead of just using `caseInstance` for UUID.

- [ ] **Step 4: Delete dead code**

Remove:
- `findLastMilestoneEvent()` method
- `MILESTONE_LIFECYCLE_EVENTS` EnumSet field
- `EventLogRepository` injection (check: still needed for `calculateSlaDeadline()` — if yes, keep it; verify by searching for `eventLogRepository` usage in the file)

- [ ] **Step 5: Run milestone lifecycle tests**

Run: `TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl runtime -Dtest="MilestoneLifecycleTest" -q`
Expected: All tests pass

- [ ] **Step 6: Run full runtime test suite**

Run: `TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl runtime -q`
Expected: All tests pass (no regressions)

- [ ] **Step 7: Commit**

```bash
git add runtime/src/main/java/io/casehub/engine/internal/milestone/MilestoneLifecycleManager.java
git add runtime/src/test/java/io/casehub/engine/MilestoneLifecycleTest.java
git commit -m "refactor(#84): MilestoneLifecycleManager reads from CaseContext

Replaces EventLog queries (O(milestones * events) per CONTEXT_CHANGED)
with CaseContext reads (O(1)). CaseContext at milestones.<name>.* is
the canonical runtime state, written by MilestoneActivatedEventHandler
and MilestoneCompletedEventHandler.

MilestoneSLATimeoutJob retains EventLog-based queries — it fires from
Quartz after JVM restarts when CaseContext is not populated.

Refs #84"
```

---

### Task 5: Remove dead enum values and unimplemented SLA modes

**Files:**
- Modify: `api/src/main/java/io/casehub/api/model/MilestoneLifecycleStatus.java`
- Modify: `api/src/main/java/io/casehub/api/model/SlaStartFrom.java`
- Modify: `runtime/src/main/java/io/casehub/engine/internal/engine/handler/MilestoneActivatedEventHandler.java`

**Interfaces:**
- Consumes: Task 3 and 4 complete (references to FAILED/CANCELLED in CasePlanModel already removed)
- Produces: cleaned enums with only implemented values

- [ ] **Step 1: Remove FAILED and CANCELLED from MilestoneLifecycleStatus**

Replace the enum body with:

```java
/**
 * Lifecycle status of a milestone.
 *
 * <p>A milestone progresses: PENDING → ACTIVE → COMPLETED.
 *
 * <ul>
 *   <li><b>PENDING</b> — waiting for entryCriteria to become true
 *   <li><b>ACTIVE</b> — entryCriteria met, working toward completionCriteria
 *   <li><b>COMPLETED</b> — completionCriteria met successfully
 * </ul>
 */
public enum MilestoneLifecycleStatus {
  PENDING,
  ACTIVE,
  COMPLETED
}
```

- [ ] **Step 2: Remove PREVIOUS_MILESTONE_COMPLETED and EVENT_OCCURRED from SlaStartFrom**

Replace the enum body with:

```java
/**
 * Defines when SLA deadline calculation starts for a milestone.
 *
 * <ul>
 *   <li><b>CASE_CREATED</b> — SLA starts from case creation timestamp
 *   <li><b>MILESTONE_ACTIVATED</b> — SLA starts from PENDING → ACTIVE transition (default)
 * </ul>
 */
public enum SlaStartFrom {
  CASE_CREATED,
  MILESTONE_ACTIVATED
}
```

- [ ] **Step 3: Update `isTerminalLifecycleStatus()` in MilestoneActivatedEventHandler**

Replace (lines 226-230):

```java
private boolean isTerminalLifecycleStatus(String lifecycleStatus) {
  return MilestoneLifecycleStatus.COMPLETED.name().equals(lifecycleStatus);
}
```

- [ ] **Step 4: Build and verify**

Run: `mvn compile -q`
Expected: BUILD SUCCESS (no references to removed enum values remain)

If compilation fails, check for references to removed enum values in other files and fix them.

- [ ] **Step 5: Run full test suite for affected modules**

Run: `TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl api,runtime -q`
Expected: All tests pass

- [ ] **Step 6: Commit**

```bash
git add api/src/main/java/io/casehub/api/model/MilestoneLifecycleStatus.java
git add api/src/main/java/io/casehub/api/model/SlaStartFrom.java
git add runtime/src/main/java/io/casehub/engine/internal/engine/handler/MilestoneActivatedEventHandler.java
git commit -m "refactor(#84): remove dead MilestoneLifecycleStatus and SlaStartFrom values

FAILED/CANCELLED had no code path producing them.
PREVIOUS_MILESTONE_COMPLETED/EVENT_OCCURRED threw UnsupportedOperationException.
Removed to prevent runtime traps. Re-add when implemented.

Refs #84"
```

---

### Task 6: Platform design documentation

**Files:**
- Modify: `docs/DESIGN.md`

**Interfaces:**
- Consumes: Tasks 1-5 complete
- Produces: documented design decisions in DESIGN.md

- [ ] **Step 1: Read current DESIGN.md structure**

Read `docs/DESIGN.md` to understand existing headings and placement.

- [ ] **Step 2: Add Milestone and Goal Alignment section**

Add a new section (placement depends on existing structure — after any existing concept overview):

```markdown
## Milestone and Goal Alignment

### Composition model

`ExpressionEvaluator` on `CaseContext` is the platform's universal evaluation surface.
Milestone conditions, goal conditions, binding triggers, and compound entry/exit criteria
all compose through pluggable expression evaluation (JQ, MVEL, lambda). Structural
containment relationships (CMMN Stage→Milestone) are replaced by expression-based
composition — any condition can reference any context data.

### Milestone/Goal boundary

| Concept | Question | Nature | State | Scope |
|---------|----------|--------|-------|-------|
| Milestone | Where are we? | Neutral progress marker | PENDING→ACTIVE→COMPLETED in CaseContext | Case-level |
| Goal | What outcome? | Terminal condition with GoalKind | Drives CaseStatus via GoalBasedCompletion | Case-level |

Goals are always terminal. Unreferenced goals are rejected at registration. A
non-terminal checkpoint is a Milestone.

### Milestone lifecycle state

CaseContext (`milestones.<name>.*`) is the canonical runtime state for event-driven
consumers. EventLog records events for audit and scheduled job queries
(`MilestoneSLATimeoutJob`). `MilestoneLifecycleManager` reads from CaseContext;
the SLA timeout Quartz job reads from EventLog (CaseContext unavailable after JVM restart).

### CMMN deviations (deliberate)

| CMMN concept | casehub equivalent | Rationale |
|-------------|-------------------|-----------|
| Stage containment of Milestones | Expression-based composition via CaseContext | More flexible — any condition can reference any milestone |
| Milestones as PlanItemDefinitions | Separate definition-time concept | Milestones are evaluated (condition-driven), not dispatched (execution-driven) |
| Exit criteria on Case/Stage for completion | Explicit Goal + GoalBasedCompletion | Clearer intent — goals are named, typed, and composed via GoalExpressions |

### Extension points

- `Compound.scopedMilestones: Set<String>` — for future compound-scoped milestone evaluation (follows `scopedBindings` pattern)
- `SlaStartFrom` — for future SLA start modes (milestone chaining, event correlation)
```

- [ ] **Step 3: Commit**

```bash
git add docs/DESIGN.md
git commit -m "docs(#84): document Milestone/Goal alignment design decisions

Records the deliberate platform design: ExpressionEvaluator-based
composition, Goal/Milestone boundary, CaseContext as canonical milestone
state, and CMMN deviations with rationale.

Closes #84"
```
