# Design: CaseLifecycleEvent Enrichment + Composed GoalExpression

**Date:** 2026-07-13
**Issues:** #571, #548
**Branch:** issue-571-lifecycle-event-goals

---

## #571 — CaseLifecycleEvent Enrichment

### Problem

`CaseLifecycleEvent` is a thin record: `(caseId, tenancyId, commandType, eventType, caseStatus, actorId, actorRole, traceId)`. Consumers that need to discriminate by case type or extract context data must call `caseInstanceRepository.findByUuid().await()` — a reactive round-trip. Under `@Transactional` observers, this holds a JDBC connection from the Agroal pool during `.await()`, creating pool contention under sustained load.

### Design

Add three fields to the record:

```java
public record CaseLifecycleEvent(
    UUID caseId,
    String tenancyId,
    String commandType,
    String eventType,
    String caseStatus,
    String actorId,
    String actorRole,
    String traceId,
    // New fields:
    String caseDefinitionName,   // from CaseMetaModel.getName()
    String namespace,            // from CaseMetaModel.getNamespace()
    JsonNode contextSnapshot     // working layer as JsonNode
) {}
```

**Field semantics:**

| Field | Source | Nullable | When null |
|-------|--------|----------|-----------|
| `caseDefinitionName` | `caseInstance.getCaseMetaModel().getName()` | Yes | Events fired before meta model association (unlikely in practice) |
| `namespace` | `caseInstance.getCaseMetaModel().getNamespace()` | Yes | Same as above |
| `contextSnapshot` | `caseInstance.getCaseContext().layer(ContextLayer.WORKING).asJsonNode()` | Yes | Events fired before context initialization |

**Module dependency:** `CaseLifecycleEvent` lives in `casehub-engine-common`. `JsonNode` (Jackson) is already available in `common` (`EventLog.metadata` uses it). `ContextLayer` is in `api/context/` — but the fire sites are in `runtime` which has access to both. The record just declares `JsonNode`; the conversion happens at the fire site.

### Fire sites to update

All handlers in `runtime/internal/engine/handler/` that construct `new CaseLifecycleEvent(...)`:

1. `CaseStartedEventHandler.onCaseStarted()`
2. `CaseStatusChangedHandler.onCaseStatusChangedHandler()`
3. `CaseContextChangedEventHandler.tryProvision()`
4. `GoalReachedEventHandler.onGoalReachedEventHandler()`
5. `SignalReceivedEventHandler.applySignalUnderLock()`
6. `SignalReceivedEventHandler.applyBulkSignalUnderLock()`
7. `WorkflowExecutionCompletedHandler.onWorkflowExecutionCompletedHandler()`
8. `WorkflowExecutionCompletedHandler.handleSemanticFailure()`
9. `WorkflowExecutionCompletedHandler.handleGate()`
10. `MilestoneActivatedEventHandler.onMilestoneActivated()`
11. `MilestoneReachedEventHandler.onMilestoneReachedEventHandler()`
12. `MilestoneCompletedEventHandler.onMilestoneCompleted()`
13. `QuartzWorkerExecutionJobListener.jobToBeExecuted()`

All except #13 have `CaseInstance` in scope — extraction is mechanical. `QuartzWorkerExecutionJobListener.jobToBeExecuted()` (#13) runs in a Quartz thread and constructs the event from `JobDataMap` strings — no `CaseInstance` is available. This site uses the overloaded factory (see below) and passes null for all enrichment fields.

### Helper method

To avoid repeating extraction logic at 13 call sites, add a static factory:

```java
public record CaseLifecycleEvent(...) {

    public static CaseLifecycleEvent of(
            CaseInstance caseInstance,
            String commandType,
            String eventType,
            String actorId,
            String actorRole,
            String traceId) {
        // Extract enrichment fields from caseInstance
    }
}
```

**Module concern:** `CaseInstance` is in `common/internal/model/`, so the factory can live on `CaseLifecycleEvent` in `common/spi/event/` — both are in `common`. `common` depends on `api`, so `ContextLayer.WORKING` is available for the snapshot extraction. The factory handles null-safety for `getCaseMetaModel()` and `getCaseContext()`.

**Overloaded factory for sites without CaseInstance:**

