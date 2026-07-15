# Unified Execution Model

## 1. Current State

Execution infrastructure is spread across engine and blocks with overlapping concerns, inconsistent abstraction levels, and compile-time decisions that should be runtime choices.

### 1.1 Engine's execution infrastructure

| Component | Module | What it does | Abstraction level |
|---|---|---|---|
| `ChoreographyLoopControl` | runtime | Fire all eligible bindings concurrently | Case — global default |
| `PlanningStrategyLoopControl` | blackboard (`@Alternative @Priority(10)`) | Stage gating + strategy delegation | Case — replaces choreography when present |
| `DefaultPlanningStrategy` | blackboard | Fire all eligible (choreography within blackboard) | Binding selection |
| `SequentialPlanningStrategy` | blackboard | Fire one binding at a time | Binding selection |
| `Stage` | blackboard | Container of bindings with entry conditions and autocomplete | Scope boundary |
| `PlanItem` | blackboard | Unit of work: binding name, status, executor | Work tracking |
| `CasePlanModel` | blackboard | Per-case plan state: plan items, stages, milestones | Plan state |
| `DagPlan<T>`, `DagNode<T>`, `JoinType` | engine-common | DAG plan construction and validation | Generic infrastructure |
| `DagDriver<T,R>` | engine-common | Synchronous topological task dispatch | Generic infrastructure |
| `DefaultWorkOrchestrator` | runtime | Submit work to Quartz backends | Dispatch |
| `CaseInstance` | common | Case lifecycle, context, EventLog | Execution context |

### 1.2 Blocks' execution infrastructure

| Component | Package | What it does | Abstraction level |
|---|---|---|---|
| `ExecutionPlan<T>` | `agentic/plan` | DAG plan construction and validation | Generic infrastructure |
| `ExecutionNode<T>` | `agentic/plan` | DAG node (id, task, dependsOn, joinType) | Generic infrastructure |
| `AbstractExecutionDriver` | `agentic/model` | Five-phase loop: route → activate → dispatch → aggregate → terminate | Task-level execution |
| `OrchestratedDriver` | `agentic/model` | Orchestrated variant of five-phase loop | Task-level execution |
| `ChoreographedDriver` | `agentic/model` | Choreographed variant of five-phase loop | Task-level execution |
| `TaskNode<T>` | `agentic/decomposition` | Sealed hierarchy: LeafTask / CompoundTask | Task model |
| `DecompositionStrategy<T>` | `agentic/decomposition` | HTN: compound tasks → subtask tree | Planning |
| `RoutingStrategy<T>` | `agentic/routing` | Which agent handles this task in a pattern? | Task-level routing |
| `AggregationStrategy<T>` | `agentic/aggregation` | Combine results from multiple agents | Result composition |
| `TerminationCondition<T>` | `agentic/termination` | When is the pattern done? | Completion |
| `ActivationRule<T>` | `agentic/activation` | Should this agent fire? | Gating |
| Pattern builders (8) | `agentic/pattern` | Supervisor, Debate, Voting, Loop, Parallel, Sequence, Conditional, HTN | Problem-solving patterns |
| `AgentRef` (sealed, 5 variants) | `agentic` | Agent identity: Worker, Channel, Human, External, Composed | Identity |

## 2. Tensions

### T1: Global execution model — compile-time binary

```
ChoreographyLoopControl (runtime, always active)
         ↕ @Alternative @Priority(10)
PlanningStrategyLoopControl (blackboard, replaces choreography when on classpath)
```

Every case in a deployment gets the same execution model. A deployment that includes `casehub-engine-blackboard` uses planning strategies for ALL cases. A deployment without it uses choreography for ALL cases. There is no per-case choice.

**Consequence:** A case that needs sequential planning forces ALL cases into the blackboard module, even those that only need simple choreography.

### T2: DAG infrastructure duplication

| Engine | Blocks | Structural difference |
|---|---|---|
| `DagPlan<T>` | `ExecutionPlan<T>` | None — identical validation, topo-sort, cycle detection |
| `DagNode<T>` | `ExecutionNode<T>` | None — same fields (id, task, dependsOn, joinType) |
| `JoinType` (enum) | `ExecutionPlan.JoinType` (enum) | None — ALL_OF / ANY_OF |

