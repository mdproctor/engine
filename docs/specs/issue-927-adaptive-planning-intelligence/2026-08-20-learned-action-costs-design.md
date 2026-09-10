# Learned Action Costs from CBR Traces — Design Spec

**Issue:** #937
**Date:** 2026-08-20
**Epic:** #927 (Adaptive Planning Intelligence) — Phase C (Intelligence)
**Depends on:** #929 (GOAP as DecompositionStrategy)

---

## Summary

Bridge CBR execution traces to GOAP action costs so the planner naturally steers toward paths that historically succeed. Completes the three-layer cost model: static (declared) → dynamic (context-evaluated) → learned (CBR-adjusted).

## Motivation

GOAP actions have static `cost` fields and optional dynamic `CostFunction` instances evaluated against the world state. Neither reflects actual observed performance. A worker that historically takes 30 seconds and fails 40% of the time has a much higher effective cost than one completing in 2 seconds with 95% success — but the planner treats them identically unless the developer manually tunes costs.

The CBR infrastructure already records everything needed: `ExperiencePlanStep` carries `stepOutcome` (RoutingOutcome enum). `ExperienceAnalyser.workerSuccessRates()` computes per-worker rates weighted by case similarity. The missing piece: feeding this back into GOAP's cost model.

This closes the learning loop: cases execute → traces retained in CBR → costs updated → future plans prefer cheaper/more reliable paths → better cases execute.

## Design

### 1. ExperienceAnalyser.actionCostFactors()

New static method in `ExperienceAnalyser` (engine-api, `io.casehub.api.spi.routing`) alongside the existing `workerSuccessRates()`. Computes per-action reliability multipliers from CBR plan traces.

**Signature:**
```java
public static Map<String, Double> actionCostFactors(
    List<RetrievedExperience> experiences,
    Set<String> actionNames,
    int minSamples,
    double maxCostFactor,
    Map<RoutingOutcome, Double> outcomeWeights)
```

**Algorithm:**
1. For each action in `actionNames`, scan all plan trace steps where `capabilityName` matches the action name.
2. Accumulate similarity-weighted success scores using the same outcome-weighting pattern as `workerSuccessRates()`.
3. Compute `successRate` from accumulated scores (range `[-1.0, 1.0]`, same as `workerSuccessRates()`). Negative scores mean more weighted failures than successes.
4. Convert to cost factor: `costFactor = 1.0 / max(successRate, 1.0 / maxCostFactor)`. When `successRate <= 0` (net-negative or zero), the factor caps at `maxCostFactor` (default 10.0). When `successRate > 0`, the factor is inversely proportional to reliability. At `successRate = 1.0`, factor = 1.0 (no adjustment).
5. If total discrete sample count (number of plan trace steps matching the action, before weighting) is below `minSamples`, exclude it from the result (no adjustment — cold start). The sample count is a separate integer counter from the similarity-weighted score accumulator — it tracks raw occurrences, not evidence mass.
6. Skip steps with `adaptationAction` of `ADDED` or `SUBSTITUTED` (same filtering as `workerSuccessRates()`).

**Returns:** `Map<String, Double>` — action name → cost multiplier. Empty map when no CBR data or all actions below sample threshold.

**Convenience overload:**
```java
public static Map<String, Double> actionCostFactors(
    List<RetrievedExperience> experiences,
    Set<String> actionNames,
    int minSamples)
```
Uses `DEFAULT_OUTCOME_WEIGHTS` and `maxCostFactor = 10.0`.

### 2. GoalDecompositionContext gains experiences

`GoalDecompositionContext` (planning, `io.casehub.engine.planning.decomposition`) gains a 6th field:

```java
public record GoalDecompositionContext(
    JsonNode state,
    int depth,
    List<Capability> availableCapabilities,
    PlanningConstraints planningConstraints,
    CaseDefinition definition,
    List<RetrievedExperience> experiences)
    implements DecompositionContext<JsonNode> {
```