```java
public static CaseLifecycleEvent of(
        UUID caseId,
        String tenancyId,
        String commandType,
        String eventType,
        String caseStatus,
        String actorId,
        String actorRole,
        String traceId) {
    return new CaseLifecycleEvent(caseId, tenancyId, commandType, eventType,
        caseStatus, actorId, actorRole, traceId, null, null, null);
}
```

Used by `QuartzWorkerExecutionJobListener` and test code that constructs minimal events without a `CaseInstance`. Enrichment fields are explicitly null — consumers must handle this.

### contextSnapshot contract

**Timing:** `contextSnapshot` captures the case context **at the moment the event is fired** — point-in-time semantics. Different event types fire at different lifecycle points:
- Post-transition: `CaseStatusChangedHandler` sets the new state before firing
- Post-output: `WorkflowExecutionCompletedHandler` applies outputs before firing
- Pre-transition: `GoalReachedEventHandler` fires before evaluating completion

Consumers must understand that the snapshot's position in the lifecycle depends on the event type. There is no universal "before" or "after" guarantee — only "at fire time."

**Immutability:** `WritableLayerImpl.asJsonNode()` calls `MAPPER.convertValue(data, JsonNode.class)`, which creates a **fresh Jackson tree** from the internal `Map<String, Object>` data — not a live reference. Mutations to the returned `JsonNode` do not affect the case context. However, because `Event.fireAsync()` delivers the same event object to all `@ObservesAsync` observers, observers must treat `contextSnapshot` as **read-only**. Observers that need to annotate or transform the snapshot should call `contextSnapshot.deepCopy()` locally.

**Memory:** Each snapshot is a Jackson tree sized by the working layer's content, which is bounded by the case definition's complexity (controlled by the definition author). Snapshots are retained until all async observers complete. Under sustained load with slow observers (e.g., `CaseLedgerEventCapture` under DB contention), snapshots may accumulate. This is an acceptable tradeoff — the alternative (lazy `Supplier<JsonNode>`) introduces timing non-determinism between fire and observation, and opt-in snapshots add API complexity for a speculative problem.

### Consumer impact

Existing consumers (`CaseLedgerEventCapture`, `CaseMemoryObserver`, `SubCaseCompletionService`) continue working — they use named accessors, not positional. The new fields are available but unused until consumers opt in. No consumer code changes required.

### Test sites

All test files that construct `new CaseLifecycleEvent(...)` need the three new fields added (~25 constructor call sites across 12 files):

**Runtime module:**
- `CaseLifecycleCdiEventTest.java`
- `CaseMemoryObserverTest.java` (3 sites)
- `CaseContextChangedEventHandlerRoutingTest.java`
- `BulkSignalEventLogAuditTest.java`

**Ledger module:**
- `CaseLedgerEventCaptureTest.java` (11 sites)
- `CaseLedgerEventCaptureDisabledTest.java`

**Blackboard module:**
- `SubCaseCompletionServiceTest.java`
- `SubCaseMofNIntegrationTest.java` (2 sites)
- `SubCaseParallelIntegrationTest.java` (3 sites)
- `SubCaseMofNOutputMappingTest.java`

Tests with `CaseInstance` available use `CaseLifecycleEvent.of(caseInstance, ...)`. Cross-module tests (ledger, blackboard) that construct minimal events from raw data use the overloaded `CaseLifecycleEvent.of(caseId, tenancyId, ...)` factory.

### Acknowledged TODOs

`MilestoneActivatedEventHandler.java:200` contains `// TODO: could immediately fire SLA violation here`. This handler is fire site #10. The TODO is about SLA violation handling — a separate concern from enrichment. Tracked as a GitHub issue.

---

## #548 — Composed GoalExpression

### Problem

`GoalExpression` is structurally flat. `AllOfGoalExpression` and `AnyOfGoalExpression` each hold a `Collection<Goal>` — no nesting. `anyOf(allOf(a,b,c), d)` is not expressible. devtown works around this by prepending `.pr.status == "merged" or` to each individual goal's JQ condition, polluting goal semantics.

### Additional finding

`GoalExpression extends Predicate<Collection<Goal>>` with `getGoals()` returning `Collection<Goal>`. The `Predicate<Collection<Goal>>` interface and its `test()` method are **never called in production** — `GoalReachedEventHandler` has its own `isGoalExpressionSatisfied()` method that works with goal names (`Set<String>`), not goal objects. The handler duplicates evaluation logic via instanceof checks. `getGoals()` IS called at 9 production sites (5 in `GoalReachedEventHandler`, 4 in `DefaultCaseDefinitionRegistry`) — these are migrated to `goalNames()` below.

