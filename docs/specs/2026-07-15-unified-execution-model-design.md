# Unified Execution Model

How CaseHub's execution infrastructure reduces to two primitives, one
graph, and one runtime — and why that's enough for every execution
model we've encountered.

**Companion documents:**
- Blocks migration spec: casehubio/blocks#60
- Execution backend architecture: `blocks/docs/execution-backend-architecture.md`
- Agentic orchestration research: `blocks/docs/agentic-orchestration-research.md`

---

## Part 1 — The Problem

### 1.1 Current state

Execution infrastructure is spread across engine and blocks with overlapping concerns, inconsistent abstraction levels, and compile-time decisions that should be runtime choices.

**Engine's execution infrastructure:**

| Component | Module | What it does |
|---|---|---|
| `ChoreographyLoopControl` | runtime | Fire all eligible bindings concurrently (global default) |
| `PlanningStrategyLoopControl` | blackboard (`@Alternative @Priority(10)`) | Stage gating + strategy delegation (replaces choreography when present) |
| `DefaultPlanningStrategy` | blackboard | Fire all eligible (choreography within blackboard) |
| `SequentialPlanningStrategy` | blackboard | Fire one binding at a time |
| `Stage` | blackboard | Container of bindings with entry conditions and autocomplete |
| `PlanItem` | blackboard | Unit of work: binding name, status, executor |
| `CasePlanModel` | blackboard | Per-case plan state: plan items, stages, milestones |
| `DagPlan<T>`, `DagNode<T>`, `JoinType` | engine-common | DAG plan construction and validation |
| `DagDriver<T,R>` | engine-common | Synchronous topological task dispatch |
| `DefaultWorkOrchestrator` | runtime | Submit work to Quartz backends |
| `CaseInstance` | common | Case lifecycle, context, EventLog |

**Blocks' execution infrastructure:**