**Backward compatibility:** Existing 3-arg, 4-arg, and 5-arg constructors delegate to the canonical constructor with `List.of()` for experiences.

**Population:** `DefaultGoalDecomposer` injects `Instance<CbrRetrievalService>`. When resolvable and the definition has a `CbrConfig`, it retrieves experiences using the `CaseInstance` already available as a parameter. When unresolvable or no `CbrConfig`, passes `List.of()`.

### 3. CbrConfig gains minCostSamples

`CbrConfig` (engine-api, `io.casehub.api.model.cbr`) gains:

```java
Integer minCostSamples   // nullable, default 5
```

**YAML:**
```yaml
cbr:
  minCostSamples: 5
  # existing fields unchanged
```

**CaseDefinitionYamlMapper:** Parses `minCostSamples` from the `cbr:` block. Null = default 5.

**Builder:** `CbrConfig.Builder.minCostSamples(int)`.

### 4. Cost application — shared enrichment logic

All three GOAP strategies — `GoapDecompositionStrategy`, `GoapPlanningStrategy`, and `AdaptivePlanningStrategy` — share the same cost enrichment pattern. The logic is extracted to a public static utility class to avoid duplication. Public visibility is required because `GoapDecompositionStrategy` is in `io.casehub.engine.planning.decomposition` while the other two are in `io.casehub.engine.planning.control`.

**`GoapCostEnricher`** (planning, `io.casehub.engine.planning.control`):

```java
public static List<GoapAction> enrichWithLearnedCosts(
    List<GoapAction> actions,
    List<RetrievedExperience> experiences,
    int minCostSamples)
```

1. If `experiences` is empty, return `actions` unchanged.
2. Collect action names from the input list.
3. Call `ExperienceAnalyser.actionCostFactors(experiences, actionNames, minCostSamples)`.
4. If the result is empty, return `actions` unchanged.
5. For each action with a learned factor:
   - Create a new `GoapAction` with a wrapped `CostFunction`:
     ```java
     CostFunction learned = state -> {
         double base = action.costFunction() != null
             ? action.costFunction().compute(state) : action.cost();
         return base * factor;
     };
     new GoapAction(action.name(), action.preconditions(), action.effects(),
         action.cost(), action.benefit(), action.softPreconditions(), learned);
     ```
   - Actions without a learned factor are passed through unchanged.

This composition preserves all three layers:
- **Static** (`action.cost()`) — used when no dynamic cost function exists
- **Dynamic** (`action.costFunction().compute(state)`) — used when present, evaluated against current world state
- **Learned** (`× factor`) — multiplied on top of whichever base is active

The final `effectiveCost(state)` method on `GoapAction` then applies `× (1 - benefit)`, completing the chain.

### 5. GoapDecompositionStrategy integration

In `decompose()` and `replan()`, after filtering actions by available capabilities and before calling `planner.plan()`:

```java
int minSamples = extractMinCostSamples(context);
List<RetrievedExperience> experiences = extractExperiences(context);
actions = GoapCostEnricher.enrichWithLearnedCosts(actions, experiences, minSamples);
```

Helper methods:
- `extractExperiences()` — casts to `GoalDecompositionContext`, returns experiences or `List.of()`
- `extractMinCostSamples()` — casts to `GoalDecompositionContext`, reads `definition.getCbrConfig().minCostSamples()`, defaults to 5

### 6. GoapPlanningStrategy integration

In `select()`, after filtering actions and before calling `planner.plan()`:

```java
int minSamples = definition.getCbrConfig() != null
    && definition.getCbrConfig().minCostSamples() != null
    ? definition.getCbrConfig().minCostSamples() : 5;
filteredActions = GoapCostEnricher.enrichWithLearnedCosts(
    filteredActions, context.experiences(), minSamples);
```

No additional plumbing needed — `PlanExecutionContext` already carries experiences populated by `CaseContextChangedEventHandler`.

### 6b. AdaptivePlanningStrategy integration