### Design

Replace the flat type hierarchy with a sealed recursive tree:

```java
public sealed interface GoalExpression
    permits AllOfGoalExpression, AnyOfGoalExpression, SingleGoalExpression {

    boolean isSatisfiedBy(Set<String> reachedGoalNames);

    Set<String> goalNames();

    /**
     * Returns a representative goal name if this expression is satisfied
     * by the given reached goals, null otherwise. Combines satisfaction
     * check and name extraction into a single atomic operation.
     */
    String satisfiedGoalName(Set<String> reachedGoalNames);
}
```

All types in `io.casehub.api.model` (same package as `GoalExpression`).

**Removed:** `extends Predicate<Collection<Goal>>`, `getGoals()`.

#### SingleGoalExpression — leaf node

```java
public record SingleGoalExpression(String goalName) implements GoalExpression {

    @Override
    public boolean isSatisfiedBy(Set<String> reachedGoalNames) {
        return reachedGoalNames.contains(goalName);
    }

    @Override
    public Set<String> goalNames() {
        return Set.of(goalName);
    }

    @Override
    public String satisfiedGoalName(Set<String> reachedGoalNames) {
        return reachedGoalNames.contains(goalName) ? goalName : null;
    }
}
```

#### AllOfGoalExpression — recursive AND

```java
public record AllOfGoalExpression(List<GoalExpression> children) implements GoalExpression {

    public AllOfGoalExpression {
        if (children.isEmpty()) {
            throw new IllegalArgumentException("AllOfGoalExpression requires at least one child");
        }
        children = List.copyOf(children);
    }

    @Override
    public boolean isSatisfiedBy(Set<String> reachedGoalNames) {
        return children.stream().allMatch(c -> c.isSatisfiedBy(reachedGoalNames));
    }

    @Override
    public Set<String> goalNames() {
        return children.stream()
            .flatMap(c -> c.goalNames().stream())
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public String satisfiedGoalName(Set<String> reachedGoalNames) {
        String firstName = null;
        for (GoalExpression child : children) {
            String name = child.satisfiedGoalName(reachedGoalNames);
            if (name == null) return null;
            if (firstName == null) firstName = name;
        }
        return firstName;
    }
}
```

#### AnyOfGoalExpression — recursive OR

```java
public record AnyOfGoalExpression(List<GoalExpression> children) implements GoalExpression {

    public AnyOfGoalExpression {
        if (children.isEmpty()) {
            throw new IllegalArgumentException("AnyOfGoalExpression requires at least one child");
        }
        children = List.copyOf(children);
    }

    @Override
    public boolean isSatisfiedBy(Set<String> reachedGoalNames) {
        return children.stream().anyMatch(c -> c.isSatisfiedBy(reachedGoalNames));
    }

    @Override
    public Set<String> goalNames() {
        return children.stream()
            .flatMap(c -> c.goalNames().stream())
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public String satisfiedGoalName(Set<String> reachedGoalNames) {
        for (GoalExpression child : children) {
            String name = child.satisfiedGoalName(reachedGoalNames);
            if (name != null) return name;
        }
        return null;
    }
}
```

#### Static factories on GoalExpression

```java
// Backward-compatible: tests keep compiling
static GoalExpression allOf(Goal... goals) {
    return new AllOfGoalExpression(
        Arrays.stream(goals).map(g -> new SingleGoalExpression(g.getName())).toList());
}

static GoalExpression allOf(Collection<Goal> goals) {
    return new AllOfGoalExpression(
        goals.stream().map(g -> new SingleGoalExpression(g.getName())).toList());
}

static GoalExpression anyOf(Goal... goals) {
    return new AnyOfGoalExpression(
        Arrays.stream(goals).map(g -> new SingleGoalExpression(g.getName())).toList());
}

// New: composition
static GoalExpression allOf(GoalExpression... children) {
    return new AllOfGoalExpression(List.of(children));
}

static GoalExpression anyOf(GoalExpression... children) {
    return new AnyOfGoalExpression(List.of(children));
}

static GoalExpression goal(String name) {
    return new SingleGoalExpression(name);
}
```