Two implementations of the same DAG. Bugs fixed in one are not fixed in the other. Consumers must choose which to use with no guidance.

### T3: Stage is hardcoded choreography

A Stage fires all contained bindings when active. There is no per-stage strategy. You cannot orchestrate within a stage — all contained workers dispatch concurrently.

| What you can do | What you cannot do |
|---|---|
| All workers in a stage fire concurrently | Sequence workers within a stage |
| Stage autocompletes when all items terminal | Choose M-of-N completion within a stage |
| Stage has entry conditions | Compose strategies within a stage |

**Consequence:** Any orchestration (sequential, HTN, DAG-ordered) must happen at the case level, not within a stage. Stages are purely choreographed containers.

### T4: PlanItem is flat — no compound tasks

`PlanItem` has no parent-child relationship. There is no concept of a compound task that decomposes into subtasks. The only grouping mechanism is Stage, which is a separate type from PlanItem.

| PlanItem has | PlanItem lacks |
|---|---|
| Binding name, status, executor | Children |
| Owning stage (via stage registration) | Decomposition strategy |
| Completion tracking | Completion semantics (all, M-of-N, first-wins) |
| CAS-based status transitions | Hierarchical structure |

**Consequence:** HTN decomposition has nowhere to put compound tasks. Decomposition would need to either (a) create sub-cases (heavyweight — full CaseInstance per phase) or (b) abuse stages as compound task containers.

### T5: Routing at two tiers — parallel but unconnected

| Engine | Blocks | Scope |
|---|---|---|
| `AgentRoutingStrategy` | — | Case-scoped: which worker handles this capability? |
| — | `RoutingStrategy<T>` | Pattern-scoped: which agent handles this task in this pattern? |

These answer different questions at different tiers but share the same concept (select an executor for a task). They don't compose — there's no way for a pattern-scoped routing decision to delegate to engine's case-scoped routing.

### T6: Planning and techniques interleaved in blocks

Blocks' `agentic` package mixes two fundamentally different concerns:

| Concern | Examples | What it produces |
|---|---|---|
| **Planning** | `DecompositionStrategy`, `ExecutionPlan`, `TaskNode`, `HtnBuilder` | Task structures — what to do and in what order |
| **Techniques** | `SupervisorBuilder`, `DebateBuilder`, `VotingBuilder` | Answers — by coordinating agents to solve a problem |

These are interleaved in the same package hierarchy with no clear separation. `HtnBuilder` (planning) extends the same `AbstractPatternBuilder` as `DebateBuilder` (technique), making them look like peers when they operate at different abstraction levels.

### T7: CMMN terminology limits the model

"Stage" implies CMMN semantics — entry sentries, exit sentries, autocomplete, milestones. The general concept is broader: a container of workers with a dispatch strategy and completion semantics. By naming the concept "Stage," the engine inherits CMMN's assumptions and constraints.

| CMMN Stage assumption | General concept |
|---|---|
| Choreographed dispatch (all fire when active) | Any dispatch strategy |
| Autocomplete when all required items terminal | Configurable completion (all, M-of-N, first-wins) |
| Entry/exit sentries | Entry conditions (sentries are one form) |
| Part of a CMMN case model | Part of any plan graph |

### T8: Dispatch modes are implicit

Choreography (trigger-based, "do this when") and orchestration (strategy-based, "do this now") are the two fundamental dispatch archetypes. But they're not named or first-class in the model — they emerge from how `ContextChangeTrigger` interacts with `PlanningStrategy.select()`.

| Archetype | Current mechanism | Named? | First-class? |
|---|---|---|---|
| Choreography | `ContextChangeTrigger.filter` on binding | No | No — it's a binding property |
| Orchestration | `PlanningStrategy.select()` return value | No | No — it's a strategy side-effect |

**Consequence:** You can't declare "this PlanItem is orchestrated" or "this PlanItem is choreographed." The dispatch mode is an emergent property, not a design choice.

### T9: Strategies don't compose

