# GOAP as DecompositionStrategy — Design Spec

**Issue:** #929
**Date:** 2026-08-18
**Module:** `casehub-engine-api` (planner enhancements), `casehub-engine-planning` (strategy wiring)

---

## Summary

Wire the existing `GoapPlanner` as a `DecompositionStrategy<JsonNode>` (id=`"goap"`) that produces a full `DagPlan<LeafTask<JsonNode>>` from A* search over the precondition/effect graph. Enhance the planner with backward pruning, forward simulation, ternary world state, dynamic cost computation, iteration safety ceiling, action blacklisting, and admissible heuristic verification.

## Motivation

The `GoapPlanner` currently operates only at dispatch time via `GoapPlanningStrategy` — it selects the next single action per evaluation cycle. It cannot produce a complete upfront plan because it is not connected to the `DecompositionStrategy` SPI. This means:

- When `LlmDecompositionStrategy` is unavailable (no `ChatModelProvider`), there is no decomposition — cases fall back to choreography.
- When the domain is well-specified (all preconditions and effects are declared), the LLM is unnecessary overhead — A* produces the same plan in milliseconds.
- Plan adaptation via `ForwardReplanRevision` has no classical fallback. Note: this issue provides the GOAP decomposition foundation. A GOAP-backed `PlanRevisionStrategy` for the adaptation layer is a separate concern addressed by #935 (Plan repair vs plan optimization separation).

## Architecture

### Module Placement

| Component | Module | Package |
|-----------|--------|---------|
| `GoapPlanner` enhancements | `engine-api` | `io.casehub.engine.plan.goap` |
| `PlannerConfig` | `engine-api` | `io.casehub.engine.plan.goap` |
| `GoapWorldState` ternary | `engine-api` | `io.casehub.engine.plan.goap` |
| `GoapDecompositionStrategy` | `planning` | `io.casehub.engine.planning.decomposition` |

The planner enhancements go in `engine-api` so all consumers benefit (GoapPlanningStrategy, AdaptivePlanningStrategy, and the new GoapDecompositionStrategy). The strategy itself goes in `planning` alongside `LlmDecompositionStrategy`.

### Component Overview

```
CaseDefinition
  ├── goapActions: List<GoapAction>         (existing)
  ├── goalToEffectKeys: Map<String, Set<String>>  (existing)
  └── decompositionStrategy: "goap"         (YAML selection)
                │
                ▼
  GoapDecompositionStrategy.decompose()
    1. Extract capabilities from GoalDecompositionContext
    2. Build GoapActions from bindings + capabilities
    3. Build GoapWorldState from context (ternary)
    4. Resolve goal conditions from definition
    5. Call GoapPlanner.plan(initial, goals, actions, config)
    6. Map planned actions → GoalStep → DagNode → DagPlan
                │
                ▼
  GoapPlanner.plan(initial, goals, actions, config)
    1. Backward prune: remove actions not contributing to goal
    2. A* forward search (with iteration ceiling)
    3. Forward simulate: strip redundant actions
    4. Return action sequence
```

---

## 1. GoapPlanner Enhancements (engine-api)

### 1.1 PlannerConfig

New record controlling planner behavior:

```java
public record PlannerConfig(
    int maxIterations,
    Set<String> blacklistedActions,
    boolean backwardPruning,
    boolean forwardSimulation
) {
  public static final int DEFAULT_MAX_ITERATIONS = 10_000;

  public static PlannerConfig defaults() {
    return new PlannerConfig(DEFAULT_MAX_ITERATIONS, Set.of(), true, true);
  }

  public PlannerConfig {
    blacklistedActions = Set.copyOf(blacklistedActions);
    if (maxIterations < 1) throw new IllegalArgumentException("maxIterations must be >= 1");
  }
}
```

### 1.2 New plan() Overload

```java
public List<GoapAction> plan(
    GoapWorldState initial, Set<String> goalConditions,
    List<GoapAction> actions, PlannerConfig config)
```

The existing 3-arg `plan()` methods delegate to this with `PlannerConfig.defaults()` — backward compatible.

### 1.3 Iteration Safety Ceiling

Inside the A* loop, track iteration count. When `iterations >= config.maxIterations()`, return empty list (no plan found). This prevents runaway planning on large or cyclic action spaces.

### 1.4 Action Blacklisting