| Component | Package | What it does |
|---|---|---|
| `ExecutionPlan<T>`, `ExecutionNode<T>` | `agentic/plan` | DAG plan (duplicate of engine's `DagPlan`) |
| `AbstractExecutionDriver` | `agentic/model` | Five-phase loop: route → activate → dispatch → aggregate → terminate |
| `OrchestratedDriver`, `ChoreographedDriver` | `agentic/model` | Execution variants of five-phase loop |
| `TaskNode<T>` (LeafTask, CompoundTask) | `agentic/decomposition` | Sealed task hierarchy |
| `DecompositionStrategy<T>` | `agentic/decomposition` | HTN: compound tasks → subtask tree |
| `RoutingStrategy<T>` | `agentic/routing` | Task-level routing (parallel to engine's `AgentRoutingStrategy`) |
| `AggregationStrategy<T>`, `TerminationCondition<T>`, `ActivationRule<T>` | various | Five-phase loop SPIs |
| Pattern builders (8) | `agentic/pattern` | Supervisor, Debate, Voting, Loop, Parallel, Sequence, Conditional, HTN |
| `AgentRef` (sealed, 5 variants) | `agentic` | Agent identity |

Both stacks share leaf types (`ExecutorRef`, `TaskDescriptor`) but diverge on everything above them.

### 1.2 Tensions

**T1: Global execution model — compile-time binary.** `ChoreographyLoopControl` vs `PlanningStrategyLoopControl` is a deployment-time `@Alternative` choice. Every case gets the same model. No per-case selection.

**T2: DAG infrastructure duplication.** `ExecutionPlan<T>` (blocks) and `DagPlan<T>` (engine) are structurally identical — same validation, topo-sort, cycle detection. Bugs fixed in one aren't fixed in the other.

**T3: Stage is hardcoded choreography.** A Stage fires all contained bindings when active. No per-stage strategy. Cannot orchestrate within a stage.

**T4: PlanItem is flat — no compound tasks.** No parent-child relationship. No concept of a compound task that decomposes into subtasks. Stage is a separate type, not a PlanItem.

**T5: Two routing tiers — parallel but unconnected.** `AgentRoutingStrategy` (engine, case-scoped) and `RoutingStrategy<T>` (blocks, pattern-scoped) answer similar questions at different tiers with no composition path.

**T6: Planning and techniques interleaved in blocks.** `DecompositionStrategy` (planning) and `DebateBuilder` (technique) share the same package and base class, though they operate at different abstraction levels.

**T7: CMMN terminology limits the model.** "Stage" implies CMMN semantics. The general concept — container of workers with dispatch strategy and completion semantics — is broader.

**T8: Dispatch modes are implicit.** Choreography ("do this when") and orchestration ("do this now") are the two fundamental archetypes, but they're not named or first-class. They emerge from how `ContextChangeTrigger` interacts with `PlanningStrategy.select()`.

**T9: Strategies don't compose.** A sequential strategy cannot say "within this step, use choreography for sub-tasks." No delegation mechanism between strategies.

**T10: Three execution loops with unclear relationship.** `PlanningStrategyLoopControl` (engine), `DefaultWorkOrchestrator` (engine), `AbstractExecutionDriver` (blocks) — they nest implicitly but the nesting is undocumented.

---

## Part 2 — The Model

### 2.1 Two primitives

Everything in the execution model is a PlanItem. There are exactly two kinds:

```
PlanItem (sealed)
  ├─ Primitive     — dispatches a single worker
  └─ Compound      — contains children + strategy + completion semantics
```

A **primitive** PlanItem dispatches a worker and tracks the result. This is what engine's `PlanItem` does today and what blocks' `TaskNode.PrimitiveTask` does. Same concept, different names, now unified.

A **compound** PlanItem is a container of other PlanItems. It has:
- **children** — the PlanItems it contains (primitive or compound)
- **planningStrategy** — how to dispatch those children (resolved by name)
- **completionSemantics** — when this node is "done" (all, M-of-N, first-wins)
- **dispatchMode** — how this PlanItem is activated (see below)
- **entryCondition** — the trigger expression (required when choreographable)
- **repeatable** — iteration support

**Resolves:** T4 (flat PlanItem), T7 (CMMN terminology — Stage becomes a compound PlanItem configuration)

### 2.2 Two dispatch modes

Every known model of execution control reduces to two archetypes, or a composition of both. This is not CaseHub-specific — it is an observation about the structure of coordination models in general.

| Dimension | Name | Question | Mechanism |
|---|---|---|---|
| **trigger** | Choreography | "when should this happen?" | Condition on context, time, event |
| **strategy** | Orchestration | "what should happen now?" | Selected by containing compound's strategy |

Each PlanItem declares which dimensions apply via its `DispatchMode`:

```
DispatchMode (enum)
  ORCHESTRATED    — parent's strategy selects this item ("do this now")
  CHOREOGRAPHED   — fires when entry condition is satisfied ("do this when")
  HYBRID          — both: eligible for strategy selection AND trigger-activated
```

A PlanItem declared `ORCHESTRATED` appears in its parent compound's strategy candidate set. No entry condition needed or evaluated.

A PlanItem declared `CHOREOGRAPHED` fires when its entry condition is satisfied, independent of any strategy. Not a candidate for strategy selection. Entry condition required.

A PlanItem declared `HYBRID` participates in both. Whichever happens first activates the item.

**Resolves:** T8 (implicit dispatch modes), T3 (hardcoded choreography — compound PlanItems can orchestrate, choreograph, or both)

### 2.3 Evidence: every execution model maps to these two archetypes

**Orchestrated models** — a central authority decides what executes next:

| Model | Domain | How it maps |
|---|---|---|
| Sequential pipeline | Workflow | Strategy fires children in declared order |
| Parallel fan-out | Workflow | Strategy fires all children simultaneously |
| Conditional routing | Workflow | Strategy evaluates predicates to select a path |
| Loop / iterative refinement | Workflow | Strategy re-fires children until exit condition |
| Supervisor | Multi-agent AI | Strategy asks an LLM to select the next agent |
| HTN | Classical AI | Strategy decomposes compound tasks via methods |
| GOAP | Classical AI | Strategy computes dependency graph backward from goal |
| Voting / ensemble | Multi-agent AI | Strategy fans out to all agents, aggregates votes |
| Debate / adversarial | Multi-agent AI | Strategy alternates debaters, evaluates convergence |
| Contract Net | Multi-agent systems | Strategy announces task, collects bids, selects contractor |
| Priority scheduling | OS / real-time | Strategy selects highest-priority ready item |

**Choreographed models** — participants decide when to act based on conditions:

| Model | Domain | How it maps |
|---|---|---|
| Blackboard (Hayes-Roth BB1) | Classical AI | Agents fire when preconditions match |
| Event-driven reactive | EDA | Handler fires when subscribed event arrives |
| Stigmergy | Swarm intelligence | Agents respond to environment markers left by others |
| P2P / mesh | Multi-agent systems | Agents fire when dependency inputs are satisfied |
| Rule engine (Rete/PHREAK) | Expert systems | Rules fire when working memory satisfies LHS patterns |
| Petri nets | Formal methods | Transition fires when all input places have tokens |
| Actor model | Concurrency | Actor processes message when it arrives in mailbox |
| Dataflow | Functional reactive | Node fires when all input signals are available |

**Hybrid models** — both archetypes active simultaneously:

| Model | Domain | How it maps |
|---|---|---|
| BDI agents | Cognitive agents | Desires trigger plan selection (choreography) + plan follows strategy (orchestration) |
| Behaviour trees | Game AI | Condition nodes evaluate triggers + composite nodes select children via strategy |
| Saga / compensation | Distributed systems | Forward steps orchestrated + compensation triggers choreographed on failure |
| ReAct | LLM agents | LLM selects next action (orchestration) + observation triggers re-evaluation (choreography) |

No execution model in this catalogue requires a third archetype.

### 2.4 Planning algorithms (under orchestration)

Within the orchestration dispatch mode, different algorithms build the plan. These are peers, all producing `DagPlan<T>` as their output format:

| Algorithm | What you specify | What the solver finds |
|---|---|---|
| Sequential | The steps, in order | Nothing — fixed list |
| Flow | Control flow: loops, conditionals, compensation | Nothing — fixed graph |
| HTN | Decomposition methods (or LLM generates them) | How to break compound tasks into primitives |
| (unnamed — goal-directed) | Operators (capabilities with I/O schemas) + goal state | Sequence of operators reaching the goal |

**Key design rules:**
- Sequential stays simple — an ordered list, nothing more. The moment you need loops or conditionals, use Flow. Don't grow Sequential into a workflow language.
- Flow already partially exists as `casehub-engine-flow` (Serverless Workflow SDK), currently positioned as worker execution (Tier 3). Should be a peer planning strategy.
- `DagPlan<T>` is the universal output format — infrastructure, not an algorithm. All algorithms produce DAGs.
- ReAct is NOT a separate algorithm. It's the native `CONTEXT_CHANGED` evaluation loop: strategy evaluates state (Thought) → dispatches worker (Action) → context changes from output (Observation) → repeat.
- The unnamed goal-directed algorithm already exists in one form: `LlmDecomposition` in blocks takes a goal and capabilities and produces a plan. LangChain4j calls their version `GoalOrientedPlanner` — graph search over agent I/O keys. Our capabilities already declare `inputSchema`/`outputSchema`.

### 2.5 Composable strategies

Planning strategies are peers. They share one SPI, one resolution mechanism, and compose by nesting.

All are `NamedStrategy` implementations resolved per-compound-node at runtime via `StrategyResolver`. No `@Alternative`. No compile-time selection. Each compound PlanItem names its strategy. Different compounds in the same case can use different strategies.

A strategy can delegate to any other strategy by name. An HTN strategy decomposes and creates child compound PlanItems, each with their own strategy. Responsibility scopes to each compound node. No strategy needs to know what other strategies exist.

**Resolves:** T1 (compile-time binary), T9 (non-composable), T3 (hardcoded choreography)

### 2.6 The planning/technique line

This is the architectural boundary between engine and blocks.

**Planning** produces task structures — PlanItem graphs. "What needs to happen and in what order." Planning is domain-independent. A sequential pipeline in insurance claims processing is structurally identical to one in code review.

**Techniques** produce answers by coordinating agents within a single task. "How to solve this specific problem." A supervisor delegates and reviews. A debate stages adversarial argument. A voting round collects independent opinions. These are problem-solving methods, not planning methods.

| Engine (planning) | Blocks (techniques) |
|---|---|
| Sequential — one at a time | Supervisor — delegate and review |
| Flow — control flow | Debate — adversarial argument |
| HTN — decompose then dispatch | Voting — majority consensus |
| (goal-directed) — find a plan | Loop — iterative refinement |

Planning happens BEFORE dispatch (structuring work). Techniques happen DURING worker execution (solving problems). The boundary test: does it produce a `DagPlan<T>` (planning) or an answer (technique)?

Blocks' pattern builders, execution drivers, five-phase loop SPIs (aggregation, termination, activation) are technique concerns. They stay in blocks.

**Resolves:** T6 (interleaved concerns), T10 (three loops — planning loop dispatches workers, technique loop runs inside workers)

### 2.7 Where Stages land

CMMN Stages were a distinct type: a named container with entry/exit sentries, autocomplete, and lifecycle. In the unified model, Stage is a configuration of a compound PlanItem:

- `planningStrategy` = choreography (fire children whose triggers are met)
- `completionSemantics` = ALL (autocomplete when all children complete)
- `entryCondition` = sentry expression
- `dispatchMode` of children = CHOREOGRAPHED

YAML `stages:` maps to compound PlanItems with this configuration. Internally, one type.

Retiring Stage as a distinct type removes the temptation to model execution phases as Stages when they should be compound PlanItems. An HTN phase is not a Stage — it's a compound PlanItem with an HTN strategy. A parallel fan-out is not a Stage — it's a compound PlanItem whose strategy fires all children.

### 2.8 How AI execution forms fit

Each AI pattern maps to a combination of planning strategy (engine) and technique (blocks):

**HTN Planning:** An HTN-aware planning strategy calls `DecompositionStrategy` to produce children. Static decomposition uses predefined methods. LLM decomposition asks a language model. `DecompositionStrategy` and `TaskNode` promote to engine-api (planning concept). `LlmDecomposition` stays in blocks. `HtnBuilder` stays in blocks (it's a technique — composes the five-phase loop with decomposition).

**ReAct / Tool-Use Loops:** An agent that reasons, acts, observes, and repeats. Pure blocks technique: `LoopBuilder` with supervisor routing and goal-reached termination. Engine sees a single primitive PlanItem.

**Multi-Agent Debate:** `DebateBuilder` composes round-robin routing, collect-all aggregation, judge-convergence termination. Pure blocks technique.

**GOAP (Goal-Oriented Action Planning):** Maps to the `DecompositionStrategy` SPI with a state-space search instead of method-based decomposition. The SPI is general enough: receives a compound task and context, returns a plan.

**Voting / Ensemble:** `VotingBuilder` composes parallel routing, majority-vote aggregation. Pure blocks technique.

**Supervisor / Delegation:** `SupervisorBuilder` composes first-match or LLM routing, pass-through aggregation, goal-reached termination. Pure blocks technique.

### 2.9 Orthogonality

Three independent axes. Changing one doesn't force changes in the others.

**Axis 1: Task structure** — How work is decomposed into PlanItems. Flat list, sequential chain, DAG, HTN tree. Determined by decomposition strategy.

**Axis 2: Dispatch strategy** — How a compound PlanItem's children are selected for execution. Choreography, sequential, HTN-aware. Independent of what the children are.

**Axis 3: Problem-solving technique** — How a worker solves its assigned task. Supervisor, debate, voting, loop, tool-use. Independent of how the task was planned or dispatched.

A sequential pipeline (axis 2) of workers each running debate techniques (axis 3) over an HTN-decomposed task tree (axis 1) composes without friction.

### 2.10 Type boundaries

**Engine sees `ExecutorRef`.** Planning strategies, PlanItems, decomposition contexts all reference `ExecutorRef` — the shared executor identity from engine-api.

**Blocks sees `AgentRef`.** Sealed, extends `ExecutorRef`. Blocks creates `LeafTask` instances passing `AgentRef` transparently — engine receives `ExecutorRef` at the SPI boundary.

```java
// engine-api — open, shared identity
interface ExecutorRef { String name(); String description(); }

// blocks — sealed, blocks-specific variants
sealed interface AgentRef extends ExecutorRef
    permits WorkerAgent, ChannelAgent, HumanAgent, ExternalAgent, ComposedAgent
```

No unsealing. No circular dependencies. `AgentRef extends ExecutorRef` is already implemented.

---

## Part 3 — Comparison with LangChain4j

LangChain4j (1.17.0, June 2026) provides a `Planner` interface that all orchestration patterns implement. CaseHub's unified model takes a different architectural approach.

### Pattern mapping

| LangChain4j Planner | CaseHub equivalent | Category |
|---|---|---|
| `SequentialPlanner` | Sequential planning strategy | Planning |
| `ParallelPlanner` | Choreography dispatch mode (concurrent) | Dispatch mode |
| `LoopPlanner` | Flow planning strategy | Planning |
| `ConditionalPlanner` | Flow planning strategy (branching) | Planning |
| `GoalOrientedPlanner` | (unnamed) goal-directed planning strategy | Planning |
| `SupervisorPlanner` | Blocks Supervisor technique (or HTN with LLM) | Technique |
| `P2PPlanner` | Choreography — `ContextChangeTrigger` | Dispatch mode |

### Structural differences

| Concern | LangChain4j | CaseHub |
|---|---|---|
| Pattern representation | Monolithic `Planner` class per pattern | Composition of independent SPIs |
| Composition | Agent nesting (implicit) | Strategy delegation by name (explicit, per-node) |
| Dispatch modes | Mixed into each Planner impl | Two orthogonal dimensions, declared per PlanItem |
| Execution scope | Single JVM, `AgentInstance` references | Distributed: workers, channels, humans |
| State model | `AgenticScope` (mutable key-value) | `CaseContext` (typed layers, auditable, event-sourced) |
| Durability | Application-managed or via Flow | Engine runtime (uniform, EventLog checkpoint + replay) |
| Routing | Pattern-internal | Two-tier SPI (engine runtime + blocks technique) |
| Agent definition | Owns (`@Agent` proxy generation) | Delegates to LangChain4j |
| Plan representation | None shared across planners | `DagPlan<T>` — universal output format |

### Where CaseHub's model is stronger

- **Orthogonal dispatch modes composable per-node.** LangChain4j's P2P (choreography) and workflow (orchestration) are separate implementations. No per-node hybrid.
- **Compound PlanItems with nested strategies.** Arbitrary depth, each level can use a different algorithm. LangChain4j's planners are flat.
- **Plan graph as model, CaseInstance as runtime.** Clean separation. LangChain4j's `AgenticScope` mixes both.
- **Planning algorithms as peers with shared output format.** LangChain4j has separate Planner implementations with no shared plan representation.
- **Deterministic execution without LLM.** Static plans execute without LLM cost. LangChain4j's Supervisor requires LLM per decision.
- **Cost at scale.** LLM for decomposition ONCE, then deterministic execution. Not LLM call per decision × thousands of cases.
- **Non-AI cases.** Full spectrum from zero-AI human workflows to full-AI agent orchestration.
- **Durability and recovery.** Uniform, EventLog-based. Not pattern-specific.

### Where LangChain4j is simpler

- Three-method `Planner` interface vs multiple SPIs
- Monolithic patterns are easier to understand in isolation
- `@Agent` proxy generation for typed domain interfaces
- Quarkus Flow integration for workflow-shaped patterns

### The bar we must clear

LangChain4j's simplicity IS a strength. Our model wins ONLY if it is as simple as theirs for simple cases AND richer for complex ones.

- Hello World case with one worker: no ceremony, no compound PlanItems, no strategy resolvers visible.
- Complex case with HTN + mixed strategies: the full model is available but only surfaces when declared.
- A developer who only needs Sequential should never encounter the word "CompoundPlanItem" in their API surface, logs, or error messages.

Complexity must be layered and never leak. If simple cases force users to understand the full type system, the YAGNI argument wins and LangChain4j's simplicity is the better design.

---

## Part 4 — What Changes

### 4.1 Structural changes

**Rename:** `casehub-engine-blackboard` → `casehub-engine-planning`. Module: `casehub-engine-planning`. Package: `io.casehub.engine.planning`. Coordinate with trebleel before executing.

**Retire:** `ChoreographyLoopControl`. `PlanningStrategyLoopControl` becomes the only `LoopControl`. Choreography behavior via `DefaultPlanningStrategy`.

**Retire:** `Stage` as a distinct type. Replaced by compound PlanItem configuration.

**PlanItem sealed hierarchy:**

```java
sealed interface PlanItem permits PlanItem.Primitive, PlanItem.Compound {
    record Primitive(...) implements PlanItem {}
    record Compound(
        List<PlanItem> children,
        String planningStrategy,
        CompletionSemantics completion,
        DispatchMode dispatchMode,
        EntryCondition entryCondition,
        boolean repeatable
    ) implements PlanItem {}
}
```

**Per-compound dispatch:** `PlanningStrategyLoopControl` groups eligible bindings by containing compound PlanItem, resolves each compound's strategy, delegates.

**Composable delegation:** `PlanningStrategy` gains access to `StrategyResolver` for sibling delegation.

### 4.2 Redesign requirements

- **TaskNode uses ExecutorRef** — `AgentRef extends ExecutorRef` (already implemented). `LeafTask.executor()` returns `ExecutorRef`.
- **DecompositionContext uses ExecutorRef** — `List<? extends ExecutorRef>` instead of `RoutingCandidate`.
- **sequentialMerge on DagPlan** — hard prerequisite. `StaticDecomposition` needs it. Not optional.
- **HtnBuilder stays in blocks** — it's a pattern builder extending `AbstractPatternBuilder`, not planning infrastructure.
- **Compound tasks are ephemeral** — decomposition produces leaf PlanItems. Compound tasks recorded in EventLog metadata for audit, not stored in `CasePlanModel`.
- **DagDriver stays standalone** — not used in planning dispatch path. Compound PlanItems dispatch children via strategy.

### 4.3 What moves where

**Promote to engine-api:**

| Type | Why | Redesign |
|---|---|---|
| `TaskNode<T>` (LeafTask, CompoundTask) | HTN task model | `ExecutorRef` replaces `AgentRef` |
| `DecompositionStrategy<T>` | Core HTN SPI | Return type → `DagPlan<LeafTask<T>>` |
| `DecompositionMethod<T>` | Method selection | Minimal |
| `DecompositionContext<T>` | Decomposition context | `ExecutorRef` list replaces `RoutingCandidate` |
| `NoMethodMatchedException` | Shared exception | None |

**Promote to engine-planning:**

| Type | Why |
|---|---|
| `StaticDecomposition` | Pure logic, `@DefaultBean` |
| `IdentityDecomposition` | Leaf task passthrough |
| HTN-aware `PlanningStrategy` (new) | Decompose → PlanItems → delegate to child strategies |

**Stay in blocks permanently:**

| Type | Why |
|---|---|
| All pattern builders (Supervisor, Debate, Voting, Loop, Parallel, Sequence, Conditional, HTN) | Techniques — produce answers, not task structures |
| `AbstractExecutionDriver`, `OrchestratedDriver`, `ChoreographedDriver` | Five-phase technique loop |
| `LlmDecomposition`, `HybridDecomposition` | LLM-powered (implements engine-api SPI from blocks) |
| `AgentRef` (sealed, extends `ExecutorRef`) | Blocks agent identity |
| Five-phase loop SPIs (Aggregation, Termination, Activation) | Technique concerns |
| `OrchestrationRoutingStrategy<T>` | Task-level routing within techniques |

**Retire:**

| Type | Replaced by |
|---|---|
| `ChoreographyLoopControl` | `PlanningStrategyLoopControl` as the only `LoopControl` |
| `Stage` (as distinct type) | Compound PlanItem configuration |
| `ExecutionPlan<T>` (blocks) | `DagPlan<T>` (engine-common) |
| `ExecutionNode<T>` (blocks) | `DagNode<T>` (engine-common) |

### 4.4 Phased migration

**Phase 0: Prerequisites.** `sequentialMerge()` on `DagPlan`. Verify engine-common transitively available to blocks.

**Phase 1: Retire ChoreographyLoopControl.** `PlanningStrategyLoopControl` becomes the only `LoopControl`. All existing tests pass unchanged.

**Phase 2: Rename blackboard → engine-planning.** Coordinate with trebleel. Consumer repos update imports.

**Phase 3: PlanItem sealed hierarchy.** `Primitive | Compound`. Stage migrates to compound PlanItem. Per-compound strategy resolution.

**Phase 4: DAG plan unification (blocks).** `ExecutionPlan<T>` → `DagPlan<T>`.

**Phase 5: HTN decomposition SPI.** Promote `TaskNode`, `DecompositionStrategy` to engine-api. HTN-aware planning strategy in engine-planning.

**Phase 6: Composable strategy wiring.** Strategy delegation via `StrategyResolver`. Per-subtask strategy overrides. Integration test: mixed strategies in one case.

---

## Part 5 — Unresolved

### Contradictions in this spec

**C1: Naming the choreography default.** When a compound PlanItem has no strategy, its children are purely choreographed — triggers drive dispatch. `DefaultPlanningStrategy` is a passthrough (no planning). Is this "choreography as a strategy" or "absence of strategy"? The naming matters for API consistency.

**C2: Flow as planning strategy.** `casehub-engine-flow` currently positions Serverless Workflow as `FlowWorkerFunction` (worker execution tier). Promoting it to a peer planning strategy has implications: does `FlowWorkerFunctionHandler` become a `PlanningStrategy`? How does Flow's error handling interact with case lifecycle?

**C3: Selection criteria vs planning algorithms.** Orchestration has two orthogonal dimensions: selection criteria (HOW the strategy picks — priority, goal-driven, resource-aware) and planning algorithms (WHAT structure the plan has — sequential, flow, HTN). These are independent but not modeled separately.

**C4: Stage-to-compound-PlanItem structural transition.** Current Stage has `containedBindingNames` (strings) and `containedPlanItemIds` (strings). Compound PlanItem has `children` (PlanItem references). Different structures. `StageAutocompleteEvaluator` must be migrated. YAML backward compatibility must be preserved.

### Open questions

**Q1: engine#101 sub-issue coverage.** The agentic orchestration epic defines specific patterns. Must enumerate sub-issues and verify each maps to a planning strategy (engine) or technique (blocks). Not yet done.

**Q2: The unnamed goal-directed algorithm.** LangChain4j calls it `GoalOrientedPlanner`. Our capabilities already declare I/O schemas. LLM decomposition is one form. "Goal" collides with existing `Goal`/`GoalKind`/`GoalBasedCompletion`. No name agreed.

**Q3: CompletionSemantics type.** Both documents reference it (all, M-of-N, first-wins) but it doesn't exist in the codebase. Current `StageAutocompleteEvaluator` only checks "all required items terminal."

**Q4: Compound PlanItem detailed design.** How does parent-child indexing work in `CasePlanModel`? How does completion propagate from children to compound parent? How does per-compound strategy resolution work in `PlanningStrategyLoopControl`?

**Q5: Adversarial review findings.** TaskNode.LeafTask depends on AgentRef (resolution: `AgentRef extends ExecutorRef` — done). DecompositionContext depends on RoutingCandidate (resolution: use `ExecutorRef` list). CasePlanModel has no parent-child PlanItem support (resolution: compound tasks ephemeral, but needs verification). DagDriver synchronous vs blackboard reactive (resolution: don't use DagDriver in planning path).

---

## Design Invariants

1. **PlanItem is the graph.** Primitive nodes dispatch workers. Compound nodes dispatch according to their strategy. No other node type exists.

2. **CaseInstance is the runtime.** Context, EventLog, lifecycle, tenancy. The PlanItem graph executes within it. One CaseInstance, arbitrarily deep nesting. SubCases only for true execution isolation.

3. **Two dispatch modes, declared not inferred.** Orchestrated ("now") and choreographed ("when"). Each PlanItem declares its `DispatchMode`. No third axis. Everything reduces to one or a composition of both.

4. **Strategies are peers.** Same SPI, same resolution, composable by nesting. No strategy is more fundamental than another. No compile-time alternatives.

5. **Planning produces structure. Techniques produce answers.** Engine owns planning. Blocks owns techniques. The line is: does this create task structure, or does this solve a task?

6. **ExecutorRef at the boundary.** Engine never imports blocks types. Blocks extends engine types through subtyping. The dependency arrow points one way.

7. **Sequential stays simple.** Ordered list, nothing more. Need loops or conditionals? Use Flow.

8. **Complexity never leaks.** Hello World sees no compound PlanItems, no strategy resolvers, no dispatch mode enums. The full model surfaces only when declared.