`PlanningStrategy.select()` returns a list of bindings to fire. It cannot delegate to another strategy. A sequential strategy cannot say "within this step, use choreography for sub-tasks." There is no nesting.

**Consequence:** Hybrid execution (orchestrate phases, choreograph within each phase) requires either (a) complex per-binding logic within a single strategy or (b) sub-cases, each with their own strategy.

### T10: Three execution loops with unclear relationship

| Loop | Module | What it drives |
|---|---|---|
| `PlanningStrategyLoopControl` | blackboard | Binding evaluation → strategy → dispatch |
| `DefaultWorkOrchestrator` | runtime | Work submission to Quartz backends |
| `AbstractExecutionDriver` | blocks | Five-phase agentic loop |

Are these peers? Do they compose? Do they overlap? The answer is: they nest (`PlanningStrategyLoopControl` → `DefaultWorkOrchestrator` → Quartz → worker → `AbstractExecutionDriver`), but this nesting is implicit and undocumented.

## 3. The Universal Model

Six design invariants resolve all ten tensions:

### Invariant 1: PlanItem is the graph

```
PlanItem (sealed)
  ├─ Primitive     — dispatches a worker
  └─ Compound      — contains children + strategy + completion semantics
```

A compound PlanItem replaces Stage as the universal container of workers. It has children, a dispatch strategy, completion semantics, entry conditions, and repeat capability. Primitive PlanItems dispatch workers and track status.

**Resolves T4** (flat PlanItem) — compound PlanItems provide hierarchical structure.
**Resolves T7** (CMMN terminology) — Stage becomes one configuration of a compound PlanItem, not a distinct concept.

### Invariant 2: CaseInstance is the runtime

The PlanItem graph is the PLAN — what needs to happen. The CaseInstance is the RUNTIME — context, EventLog, lifecycle. One CaseInstance hosts arbitrarily deep PlanItem graphs. SubCases create new CaseInstances only when true isolation is needed (independent context, independent lifecycle).

**Resolves T4** — compound PlanItems scope within a single CaseInstance. No sub-case overhead for HTN phases.

### Invariant 3: Two dispatch modes

Every PlanItem dispatch has exactly two dimensions:

| Dimension | Name | Meaning | Mechanism |
|---|---|---|---|
| `trigger` | Choreography | "do this when" | Condition on context, time, or event |
| `strategy` | Orchestration | "do this now" | Selected by containing compound's strategy |

Both can be present (hybrid), either can be absent (pure orchestration or pure choreography). Time-based, event-based, priority-based, goal-driven — all reduce to one or a composition of both.

**Resolves T8** (implicit dispatch modes) — dispatch modes are named and first-class.
**Resolves T3** (hardcoded choreography) — a compound PlanItem can orchestrate, choreograph, or both.

### Invariant 4: Strategies are peers

All planning strategies are `NamedStrategy` implementations resolved per-compound-node at runtime. No compile-time `@Alternative`. No global choice. Per-node, per-case, runtime resolution via `StrategyResolver`.

| Strategy | Mode | What it does |
|---|---|---|
| `choreography` | "when" | Fire all children whose triggers are met |
| `sequential` | "now" | Fire children one at a time, in order |
| `htn` | "now" + decompose | Decompose compound task, then delegate to child strategies |
| `dag` | "now" + dependencies | Fire children as dependencies are met |

Strategies compose by nesting — a compound PlanItem's strategy can create child compound PlanItems with their own strategies. A strategy delegates to any other strategy by name via `StrategyResolver`.

**Resolves T1** (compile-time binary) — per-case, per-node strategy resolution replaces `@Alternative`.
**Resolves T9** (non-composable) — strategies delegate to peers by name.
**Resolves T3** (hardcoded choreography) — any strategy per compound node.

### Invariant 5: Planning produces structure — techniques produce answers

**Planning** (engine) — strategies that produce task structures. PlanItem graphs. "What to do and in what order."

**Techniques** (blocks) — patterns that produce answers by coordinating agents. "How to solve this specific task."