Before A* search begins, filter `actions` to exclude any action whose name is in `config.blacklistedActions()`. This operates at the planning layer — complementary to routing-layer `excludedAgents`.

### 1.5 Backward Pruning

Before A* search, work backward from the goal conditions:

1. Start with `relevant = goalConditions`
2. For each action whose effects intersect `relevant`, add that action's preconditions to `relevant`
3. Repeat until no new conditions are added
4. Remove any action whose effects do NOT intersect `relevant`

This is a fixed-point computation over the precondition/effect graph. Shrinks the branching factor for definitions with many bindings, most irrelevant to the current goal.

### 1.6 Forward Simulation

After A* produces a plan, simulate execution forward:

1. Start with `state = initial`
2. For each action in the plan, check if every effect entry `(key, value)` already matches in `state`: `state.get(key) == Condition.fromBoolean(value)`. UNKNOWN does NOT match — if the state has UNKNOWN for a key and the effect sets it to TRUE, the action is not redundant.
3. If ALL effects already match, remove the action (redundant)
4. Otherwise, apply the action's effects to `state`
5. Return the filtered plan

Each redundant action removed saves a real worker execution.

### 1.7 Admissible Heuristic Verification

The existing heuristic `goalConditions.stream().filter(c -> !state.satisfies(c)).count()` counts unsatisfied goals. This is admissible — each action can satisfy at most one goal condition per step, so the count never overestimates the remaining cost (assuming minimum action cost >= 1.0).

With the [0,1] cost constraint removed (D2), an action can have cost < 1.0, which makes the raw count heuristic inadmissible. Fix: scale by the minimum action cost in the action set:

```java
private double heuristic(GoapWorldState state, Set<String> goalConditions, double minCost) {
  return goalConditions.stream().filter(c -> !state.satisfies(c)).count() * minCost;
}
```

`minCost` is computed once before A* begins. Zero-cost actions are excluded to prevent heuristic degeneration (A* → Dijkstra): `actions.stream().mapToDouble(GoapAction::effectiveCost).filter(c -> c > 0).min().orElse(1.0)`. If ALL actions have zero effective cost, the heuristic degenerates gracefully — Dijkstra is correct, just slower.

---

## 2. Ternary World State (engine-api)

### 2.1 Condition Enum

```java
public enum Condition {
  TRUE, FALSE, UNKNOWN;

  public static Condition fromBoolean(boolean value) {
    return value ? TRUE : FALSE;
  }
}
```

### 2.2 GoapWorldState Changes

`GoapWorldState` transitions from `Map<String, Boolean>` to `Map<String, Condition>`:

```java
public record GoapWorldState(Map<String, Condition> conditions) {

  public GoapWorldState {
    conditions = Map.copyOf(conditions);
  }

  public Condition get(String key) {
    return conditions.getOrDefault(key, Condition.UNKNOWN);
  }

  public GoapWorldState with(String key, Condition value) {
    Map<String, Condition> copy = new HashMap<>(conditions);
    copy.put(key, value);
    return new GoapWorldState(copy);
  }

  public GoapWorldState with(String key, boolean value) {
    return with(key, Condition.fromBoolean(value));
  }

  public boolean satisfies(String goalCondition) {
    return get(goalCondition) == Condition.TRUE;
  }

  public boolean satisfiesAll(Set<String> goalConditions) {
    return goalConditions.stream().allMatch(this::satisfies);
  }
}
```

Both `with(String, Condition)` and `with(String, boolean)` are provided. `GoapAction.applyTo()` continues to call `with(key, booleanValue)` — no change needed since the boolean overload delegates to the Condition overload.

### 2.3 GoapAction Precondition and Soft Precondition Checking (Optimistic)

`isApplicable()` changes to treat UNKNOWN as satisfying (hard preconditions):

```java
public boolean isApplicable(GoapWorldState state) {
  return preconditions.entrySet().stream().allMatch(e -> {
    Condition c = state.get(e.getKey());
    if (c == Condition.UNKNOWN) return true; // optimistic
    return (c == Condition.TRUE) == e.getValue();
  });
}
```

`GoapPlanner.softPenalty()` must also handle ternary state. UNKNOWN soft preconditions incur the penalty (pessimistic for soft — we want to prefer actions where soft conditions are known-satisfied):

