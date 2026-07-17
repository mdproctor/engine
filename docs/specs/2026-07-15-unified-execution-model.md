# Unified Execution Model

How CaseHub's execution infrastructure reduces to two primitives, one
graph, and one runtime — and why that's enough for every execution
model we've encountered.

**Companion documents:**
- Blocks migration spec: [casehubio/blocks#60](https://github.com/casehubio/blocks/issues/60)
- Engine spec (reviewed): [unified-execution-model-design.md](2026-07-15-unified-execution-model-design.md)
- Execution backend architecture: [execution-backend-architecture.md](../../../blocks/docs/execution-backend-architecture.md)
- Agentic orchestration research: [agentic-orchestration-research.md](../../../blocks/docs/agentic-orchestration-research.md)
- DSL conventions: `casehubio/parent docs/DSL-STYLE-GUIDE.md`
- Execution models epic: casehubio/engine#595

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
| `AbstractExecutionDriver` | `agentic/model` | Five-phase loop: route -> activate -> dispatch -> aggregate -> terminate |
| `OrchestratedDriver`, `ChoreographedDriver` | `agentic/model` | Execution variants of five-phase loop |
| `TaskNode<T>` (LeafTask, CompoundTask) | `agentic/decomposition` | Sealed task hierarchy |
| `DecompositionStrategy<T>` | `agentic/decomposition` | HTN: compound tasks -> subtask tree |
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
  |-- Primitive     — dispatches a single worker
  +-- Compound      — contains children + strategy + completion semantics
```

A **primitive** PlanItem dispatches a worker and tracks the result. This is what engine's `PlanItem` does today and what blocks' `TaskNode.PrimitiveTask` does. Same concept, different names, now unified.

A **compound** PlanItem is a container of other PlanItems. It has:
- **children** — the PlanItems it contains (primitive or compound)
- **planningStrategy** — how to dispatch those children (resolved by name)
- **completionSemantics** — when this node is "done" (all, M-of-N, first-wins)
- **dispatchMode** — how this PlanItem is activated (see below)
- **entryCondition** — the trigger expression (required when choreographable)
- **exitCondition** — optional, evaluated for early completion
- **repeatable** — iteration support

**Plan definition vs execution state.** `PlanItem` is the immutable plan definition — what work needs to happen and how it should be dispatched. Execution state (status, timestamps, CAS transitions) is tracked externally by `CasePlanModel`, keyed by PlanItem ID. This separates two concerns currently conflated in the existing `PlanItem` class:

- **Plan definition** (sealed, immutable): task identity, executor reference, dispatch mode, entry conditions, parent-child relationships
- **Execution state** (mutable, CasePlanModel-managed): `TaskStatus` lifecycle (PENDING -> RUNNING -> COMPLETED/FAULTED/...), created/activated timestamps, CAS-guarded transitions

The current `PlanItem`'s `AtomicReference<TaskStatus>`, `tryMarkRunning()`, `markCompleted()`, etc. move to an execution state tracker within `CasePlanModel`. The plan definition type becomes a sealed interface with record variants — immutable by construction.

**Compound PlanItems are persistent.** Both primitive and compound PlanItems are stored in `CasePlanModel`. A compound PlanItem replacing a Stage is a first-class persistent plan node with its own lifecycle. This is distinct from `TaskNode.CompoundTask` (blocks), which is an HTN decomposition *input* — consumed during decomposition to produce PlanItems. The input is ephemeral; the resulting PlanItems are persistent.

**Declared children vs runtime children.** The Compound record's `children` field is the *declared* set — the children specified at plan definition time (from the case definition, YAML DSL, or initial decomposition). Runtime plan modifications (HTN decomposition creating new children, `BlackboardPlanConfigurer` adding bindings during initialization, repeatable compounds resetting with fresh children) are tracked by `CasePlanModel`'s parent-child index, not by mutating the immutable Compound record. `CasePlanModel` maintains a `Map<String, Set<String>>` of compound ID -> child IDs that starts from the declared children and evolves at runtime. Queries like "what are this compound's children?" go through `CasePlanModel.getChildrenOf(compoundId)`, which merges declared and runtime children.

**Implicit root compound.** Every case has a root compound PlanItem that serves as the top-level container. It is never declared by the user — the runtime creates it transparently when the case starts. The case definition's `planningStrategy` field (currently resolved as the single per-case strategy by `PlanningStrategyLoopControl`) becomes the root compound's strategy name. If null, the root compound uses `ChoreographyStrategy` — which is exactly the current behavior.

This eliminates the need for special-case handling of "free-floating" bindings (those not in any Stage). In the new model, all top-level bindings are children of the root compound. Per-compound dispatch treats the root compound like any other compound — no special cases. Simple cases never see or declare the root compound; it is infrastructure. Complex cases with nested compounds add them as children of the root.

The current code already implements this pattern implicitly: `PlanningStrategyLoopControl.select()` treats the entire case as a single scope with one strategy (line 154: `ctx.definition().getPlanningStrategy()`). The root compound formalizes what is already happening, making the per-compound dispatch model uniform from top to bottom.

**Resolves:** T4 (flat PlanItem), T7 (CMMN terminology — Stage becomes a compound PlanItem configuration)

### 2.2 Two dispatch modes

Every known model of execution control reduces to two archetypes, or a composition of both. This is not CaseHub-specific — it is an observation about the structure of coordination models in general.

**Naming note:** `DispatchMode` is the right name for this universal concept — it describes how work gets dispatched. The existing `io.casehub.engine.plan.DispatchMode` enum (`STREAMING`/`BARRIER`) must be renamed to `DagSchedulingMode` — it controls DagDriver wave scheduling, a narrower concern that should yield the name.

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

### 2.3 Composable strategies

Planning strategies are peers. They share one SPI, one resolution mechanism, and compose by nesting.

All are `NamedStrategy` implementations resolved per-compound-node at runtime via `StrategyResolver`. No `@Alternative`. No compile-time selection. Each compound PlanItem names its strategy. Different compounds in the same case can use different strategies.

A strategy can delegate to any other strategy by name. An HTN strategy decomposes and creates child compound PlanItems, each with their own strategy. Responsibility scopes to each compound node. No strategy needs to know what other strategies exist.

**Choreography IS a strategy** — specifically, `DefaultPlanningStrategy` (renamed to `ChoreographyStrategy` for clarity). It passes through all eligible bindings whose entry conditions are met. A compound PlanItem with `planningStrategy = null` or `planningStrategy = "choreography"` resolves to this strategy. "No strategy" is not a valid state — every compound has a strategy, and choreography is the default. This eliminates the ambiguity of `null` meaning either "error" or "choreography."

**Resolves:** T1 (compile-time binary), T9 (non-composable), T3 (hardcoded choreography)

### 2.4 The planning/technique line

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

### 2.5 Planning algorithms (under orchestration)

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
- ReAct is NOT a separate algorithm. It's the native `CONTEXT_CHANGED` evaluation loop: strategy evaluates state (Thought) -> dispatches worker (Action) -> context changes from output (Observation) -> repeat.
- The unnamed goal-directed algorithm already exists in one form: `LlmDecomposition` in blocks takes a goal and capabilities and produces a plan. LangChain4j calls their version `GoalOrientedPlanner` — graph search over agent I/O keys. Our capabilities already declare `inputSchema`/`outputSchema`.

### 2.6 Where Stages land

CMMN Stages were a distinct type: a named container with entry/exit sentries, autocomplete, and lifecycle. In the unified model, Stage is a configuration of a compound PlanItem:

- `planningStrategy` = choreography (fire children whose triggers are met)
- `completionSemantics` = ALL (autocomplete when all children complete)
- `entryCondition` = sentry expression
- `dispatchMode` of children = CHOREOGRAPHED

The programmatic Stage builder API (`Stage.builder()`) maps to compound PlanItems with this configuration. Internally, one type. There is no YAML `stages:` feature today — Stages are configured exclusively through the Java DSL and `BlackboardPlanConfigurer`.

Retiring Stage as a distinct type removes the temptation to model execution phases as Stages when they should be compound PlanItems. An HTN phase is not a Stage — it's a compound PlanItem with an HTN strategy. A parallel fan-out is not a Stage — it's a compound PlanItem whose strategy fires all children.

**Stage infrastructure mapping:**

| Stage infrastructure | Compound PlanItem equivalent |
|---|---|
| `StageStatus` (PENDING->ACTIVE->COMPLETED/TERMINATED/FAULTED) | Execution state in `CasePlanModel` — same lifecycle states on compound PlanItem |
| `StageLifecycleEvaluator` (entry/exit condition evaluation, activate/complete/terminate) | Compound PlanItem lifecycle evaluator — same logic, scoped to compound node |
| `StageAutocompleteEvaluator` (all required items terminal -> complete stage) | `CompletionSemantics.ALL` on compound PlanItem — generalized to support ALL, M-of-N, FIRST_WINS |
| `StageResetOutcomesCleaner` (clean outcomes on repeatable stage reset) | Compound PlanItem reset logic — clears child execution state on `repeatable` reset |
| Stage events (`StageCompletedEvent`, `StageActivatedEvent`, `StageTerminatedEvent`) | Compound PlanItem lifecycle events (same semantics, unified type) |
| `containedBindingNames` (design-time binding declarations) | Compound PlanItem `children` — children ARE the binding declarations |
| `containedPlanItemIds` (runtime plan item tracking) | Parent-child relationship in `CasePlanModel` — compound tracks its children by ID |
| `containedStageIds` (nested stages) | Compound PlanItem nesting — a compound child can itself be compound |
| `containedMilestoneIds` | Milestone containment moves to compound PlanItem |
| `requiredItemIds` | Derived from `CompletionSemantics` — ALL means all children are required |
| `repeatable` + `resetForRepetition()` | `repeatable` flag on compound PlanItem + reset logic in execution state tracker |
| Manual activation flag | `dispatchMode = ORCHESTRATED` — parent explicitly activates |
| Exit conditions | `exitCondition` on compound PlanItem (already in S2.1) |
| `parentStageId` on PlanItem | Parent compound PlanItem ID — natural tree relationship |

### 2.7 Orthogonality

Three independent axes. Changing one doesn't force changes in the others.

**Axis 1: Task structure** — How work is decomposed into PlanItems. Flat list, sequential chain, DAG, HTN tree. Determined by decomposition strategy.

**Axis 2: Dispatch strategy** — How a compound PlanItem's children are selected for execution. Choreography, sequential, HTN-aware. Independent of what the children are.

**Axis 3: Problem-solving technique** — How a worker solves its assigned task. Supervisor, debate, voting, loop, tool-use. Independent of how the task was planned or dispatched.

A sequential pipeline (axis 2) of workers each running debate techniques (axis 3) over an HTN-decomposed task tree (axis 1) composes without friction.

---

## Part 3 — Evidence: Execution Model Catalogue

### 3.1 Dispatch archetype mapping

Every execution model maps to orchestration, choreography, or a hybrid of both. No model requires a third archetype.

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

### 3.2 Unified execution model catalogue

One table covering all known execution models with their five-SPI decomposition, backend mapping, and implementation status.

| Execution Model | Domain | Dispatch Archetype | Routing | Decomposition | Activation | Aggregation | Termination | Backend | Status / Issue |
|---|---|---|---|---|---|---|---|---|---|
| Sequential | Workflow | Orchestrated | Ordered list | Flat sequence | On predecessor complete | Pass-through | Last step done | Flow | engine#484 (SequenceWorker) |
| Loop / Iterative Refinement | Workflow | Orchestrated | Same agent | None (repeat) | On iteration complete | Overwrite | Exit condition | Flow | engine#596 |
| Parallel / Fan-out | Workflow | Orchestrated | All eligible | None | Simultaneous | Collect-all / barrier | All complete | Flow | engine#597 |
| Conditional Routing | Workflow | Orchestrated | Predicate-selected | None | On dispatch | Pass-through | Selected path done | Flow | engine#598 |
| Supervisor | Multi-agent AI | Orchestrated | LLM-selected | None (one at a time) | On result | Pass-through | LLM says done | Custom | engine#101 |
| HTN | Classical AI | Orchestrated | Decomposition-driven | Hierarchical tree | On parent decomposed | Pass-through | All primitives done | Custom | engine#600 |
| GOAP | Classical AI | Orchestrated | Dependency-computed | Dependency graph | On predecessor complete | Pass-through | Goal keys present | Custom | engine#599 |
| Voting / Ensemble | Multi-agent AI | Orchestrated | All (same task) | None | Simultaneous | Majority / weighted | All votes in | Custom | engine#601 |
| Debate / Adversarial | Multi-agent AI | Orchestrated | Round-robin | None | On round complete | Convergence check | Judge decides | Custom | engine#602 |
| Contract Net | Multi-agent systems | Orchestrated | Bid-winning | None | On award | Pass-through | Task complete | Custom | engine#103 |
| Market / Auction | Multi-agent systems | Orchestrated | Bid-evaluated | None | On award | Pass-through | Task complete | Custom | engine#603 |
| Hierarchical Delegation | Org-chart | Orchestrated | Tier-delegated | Responsibility tree | On delegation | Upward synthesis | Executive satisfied | Custom | engine#606 |
| Blackboard | Classical AI | Choreographed | Input-ready | None | On state change | Merge to blackboard | Goal condition | Custom | Native (engine#445 Drools) |
| Event-driven Reactive | EDA | Choreographed | — | — | On event | — | — | Custom | Native engine binding model |
| P2P / Mesh | Multi-agent systems | Choreographed | Neighbour-driven | None | On dependency satisfied | Merge | All stable | Custom | engine#107 |
| Stigmergy | Swarm intelligence | Choreographed | Self-selected | None | On env change | Merge to env | Emergent | Custom | engine#604 |
| Swarm / Emergent | Swarm intelligence | Choreographed | Local-rule | None | On neighbour change | Emergent | Emergent | Custom | engine#605 |
| BDI agents | Cognitive agents | Hybrid | — | — | Desire triggers plan | — | — | Custom | — |
| Behaviour trees | Game AI | Hybrid | — | — | Condition + composite | — | — | Custom | — |
| Saga / compensation | Distributed systems | Hybrid | — | — | Forward orchestrated + compensation choreographed | — | — | Flow | — |
| ReAct | LLM agents | Hybrid | — | — | Orchestrated action + choreographed observation | — | — | Custom | — |
| Goal decomposition / Planning | Classical AI | Orchestrated | — | Goal-directed | — | — | Goal reached | Custom | engine#110, #208 |

---

## Part 4 — Five Compositional SPIs

Every execution model decomposes into decisions on five independent concerns.
This is CaseHub's architectural departure from langchain4j's single `Planner`
interface — the concerns are the primitives, not the coordinator pattern.

### 4.1 SPI definitions

| Concern | Question It Answers | Examples |
|---------|-------------------|----------|
| **Routing** | Which agent(s) handle this task? | First-match, LLM-selected, bid/auction, capability-matched, round-robin, trust-weighted |
| **Decomposition** | How is a task broken into subtasks? | Flat sequence, HTN tree, GOAP dependency graph, LLM-driven, none (primitive) |
| **Activation** | When does an agent fire? | On explicit dispatch, on state change, on event, on schedule, on input readiness |
| **Aggregation** | How are multiple results combined? | Collect-all (barrier), majority vote, scored, convergence/judge, first-to-complete, weighted merge |
| **Termination** | When is the work done? | Goal reached, convergence detected, max iterations, budget exhausted, all subtasks complete |

### 4.2 Generic signatures and platform mappings

| SPI | Generic Signature | Platform Mapping |
|-----|------------------|-----------------|
| `RoutingStrategy<T>` | `route(RoutingContext<T>) -> RoutingDecision` | Generalises `AgentRoutingStrategy` |
| `DecompositionStrategy<T>` | `decompose(TaskNode, DecompositionContext<T>) -> List<TaskNode>` | New — extends plan model |
| `ActivationRule<T>` | `shouldActivate(ActivationContext<T>) -> boolean` | Generalises `ContextChangeTrigger` |
| `AggregationStrategy<T, R>` | `aggregate(List<AgentResult>, AggregationContext<T>) -> AggregationResult<R>` | Maps to qhorus COLLECT/BARRIER |
| `TerminationCondition<T>` | `evaluate(TerminationContext<T>) -> TerminationDecision` | Generalises `Goal` + `GoalExpression` |

All SPIs are generic over `<T>` to align with `ContextBridge<T>`.
`RoutingContext<T>` carries `List<AgentCandidate>` with eidos `AgentDescriptor`
per agent for personality-aware, capability-health-aware routing.

### 4.3 Implication for blocks SPIs

The five compositional concern SPIs are **generic over the context type `<T>`**,
not hardcoded to CaseContext or a StateView wrapper. This allows:

- LangChain4j users to pass `AgenticScope` and keep type safety
- Drools users to pass `WorkingMemory` for rule-based activation
- Domain engineers to use typed records (`@CaseFile`) as context
- All of these to compose with the same routing, decomposition, activation,
  aggregation, and termination SPIs

### 4.4 The 16-row composition matrix

The unified catalogue in Part 3, section 3.2 serves as the definitive composition matrix — every execution model decomposed across all five SPIs plus backend and status. That table is not repeated here.

---

## Part 5 — AI Execution Forms

Each AI pattern maps to a combination of planning strategy (engine) and technique (blocks):

### 5.1 HTN Planning

An HTN-aware planning strategy calls `DecompositionStrategy` to produce children. Static decomposition uses predefined methods. LLM decomposition asks a language model. `DecompositionStrategy` and `TaskNode` promote to engine-api (planning concept). `LlmDecomposition` stays in blocks. `HtnBuilder` stays in blocks (it's a technique — composes the five-phase loop with decomposition).

### 5.2 ReAct / Tool-Use Loops

An agent that reasons, acts, observes, and repeats. Pure blocks technique: `LoopBuilder` with supervisor routing and goal-reached termination. Engine sees a single primitive PlanItem.

### 5.3 Multi-Agent Debate

`DebateBuilder` composes round-robin routing, collect-all aggregation, judge-convergence termination. Pure blocks technique.

### 5.4 GOAP (Goal-Oriented Action Planning)

Maps to the `DecompositionStrategy` SPI with a state-space search instead of method-based decomposition. The SPI is general enough: receives a compound task and context, returns a plan.

### 5.5 Voting / Ensemble

`VotingBuilder` composes parallel routing, majority-vote aggregation. Pure blocks technique.

### 5.6 Supervisor / Delegation

`SupervisorBuilder` composes first-match or LLM routing, pass-through aggregation, goal-reached termination. Pure blocks technique.

### 5.7 GOAP vs HTN

| Aspect | GOAP | HTN |
|--------|------|-----|
| Direction | Backward (goal -> start via dependency graph) | Forward (start -> goal via tree decomposition) |
| Structure | Flat action sequence via graph shortest-path | Hierarchical task tree with explicit decomposition |
| Planning | Pre-compute full path from current state to goal | Expand tree incrementally, one action at a time |
| Best for | Dependency-driven pipelines | Workflows with natural hierarchy and conditional branching |

---

## Part 6 — Platform Integration

### 6.1 AgentRef sealed interface with execution surfaces

An agent in the DSL abstracts over all platform execution surfaces. The
`AgentRef<T>` sealed type resolves to the appropriate runtime mechanism.

```java
sealed interface AgentRef<T> {
    record WorkerAgent<T>(Worker worker) implements AgentRef<T> {}
    record ChannelAgent<T>(UUID channelId, ChannelAgentHandler handler) implements AgentRef<T> {}
    record HumanAgent<T>(WorkItemCreateRequest template) implements AgentRef<T> {}
    record ExternalAgent<T>(Function<T, CompletionStage<AgentResult>> fn) implements AgentRef<T> {}
}
```

**Execution surfaces:**

| Platform Surface | AgentRef Variant | How Agents Run |
|-----------------|-----------------|----------------|
| Engine Worker | `WorkerAgent` | `WorkerProvisioner` dispatches; JVM-local, Docker, remote |
| Qhorus Channel | `ChannelAgent` | Message posted to channel, response via projection |
| Work WorkItem | `HumanAgent` | Human task created, claimed, completed |
| External | `ExternalAgent` | Arbitrary async function (LangChain4j, REST, etc.) |

**Factory methods for ergonomics:**

```java
supervisor(chatModel)
    .agents(
        worker(reviewerWorker),
        worker(implementorWorker),
        human(WorkItemCreateRequest.builder()
            .title("Arbitration needed")
            .candidateGroups("senior-reviewers")
            .build())
    )
    .build()
```

This is where blocks' cross-cutting nature shows — an execution model can
mix engine workers, channel agents, and human tasks in the same composition.

### 6.2 ContextBridge protocol

Engine epic: [casehubio/engine#203](https://github.com/casehubio/engine/issues/203)

The `ContextBridge<T>` SPI makes the context passed between agents a user-domain
class. Workers declare their bridge; the engine applies it at scheduling time.
This follows the approach taken by Serverless Workflow and Quarkus Flow.

```java
interface ContextBridge<T> {
    T initialise(Object enclosingContext, Map<String, Object> inputMapping,
                 PropagationContext propagation);
    void onWrite(String key, Object value, Object enclosingContext);
    void complete(T context, Object enclosingContext,
                  Map<String, Object> outputMapping,
                  PropagationContext propagation);
}
```

#### Built-in bridges (planned)

| Bridge | Context Type | Use Case |
|--------|-------------|----------|
| `PlainMapBridge` | `Map<String, Object>` | Plain lambda workers |
| `AgenticScopeBridge` | LangChain4j `AgenticScope` | LangChain4j interop (engine#419) |
| `WorkingMemoryBridge` | Drools `WorkingMemory` | Drools rule evaluation (engine#446) |
| `SubCaseBridge` | CaseContext (child) | Sub-case spawning |
| `WorkflowContextBridge` | Flow context | Quarkus Flow workers |

#### Status

`ContextBridge<T>` is designed (#203) but not yet implemented — being prioritised
for immediate delivery. Cases currently use `CaseContext` directly. The blocks SPIs
are designed generic over `<T>` from the start so no retrofit is needed when
`ContextBridge<T>` lands.

#### Eight-step agent invocation cycle

The execution driver holds the `ContextBridge<T>`. Agent invocation cycle:

1. Bridge provides typed context `T` to the routing strategy
2. Routing selects agent(s)
3. Bridge serialises relevant state for agent input
4. Agent executes (via platform surface)
5. Agent result returns
6. Bridge applies result to typed context
7. Termination condition evaluates against updated context
8. Loop (orchestration) or wait for next event (choreography)

The SPIs never touch raw CaseContext or raw AgenticScope — the bridge is the
single integration point.

#### State interoperability via ContextBridge

Engine's `ContextBridge<T>` (engine#203) bridges typed context between
execution environments:

| Bridge | Context Type | Direction |
|--------|-------------|-----------|
| `AgenticScopeBridge` | langchain4j `AgenticScope` | CaseHub <-> langchain4j |
| `WorkingMemoryBridge` | Drools `WorkingMemory` | CaseHub <-> Drools |
| `PlainMapBridge` | `Map<String, Object>` | CaseHub <-> plain workers |
| `SubCaseBridge` | CaseContext (child) | CaseHub <-> sub-cases |
| `WorkflowContextBridge` | Flow context | CaseHub <-> Quarkus Flow |

`AgenticScopeBridge` (engine#419) makes `AgenticScope` writes visible to
CaseHub's stage gating, goal evaluation, and EventLog — without copying state.
All writes route through the provider abstraction regardless of originating
framework.

#### Related issues

- engine#419 — CaseContextProvider SPI for AgenticScope interop
- engine#446 — WorkingMemoryBridge for Drools
- engine#203 — ContextBridge protocol epic
- engine#201 — Adaptive execution architecture (parent)

### 6.3 Eidos agent identity

Eidos provides structured agent identity that no peer framework offers.
LangChain4j has agent name + description string. CaseHub has a four-layer
identity model with vocabulary-backed personality traits and learned
performance history.

#### AgentDescriptor — Four Layers

| Layer | What It Contains | Vocabulary |
|-------|-----------------|------------|
| **Identity** | Who the agent is (name, tenancy, model) | -- |
| **Slot** | What role it fills | `CasehubSlotTerm` |
| **Capabilities** | What it can do — inputTypes, outputTypes, qualityHint, latencyHintP50Ms, costHint, epistemicDomains | Per-capability metadata |
| **Disposition** | Behavioural traits / personality | Belbin (9 team roles), DISC (4 types), Thomas-Kilmann (5 conflict modes), Conscientiousness, SVO |

#### Supporting infrastructure (6 SPIs)

| SPI | What It Does |
|-----|-------------|
| `AgentRegistry` | Discover agents by slot or capability; blocking + reactive |
| `CapabilityHealth` | Probe readiness: Ready, Degraded, Unavailable, EpistemicallyWeak |
| `VocabularyRegistry` | Cross-vocabulary equivalence (DISC <-> Conscientiousness <-> TK) |
| `SystemPromptRenderer` | Generate system prompts from descriptors (MARKDOWN, PROSE, A2A_CARD) |
| `AgentGraphStore` | Record task history and outcomes per agent |
| `CapabilitySpecializationStore` | Learned DECLINE/FAIL patterns for proactive routing exclusion |

#### Impact on blocks SPIs

**RoutingContext<T>** should carry `AgentDescriptor` per available agent.
Routing strategies can then use:

- **Slot vocabulary** — role-appropriate selection (Coordinator vs Specialist vs Evaluator)
- **Disposition axes** — personality-informed selection:
  - Belbin: Plant for creative work, Completer-Finisher for review
  - DISC: Dominant for decisive routing, Conscientious for analytical tasks
  - Thomas-Kilmann: Collaborator for debate, Competitor for adversarial review
- **Capability health** — filter agents that are Degraded or EpistemicallyWeak
- **Epistemic domains** — subject-matter confidence matching
- **Historical performance** — learned DECLINE/FAIL patterns from ledger

**AgentRef<T>** should optionally carry an `AgentDescriptor`. Worker-based
agents get their descriptor from `Worker.agentDescriptor()`. Channel and human
agents declare descriptors at composition time.

#### Platform advantage over LangChain4j

| Aspect | CaseHub (Eidos) | LangChain4j |
|--------|-----------------|-------------|
| Agent identity | 4-layer structured descriptor | Name + description string |
| Personality model | Belbin, DISC, Thomas-Kilmann vocabularies | None |
| Capability discovery | Registry with slot/capability queries | Static agent list |
| Health probing | Ready/Degraded/Unavailable/EpistemicallyWeak | None |
| Learned routing | DECLINE/FAIL pattern aggregation via ledger | None |
| System prompt | Generated from descriptor, format-specific | Manual string |
| Cross-vocab | Equivalence mapping across personality frameworks | None |

### 6.4 Event flow for choreography

The choreography driver subscribes to state changes via platform event sources:

| AgentRef Variant | Event Source | Mechanism |
|-----------------|-------------|-----------|
| `WorkerAgent` | Engine EventBus | `ContextChangeTrigger`, binding events |
| `ChannelAgent` | Qhorus channel | `ChannelProjection` message observation |
| `HumanAgent` | Work lifecycle | WorkItem status events (claimed, completed) |
| `ExternalAgent` | Callback | `CompletionStage` completion |

The choreography driver registers `ActivationRule<T>` instances against the
appropriate event source based on `AgentRef` type.

---

## Part 7 — Execution Backends

### 7.1 The two-backend model

Not all execution models are workflows. Some have fixed topology known at
definition time (sequential, parallel, loop, conditional). Others have
topology that emerges at runtime from state, guards, LLM decisions, or
reactive activation. These are fundamentally different execution problems.

CaseHub's compositional SPI layer (five concerns: routing, decomposition,
activation, aggregation, termination) sits above both backends. The DSL is
the same. The `ExecutionModel<T>` record is the same. The execution backend
differs based on what the pattern requires.

```
                    ExecutionModel<T>
                    (five SPIs + DSL)
                         |
              +----------+----------+
              |                     |
     Quarkus Flow Backend    Custom Driver Backend
     (workflow-shaped)       (runtime-adaptive)
              |                     |
   +----------+-----+    +--------+--------+
   |   Sequential   |    |   Supervisor    |
   |   Parallel     |    |   HTN           |
   |   Loop         |    |   GOAP          |
   |   Conditional  |    |   Blackboard    |
   |                |    |   Debate        |
   |                |    |   Voting        |
   |                |    |   P2P           |
   |                |    |   Stigmergy     |
   |                |    |   Swarm         |
   |                |    |   Market/Auction|
   |                |    |   Hierarchical  |
   +----------------+    +----------------+
```

### 7.2 Quarkus Flow backend

Use when the pattern has **fixed topology known at definition time** — the
sequence of steps, fork/join points, and conditional branches can be declared
before execution starts.

**What Flow provides that a custom driver cannot:**
- **Durability** — workflow state persists across restarts (DB, K8s)
- **Observability** — DevUI visualisation, Mermaid diagrams, full traces
- **Recovery** — automatic retries, saga compensation, checkpoint resume
- **Build-time validation** — topology and input schema checked at compile time
- **Standards compliance** — CNCF Serverless Workflow spec

**What Flow cannot express:**
- Guard-evaluated decomposition (HTN method selection at runtime)
- Dependency graphs computed from agent I/O declarations (GOAP)
- Reactive activation on arbitrary state changes (blackboard, stigmergy)
- LLM-decided next step (supervisor)
- Convergence detection across rounds (debate)
- Bid collection and evaluation (market/auction)

#### Pattern-to-Flow mapping

| Pattern | Flow Construct | Topology | Notes |
|---------|---------------|----------|-------|
| Sequential | `@SequenceAgent` / `agent()` steps | SEQUENCE | Agents execute in declared order |
| Parallel | `@ParallelAgent` / `fork()` | PARALLEL | Fork/join with aggregation |
| Loop | `@LoopAgent` / `forEach()` | LOOP | Exit condition evaluated per iteration |
| Conditional | `@ConditionalAgent` / `switchWhenOrElse()` | SEQUENCE + predicates | Predicate-based path selection |

These four patterns have direct langchain4j-agentic <-> Quarkus Flow equivalents.
Quarkus Flow generates Serverless Workflow definitions at build time from the same
`@SequenceAgent`, `@ParallelAgent`, `@LoopAgent`, `@ConditionalAgent` annotations
that langchain4j uses. The `FlowPlanner` bridges langchain4j's `Planner` interface
to Flow's workflow execution.

**CaseHub integration path:** Pattern builders (`sequence()`, `parallel()`,
`loop()`, `conditional()`) generate Serverless Workflow definitions rather than
driving an in-process loop. The five SPIs inform workflow construction:
- Routing -> agent ordering or conditional predicates
- Termination -> exit conditions on loops
- Aggregation -> fork/join result handling
- Activation -> not needed (workflow engine handles step sequencing)
- Decomposition -> not applicable (topology is fixed)

#### Build-time generation

Quarkus Flow scans langchain4j annotations at build time and generates
concrete `Flow` subclasses via Gizmo bytecode generation:

```
@SequenceAgent   -> GeneratedXxxAgenticFlow extends SequentialAgenticFlow
@ParallelAgent   -> GeneratedXxxAgenticFlow extends ParallelAgenticFlow
@LoopAgent       -> GeneratedXxxAgenticFlow extends LoopAgenticFlow
@ConditionalAgent -> GeneratedXxxAgenticFlow extends ConditionalAgenticFlow
```

Generated workflows are visible in DevUI before first execution. Input
schemas auto-generate from `@V("param")` annotations (JSON Schema Draft-7).

#### Runtime classes

| Class | Pattern | Key Mechanism |
|-------|---------|--------------|
| `SequentialAgenticFlow` | Sequential | Index-based agent execution |
| `ParallelAgenticFlow` | Parallel | Fork/join branches |
| `ConditionalAgenticFlow` | Conditional | `@ActivationCondition` predicates |
| `LoopAgenticFlow` | Loop | `@ExitCondition` evaluation per iteration |

Each has a `Runtime*` variant for programmatic (non-annotation) construction.

#### State flow

AgenticScope <-> Workflow Context mapping is bidirectional:
- `AgenticScope.state()` maps directly to Workflow Global Context
- Standard workflow tasks (JQ expressions, HTTP calls) can read/write AI memory
- No manual marshaling required

#### Manual DSL

For workflows not defined via annotations, the `agent()` step integrates
langchain4j agents as first-class workflow tasks:

```java
workflow("newsletter").tasks(
    agent("draft", drafter::write, Request.class),
    emitJson("ready", "review.required", Draft.class),
    listen("review", toOne(consumed("review.done"))),
    switchWhenOrElse(h -> ok(h), "send", "revise", Review.class),
    function("revise", editor::edit, Review.class).then("ready"),
    consume("send", draft -> mail.send(draft), Draft.class)
).build()
```

This mixes AI agents with HTTP calls, event listening, human-in-the-loop,
and conditional routing in a single durable workflow — something langchain4j's
standalone patterns cannot express.

### 7.3 Custom driver backend

Use when the pattern has **topology that emerges at runtime** — what happens
next depends on the current state, LLM decisions, reactive events, or
computational planning that cannot be pre-declared as a workflow graph.

**What the custom driver provides:**
- Runtime-adaptive control flow (the five SPIs drive execution dynamically)
- Reactive event-driven activation (ChoreographedDriver)
- Imperative loop with dynamic routing (OrchestratedDriver)
- Full platform integration (engine, qhorus, work, eidos) in the execution loop

**What the custom driver lacks vs Flow:**
- No built-in durability (must persist to CaseContext manually)
- No workflow visualisation (must build observability via ExecutionEventListener)
- No automatic recovery (must implement via FailurePolicy + EventLog)

#### Runtime-adaptive patterns

| Pattern | Why Not Workflow | Driver Type | Key SPI |
|---------|-----------------|-------------|---------|
| Supervisor | LLM decides next agent at each step | Orchestrated | Routing (LlmSelectedRouting) |
| HTN | Guard-evaluated hierarchical decomposition | Orchestrated | Decomposition (StaticDecomposition with methods) |
| GOAP | Dependency graph computed from agent I/O | Orchestrated | Decomposition (GoalOrientedDecomposition) |
| Blackboard | Agents fire when inputs appear in state | Choreographed | Activation (OnInputReady) |
| Debate | Multi-round convergence with judge | Orchestrated | Aggregation (ConvergenceCheck) + Termination |
| Voting | Parallel + aggregation strategy | Orchestrated | Aggregation (MajorityVote, WeightedVote) |
| P2P | Reactive activation on dependency satisfaction | Choreographed | Activation (OnInputReady) |
| Stigmergy | Indirect coordination via environment | Choreographed | Activation (OnStateChange) |
| Swarm | Self-organisation via local rules | Choreographed | Activation (local rules) + Termination (EmergentStability) |
| Market/Auction | Bid collection and evaluation | Orchestrated | Routing (BidEvaluatedRouting) |
| Hierarchical | Multi-tier delegation with synthesis | Orchestrated | Decomposition (HierarchicalDecomposition) + Aggregation (UpwardSynthesis) |
| Contract Net | Announce -> bid -> award protocol | Orchestrated | Routing (BidEvaluatedRouting) |

#### Borderline patterns

Some patterns could theoretically map to either backend:

**Voting** — structurally parallel (fan-out all agents, collect results). Could
use `@ParallelAgent` with a custom output aggregator. But the aggregation
strategy (majority, weighted, scored, unanimous) is the core value, and
Quarkus Flow has no built-in aggregation SPI. Custom driver is simpler.

**Supervisor** — could be modelled as a loop workflow where each iteration
calls an LLM router that returns the next agent name, then dispatches to it.
But the LLM routing decision is the defining characteristic, and embedding
it in a workflow step loses the natural expression. Custom driver.

**Debate** — could be modelled as a loop with agent(debater1), agent(debater2),
function(judge) per round. But convergence detection across rounds requires
state that spans iterations, and the round structure (2+ debaters + judge) is
richer than a simple loop body. Custom driver.

### 7.4 Why custom drivers are not redundant

The custom drivers (`OrchestratedDriver`, `ChoreographedDriver`) handle
patterns that no workflow engine can express:

- **HTN** — the task tree is expanded at runtime via guard evaluation.
  Which decomposition method fires depends on the current state. The
  resulting execution sequence is not knowable at definition time.

- **GOAP** — the dependency graph between agents is computed from their
  declared inputs/outputs. The shortest path to the goal state is a
  graph search result, not a declared workflow.

- **Supervisor** — the LLM decides which agent to invoke next based on
  accumulated context. Each step's routing is an LLM inference call,
  not a pre-declared path.

- **Blackboard/P2P/Stigmergy** — agents fire reactively when their
  activation conditions are met by state changes. There is no step
  sequence — the execution order emerges from the data.

- **Debate** — convergence detection requires evaluating all prior
  round results against a judge or metric. The number of rounds is
  not known in advance.

These patterns need the five SPIs driving execution dynamically at
runtime. The drivers are the mechanism; the SPIs are the abstraction.

### 7.5 Why langchain4j's standalone workflow patterns are redundant

langchain4j-agentic provides `SequentialPlanner`, `ParallelPlanner`,
`LoopPlanner`, and `ConditionalPlanner` as in-process Java orchestrators.
These are convenience implementations for quick starts — they run agents
in a loop with no durability, no recovery, and no observability beyond
`AgentMonitor`.

Quarkus Flow provides the same four patterns (`@SequenceAgent`,
`@ParallelAgent`, `@LoopAgent`, `@ConditionalAgent`) backed by CNCF
Serverless Workflow with full enterprise infrastructure. The DSL is
nearly identical. Mario Fusco built both.

For CaseHub:
- **Do not use** langchain4j's standalone workflow planners — they
  reimagine what Quarkus Flow already provides properly
- **Do use** Quarkus Flow for workflow-shaped patterns
- **Do use** langchain4j's `@Agent` interfaces for individual agent
  definition — they are clean, typed, and compose naturally
- **Do use** CaseHub's custom drivers for patterns Flow cannot express

---

## Part 8 — Comparison with LangChain4j

LangChain4j (1.17.0, June 2026) provides a `Planner` interface that all orchestration patterns implement. CaseHub's unified model takes a different architectural approach.

### 8.1 Planner interface

All patterns implement `Planner`:

```java
interface Planner {
    Action firstAction(PlanningContext ctx);    // optional
    Action nextAction(PlanningContext ctx);     // required
    boolean terminated();
    Map<String, Object> executionState();       // crash recovery
    void restoreExecutionState(Map<String, Object> state);
    AgenticSystemTopology topology();           // SEQUENCE | PARALLEL | STAR | LOOP | ROUTER
}
```

`Action` is sealed: `call(AgentInstance...)`, `noOp()`, `done()`, `done(result)`.

`FlowPlanner` in Quarkus Flow implements this interface, bridging langchain4j's
planning abstraction to Serverless Workflow execution.

### 8.2 Pattern catalogue

One merged table covering langchain4j patterns, CaseHub equivalents, and Quarkus Flow mappings.

#### Core module (`langchain4j-agentic`)

| Pattern | LangChain4j Class | Topology | CaseHub Equivalent | Category | Flow Equivalent |
|---------|---|---|---|---|---|
| Sequential | `SequentialPlanner` | SEQUENCE | Sequential planning strategy | Planning | `@SequenceAgent` |
| Parallel | `ParallelPlanner` | PARALLEL | Choreography dispatch mode (concurrent) | Dispatch mode | `@ParallelAgent` |
| Parallel Mapper | `ParallelMapperPlanner` | PARALLEL | — | — | Partial — needs custom |
| Loop | `LoopPlanner` | LOOP | Flow planning strategy | Planning | `@LoopAgent` |
| Conditional | `ConditionalPlanner` | SEQUENCE | Flow planning strategy (branching) | Planning | `@ConditionalAgent` |
| Supervisor | `SupervisorPlanner` | STAR | Blocks Supervisor technique (or HTN with LLM) | Technique | None |

#### Patterns module (`langchain4j-agentic-patterns`)

| Pattern | LangChain4j Class | Topology | CaseHub Equivalent | Category | Flow Equivalent |
|---------|---|---|---|---|---|
| GOAP | `GoalOrientedPlanner` | SEQUENCE | (unnamed) goal-directed planning strategy | Planning | None |
| Blackboard | `BlackboardPlanner` | STAR | Native engine (choreography) | Dispatch mode | None |
| Debate | `DebatePlanner` | STAR | Blocks Debate technique | Technique | None |
| Voting | `VotingPlanner` | PARALLEL | Blocks Voting technique | Technique | None |
| P2P | `P2PPlanner` | STAR | Choreography — `ContextChangeTrigger` | Dispatch mode | None |

#### In PR (not yet shipped)

| Pattern | PR | CaseHub Equivalent | Flow Equivalent |
|---------|-----|---|---|
| HTN | #5584 | TaskNode + DecompositionMethod + guard evaluation | None |
| AgentRegistry | #5551 | Eidos `AgentRegistry` | None |

### 8.3 HTN detail (LangChain4j)

- `TaskNode` — sealed: `PrimitiveTask` (agent ref + optional precondition/effect) or `CompoundTask` (name + decomposition methods)
- `DecompositionMethod` — guard (`Predicate<AgenticScope>`) + strategy
- `DecompositionStrategy` — `(AgenticScope, Map<Class<?>, AgentInstance>) -> List<TaskNode>`
- `LlmDecompositionStrategy` — LLM selects agents; supports recursive decomposition via `maxDepth`
- SHOP-style forward planning: effects applied during traversal, downstream guards see updated state

### 8.4 Structural differences

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

### 8.5 Three-layer integration architecture

CaseHub and langchain4j operate at three distinct levels. Each level has a
different integration strategy.

**Layer 1: Individual Agent Definition — langchain4j owns this**

langchain4j's `@Agent` annotation + proxy generation creates typed domain
interfaces that the LLM implements:

```java
interface Reviewer {
    @Agent("Review code for bugs and style issues")
    String review(@V("code") String code, @V("language") String language);
}

var reviewer = AgentServices.builder(Reviewer.class)
    .chatModel(model)
    .tools(lintTool)
    .build();

reviewer.review(code, "java");  // reads like domain code
```

Parameters map to `AgenticScope` state via `@V`. The proxy handles prompt
construction, tool invocation, and result extraction. This is about making
individual LLM calls look like typed method calls.

CaseHub does not replicate this — it uses langchain4j agents directly.

**Layer 2: Workflow Orchestration — Quarkus Flow owns this**

For workflow-shaped patterns, Quarkus Flow provides:
- Build-time annotation scanning (`@SequenceAgent`, etc.)
- Serverless Workflow definition generation via Gizmo bytecode
- `FlowPlanner` bridging langchain4j's `Planner` to Flow execution
- `AgenticScope` <-> workflow context bidirectional state mapping
- DevUI visualisation, durability, recovery

CaseHub's pattern builders for workflow-shaped patterns should target Flow
rather than reimplementing the orchestration loop.

**Layer 3: Runtime-Adaptive Orchestration — CaseHub owns this**

For patterns that workflow engines cannot express, CaseHub provides:
- Five compositional SPIs (routing, decomposition, activation, aggregation, termination)
- `OrchestratedDriver` (imperative loop) and `ChoreographedDriver` (reactive)
- Cross-platform `AgentRef` spanning engine workers, qhorus channels, human tasks
- Eidos personality-aware routing via `AgentDescriptor`
- Platform-grade audit via `ExecutionEventListener` -> EventLog + Ledger

This is where CaseHub's model handles patterns that neither langchain4j nor
Quarkus Flow can express — HTN decomposition with guard-evaluated methods,
GOAP dependency graphs, personality-matched routing, or human-in-the-loop
with SLA-governed work items.

### 8.6 Agent interoperability

A langchain4j `@Agent` proxy is a component in CaseHub compositions via
`AgentRef`:

```java
// langchain4j agent definition (Layer 1)
Reviewer reviewer = AgentServices.builder(Reviewer.class)
    .chatModel(model).build();

// CaseHub composition (Layer 3) using the langchain4j agent
supervisor(chatModel)
    .agents(
        AgentRef.external(ctx -> reviewer.review(ctx.getCode(), "java")),
        AgentRef.worker(implementorWorker),
        AgentRef.human(arbitrationRequest)
    )
    .build()
```

### 8.7 AgenticScope naming changes (as of langchain4j 1.17+)

| Old Name | Current Name | Package |
|----------|-------------|---------|
| Cognisphere | `AgenticScope` | `dev.langchain4j.agentic.scope` |
| CognisphereOwner | `AgenticScopeOwner` | `dev.langchain4j.agentic.internal` |
| CognisphereRegistry | `AgenticScopeRegistry` | `dev.langchain4j.agentic.scope` |

The rename also introduced persistence support:
- `AgenticScopeStore` — persistence SPI
- `AgenticScopePersister` — serialisation
- `AgenticScopeKey` — typed state keys
- `AgenticScopeSerializer` — JSON codec
- `ResultWithAgenticScope` — result + scope pair

### 8.8 Where CaseHub's model is stronger

- **Orthogonal dispatch modes composable per-node.** LangChain4j's P2P (choreography) and workflow (orchestration) are separate implementations. No per-node hybrid.
- **Compound PlanItems with nested strategies.** Arbitrary depth, each level can use a different algorithm. LangChain4j's planners are flat.
- **Plan graph as model, CaseInstance as runtime.** Clean separation. LangChain4j's `AgenticScope` mixes both.
- **Planning algorithms as peers with shared output format.** LangChain4j has separate Planner implementations with no shared plan representation.
- **Deterministic execution without LLM.** Static plans execute without LLM cost. LangChain4j's Supervisor requires LLM per decision.
- **Cost at scale.** LLM for decomposition ONCE, then deterministic execution. Not LLM call per decision x thousands of cases.
- **Non-AI cases.** Full spectrum from zero-AI human workflows to full-AI agent orchestration.
- **Durability and recovery.** Uniform, EventLog-based. Not pattern-specific.

### 8.9 Where LangChain4j is simpler

- Three-method `Planner` interface vs multiple SPIs
- Monolithic patterns are easier to understand in isolation
- `@Agent` proxy generation for typed domain interfaces
- Quarkus Flow integration for workflow-shaped patterns

### 8.10 The bar we must clear

LangChain4j's simplicity IS a strength. Our model wins ONLY if it is as simple as theirs for simple cases AND richer for complex ones.

- Hello World case with one worker: no ceremony, no compound PlanItems, no strategy resolvers visible.
- Complex case with HTN + mixed strategies: the full model is available but only surfaces when declared.
- A developer who only needs Sequential should never encounter the word "CompoundPlanItem" in their API surface, logs, or error messages.

Complexity must be layered and never leak. If simple cases force users to understand the full type system, the YAGNI argument wins and LangChain4j's simplicity is the better design.

### 8.11 What CaseHub adds that neither framework has

| Capability | langchain4j | Quarkus Flow | CaseHub |
|-----------|-------------|-------------|---------|
| **Distributed agents** | JVM-local + A2A | Workflow tasks | WorkerProvisioner (JVM, Docker, remote, human) |
| **Agent identity** | Name + description | None | Eidos 4-layer descriptor (slot, capability, disposition) |
| **Personality routing** | None | None | Belbin, DISC, Thomas-Kilmann vocabulary matching |
| **Capability health** | None | None | Ready/Degraded/Unavailable/EpistemicallyWeak probe |
| **Learned routing** | None | None | DECLINE/FAIL pattern aggregation via ledger |
| **Human tasks** | None | listen() + events | Work — first-class items, SLA, claim/escalate |
| **Structured channels** | None | None | Qhorus — typed channels, speech acts, commitments |
| **Compliance audit** | AgentMonitor | Workflow traces | EventLog + Ledger (EU AI Act Art.12) |
| **Scope control** | None | None | CMMN Stages — lifecycle-gated eligibility |
| **HTN decomposition** | PR #5584 | None | TaskNode + DecompositionMethod + guard evaluation |
| **Compositional SPIs** | Single Planner | Fixed topologies | Five independent concerns, any composition |

### 8.12 The three-layer stack

```
+-----------------------------------------------------+
|  CaseHub Application Layer                          |
|  (drafthouse, quarkmind, clinical, etc.)            |
|  Domain-specific wiring, agent definitions          |
+---------+-----------+------+------+-----------------+
|  CaseHub Blocks — Compositional Orchestration       |
|  Five SPIs, ExecutionModel<T>, pattern builders     |
|  Custom drivers for runtime-adaptive patterns       |
|  Flow integration for workflow-shaped patterns      |
|  Cross-platform AgentRef (engine+qhorus+work+eidos) |
+-----------------------------------------------------+
|  Platform Foundations                               |
|  +----------+----------+--------+--------+--------+ |
|  |  Engine  |  Qhorus  |  Work  | Ledger |  Eidos | |
|  | Cases,   | Channels,| Tasks, | Audit, | Agent  | |
|  | Context, | Messages,| SLA,   | Comply,| Identity|
|  | Goals,   | Speech   | Human  | Attest | Persona| |
|  | Stages   | Acts     | Route  |        | Health | |
|  +----------+----------+--------+--------+--------+ |
+-----------------------------------------------------+
|  External Frameworks                                |
|  +------------------+---------------------------+   |
|  |  LangChain4j     |  Quarkus Flow             |   |
|  |  @Agent proxies, |  Serverless Workflow,      |   |
|  |  AgenticScope,   |  durability, recovery,     |   |
|  |  tools, memory   |  DevUI, build-time gen     |   |
|  +------------------+---------------------------+   |
+-----------------------------------------------------+
```

---

## Part 9 — Composition DSL

Two-level API following the DSL Style Guide (`parent/docs/DSL-STYLE-GUIDE.md`):
pre-composed pattern builders for the 80% case, compositional builders for custom.

### 9.1 Pre-composed pattern builders (80% case)

Each named pattern provides defaults for all five concerns. Override only
what differs from the default.

```java
// Supervisor — defaults: routing=llmSelected, activation=onResult,
//   decomposition=none, aggregation=passThrough, termination=llmDecides
supervisor(chatModel)
    .agents(reviewer, implementor, arbitrator)
    .terminate(goalReached(".review.complete"))
    .build()

// Debate — defaults: routing=roundRobin, activation=onRoundComplete,
//   aggregation=convergenceCheck, termination=judgeDecides
debate()
    .debaters(critic, advocate)
    .judge(arbitrator)
    .maxRounds(5)
    .convergence(judgeDecides())
    .build()

// Voting — defaults: routing=allEvaluators, activation=simultaneous,
//   aggregation=majorityVote, termination=allVotesIn
voting()
    .evaluators(analyst1, analyst2, analyst3)
    .strategy(majorityVote())
    .build()

// HTN — defaults: routing=decompositionDriven, activation=onParentDecomposed,
//   aggregation=passThrough, termination=allSubtasksComplete
htn()
    .task(compound("analyse",
        method(when(".hasData"), sequence(extract, transform, load)),
        method(when(".needsCollection"), sequence(collect, validate, extract))
    ))
    .terminate(allSubtasksComplete())
    .build()

// Loop — defaults: routing=sameAgent, activation=onIterationComplete,
//   aggregation=overwrite, termination=exitCondition
loop()
    .agents(scorer, editor)
    .maxIterations(5)
    .exitCondition(ctx -> ctx.readState("score") >= 0.8)
    .build()
```

### 9.2 Compositional builders (20% custom case)

Two entry points matching the two execution drivers:

```java
// Orchestrated — imperative driver, coordinator decides next action
orchestration("custom-pipeline")
    .route(bidEvaluated(costWeighted()))
    .decompose(goapGraph(agentCapabilities))
    .activate(onPredecessorComplete())
    .aggregate(collectAll())
    .terminate(goalReached(".pipeline.done"))
    .build()

// Choreographed — reactive driver, agents fire on state changes
choreography("reactive-mesh")
    .route(capabilityMatched())
    .activate(onStateChange(".data.*"))
    .aggregate(mergeToContext())
    .terminate(emergentStability(5))
    .build()
```

### 9.3 Static factory imports

Each concern has its own factory class for static import:

```java
import static io.casehub.blocks.agentic.Routing.*;
import static io.casehub.blocks.agentic.Decomposition.*;
import static io.casehub.blocks.agentic.Activation.*;
import static io.casehub.blocks.agentic.Aggregation.*;
import static io.casehub.blocks.agentic.Termination.*;
```

### 9.4 Expression overloads (CaseHub convention)

Following the platform-wide three-way overload convention:

```java
.terminate(goalReached(".done == true"))                // JQ string
.terminate(goalReached(ctx -> ctx.isComplete()))         // typed predicate
.terminate(goalReached(evaluator))                       // evaluator instance

.activate(onStateChange(".data.ready == true"))          // JQ string
.activate(onStateChange(ctx -> ctx.dataReady()))         // typed predicate
```

### 9.5 Composability

Any pattern can be a component in another — following LangChain4j's model:

```java
var reviewLoop = loop()
    .agents(reviewer, editor)
    .exitCondition(ctx -> ctx.readState("score") >= 0.8)
    .build();

var pipeline = sequence()
    .agents(drafter, reviewLoop, publisher)
    .build();
```

---

## Part 10 — Type Boundaries

### 10.1 ExecutorRef vs AgentRef

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

### 10.2 What promotes to engine-api

| Type | Why | Redesign |
|---|---|---|
| `TaskNode<T>` (LeafTask, CompoundTask) | HTN task model | `ExecutorRef` replaces `AgentRef` |
| `DecompositionStrategy<T>` | Core HTN SPI | Return type -> `DagPlan<LeafTask<T>>` |
| `DecompositionMethod<T>` | Method selection | Minimal |
| `DecompositionContext<T>` | Decomposition context | `ExecutorRef` list replaces `RoutingCandidate` |
| `NoMethodMatchedException` | Shared exception | None |

### 10.3 Dependency direction

Blocks depends on engine-api, qhorus-api, work-api. Foundation modules do not
depend on blocks. Application repos (drafthouse, quarkmind, etc.) depend on blocks.

### 10.4 Layering

| Level | What Lives Here | Why |
|-------|----------------|-----|
| **engine-api** | Core primitives that belong alongside Goal, Stage, PlanItem | Pure orchestration vocabulary |
| **engine** | Pattern implementations needing runtime (EventLog, CaseContext versioning, persistence) | Runtime dependencies |
| **blocks** | Cross-cutting compositions spanning engine + qhorus + work + eidos | The composition layer |
| **application** | Domain wiring — hook methods, vocabularies, agent definitions | Like DebateChannelProjection today |

Primitives incubate in blocks, then push down to the owning foundation module
once the interface stabilises.

---

## Part 11 — What Changes

### 11.1 Structural changes

**Rename:** `casehub-engine-blackboard` -> `casehub-engine-planning`. Module: `casehub-engine-planning`. Package: `io.casehub.engine.planning`. Coordinate with trebleel before executing.

**Retire:** `ChoreographyLoopControl`. `PlanningStrategyLoopControl` becomes the only `LoopControl`. Choreography behavior via `ChoreographyStrategy` (renamed from `DefaultPlanningStrategy`, see S2.3).

**Retire:** `Stage` as a distinct type. Replaced by compound PlanItem configuration.

**PlanItem sealed hierarchy** (plan definition — immutable):

```java
sealed interface PlanItem permits PlanItem.Primitive, PlanItem.Compound {
    String id();
    String name();
    DispatchMode dispatchMode();

    record Primitive(
        String id, String name,
        ExecutorRef executor,
        DispatchMode dispatchMode,
        ExpressionEvaluator entryCondition   // required when CHOREOGRAPHED; null when ORCHESTRATED
    ) implements PlanItem {}

    record Compound(
        String id, String name,
        List<PlanItem> children,             // declared children; runtime additions via CasePlanModel
        String planningStrategy,             // resolved by name via StrategyResolver; null -> "choreography"
        CompletionSemantics completion,
        DispatchMode dispatchMode,
        ExpressionEvaluator entryCondition,  // required when CHOREOGRAPHED; null when ORCHESTRATED
        ExpressionEvaluator exitCondition,   // optional, evaluated for early completion
        boolean repeatable
    ) implements PlanItem {}
}
```

Execution state (status, timestamps, CAS transitions) is managed by `CasePlanModel`, not by `PlanItem`. See S2.1 for the plan/execution separation rationale.

**Per-compound strategy dispatch:** `PlanningStrategyLoopControl` groups eligible bindings by containing compound PlanItem, resolves each compound's strategy, delegates. The `PlanningStrategy` SPI gains a compound node parameter:

```java
Uni<List<Binding>> select(
    CasePlanModel plan,
    PlanExecutionContext context,
    PlanItem.Compound compound,     // the compound node this strategy is invoked for
    List<Binding> eligible);        // pre-filtered to this compound's children
```

Existing implementations (`ChoreographyStrategy`, `SequentialPlanningStrategy`) add the parameter — `compound` scopes their decisions to the containing node.

**Composable delegation:** `PlanningStrategy` gains access to `StrategyResolver` for sibling delegation.

**Binding-to-PlanItem mapping:** The current architecture has `Binding` as the central declaration — it carries trigger, condition, target, outcome policy. In the new model, a compound PlanItem's children replace the binding declarations within a Stage:

| Current (Stage + Bindings) | New (Compound PlanItem) |
|---|---|
| `Stage.containedBindingNames` | Compound PlanItem's `children` list |
| `Binding.name` | Child PlanItem `name` / `id` |
| `Binding.on` (Trigger) + `Binding.when` (guard) | Child PlanItem `entryCondition` (for CHOREOGRAPHED children) |
| `Binding.target` (CapabilityTarget, etc.) | Child Primitive PlanItem `executor` |
| Stage entry condition activating bindings | Compound PlanItem lifecycle evaluation |
| `PlanningStrategy.select()` on eligible bindings | Per-compound strategy dispatch on ORCHESTRATED children |

`Binding` itself remains as the runtime dispatch unit — `PlanningStrategyLoopControl` still produces `List<Binding>` for the engine's dispatch infrastructure. The compound PlanItem model structures the plan; bindings are the dispatch mechanism. Full binding migration design: casehubio/engine#TBD.

**CasePlanModel API evolution.** The current `CasePlanModel` interface has 30+ methods organized around mutable PlanItems and separate Stages. The unified model requires:

| Current API | New API | Rationale |
|---|---|---|
| `addPlanItem(PlanItem)` — stores mutable object | `registerPlanItem(PlanItem)` — stores immutable record | Plan definition is immutable |
| `getPlanItem(id)` -> live mutable object | `getPlanItem(id)` -> immutable snapshot | Callers cannot mutate through the returned object |
| `PlanItem.tryMarkRunning()` (caller mutates) | `tryTransition(id, from, to)` -> boolean | CAS-guarded state transitions owned by CasePlanModel |
| `getStatus()` on PlanItem object | `getStatus(id)` -> TaskStatus | Execution state queried from CasePlanModel, not from PlanItem |
| `addStage(Stage)` | `registerPlanItem(PlanItem.Compound)` | Stages are compound PlanItems |
| `getActiveStages()` | `getActiveCompounds()` | Compound lifecycle queries replace stage queries |
| `getPendingStages()` | `getPendingCompounds()` | Same |
| -- (no parent-child API) | `getChildrenOf(compoundId)` -> Set\<String\> | Runtime parent-child index (merges declared + dynamic) |
| -- | `getParentOf(planItemId)` -> Optional\<String\> | Reverse lookup for completion propagation |
| -- | `addChild(compoundId, PlanItem)` | Runtime plan modification (HTN decomposition, configurers) |
| Stage autocomplete evaluation | `evaluateCompletion(compoundId)` | CompletionSemantics-driven, replaces StageAutocompleteEvaluator |

The priority queue (`PriorityBlockingQueue<PlanItem>`) is replaced by priority ordering on the execution state tracker — priority is an execution concern, not a plan definition property. The `ConcurrentHashMap<String, PlanItem>` by-ID index remains but now stores immutable records. Execution state lives in a parallel `ConcurrentHashMap<String, PlanItemExecutionState>` with CAS-guarded transitions.

### 11.2 Redesign requirements

- **TaskNode uses ExecutorRef at SPI boundary** — `AgentRef extends ExecutorRef` (the subtype relationship is already implemented). Promotion to engine-api requires: (1) change `LeafTask.agent()` -> `LeafTask.executor()` returning `ExecutorRef`, (2) change `PrimitiveTask` and `PlannedTask` constructor parameters from `AgentRef` to `ExecutorRef`, (3) remove the `agent()` method from the promoted SPI (blocks subclasses can add it back). This is the actual work of promotion — the subtype relationship makes it possible, but the field-level migration is required.
- **DecompositionContext uses `List<? extends ExecutorRef>`** — replaces `List<RoutingCandidate>`. `RoutingCandidate` pairs `AgentRef` with `@Nullable AgentDescriptor` (routing metadata). At the engine-api SPI level, `ExecutorRef` is sufficient — plan-level decomposition doesn't need agent descriptors. Blocks strategies that need descriptors receive `AgentRef` instances (which ARE `ExecutorRef` via subtyping) and access `AgentDescriptor` through blocks-level APIs. No metadata loss — the concrete objects are unchanged, only the SPI-level type narrows.
- **`sequentialMerge` on DagPlan** — hard prerequisite, net-new implementation. `ExecutionPlan.sequentialMerge()` (blocks, line 146) serves as reference implementation. Must be written on `DagPlan`, tested, and verified against `StaticDecomposition`'s usage before `ExecutionPlan` can be retired.
- **HtnBuilder stays in blocks** — it's a pattern builder extending `AbstractPatternBuilder`, not planning infrastructure.
- **`TaskNode.CompoundTask` is ephemeral; compound PlanItems are not.** `CompoundTask` is an HTN decomposition *input* — consumed by `DecompositionStrategy` to produce a plan. The decomposition process creates PlanItems (both primitive and compound) that ARE persistent in `CasePlanModel`. The compound PlanItem replacing a Stage is a first-class persistent plan node. `CompoundTask`'s methods and name are recorded in EventLog metadata for audit.
- **DagPlan<T> type alignment** — `DagPlan<T>` remains generic. In the planning path, `T` is instantiated as `LeafTask<ContextType>`, yielding `DagPlan<LeafTask<ContextType>>`. `DagNode<T>` wraps `T` directly as payload — `DagNode<LeafTask<ContextType>>` carries the leaf task. `ExecutionNode<T>` wraps `LeafTask<T>` explicitly; when `ExecutionPlan` is retired, this wrapping becomes `DagNode`'s generic parameter. No structural change to DagPlan — it stays general-purpose.
- **DagDriver stays standalone** — not used in planning dispatch path. Compound PlanItems dispatch children via strategy.
- **Persistence layer redesign** — the current persistence model (`PlanItemStore` SPI, `PlanItemRecord`, `PlanItemSaveRequest`, `PlanItemEntity`, `InMemoryPlanItemStore`, `PlanItemRestorer`) is flat: 11 fields covering both plan definition (bindingName, executorName) and execution state (status, createdAt) in a single record, with no compound support (no type discriminator, no parent-child relationship, no planning strategy, no completion semantics, no dispatch mode). The new model requires: (1) a type discriminator (Primitive vs Compound) on the persistence record, (2) compound-specific fields (planningStrategy, completionSemantics, dispatchMode, repeatable), (3) parent-child relationship persistence for runtime-created children (HTN decomposition, configurer additions), and (4) a design decision on whether plan-definition persistence and execution-state persistence remain co-located (denormalized, simpler migration) or split into separate stores (cleaner separation, matches domain model). Note: Stages are NOT persisted today — they are rebuilt by `BlackboardPlanConfigurer` from case definitions on each case access. Compound PlanItem persistence is therefore net-new work, not adaptation of existing Stage persistence. `PlanItemRestorer` must learn to reconstruct `PlanItem.Compound` variants.

### 11.3 What moves where

**Promote to engine-api:**

| Type | Why | Redesign |
|---|---|---|
| `TaskNode<T>` (LeafTask, CompoundTask) | HTN task model | `ExecutorRef` replaces `AgentRef` |
| `DecompositionStrategy<T>` | Core HTN SPI | Return type -> `DagPlan<LeafTask<T>>` |
| `DecompositionMethod<T>` | Method selection | Minimal |
| `DecompositionContext<T>` | Decomposition context | `ExecutorRef` list replaces `RoutingCandidate` |
| `NoMethodMatchedException` | Shared exception | None |

**Promote to engine-planning:**

| Type | Why |
|---|---|
| `StaticDecomposition` | Pure logic, `@DefaultBean` |
| `IdentityDecomposition` | Leaf task passthrough |
| HTN-aware `PlanningStrategy` (new) | Decompose -> PlanItems -> delegate to child strategies |

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

### 11.4 Phased migration (engine)

**Phase 0: Prerequisites.** Implement `sequentialMerge()` on `DagPlan` (reference: `ExecutionPlan.sequentialMerge()` in blocks). Write tests, verify `StaticDecomposition` compatibility. Verify engine-common transitively available to blocks.

**Phase 1: Retire ChoreographyLoopControl.** `PlanningStrategyLoopControl` becomes the only `LoopControl`. All existing tests pass unchanged.

**Phase 2: Rename blackboard -> engine-planning.** Coordinate with trebleel. Consumer repos update imports.

**Phase 3: PlanItem sealed hierarchy and Stage migration.** The largest phase — broken into sub-phases:

- **Phase 3a: Sealed hierarchy + execution state externalization.** Define `PlanItem` sealed interface with `Primitive`/`Compound` records. Create `PlanItemExecutionState` for CAS-guarded status transitions. Existing `PlanItem` class adapts to the new model with a compatibility layer during migration. Persistence schema changes: add type discriminator to `PlanItemRecord`/`PlanItemSaveRequest`/`PlanItemEntity`, add compound-specific columns (planningStrategy, completionSemantics, dispatchMode, repeatable). Compound PlanItem persistence is net-new — Stages are not persisted today (rebuilt by `BlackboardPlanConfigurer`), so this introduces a new persistence path. Update `PlanItemRestorer` to reconstruct `PlanItem.Compound` variants.
- **Phase 3b: CasePlanModel API redesign.** New state management (`tryTransition`, `getStatus`), parent-child tracking (`getChildrenOf`, `getParentOf`, `addChild`), compound lifecycle queries (`getActiveCompounds`). Replace mutable-object-return pattern with immutable-record-return + external state. Parent-child relationship persistence: `CasePlanModel`'s `Map<String, Set<String>>` compound-to-children index must be persistable for runtime-created children (HTN decomposition, configurer additions) to survive engine restart. Design decision: co-locate plan-definition and execution-state in a denormalized record (simpler migration) or split into separate stores (matches domain model separation). Either way, the `PlanItemStore` SPI expands to support compound save/restore.
- **Phase 3c: Stage infrastructure migration.** `StageLifecycleEvaluator` -> compound lifecycle evaluator. `StageAutocompleteEvaluator` -> `CompletionSemantics` evaluator. Stage events -> compound PlanItem lifecycle events. `StageResetOutcomesCleaner` -> compound reset logic. Milestone containment migration.
- **Phase 3d: Per-compound strategy dispatch + Stage builder API compatibility.** `PlanningStrategyLoopControl` per-compound dispatch with `PlanItem.Compound` parameter. Programmatic Stage builder API compatibility — `Stage.builder()` produces compound PlanItems with choreography strategy. Binding-to-PlanItem mapping in the builder layer.

**Phase 4: DAG plan unification (blocks).** `ExecutionPlan<T>` -> `DagPlan<T>`.

**Phase 5: HTN decomposition SPI.** Promote `TaskNode`, `DecompositionStrategy` to engine-api. HTN-aware planning strategy in engine-planning.

**Phase 6: Composable strategy wiring.** Strategy delegation via `StrategyResolver`. Per-subtask strategy overrides. Integration test: mixed strategies in one case.

### 11.5 Blocks-level implementation roadmap

#### Phase 1 (Complete) — SPI Framework

Five SPI interfaces, sealed decision types, `ExecutionModel<T>`,
`OrchestratedDriver`, `ChoreographedDriver`, eight pre-composed pattern
builders. All generic over `<T>` for `ContextBridge<T>` readiness.

#### Phase 2 — ContextBridge<T> integration

When engine#203 ships, activate the generic `<T>` with concrete bridges:
- `AgenticScopeBridge` for langchain4j agents
- `WorkingMemoryBridge` for Drools rules
- Typed domain records via `@CaseFile`

#### Phase 3 — Quarkus Flow backend

For workflow-shaped pattern builders (`sequence()`, `parallel()`, `loop()`,
`conditional()`):
- Generate Serverless Workflow definitions from `ExecutionModel<T>`
- Bridge to Quarkus Flow's `FlowPlanner` for execution
- Map CaseHub's aggregation/termination SPIs to Flow output handling
- Preserve the DSL — builders return `ExecutionModel<T>` which can
  target either backend

#### Phase 4 — Advanced SPI implementations

Runtime-adaptive pattern implementations:
- `LlmSelectedRouting` — supervisor (needs ChatModel)
- `GoalOrientedDecomposition` — GOAP (needs agent I/O declarations)
- `DispositionAwareRouting` — Belbin/DISC personality matching (needs eidos)
- `ConvergenceCheck` — debate (needs judge agent or metric)
- Agent dispatch for non-External variants (WorkerAgent -> WorkerExecutionManager,
  ChannelAgent -> MessageService, HumanAgent -> WorkBroker)

#### Phase 5 — LangChain4j agent interop

First-class `LangChain4jAgent` variant on `AgentRef`:
- Wraps langchain4j `@Agent` proxy for direct invocation
- Bridges `AgenticScope` state via `ContextBridge<T>`
- Extracts `AgentDescriptor` from agent metadata for routing

---

## Part 12 — Unresolved

### 12.1 Contradictions in the engine spec

**C1: RESOLVED — Choreography is a strategy.** `DefaultPlanningStrategy` is renamed to `ChoreographyStrategy`. A compound PlanItem with `planningStrategy = null` resolves to `"choreography"`. Choreography is the default strategy, not the absence of strategy. See S2.3.

**C2: Flow as planning strategy.** `casehub-engine-flow` currently positions Serverless Workflow as `FlowWorkerFunction` (worker execution tier). Promoting it to a peer planning strategy has implications: does `FlowWorkerFunctionHandler` become a `PlanningStrategy`? How does Flow's error handling interact with case lifecycle?

**C3: Selection criteria vs planning algorithms.** Orchestration has two orthogonal dimensions: selection criteria (HOW the strategy picks — priority, goal-driven, resource-aware) and planning algorithms (WHAT structure the plan has — sequential, flow, HTN). These are independent but not modeled separately.

**C4: Stage-to-compound-PlanItem structural transition.** Current Stage has `containedBindingNames` (strings) and `containedPlanItemIds` (strings). Compound PlanItem has `children` (PlanItem references). Different structures. `StageAutocompleteEvaluator` must be migrated. Programmatic Stage builder API (`Stage.builder()`) compatibility must be preserved — there is no YAML `stages:` feature (see S2.6).

### 12.2 Open questions from the engine spec

**Q1: engine#101 sub-issue coverage.** The agentic orchestration epic defines specific patterns. Must enumerate sub-issues and verify each maps to a planning strategy (engine) or technique (blocks). Not yet done.

**Q2: The unnamed goal-directed algorithm.** LangChain4j calls it `GoalOrientedPlanner`. Our capabilities already declare I/O schemas. LLM decomposition is one form. "Goal" collides with existing `Goal`/`GoalKind`/`GoalBasedCompletion`. No name agreed.

**Q3: CompletionSemantics type — outline.** Three variants:

- **ALL** — current `StageAutocompleteEvaluator` behavior. Compound completes when all children reach terminal state. Default.
- **M_OF_N(m)** — compound completes when `m` children reach terminal state. `m` is statically declared on the compound PlanItem. Remaining children are cancelled (status -> CANCELLED). Cancellation is best-effort for in-flight workers.
- **FIRST_WINS** — special case of M_OF_N(1). First child to complete triggers cancellation of siblings.

Completion propagates upward: when a compound completes, its parent re-evaluates its own CompletionSemantics. `GoalBasedCompletion` (case-level) is orthogonal — it evaluates case goals, not compound PlanItem completion. Detailed design: casehubio/engine#TBD.

**Q4: RESOLVED — Compound PlanItem detailed design.** Parent-child indexing: S2.1 "Declared children vs runtime children" + S11.1 CasePlanModel API table (`getChildrenOf`, `getParentOf`, `addChild`). Completion propagation: S12.2 Q3 outline + S11.1 API table (`evaluateCompletion`). Per-compound strategy resolution: S11.1 updated SPI signature with `PlanItem.Compound` parameter + per-compound grouping. Root compound: S2.1 "Implicit root compound."

**Q5: RESOLVED — Adversarial review findings.** TaskNode.LeafTask depends on AgentRef (resolution: `AgentRef extends ExecutorRef` — field migration scoped in S11.2). DecompositionContext depends on RoutingCandidate (resolution: use `List<? extends ExecutorRef>` — see S11.2). CasePlanModel parent-child support (resolution: compound PlanItems persistent in CasePlanModel; parent-child index tracks declared + runtime children — see S2.1). DagDriver synchronous vs blackboard reactive (resolution: don't use DagDriver in planning path).

### 12.3 Pre-unification open questions (from research)

These questions preceded the unified model. Annotations indicate which are now answered.

**RQ1: How do orchestration/choreography drivers compose — can an orchestrated Supervisor contain a choreographed Blackboard sub-step?**
*Answered by the unified model.* Yes. A compound PlanItem with orchestration strategy can contain child compound PlanItems with choreography strategy. Each compound node independently resolves its own strategy. Nesting is arbitrary. See S2.3 (composable strategies) and S2.7 (orthogonality).

**RQ2: Which SPIs push down to engine-api vs stay in blocks permanently?**
*Answered.* `DecompositionStrategy`, `TaskNode`, `DecompositionMethod`, `DecompositionContext` promote to engine-api (planning concern). Aggregation, Termination, Activation, Routing stay in blocks (technique concerns). See S11.3.

**RQ3: How does `TaskNode` relate to engine-api's `PlanElement` marker — should `PrimitiveTask` implement `PlanElement`?**
*Open.* `PlanElement` is a marker interface. `TaskNode` is an HTN input type consumed during decomposition. They serve different purposes and the relationship needs explicit design.

**RQ4: How does the DSL integrate with Quarkus Flow — can a `function()` step in FuncDSL invoke a blocks execution model?**
*Partially answered.* Workflow-shaped patterns target Flow as their backend (S7.2). The reverse direction (Flow invoking blocks) is part of the ContextBridge integration (engine#203). Full details pending.

**RQ5: How should disposition-aware routing compose with other routing strategies — is it a decorator, a filter, or a scoring factor?**
*Open.* Disposition-aware routing via Eidos is a design goal (S6.3 impact on blocks SPIs) but the composition mechanism (decorator, filter, or scoring factor) is not yet decided.

---

## Part 13 — Design Invariants and Principles

### 13.1 Design invariants (engine model)

1. **PlanItem is the graph.** Primitive nodes dispatch workers. Compound nodes dispatch according to their strategy. No other node type exists. Every case has an implicit root compound — the per-compound dispatch model is uniform from top to bottom.

2. **CaseInstance is the runtime.** Context, EventLog, lifecycle, tenancy. The PlanItem graph executes within it. One CaseInstance, arbitrarily deep nesting. SubCases only for true execution isolation.

3. **Two dispatch modes, declared not inferred.** Orchestrated ("now") and choreographed ("when"). Each PlanItem declares its `DispatchMode`. No third axis. Everything reduces to one or a composition of both.

4. **Strategies are peers.** Same SPI, same resolution, composable by nesting. No strategy is more fundamental than another. No compile-time alternatives.

5. **Planning produces structure. Techniques produce answers.** Engine owns planning. Blocks owns techniques. The line is: does this create task structure, or does this solve a task?

6. **ExecutorRef at the boundary.** Engine never imports blocks types. Blocks extends engine types through subtyping. The dependency arrow points one way.

7. **Sequential stays simple.** Ordered list, nothing more. Need loops or conditionals? Use Flow.

8. **Complexity never leaks.** Hello World sees no compound PlanItems, no strategy resolvers, no dispatch mode enums. The full model surfaces only when declared.

### 13.2 Design principles (blocks integration)

1. **langchain4j for agent definition, CaseHub for agent orchestration.**
   Don't replicate proxy generation. Use `@Agent` interfaces.

2. **Quarkus Flow for workflow-shaped patterns, custom drivers for the rest.**
   Don't reimagine workflow infrastructure. Don't force non-workflow
   patterns into workflow shapes.

3. **The five SPIs are the abstraction layer above both backends.**
   The DSL and `ExecutionModel<T>` are backend-agnostic. Backend
   selection is an implementation detail of the builder or driver.

4. **ContextBridge<T> is the state integration point.**
   All state interop between CaseHub, langchain4j, Drools, and
   Quarkus Flow routes through the bridge. No framework-specific
   state access in SPIs.

5. **Platform capabilities compose, not duplicate.**
   Eidos routing, ledger audit, work tasks, qhorus channels — these
   are CaseHub's value. They compose into the five SPIs at the blocks
   level. Individual foundation modules don't know about orchestration.

---

## Related Issues

- engine#595 — Execution Capability Models epic (16 patterns)
- engine#596-#606 — Individual execution model issues
- engine#203 — ContextBridge<T> protocol
- engine#419 — CaseContextProvider for AgenticScope interop
- engine#446 — WorkingMemoryBridge for Drools
- engine#201 — Adaptive execution architecture (parent)
- engine#101 — LLM-driven planning (Supervisor)
- engine#103 — Capability bidding (Contract Net)
- engine#107 — Qhorus peer-to-peer
- engine#110, #208 — PlanExecutor, PlanSource SPI
- engine#484 — SequenceWorker
- engine#445 — Drools integration (Blackboard)
- blocks#4 — Refactor conversation package as debate composition
- blocks#6 — Extract AbstractExecutionDriver (driver duplication)
- blocks#7 — Populate ActivationContext fields in drivers
- blocks#8 — Batch minor findings from code review
- blocks#60 — Blocks migration spec
