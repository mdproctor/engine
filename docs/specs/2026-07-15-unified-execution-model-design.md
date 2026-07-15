# Unified Execution Model — engine-planning

## The Model

Two primitives, one graph, one runtime.

### PlanItem — the universal execution unit

```
PlanItem (sealed)
  ├─ Primitive     — dispatches a worker
  └─ Compound      — contains children + strategy + completion semantics
```

A compound PlanItem is a container of workers. It has children, a dispatch strategy, completion semantics, entry conditions, and repeat capability. What CMMN called a "Stage" is one configuration of a compound PlanItem. HTN phases, parallel groups, sequential pipelines, voting rounds — all compound PlanItems with different configuration.

### Two dispatch modes — the only two archetypes

Every PlanItem dispatch has exactly two dimensions:

| Dimension | Name | Meaning | Mechanism |
|---|---|---|---|
| **trigger** | Choreography | "do this when" | Condition on context/time/event |
| **strategy** | Orchestration | "do this now" | Selected by containing compound |

Both can be present (hybrid), either can be absent (pure orchestration or pure choreography). No third axis exists — time-based, event-based, priority-based, goal-driven all reduce to one or a composition of both.

### CaseInstance — the execution context

The PlanItem graph is the PLAN (what needs to happen). The CaseInstance is the RUNTIME (how it executes). One CaseInstance provides context, EventLog, lifecycle, tenancy. The PlanItem graph can be arbitrarily deep within a single CaseInstance. SubCases create separate CaseInstances only when true isolation is needed (independent context, independent lifecycle).

### Composable strategies — peers, not alternatives

All planning strategies are `NamedStrategy` implementations resolved per-compound-node at runtime:

| Strategy | Dispatch mode | What it does |
|---|---|---|
| choreography | "when" | Fire all children whose triggers are met |
| sequential | "now" | Fire children one at a time, in order |
| htn | "now" + decomposition | Decompose compound task, then delegate to child strategies |
| dag | "now" + dependencies | Fire children in topological order as dependencies are met |

Strategies compose by nesting. A compound PlanItem's strategy can create child compound PlanItems with their own strategies. No compile-time alternatives. No global `@Alternative @Priority`. Per-node, per-case, runtime resolution.

A strategy can delegate to any other strategy by name via `StrategyResolver`.

## Structural changes

### Rename: blackboard → engine-planning

The module is no longer an optional CMMN-inspired alternative. It's the engine's core planning infrastructure — always active, hosting all execution models. The name should describe what it does.

- Module: `casehub-engine-planning`
- Package: `io.casehub.engine.planning`
- Subpackages: `control`, `plan`, `handler`, `registry`, `decomposition`

### Retire: ChoreographyLoopControl

`PlanningStrategyLoopControl` becomes the only `LoopControl`. `DefaultPlanningStrategy` provides choreography behavior. No `@Alternative`. No compile-time choice.

### Retire: Stage as a distinct type

Stage becomes a compound PlanItem. CMMN `stages:` in YAML maps to compound PlanItems with entry conditions and autocomplete. Internally, the same type.

### PlanItem evolution

`PlanItem` gains:
- `children` — contained PlanItems (for compound nodes)
- `planningStrategy` — how to dispatch children (for compound nodes)
- `completionSemantics` — when this node is "done" (all, M-of-N, first-wins)
- `entryCondition` — when to activate (replaces Stage entry conditions)
- `repeatable` — iteration support (replaces Stage repeat)

Primitive PlanItems have none of these — they dispatch a worker and track status. The sealed hierarchy enforces this.

### Dispatch per compound node

`PlanningStrategyLoopControl` changes from one-strategy-for-all to per-compound-node resolution:

```
eligible bindings
  → group by containing compound PlanItem
  → each compound's strategy selects from its children
  → free-floating bindings use case-level strategy
```

## The planning vs technique line

**Planning** (engine) — produces task structures. PlanItem graphs. "What to do and in what order."

**Techniques** (blocks) — produces answers by coordinating agents. "How to solve this specific task."

| Engine (planning strategies) | Blocks (problem-solving techniques) |
|---|---|
| Choreography — fire eligible | Supervisor — delegate and review |
| Sequential — one at a time | Debate — adversarial argument |
| HTN — decompose then dispatch | Voting — majority consensus |
| DAG — topological dispatch | Loop — iterative refinement |

Planning happens BEFORE dispatch (structuring work). Techniques happen DURING worker execution (solving problems). Blocks' techniques are consumers of the planning layer.

## Redesign requirements

### TaskNode uses ExecutorRef (not AgentRef)

`AgentRef` stays sealed in blocks, gains `extends ExecutorRef`:
```java
// blocks — sealed, blocks controls variants
sealed interface AgentRef extends ExecutorRef
    permits WorkerAgent, ChannelAgent, HumanAgent, ExternalAgent, ComposedAgent
```

Engine's `TaskNode.LeafTask` uses `ExecutorRef executor()`. Blocks creates `LeafTask` passing `AgentRef` transparently. Engine sees `ExecutorRef`. No unsealing.

### DecompositionContext uses ExecutorRef

```java
// engine-api
record DecompositionContext<T>(T state, List<? extends ExecutorRef> executors, int depth) {}
```

Blocks wraps with richer context in blocks-specific decomposition implementations.

### Compound tasks are compound PlanItems, not CaseInstances