`AdaptivePlanningStrategy` overrides `select()` entirely — it does NOT inherit from `GoapPlanningStrategy.select()`. It calls `definition.getGoapActions()` directly and passes unenriched actions to its own `planner.plan()`. Without explicit integration, cases using the `adaptive` strategy get zero learned cost benefit.

In `select()`, after filtering actions and before calling `planner.plan()`:

```java
filteredActions = GoapCostEnricher.enrichWithLearnedCosts(
    filteredActions, context.experiences(), minSamples);
```

Same pattern as §6.

### 7. Online updates

No new observer or cache mechanism needed. The existing `CbrCaseRetainObserver` stores plan traces (with `RoutingOutcome` per step) on case terminal state. Future CBR retrievals at decomposition/dispatch time automatically include the latest retained cases. Similarity-weighted retrieval ensures that cases similar to the current one contribute more to cost factors.

## Data Flow

```
Case completes
  └→ CbrCaseRetainObserver stores PlanCbrCase with PlanTrace[]
       (each trace has bindingName, capabilityName, workerName, stepOutcome)

New case starts
  └→ DefaultGoalDecomposer.decompose(instance, definition, context)
       └→ CbrRetrievalService.retrieve(definition, instance)
            └→ Returns List<RetrievedExperience> (similarity-weighted)
       └→ GoalDecompositionContext(state, depth, caps, constraints, def, experiences)
       └→ GoapDecompositionStrategy.decompose(task, context)
            └→ ExperienceAnalyser.actionCostFactors(experiences, actionNames, minSamples)
            └→ GoapCostEnricher.enrichWithLearnedCosts(actions, experiences, minSamples)
            └→ GoapPlanner.plan(worldState, goals, enrichedActions, config)

Context changes (dispatch time)
  └→ CaseContextChangedEventHandler.rules()
       └→ CbrRetrievalService.retrieve(definition, caseInstance)
       └→ PlanExecutionContext(caseId, def, ctx, status, tenant, experiences, ...)
       └→ PlanningStrategyLoopControl.select(planCtx, eligible)
            └→ GoapPlanningStrategy.select(plan, context, eligible)
                 └→ GoapCostEnricher.enrichWithLearnedCosts(actions, ctx.experiences(), minSamples)
                 └→ GoapPlanner.plan(worldState, goals, enrichedActions)
```

## Edge Cases

**Cold start:** No CBR data → `actionCostFactors()` returns empty map → all actions use declared costs. Zero degradation.

**Below sample threshold:** Action with fewer traces than `minCostSamples` → excluded from factors → uses declared cost. Prevents noisy adjustments from sparse data.

**Zero success rate:** Capped by `maxCostFactor` (default 10.0). An action with 0% success gets 10× cost — extremely expensive but not infinite, so the planner can still select it if no alternative exists.

**No CbrConfig:** `CbrRetrievalService.retrieve()` returns empty list when no `CbrConfig` is present → no cost enrichment. Transparent.

**Mixed outcomes:** Similarity-weighted aggregation handles mixed outcomes naturally — a 70% similar case with SUCCESS contributes 0.7 to the success score; a 90% similar case with FAILURE contributes -0.9. Net score reflects weighted evidence.

**Adaptation-excluded steps:** Steps with `adaptationAction` ADDED or SUBSTITUTED are skipped — matching `workerSuccessRates()` semantics. ADDED steps have no historical backing; SUBSTITUTED outcomes belong to the original worker.

## Test Plan

### ExperienceAnalyser.actionCostFactors()

1. **Single action, all success** — factor = 1.0 (no adjustment)
2. **Single action, 50% success** — factor = 2.0
3. **Single action, all failures** — factor capped at maxCostFactor (10.0)
4. **Below min samples** — action excluded from results
5. **Exactly min samples** — action included
6. **Multiple actions with different rates** — independent factors
7. **Similarity weighting** — high-similarity experiences contribute more
8. **ADDED/SUBSTITUTED steps skipped** — adaptation-excluded steps don't count
9. **Empty experiences** — returns empty map
10. **No matching action names** — returns empty map
11. **Clamping boundary** — successRate 0.05 (below 1/maxCostFactor) → factor = maxCostFactor (10.0), not 20.0