| Engine (planning strategies) | Blocks (problem-solving techniques) |
|---|---|
| Choreography — fire eligible | Supervisor — delegate and review |
| Sequential — one at a time | Debate — adversarial argument |
| HTN — decompose then dispatch | Voting — majority consensus |
| DAG — topological dispatch | Loop — iterative refinement |

Planning happens BEFORE dispatch (structuring work). Techniques happen DURING worker execution (solving problems). The boundary test: does it produce a `DagPlan<T>` (planning) or an answer (technique)?

**Resolves T6** (interleaved concerns) — clear ownership boundary.
**Resolves T10** (three loops) — engine's planning loop and blocks' technique loop nest cleanly: planning dispatches workers, workers use techniques.

### Invariant 6: SubCases are for isolation, not scoping

Compound PlanItems scope within a CaseInstance. SubCases create new CaseInstances when true execution isolation is needed — independent context, independent EventLog, different tenancy.

**Resolves T4** — HTN decomposition produces compound PlanItems, not sub-cases.

## 4. How each tension is resolved

| Tension | Root cause | Resolution | Invariant |
|---|---|---|---|
| T1: Global execution model | `@Alternative @Priority(10)` | Per-node strategy resolution via `StrategyResolver` | 4 |
| T2: DAG duplication | Parallel implementations | Retire blocks' `ExecutionPlan`, adopt engine's `DagPlan` | — |
| T3: Hardcoded choreography | Stage has no strategy field | Compound PlanItem with per-node strategy | 3, 4 |
| T4: Flat PlanItem | No compound type | Sealed hierarchy: Primitive / Compound | 1 |
| T5: Two routing tiers | Different scopes, no composition | Documented as complementary tiers; orchestration routing (engine) and technique routing (blocks) | 5 |
| T6: Planning/techniques mixed | Same package, same base class | Planning → engine, techniques → blocks | 5 |
| T7: CMMN terminology | "Stage" name | Rename module, Stage → compound PlanItem | 1 |
| T8: Implicit dispatch modes | Emergent from trigger + strategy | Named first-class: choreographed / orchestrated | 3 |
| T9: Non-composable strategies | No delegation mechanism | Strategies access `StrategyResolver`, delegate by name | 4 |
| T10: Three execution loops | Unclear nesting | Planning loop (engine) → dispatches worker → technique loop (blocks) | 5 |

## 5. Structural Changes

### 5.1 Rename: `casehub-engine-blackboard` → `casehub-engine-planning`

The module is no longer an optional CMMN-inspired alternative. It's the engine's core planning infrastructure — always active, hosting all execution models as peer strategies.

- Module: `casehub-engine-planning`
- Package: `io.casehub.engine.planning`
- Subpackages: `control`, `plan`, `handler`, `registry`, `decomposition`

### 5.2 Retire: `ChoreographyLoopControl`

`PlanningStrategyLoopControl` becomes the only `LoopControl` implementation. `DefaultPlanningStrategy` provides choreography behavior. No `@Alternative`. Choreography is a strategy, not a special default.

### 5.3 PlanItem sealed hierarchy

```java
sealed interface PlanItem permits PlanItem.Primitive, PlanItem.Compound {

    // Primitive — dispatches a worker
    record Primitive(...) implements PlanItem {}

    // Compound — contains children with strategy and completion semantics
    record Compound(
        List<PlanItem> children,
        String planningStrategy,         // resolved by name via StrategyResolver
        CompletionSemantics completion,  // all, M-of-N, first-wins
        EntryCondition entryCondition,   // when to activate (nullable)
        boolean repeatable               // iteration support
    ) implements PlanItem {}
}
```

CMMN `stages:` in YAML maps to compound PlanItems with entry conditions and autocomplete.

### 5.4 Per-compound dispatch in PlanningStrategyLoopControl

```
eligible bindings
  → group by containing compound PlanItem
  → each compound's strategy selects from its children
  → free-floating bindings use case-level strategy
```

### 5.5 Composable strategy delegation

`PlanningStrategy` gains access to `StrategyResolver` so it can resolve sibling strategies by name. An HTN strategy decomposes, creates child compound PlanItems, and tags each with its own `planningStrategy`. The loop control resolves recursively.

## 6. Redesign Requirements

