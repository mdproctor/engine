# HumanTask Routing Enrichment via CBR Plan Traces

**Issue:** casehubio/engine#741
**Date:** 2026-07-18
**Status:** Approved
**Convention:** Routing strategy convention (engine#634)

## Problem

The engine's CBR routing pipeline only works for `CapabilityTarget` bindings. When a
`HumanTaskTarget` binding fires, retrieved experiences are ignored — the humanTask
dispatch path threads no historical data to the work repo, and no scoring influences
candidate group/user selection.

Three gaps:

1. **Routing (consumption):** `publishHumanTaskSchedule()` ignores the `experiences`
   parameter available from `publishByTarget()`. `HumanTaskScheduleEvent` has no
   experiences or scores fields.

2. **Scoring (analysis):** `ExperienceAnalyser.workerSuccessRates()` hardcodes
   `capabilityName.equals(step.capabilityName())` as the step filter. HumanTask
   bindings have no `Capability` — the matching key is the binding name.

3. **Retention (storage):** `CbrCaseRetainObserver.buildCapabilityNameMap()` only maps
   `CapabilityTarget` bindings. HumanTask PlanItems are excluded from stored plan traces.

## Design

### SPI — `HumanTaskRoutingStrategy`

New SPI in `api/spi/routing/`, symmetric with `AgentRoutingStrategy`. Pluggable via
`NamedStrategy` + `StrategyResolver`. Follows the routing strategy convention
(engine#634): `select()` method, context/candidates separation, sealed result type.

```java
public interface HumanTaskRoutingStrategy extends NamedStrategy {
    Uni<HumanTaskRoutingResult> select(
        HumanTaskRoutingContext context, HumanTaskCandidates candidates);
}
```

#### `HumanTaskRoutingContext`

Record carrying the routing context — everything the strategy needs for
decision-making, excluding the candidates it chooses from:

```java
public record HumanTaskRoutingContext(
    UUID caseId,
    String bindingName,
    String tenancyId,
    JsonNode caseContext,
    List<RetrievedExperience> experiences) {}
```

- `bindingName` is the matching key (equivalent to `capabilityName` for agents)
- `experiences` are pre-retrieved by `CbrRetrievalService`

#### `HumanTaskCandidates`

Candidates are a separate parameter, matching the convention in
`AgentRoutingStrategy.select(context, candidates)` and
`ImplementationRoutingStrategy.select(context, candidates)`:

```java
public record HumanTaskCandidates(Set<String> groups, Set<String> users) {
  public HumanTaskCandidates {
    groups = groups != null ? Set.copyOf(groups) : Set.of();
    users = users != null ? Set.copyOf(users) : Set.of();
  }
}
```

- Pre-resolved from `CandidateSetSpec` by the handler before strategy dispatch
- Separation allows candidate resolution to evolve independently of strategy interface

#### `HumanTaskRoutingResult`

Sealed interface — matching the convention of `RoutingResult` (Selected | Unresolvable |
Escalated) and `ImplementationSelection` (Selected | RunAll | RunNone):

```java
public sealed interface HumanTaskRoutingResult
    permits HumanTaskRoutingResult.Enriched,
            HumanTaskRoutingResult.Unchanged,
            HumanTaskRoutingResult.Escalated {

  record Enriched(
      Set<String> candidateGroups,
      Set<String> candidateUsers,
      Map<String, Double> candidateScores) implements HumanTaskRoutingResult {
    public Enriched {
      candidateGroups = Set.copyOf(candidateGroups);
      candidateUsers = Set.copyOf(candidateUsers);
      candidateScores = Map.copyOf(candidateScores);
    }
  }

  record Unchanged() implements HumanTaskRoutingResult {}

  record Escalated(String reason) implements HumanTaskRoutingResult {}
}
```

- `Enriched` — strategy modified candidates and/or attached scores
- `Unchanged` — strategy has no opinion; handler uses original candidates
- `Escalated` — strategy flags a problem for human review
- `candidateScores` keys are from `candidateUsers` only — group scoring requires
  group membership resolution which is out of scope (engine#757)
- Experiences are NOT in the result — the handler holds them and passes directly
  to `HumanTaskScheduleEvent`, matching the agent routing path where
  `scheduleWorker()` passes experiences from the handler, not from `RoutingResult`

### Default implementation

`NoOpHumanTaskRoutingStrategy` — `@DefaultBean @ApplicationScoped @Unremovable` in
`runtime/internal/routing/`. Returns `Unchanged`:

```java
@DefaultBean @ApplicationScoped @Unremovable
public class NoOpHumanTaskRoutingStrategy implements HumanTaskRoutingStrategy {
    @Override public String id() { return "default"; }
    @Override public Uni<HumanTaskRoutingResult> select(
        HumanTaskRoutingContext ctx, HumanTaskCandidates candidates) {
      return Uni.createFrom().item(new HumanTaskRoutingResult.Unchanged());
    }
}
```

### `ExperienceAnalyser` generalization

New overload accepting a step filter predicate instead of a capability name string:

```java
public static Map<String, Double> workerSuccessRates(
    List<RetrievedExperience> experiences,
    Set<String> eligibleWorkerIds,
    Predicate<ExperiencePlanStep> stepFilter,
    Map<RoutingOutcome, Double> outcomeWeights)
```

The existing `capabilityName` overload delegates to the new one:

```java
public static Map<String, Double> workerSuccessRates(
    List<RetrievedExperience> experiences,
    Set<String> eligibleWorkerIds,
    String capabilityName,
    Map<RoutingOutcome, Double> outcomeWeights) {
  return workerSuccessRates(experiences, eligibleWorkerIds,
      step -> capabilityName.equals(step.capabilityName()), outcomeWeights);
}
```

For humanTask, callers pass `step -> bindingName.equals(step.bindingName())`.

### Retention — `CbrCaseRetainObserver`

`buildCapabilityNameMap()` renamed to `buildRoutingKeyMap()`. Generalized to include
`HumanTaskTarget` bindings:

```java
private Map<String, String> buildRoutingKeyMap(CaseDefinition definition) {
    Map<String, String> map = new LinkedHashMap<>();
    for (Binding binding : definition.getBindings()) {
        switch (binding.target()) {
            case CapabilityTarget ct -> map.put(binding.getName(), ct.capability().name());
            case HumanTaskTarget ht -> map.put(binding.getName(), null);
            default -> { /* SubCase, Extension — not retained */ }
        }
    }
    return map;
}
```

For HumanTask bindings, `capabilityName` is `null` — there is no capability. The
`PlanTrace` and `ExperiencePlanStep` records drop their `Objects.requireNonNull`
constraint on `capabilityName` to accommodate this:

- `PlanTrace.capabilityName`: nullable (was `Objects.requireNonNull`)
- `ExperiencePlanStep.capabilityName`: nullable (was `Objects.requireNonNull`)
- `AdaptedStep.capabilityName`: nullable (was `Objects.requireNonNull`) — without this,
  `NoOpPlanAdapter.adapt()` NPEs on HumanTask traces, silently falling back to raw
  plan traces and losing adaptation annotations

This avoids semantic pollution — `capabilityName` means "capability name" when present
and "no capability" when null, rather than overloading it with binding names.

The predicate-based `ExperienceAnalyser` overload enables HumanTask matching on
`step.bindingName()` while the existing `capabilityName` overload correctly excludes
steps with null `capabilityName` (since `"x".equals(null)` is false).

### Handler plumbing — `CaseContextChangedEventHandler`

**`publishByTarget()`** — the `HumanTaskTarget` branch passes experiences:

```java
case HumanTaskTarget ht -> publishHumanTaskSchedule(
    caseInstance, caseDefinition, binding, ht, experiences);
```

**`publishHumanTaskSchedule()`** gains `CaseDefinition caseDefinition` and
`List<RetrievedExperience> experiences` parameters. `caseDefinition` is needed to
resolve the strategy ID via `caseDefinition.getHumanTaskRouting()`. Already available
in `publishByTarget()` — thread it through.

After resolving candidate groups/users, calls the strategy:

```
resolve candidateGroups/users from CandidateSetSpec (existing)
    ↓
build HumanTaskRoutingContext(caseId, bindingName, tenancyId, caseContext, experiences)
build HumanTaskCandidates(resolvedGroups, resolvedUsers)
    ↓
strategyResolver.resolve(HumanTaskRoutingStrategy.class,
                          caseDefinition.getHumanTaskRouting())
    ↓
strategy.select(context, candidates)
    ↓
switch on result:
  Enriched  → publish event with enriched candidates, scores, and experiences (from handler)
  Unchanged → publish event with original candidates, empty scores, and experiences (from handler)
  Escalated → log escalation reason, publish event with original candidates, empty scores, and experiences (from handler)
```

The `Escalated` handler logs the escalation reason and falls through to unchanged
candidate dispatch. Unlike agent routing where escalation halts dispatch (the engine
can provision a fallback worker), human task dispatch has no automated fallback —
blocking dispatch would halt the case entirely. Dispatch-on-escalation is the safe
default; a dedicated `HumanTaskRoutingEscalationEvent` and handler can be added when
escalation workflows are designed for human tasks.

The strategy is resolved via `EngineStrategyResolver` — same pattern as agent and
implementation routing.

### `HumanTaskScheduleEvent` changes

Two new fields:

- `List<RetrievedExperience> experiences` — CBR data for downstream consumption
- `Map<String, Double> candidateScores` — per-candidate historical success scores

Constructor migration: `HumanTaskScheduleEvent` is a Java record with 14 fields.
Adding 2 fields changes the canonical constructor. Affected call sites:
- `CaseContextChangedEventHandler.publishHumanTaskSchedule()` (production)
- `HumanTaskTypedContextTest.TypedEventRecorder` (test)
- `HumanTaskTargetDispatchTest.HumanTaskEventRecorder` (test)

Nullability migration: making `ExperiencePlanStep.capabilityName` and
`PlanTrace.capabilityName` nullable affects:
- `ExperiencePlanStepTest.null_capabilityName_throws()` — invert: verify null is accepted
- Production consumers (`ExperienceAnalyser`, `CbrRoutingPromptSection`,
  `PlanCompositionAnalyser`) are null-safe — all use `capabilityName.equals(step.capabilityName())`

### `CaseDefinition` changes

New field `humanTaskRouting` (nullable String, strategy ID) alongside existing
`agentRouting` and `implementationRouting`. Builder method and YAML mapping.

YAML:

```yaml
humanTaskRouting: cbr
```

### `EngineStrategyResolver` update

Add `Instance<HumanTaskRoutingStrategy>` injection and resolution. Same pattern as
the existing `AgentRoutingStrategy` and `ImplementationRoutingStrategy` entries.

## Pipeline position

```
CbrRetrievalService.retrieve()
    ↓
PlanningStrategyLoopControl.select() → eligible bindings
    ↓
publishByTarget() dispatches by BindingTarget type:
    ├── CapabilityTarget → AgentRoutingStrategy.select()              [existing]
    ├── HumanTaskTarget  → HumanTaskRoutingStrategy.select()          [NEW]
    ├── SubCaseTarget    → publishSubCaseSchedule()                   [unchanged]
    └── ExtensionTarget  → warn                                       [unchanged]
```

## Changes by module

| Module | File | Change |
|--------|------|--------|
| `api/spi/routing/` | `HumanTaskRoutingStrategy.java` | New SPI interface |
| `api/spi/routing/` | `HumanTaskRoutingContext.java` | New context record |
| `api/spi/routing/` | `HumanTaskCandidates.java` | New candidates record |
| `api/spi/routing/` | `HumanTaskRoutingResult.java` | New sealed result type |
| `api/spi/routing/` | `ExperienceAnalyser.java` | New predicate overload, existing delegates |
| `api/spi/routing/` | `ExperiencePlanStep.java` | Make `capabilityName` nullable |
| `api/model/` | `CaseDefinition.java` | Add `humanTaskRouting` field + builder |
| `common/internal/event/` | `HumanTaskScheduleEvent.java` | Add `experiences`, `candidateScores` |
| `runtime/internal/routing/` | `NoOpHumanTaskRoutingStrategy.java` | New default impl |
| `runtime/internal/routing/` | `EngineStrategyResolver.java` | Add HumanTaskRoutingStrategy resolution |
| `runtime/internal/engine/handler/` | `CaseContextChangedEventHandler.java` | Thread experiences, call strategy |
| `runtime/internal/memory/` | `CbrCaseRetainObserver.java` | Generalize to include humanTask |
| `neocortex/memory-cbr/` | `PlanTrace.java` | Make `capabilityName` nullable |
| `neocortex/memory-cbr/` | `AdaptedStep.java` | Make `capabilityName` nullable |

## Not in scope

- CBR implementation of `HumanTaskRoutingStrategy` (engine#754)
- Constraint-based implementation (engine#755)
- Work repo changes to consume experiences/scores on `HumanTaskScheduleEvent` (engine#756)
- Group scoring via group membership resolution (engine#757)
- Cross-definition matching (binding names are definition-scoped; domain scoping is sufficient)