Decomposition produces compound PlanItems within the same CaseInstance. No sub-case overhead. The compound PlanItem's strategy handles dispatch. Sub-cases reserved for true execution isolation.

### DagDriver stays standalone

The blackboard (now planning) dispatch path does not use `DagDriver`. Compound PlanItems dispatch children via their strategy. `DagDriver` remains in engine-common for standalone use (WorkerRuntime Tier 1, non-planning execution).

### sequentialMerge on DagPlan is a prerequisite

`StaticDecomposition` needs `sequentialMerge` as its primary composition mechanism. Must be designed and added to `DagPlan` before any migration.

### HtnBuilder stays in blocks

It's a blocks pattern builder, not planning infrastructure. Engine gets `DecompositionStrategy` SPI and an HTN-aware planning strategy. Blocks keeps the builder.

## What moves where

### Promote to engine-api

| Type | Why | Redesign |
|---|---|---|
| `TaskNode<T>` (LeafTask, CompoundTask) | Plan graph node types | `ExecutorRef` instead of `AgentRef` |
| `DecompositionStrategy<T>` | HTN SPI — how compound tasks decompose | Return type → `DagPlan<LeafTask<T>>` |
| `DecompositionMethod<T>` | Method selection for decomposition | Minimal |
| `DecompositionContext<T>` | Context for decomposition | `ExecutorRef` list instead of `RoutingCandidate` |
| `NoMethodMatchedException` | Thrown by `StaticDecomposition` | None |

### Promote to engine-planning

| Type | Why |
|---|---|
| `StaticDecomposition` | Predefined methods, pure logic, `@DefaultBean` |
| `IdentityDecomposition` | Leaf task passthrough |
| HTN-aware `PlanningStrategy` | Calls `DecompositionStrategy`, creates compound PlanItems |

### Stay in blocks permanently

| Type | Why |
|---|---|
| All pattern builders (Supervisor, Debate, Voting, Loop, etc.) | Problem-solving techniques, not planning |
| `HtnBuilder` | Blocks pattern builder using five-phase loop |
| `AbstractExecutionDriver`, `OrchestratedDriver`, `ChoreographedDriver` | Blocks' execution model |
| `LlmDecomposition`, `HybridDecomposition` | LLM-powered (implements engine-api SPI) |
| `AgentRef` (sealed, extends `ExecutorRef`) | Blocks agent identity |
| Five-phase loop SPIs (Aggregation, Termination, Activation) | Part of blocks' execution model |
| `OrchestrationRoutingStrategy<T>` | Task-level routing |

### Retire

| Type | Replaced by |
|---|---|
| `ChoreographyLoopControl` | `PlanningStrategyLoopControl` as the only `LoopControl` |
| `Stage` (as distinct type) | Compound PlanItem |
| `ExecutionPlan<T>` (blocks) | `DagPlan<T>` (engine-common) |
| `ExecutionNode<T>` (blocks) | `DagNode<T>` (engine-common) |

## Phased migration

### Phase 0: Prerequisites
- `sequentialMerge()` on `DagPlan`
- `AgentRef extends ExecutorRef` in blocks
- Verify engine-common transitively available to blocks

### Phase 1: Retire ChoreographyLoopControl
- `PlanningStrategyLoopControl` becomes the only `LoopControl`, moves from blackboard to runtime
- `DefaultPlanningStrategy` provides choreography behavior
- All existing tests pass unchanged

### Phase 2: Rename blackboard → engine-planning
- Module: `casehub-engine-planning`
- Package: `io.casehub.engine.planning`
- Coordinate with trebleel
- All consumer repos update imports

### Phase 3: PlanItem sealed hierarchy
- `PlanItem` becomes sealed: `Primitive | Compound`
- Stage migrates to compound PlanItem (YAML `stages:` maps to compound PlanItems)
- Per-compound strategy resolution in `PlanningStrategyLoopControl`
- Completion semantics, entry conditions, repeat on compound PlanItem

### Phase 4: DAG plan unification (blocks)
- `ExecutionPlan<T>` → `DagPlan<T>` throughout blocks
- Retire `ExecutionPlan`, `ExecutionNode`, `ExecutionPlan.JoinType`

### Phase 5: HTN decomposition SPI
- Promote `TaskNode`, `DecompositionStrategy`, `DecompositionMethod`, `DecompositionContext` to engine-api
- Promote `StaticDecomposition`, `IdentityDecomposition` to engine-planning
- HTN-aware planning strategy in engine-planning
- blocks' `LlmDecomposition` implements the SPI

### Phase 6: Composable strategy wiring
- Strategies access `StrategyResolver` for sibling delegation
- Per-subtask strategy overrides
- Integration test: mixed strategies in one case

## Design invariants

1. **PlanItem is the graph.** Primitive nodes dispatch workers. Compound nodes dispatch according to their strategy.
2. **CaseInstance is the runtime.** Context, EventLog, lifecycle. The graph executes within it.
3. **Two dispatch modes.** Orchestrated ("now") and choreographed ("when"). No third axis.
4. **Strategies are peers.** Same SPI, same resolution, composable by nesting. No special treatment.
5. **Planning produces structure. Techniques produce answers.** Engine owns planning. Blocks owns techniques.
6. **SubCases are for isolation, not scoping.** Compound PlanItems scope within a case. SubCases create new execution contexts.