```java
private double softPenalty(GoapAction action, GoapWorldState state) {
  long unsatisfied = action.softPreconditions().entrySet().stream()
      .filter(e -> {
        Condition c = state.get(e.getKey());
        if (c == Condition.UNKNOWN) return true; // UNKNOWN = unsatisfied for soft
        return (c == Condition.TRUE) != e.getValue();
      })
      .count();
  if (unsatisfied == 0) return 0.0;
  return Math.max(0.5 * action.effectiveCost(), 0.1);
}
```

Rationale: hard preconditions are optimistic (plan proceeds despite uncertainty), soft preconditions are pessimistic (prefer known-good paths when uncertain).

### 2.4 Backward Compatibility and Closed-World vs Open-World

Two factory methods serve different semantic needs:

**Closed-world factory** (for existing dispatch-time callers):
```java
public static GoapWorldState closedWorld(Map<String, Boolean> known) {
  Map<String, Condition> conditions = new HashMap<>();
  known.forEach((k, v) -> conditions.put(k, Condition.fromBoolean(v)));
  return new GoapWorldState(conditions);
}
```
Absent keys default to UNKNOWN via `get()`, but in a closed-world context (dispatch-time), callers explicitly set all known conditions. `GoapPlanningStrategy.buildWorldState()` calls this factory and sets present working-layer keys to TRUE. Keys not in the working layer remain UNKNOWN — but since dispatch-time preconditions reference keys that ARE in the working layer (set by prior worker outputs), behavior is unchanged.

**Open-world factory** (for decomposition-time planning):
```java
public static GoapWorldState openWorld(JsonNode workingLayer) {
  Map<String, Condition> conditions = new HashMap<>();
  workingLayer.fieldNames().forEachRemaining(key -> {
    JsonNode value = workingLayer.get(key);
    if (!value.isNull()) {
      conditions.put(key, Condition.TRUE);
    }
  });
  return new GoapWorldState(conditions);
}
```
Decomposition happens before workers have run, so many conditions are genuinely unknown. The optimistic semantics (D3) allow planning under this partial observability.

**Semantic preservation for existing callers (R1-04):** `GoapPlanningStrategy.buildWorldState()` currently maps working-layer keys to TRUE. Absent keys returned FALSE (via `Boolean.TRUE.equals(conditions.get(key))` on a missing key). After the ternary change, absent keys return UNKNOWN. With optimistic semantics, UNKNOWN satisfies preconditions — changing existing behavior.

Fix: `GoapPlanningStrategy.buildWorldState()` must remain closed-world. It already only sets TRUE for present keys. The existing `GoapAction` preconditions reference keys that are expected to be in the working layer (set by prior worker outputs). If a precondition references a key that was never set, the old behavior (FALSE → not applicable) was correct. The new behavior (UNKNOWN → applicable, optimistic) would incorrectly schedule the action.

Resolution: `GoapPlanningStrategy.buildWorldState()` uses `closedWorld()` and additionally sets all precondition keys from the definition's GOAP actions to FALSE when they are not in the working layer:

```java
protected GoapWorldState buildWorldState(PlanExecutionContext context) {
  Map<String, Boolean> known = new HashMap<>();
  // ... existing: set working layer keys to true ...
  // Ensure precondition keys not in working layer are explicitly FALSE
  for (GoapAction action : context.definition().getGoapActions()) {
    for (String key : action.preconditions().keySet()) {
      known.putIfAbsent(key, false);
    }
  }
  return GoapWorldState.closedWorld(known);
}
```

This preserves existing semantics exactly: absent precondition keys are FALSE (not UNKNOWN).

---

## 3. Dynamic Cost Computation (engine-api)

### 3.1 CostFunction

```java
@FunctionalInterface
public interface CostFunction {
  double compute(GoapWorldState currentState);
}
```

### 3.2 GoapAction Enhancement

`GoapAction` gains an optional `CostFunction`:

```java
public record GoapAction(
    String name,
    Map<String, Boolean> preconditions,
    Map<String, Boolean> effects,
    double cost,
    double benefit,
    Map<String, Boolean> softPreconditions,
    CostFunction costFunction   // nullable
) {
  public double effectiveCost() {
    return cost * (1.0 - benefit);
  }

  public double effectiveCost(GoapWorldState state) {
    if (costFunction != null) {
      return costFunction.compute(state) * (1.0 - benefit);
    }
    return effectiveCost();
  }
}
```

Existing constructors pass `null` for `costFunction` — backward compatible.

### 3.3 GoapPlanner Integration