### GoapCostEnricher

11. **Empty experiences** — returns actions unchanged
12. **All actions below threshold** — returns actions unchanged
13. **Partial enrichment** — only matching actions get adjusted costs
14. **Wraps existing CostFunction** — dynamic cost × learned factor
15. **Wraps static cost** — static cost × learned factor when no CostFunction
16. **Benefit preserved** — effectiveCost applies (1 - benefit) after learned adjustment

### GoapDecompositionStrategy integration

17. **Decompose with learned costs** — plans prefer lower-cost (higher success rate) actions
18. **Decompose cold start** — no CBR data, uses declared costs, plan unchanged
19. **Replan with learned costs** — replanning also uses learned costs

### GoapPlanningStrategy integration

20. **Select with learned costs** — dispatch prefers lower-cost actions
21. **Select cold start** — no experiences on context, uses declared costs

### AdaptivePlanningStrategy integration

22. **Select with learned costs** — adaptive dispatch prefers lower-cost actions
23. **Select cold start** — no experiences, uses declared costs

### GoalDecompositionContext

22. **Backward-compatible constructors** — 3/4/5-arg constructors set experiences to List.of()
23. **6-arg constructor** — carries experiences through

### DefaultGoalDecomposer

24. **Retrieves experiences when CbrRetrievalService available** — passes to context
25. **Transparent no-op when CbrRetrievalService absent** — empty experiences

### CbrConfig

26. **minCostSamples parsed from YAML** — `cbr: { minCostSamples: 10 }`
27. **minCostSamples defaults to null (→ 5)** — when not specified in YAML

### End-to-end

28. **Learning loop** — retain a case with mixed outcomes, start a new similar case, verify the planner produces a different (better) plan than without learned costs

## Files Changed

| File | Change |
|------|--------|
| `api/.../routing/ExperienceAnalyser.java` | Add `actionCostFactors()` methods |
| `api/.../cbr/CbrConfig.java` | Add `minCostSamples` field |
| `planning/.../decomposition/GoalDecompositionContext.java` | Add `experiences` field, backward-compat constructors |
| `planning/.../decomposition/DefaultGoalDecomposer.java` | Inject `Instance<CbrRetrievalService>`, retrieve and pass experiences |
| `planning/.../control/GoapCostEnricher.java` | **New** — shared cost enrichment utility |
| `planning/.../decomposition/GoapDecompositionStrategy.java` | Call `GoapCostEnricher.enrichWithLearnedCosts()` |
| `planning/.../control/GoapPlanningStrategy.java` | Call `GoapCostEnricher.enrichWithLearnedCosts()` |
| `planning/.../control/AdaptivePlanningStrategy.java` | Call `GoapCostEnricher.enrichWithLearnedCosts()` |
| `api/.../model/CaseDefinitionYamlMapper.java` or equivalent | Parse `minCostSamples` from YAML |
| Tests for all of the above | ~31 test cases |

## Scope Boundary

**In scope:**
- `actionCostFactors()` utility on ExperienceAnalyser
- `GoalDecompositionContext` experiences field
- `DefaultGoalDecomposer` CBR retrieval threading
- `GoapCostEnricher` shared utility
- `GoapDecompositionStrategy` + `GoapPlanningStrategy` + `AdaptivePlanningStrategy` integration
- `CbrConfig.minCostSamples`
- YAML parsing for `minCostSamples`

**Out of scope:**
- Duration-based costs — future, needs PlanTrace schema change in neocortex-memory-api
- `ForwardReplanRevision` — adaptation, not planning cost model
- Annotations module `@Cost` integration — existing feature, orthogonal
- Explore/exploit mechanism for cost recovery — see Known Limitations

## Known Limitations