### 6.1 TaskNode uses ExecutorRef

`AgentRef` stays sealed in blocks, gains `extends ExecutorRef`. Engine's `TaskNode.LeafTask` uses `ExecutorRef executor()`. Blocks passes `AgentRef` transparently. Engine never sees `AgentRef`.

### 6.2 DecompositionContext uses ExecutorRef

Engine-api version uses `List<? extends ExecutorRef>`. Blocks wraps with richer context in LLM-specific implementations.

### 6.3 sequentialMerge on DagPlan

Hard prerequisite. `StaticDecomposition` needs this as its primary composition mechanism.

### 6.4 HtnBuilder stays in blocks

It's a blocks pattern builder extending `AbstractPatternBuilder` — not planning infrastructure. Engine gets the decomposition SPI and an HTN-aware planning strategy.

## 7. What Moves Where and Why

### Promote to engine-api (planning model — shared vocabulary)

| Type | Why | Redesign |
|---|---|---|
| `TaskNode<T>` (LeafTask, CompoundTask) | HTN task model for decomposition input/output | `ExecutorRef` replaces `AgentRef` |
| `DecompositionStrategy<T>` | Core HTN SPI — same SPI pattern as `AgentRoutingStrategy` | Return type → `DagPlan<LeafTask<T>>` |
| `DecompositionMethod<T>` | Method selection — which decomposition applies | Minimal |
| `DecompositionContext<T>` | Context for decomposition decisions | `ExecutorRef` list replaces `RoutingCandidate` |
| `NoMethodMatchedException` | Thrown by `StaticDecomposition`, caught by blocks' `HybridDecomposition` | None |

### Promote to engine-planning (planning strategy implementations)

| Type | Why |
|---|---|
| `StaticDecomposition` | Predefined decomposition methods, pure logic, `@DefaultBean` |
| `IdentityDecomposition` | Leaf task passthrough, no decomposition |
| HTN-aware `PlanningStrategy` (new) | Calls `DecompositionStrategy`, creates compound PlanItems, delegates to child strategies |

### Stay in blocks permanently (problem-solving techniques)

| Type | Why it stays |
|---|---|
| All pattern builders (Supervisor, Debate, Voting, Loop, Parallel, Sequence, Conditional, HTN) | Produce answers by coordinating agents, not task structures |
| `AbstractExecutionDriver`, `OrchestratedDriver`, `ChoreographedDriver` | Blocks' five-phase technique execution model |
| `LlmDecomposition`, `HybridDecomposition` | LLM-powered decomposition (implements engine-api SPI from blocks) |
| `AgentRef` (sealed, extends `ExecutorRef`) | Blocks agent identity — engine uses `ExecutorRef` |
| Five-phase loop SPIs (Aggregation, Termination, Activation) | Part of blocks' technique execution model |
| `OrchestrationRoutingStrategy<T>` | Task-level routing within techniques |
| All pattern-specific listeners, factories, DSL entry points | Blocks convenience layer |

### Retire (replaced by unified model)

| Type | Replaced by |
|---|---|
| `ChoreographyLoopControl` | `PlanningStrategyLoopControl` as the only `LoopControl` |
| `Stage` (as distinct type) | Compound PlanItem |
| `ExecutionPlan<T>` (blocks) | `DagPlan<T>` (engine-common) |
| `ExecutionNode<T>` (blocks) | `DagNode<T>` (engine-common) |
| `ExecutionPlan.JoinType` (blocks) | `JoinType` (engine-common) |

### Already in engine (no change)

| Type | Where | Role |
|---|---|---|
| `DagPlan<T>`, `DagNode<T>`, `JoinType` | engine-common | DAG planning infrastructure |
| `DagDriver<T,R>`, `DagResult<R>`, `NodeState<R>` | engine-common | Standalone DAG execution (not used in planning dispatch path) |
| `ExecutorRef`, `TaskDescriptor`, `TaskStatus` | engine-api | Shared task identity |
| `PlanningStrategy` (interface) | engine-api | Per-node strategy SPI |
| `DefaultPlanningStrategy` | engine-planning | Choreography behavior |
| `SequentialPlanningStrategy` | engine-planning | Sequential behavior |
| `CasePlanModel`, `PlanItem` | engine-planning | Plan state (PlanItem evolves to sealed hierarchy) |
| `AgentRoutingStrategy`, `RoutingResult` | engine-api | Case-level agent routing |
| `CbrRetrievalService` | engine-runtime | CBR retrieval (future decomposition input) |