The A* loop calls `action.effectiveCost(current.state())` instead of `action.effectiveCost()` when the config-bearing overload is used.

### 3.4 Cost Constraint Relaxation (D2)

Remove the `[0.0, 1.0]` validation on `cost`. New constraint: `cost >= 0.0`. The `benefit` field stays `[0.0, 1.0]` — it's a fractional multiplier.

---

## 4. GoapDecompositionStrategy (planning module)

### 4.1 Class Definition

```java
@ApplicationScoped
public class GoapDecompositionStrategy implements DecompositionStrategy<JsonNode> {

  @Override
  public String id() { return "goap"; }

  @Override
  public DagPlan<LeafTask<JsonNode>> decompose(
      TaskNode<JsonNode> task, DecompositionContext<JsonNode> context) { ... }

  @Override
  public DagPlan<LeafTask<JsonNode>> replan(
      TaskNode<JsonNode> task, DecompositionContext<JsonNode> context,
      ReplanContext<JsonNode> replanContext) { ... }
}
```

### 4.2 decompose() Flow

1. **Extract context:** Cast to `GoalDecompositionContext` for `availableCapabilities` and `planningConstraints`.
2. **Build GoapActions:** Use `CaseDefinition.getGoapActions()` filtered to actions matching available capability names. If empty, return empty plan.
3. **Build world state:** From `context.state()` (JsonNode working layer). Top-level keys with non-null values → `Condition.TRUE`. Absent keys → `Condition.UNKNOWN`.
4. **Resolve goals:** From `CaseDefinition.getGoalToEffectKeys()` — flatten all effect key sets into goal conditions.
5. **Configure planner:** `PlannerConfig.defaults()` with `backwardPruning=true`, `forwardSimulation=true`.
6. **Plan:** Call `planner.plan(worldState, goals, actions, config)`.
7. **Map to DagPlan:** Each `GoapAction` in the plan → `GoalStep(UUID, description=action.name(), capabilityName=action.name(), now())` → `DagNode`. Dependencies are derived from the precondition/effect graph: a node depends on the earliest prior node whose effects satisfy one of its preconditions. This encodes real causal dependencies, not arbitrary sequential ordering. `DefaultGoalDecomposer.isLinearChain()` currently rejects non-linear plans (v1 constraint), so parallel branches are not activated yet — but when the linear-chain restriction is lifted, GOAP plans automatically benefit from parallelism without re-work. → `DagPlan.fromNodes()`.

### 4.3 replan() Flow

1. Filter out completed step actions from the action list.
2. Resolve the failed action name: look up `replanContext.failedStep().stepId()` in `replanContext.originalPlan().nodes()` to find the corresponding `DagNode`, cast its task to `GoalStep`, and read `GoalStep.capabilityName()`. The stepId is a DagNode id (not a GoapAction name) — the original plan is the mapping source.
3. Build blacklist from the resolved action name.
4. Call `plan()` with `config.blacklistedActions(Set.of(failedActionName))`.
5. Return new `DagPlan`.

Note: this provides decomposition-time replanning (called by `DefaultGoalDecomposer`). A GOAP-backed `PlanRevisionStrategy` for the adaptation layer is a separate concern addressed by #935.

### 4.4 EngineStrategyResolver Registration

`EngineStrategyResolver` discovers `GoapDecompositionStrategy` via `@Any Instance<DecompositionStrategy>` — already wired for `LlmDecompositionStrategy`. No resolver change needed.

### 4.5 YAML Activation

```yaml
spec:
  decompositionStrategy: goap
```

### 4.6 Accessing CaseDefinition

`GoapDecompositionStrategy` needs `CaseDefinition` for `getGoapActions()` and `getGoalToEffectKeys()`. The `DecompositionContext` does not carry the definition. Options:

The `DefaultGoalDecomposer` (the caller) already has the definition and passes capabilities through `GoalDecompositionContext`. The strategy can resolve GOAP actions from the capabilities list — each `Capability` has a `name()` that matches a `GoapAction.name()`. However, the actual `GoapAction` objects (with preconditions, effects, costs) live on `CaseDefinition`.

**Solution:** Add `CaseDefinition` to `GoalDecompositionContext` as an optional field. The decomposition context already carries capabilities (from the definition) and constraints (from the definition). Adding the definition itself is consistent — it's the source of all planning metadata.