**Overload ambiguity:** `allOf(Goal...)` and `allOf(GoalExpression...)` are distinct because `Goal` does not implement `GoalExpression`. No ambiguity.

### YAML parsing

`parseGoalExpressionFromNode` becomes recursive. An array element can be:
- A string → `SingleGoalExpression(goalMap.get(name).getName())`
- An object with `allOf`/`anyOf` → recursive call

**Parse-time validation** (fixes pre-existing null-safety gap in `goalMap.get(n.asText())`):
1. String elements must resolve to a known goal in `goalMap` — throw `IllegalArgumentException` at parse time if not
2. Object elements must contain exactly one of `allOf`/`anyOf` — throw if neither or both are present
3. Empty arrays are rejected — `allOf: []` and `anyOf: []` have no semantic purpose in case completion

```yaml
# Existing (still works):
completion:
  success:
    allOf: [pr-approved, security-verified]

# New (nested composition):
completion:
  success:
    anyOf:
      - allOf: [pr-approved, security-verified, ci-passing]
      - externally-merged
```

### Handler simplification

`GoalReachedEventHandler.isGoalExpressionSatisfied()` and `findSatisfiedGoalName()` (two private methods, ~30 lines) are replaced by a single call to `satisfiedGoalName()`:

```java
// Before (two-step dance):
if (isGoalExpressionSatisfied(expr, reachedGoals)) {
    String satisfiedGoalName = findSatisfiedGoalName(expr, reachedGoals);
    // ...
}

// After (one call, semantically atomic):
String name = expr.satisfiedGoalName(reachedGoals);
if (name != null) {
    // ...
}
```

### Registry validation update

`DefaultCaseDefinitionRegistry` replaces:
```java
expr.getGoals().forEach(g -> referencedGoals.add(g.getName()));
```
with:
```java
referencedGoals.addAll(expr.goalNames());
```

The kind-mismatch warning that walks `expr.getGoals()` comparing `g.getKind()` to `kindValue` needs a different approach — `goalNames()` returns strings, not `Goal` objects.

**Mechanism:** The registry builds a local `Map<String, Goal>` from `definition.getGoals()` at the top of the validation method:

```java
Map<String, Goal> goalsByName = definition.getGoals().stream()
    .collect(Collectors.toMap(Goal::getName, Function.identity()));
```

Both validation loops use this map:
1. **Unreferenced-goal check:** `referencedGoals.addAll(expr.goalNames())`, then check `definition.getGoals()` against `referencedGoals`
2. **Kind-mismatch check:** iterate `expr.goalNames()`, look up `goalsByName.get(name)`, compare `goal.getKind()` to `kindValue`

No new API surface on `CaseDefinition` or `GoalBasedCompletion` — the map is a local concern of the validation method. Works for both YAML-parsed and programmatically built definitions since `definition.getGoals()` is available in both cases.

### Breaking changes

| What breaks | Why it's fine |
|-------------|---------------|
| `GoalExpression.getGoals()` removed | Replaced by `goalNames()` — 4 call sites in registry, 2 in `YamlSimpleCaseHubBeanTest` |
| `extends Predicate<Collection<Goal>>` removed | Never used in production, only in GoalExpressionTest |
| `AllOfGoalExpression(Collection<Goal>)` constructor changes | Becomes `AllOfGoalExpression(List<GoalExpression>)` — YAML mapper and factories updated |
| `AnyOfGoalExpression(Collection<Goal>)` constructor changes | Same |
| `GoalExpressionTest` rewritten | Tests `isSatisfiedBy(Set<String>)` instead of `test(Collection<Goal>)` |

All changes are engine-internal. Pre-release — no consumer migration cost.

---

## Implementation order

1. **#571 first** — add static factory, update record, update all fire sites and tests
2. **#548 second** — redesign GoalExpression types, update handler, update YAML parser, update registry, rewrite tests

## Out of scope

- Schema-level GoalExpression model (`io.casehub.model.GoalExpression`) — generated by jsonschema2pojo; schema JSON changes deferred to when schema validation matters (#715)
- Cross-repo consumer updates (clinical, aml, life, devtown) — they benefit automatically from #571; #548 composition is opt-in via YAML (#716)
- SLA violation immediate firing when deadline has already passed (`MilestoneActivatedEventHandler:200` TODO) — separate concern from enrichment (#714)