## 8. Phased Migration

### Phase 0: Prerequisites
- `sequentialMerge()` on `DagPlan`
- `AgentRef extends ExecutorRef` in blocks
- Verify engine-common transitively available to blocks

### Phase 1: Retire ChoreographyLoopControl
- `PlanningStrategyLoopControl` becomes the only `LoopControl`, moves to runtime
- `DefaultPlanningStrategy` provides choreography behavior
- All existing tests pass unchanged — no behavioral change

### Phase 2: Rename blackboard → engine-planning
- Module: `casehub-engine-planning`
- Package: `io.casehub.engine.planning`
- Coordinate with trebleel before executing
- Consumer repos update imports

### Phase 3: PlanItem sealed hierarchy
- `PlanItem` becomes sealed: `Primitive | Compound`
- Stage migrates to compound PlanItem configuration
- Per-compound strategy resolution in `PlanningStrategyLoopControl`
- YAML `stages:` maps to compound PlanItems with entry conditions and autocomplete

### Phase 4: DAG plan unification (blocks)
- `ExecutionPlan<T>` → `DagPlan<T>` throughout blocks
- Retire `ExecutionPlan`, `ExecutionNode`, `ExecutionPlan.JoinType`

### Phase 5: HTN decomposition SPI
- Promote `TaskNode`, `DecompositionStrategy`, `DecompositionMethod`, `DecompositionContext` to engine-api
- Promote `StaticDecomposition`, `IdentityDecomposition` to engine-planning
- HTN-aware planning strategy in engine-planning
- blocks' `LlmDecomposition` implements the SPI

### Phase 6: Composable strategy wiring
- Strategies access `StrategyResolver` for delegation
- Per-subtask strategy overrides on compound PlanItems
- Integration test: mixed strategies in one case (HTN top-level → sequential phase 1 → choreography phase 2)

## 9. Verification

After each phase:
1. `mvn install` in engine — all modules
2. `mvn install` in blocks
3. `mvn test` in both — all existing tests pass
4. No new dependencies introduced that weren't already transitive
5. Consumer repos still compile

### Hypotheses to test

| ID | Hypothesis | Test |
|---|---|---|
| H1 | All execution models expressible as composable `PlanningStrategy` | Choreography, sequential, HTN each implemented as named strategies |
| H2 | `ChoreographyLoopControl` retirable without behavioral change | All existing tests pass with `PlanningStrategyLoopControl` as sole `LoopControl` |
| H3 | Compound PlanItems replace Stage without losing functionality | Entry conditions, autocomplete, repeat, milestones all work on compound PlanItems |
| H4 | `DagDriver` not needed in planning dispatch path | Compound PlanItems dispatch children via strategy, not DagDriver |
| H5 | `TaskNode` works with `ExecutorRef` | blocks creates `LeafTask(agentRef)`, engine sees `ExecutorRef` |
| H6 | Strategies compose via delegation | HTN strategy delegates sequential phase to `SequentialPlanningStrategy` by name |

### Boundary seams to verify

| Seam | What crosses | Direction | Risk |
|---|---|---|---|
| engine-api ↔ blocks | `DecompositionStrategy<T>` SPI | engine defines, blocks implements | blocks must not pull engine-api types backward |
| engine-api ↔ blocks | `ExecutorRef` ← `AgentRef extends` | blocks extends engine type | `LeafTask(agentRef)` must work as `LeafTask(executorRef)` |
| engine-planning ↔ engine-api | `PlanningStrategy` via `StrategyResolver` | engine-api defines, planning implements | Per-case, per-node resolution |
| blocks ↔ consumers | Pattern builders, techniques | blocks exports | No change |
| engine-common ↔ planning | `DagPlan` used by decomposition output | common provides, planning consumes | Already works |