```java
public record GoalDecompositionContext(
    JsonNode state, int depth,
    List<Capability> availableCapabilities,
    PlanningConstraints planningConstraints,
    CaseDefinition definition   // nullable, new
) implements DecompositionContext<JsonNode> { ... }
```

`DefaultGoalDecomposer` already has the definition — it passes it through. `LlmDecompositionStrategy` ignores the new field. Backward-compatible constructor passes `null`.

---

## 5. Testing Strategy

### 5.1 GoapPlanner Unit Tests (engine-api)

- **Backward pruning:** verify irrelevant actions are removed before search
- **Forward simulation:** verify redundant actions are stripped from the result
- **Iteration ceiling:** verify empty plan returned when ceiling reached
- **Action blacklisting:** verify blacklisted actions are excluded
- **Heuristic admissibility:** verify h(n) <= actual cost for various states
- **Ternary world state:** verify UNKNOWN satisfies preconditions (optimistic)
- **Dynamic cost:** verify context-dependent costs affect plan selection

### 5.2 GoapDecompositionStrategy Unit Tests (planning)

- **Happy path:** decompose a 3-action linear chain, verify DagPlan structure
- **No actions:** empty plan when no GOAP actions on definition
- **Goal already satisfied:** empty plan when world state already meets goals
- **Replan:** verify failed action is blacklisted, new plan produced
- **Capability filtering:** only actions matching available capabilities are used

### 5.3 Integration Tests (planning)

- **End-to-end:** `CaseDefinition` with `decompositionStrategy: goap`, verify `DefaultGoalDecomposer` wires through correctly
- **Strategy resolution:** `EngineStrategyResolver` resolves `"goap"` to `GoapDecompositionStrategy`

---

## 6. Scope Boundaries

**In scope:**
- All enhancements to `GoapPlanner` (pruning, simulation, ceiling, blacklisting, heuristic)
- Ternary `GoapWorldState` with optimistic semantics
- Dynamic cost computation via `CostFunction` on `GoapAction`
- Cost constraint relaxation (>= 0.0, no upper bound)
- `GoapDecompositionStrategy` wiring
- Replan support via action blacklisting

**Out of scope:**
- `@Cost` annotation in the annotations module (#939 — Annotations module: @Cost and enhanced GOAP support)
- JQ-based cost expressions in YAML — `CostFunction` is the programmatic extension point; JQ expressions are a YAML convenience that can be wired through `CaseDefinitionYamlMapper` after the core is in place
- CBR-learned cost integration (#937)
- Portfolio decomposition strategy (#933)
- Lazy evaluation for ternary UNKNOWN (generate plans for both values, evaluate at runtime if plans differ) — the research doc mentioned this as an Embabel pattern; we implement the simpler optimistic semantics. Full lazy evaluation is architecturally aligned with #938 (Contingent planning branches)
- GOAP-backed `PlanRevisionStrategy` for the adaptation layer (#935)
- Changes to `DefaultGoalDecomposer` beyond passing `CaseDefinition` through context

---

## References

- `api/src/main/java/io/casehub/engine/plan/goap/GoapPlanner.java` — existing A* planner
- `api/src/main/java/io/casehub/engine/plan/goap/GoapAction.java` — action record with [0,1] cost constraint
- `api/src/main/java/io/casehub/engine/plan/goap/GoapWorldState.java` — boolean world state
- `api/src/main/java/io/casehub/engine/plan/DecompositionStrategy.java` — SPI interface
- `../../../planning-core/src/main/java/io/casehub/engine/planning/decomposition/LlmDecompositionStrategy.java` — reference implementation
- `../../../planning-core/src/main/java/io/casehub/engine/planning/decomposition/GoalStep.java` — reusable LeafTask type
- `../../../planning-core/src/main/java/io/casehub/engine/planning/decomposition/GoalDecompositionContext.java` — decomposition context
- `../../../planning-core/src/main/java/io/casehub/engine/planning/control/GoapPlanningStrategy.java` — dispatch-time consumer
- `../../../planning-core/src/main/java/io/casehub/engine/planning/control/AdaptivePlanningStrategy.java` — adaptive consumer
- `research/2026-08-18-adaptive-planning-intelligence.md` §2, §6 — GOAP design rationale and Embabel comparison
- Design review: `/Users/mdproctor/reviews/casehub-slots/929-goap-decomposition-20260818-044228/` — 11 issues addressed