**Heuristic uses static costs.** `GoapPlanner`'s A* heuristic computes `minCost` from `effectiveCost()` (no-arg), which returns the static cost ignoring the wrapped `CostFunction`. After enrichment, the heuristic underestimates actual costs. This preserves A* admissibility (correct, optimal plans) but may cause the planner to explore more nodes than necessary. The soft penalty for unsatisfied soft preconditions also uses the no-arg cost, meaning violations are relatively cheaper for high-cost-factor actions. Both are acceptable for v1.

**Feedback loop starvation.** If an action historically fails and gets a high cost factor, the planner avoids it. Since it's never selected, no new data accumulates and the high cost persists — even if the underlying issue was fixed (e.g., a buggy worker replaced). Mitigations: `maxCostFactor` (10.0) prevents total starvation — the action is expensive but still selectable when no alternatives exist. CBR `temporalDecayHalfLifeDays` ages out old experiences over time. `minCostSamples` prevents sparse early data from poisoning the model. A full explore/exploit mechanism (epsilon-greedy, UCB) is future work.

**Decomposition vs dispatch data inconsistency.** Decomposition-time and dispatch-time CBR retrievals are independent. Between the two, new cases may be retained, changing the cost landscape. The decomposition plan may be suboptimal by dispatch time. This is by design — dispatch-time GOAP re-evaluates the full action space, and the adaptation infrastructure (#803) handles plan revision when reality diverges.

**CbrConfig blast radius.** Adding `minCostSamples` as a new field to the `CbrConfig` record changes the canonical constructor. All direct construction sites, builder, YAML mapper, and tests need updating.

**replan() experience path.** `GoapDecompositionStrategy.replan()` receives a `DecompositionContext` and applies enrichment via `extractExperiences()` cast. The `replan()` call path from `DefaultPlanAdaptationEvaluator` constructs the context — it must thread experiences through. This path needs explicit verification during implementation.

## CLAUDE.md Updates

Add to the `CbrConfig` section:
- `minCostSamples` field documentation
- Three-layer cost composition description

## References

- `api/src/main/java/io/casehub/api/spi/routing/ExperienceAnalyser.java` — existing per-worker aggregation pattern
- `api/src/main/java/io/casehub/api/spi/routing/ExperiencePlanStep.java` — plan trace step type (no duration)
- `api/src/main/java/io/casehub/engine/plan/goap/GoapAction.java` — current cost model (static + dynamic + benefit)
- `api/src/main/java/io/casehub/engine/plan/goap/CostFunction.java` — dynamic cost interface
- `planning/src/main/java/io/casehub/engine/planning/decomposition/GoapDecompositionStrategy.java` — decomposition consumer
- `planning/src/main/java/io/casehub/engine/planning/control/GoapPlanningStrategy.java` — dispatch consumer
- `../../../planning-core/src/main/java/io/casehub/engine/planning/decomposition/GoalDecompositionContext.java` — decomposition context
- `planning/src/main/java/io/casehub/engine/planning/decomposition/DefaultGoalDecomposer.java` — decomposition entry point
- `runtime/src/main/java/io/casehub/engine/internal/routing/CbrRetrievalService.java` — CBR retrieval
- `runtime/src/main/java/io/casehub/engine/internal/engine/handler/CaseContextChangedEventHandler.java:244` — experiences on PlanExecutionContext
- `api/src/main/java/io/casehub/api/engine/PlanExecutionContext.java` — dispatch context with experiences
- `runtime/src/main/java/io/casehub/engine/internal/memory/CbrCaseRetainObserver.java` — CBR retain on case completion
- `research/2026-08-18-adaptive-planning-intelligence.md` — epic research (Section 3, Issue 10)
- GE-20260704-d6aacc — Quarkus ARC Instance<SuperInterface> discovery limitation
- GE-20260810-b53fd8 — explicit typed Instance required for EngineStrategyResolver
- GE-20260720-6ea915 — CbrCaseRetainObserver fires on CbrConfig presence
