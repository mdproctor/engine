# ADR-0007 — Four Execution Models as First-Class Citizens

**Date:** 2026-04-29
**Status:** Accepted
**Refs:** casehubio/engine#200 (design), casehubio/engine#201 (implementation epic)

---

## Context

casehub-engine was built around the Blackboard Architecture — a choreography model where workers self-organise around a shared context without explicit ordering. This is well-suited to emergent, open-ended coordination.

As the platform matures, three additional execution models are needed:

- **Orchestration (rules-based):** a rules engine (Drools, DMN) evaluates case context and prescribes which workers execute and in what sequence.
- **Orchestration (workflow-based):** a workflow DSL (Serverless Workflow / quarkus-flow) sequences workers explicitly, with control-flow primitives (branches, loops, parallel, wait).
- **Planning:** a planner — human, LLM, or algorithmic — produces an ordered list of steps (`WorkRequest`s) and hands it to the engine for execution.

Each model is appropriate for a different class of problem. No single model covers all cases well. The question is whether casehub treats the others as second-class add-ons to choreography, or as co-equal citizens.

## Decision

**All four execution models are first-class citizens in casehub-engine.**

The CaseEngine is execution-model agnostic. How the next worker is selected is a routing decision made by one of four routers. The execution path — `WorkerScheduleEvent` → `WorkerScheduleEventHandler` → Quartz → `WorkerExecutionTask` — is shared by all four. The engine does not know or care which router chose the worker.

| Model | Router | Decision maker |
|---|---|---|
| Choreography | `CaseContextChangedEventHandler` | Binding conditions evaluated against CaseContext |
| Orchestration (rules) | `WorkOrchestrator` driven by rules engine output | Rules engine (Drools/DMN) |
| Orchestration (workflow) | `FlowWorker` steps dispatching via `WorkOrchestrator` | Serverless Workflow DSL |
| Planning | `PlanExecutor` (to be designed) consuming a `Plan` | Human or LLM planner |

A single case may use all four simultaneously. There is no hierarchy: choreography is not the default that others extend; workflow is not a special case of orchestration. Each is a first-class routing strategy.

## Consequences

**Shared execution path:** All four models produce the same EventLog entries (`WORKER_SCHEDULED`, `WORKER_EXECUTION_STARTED`, `WORKER_EXECUTION_COMPLETED`), the same `CaseLedgerEntry` chain, and the same lineage graph. Observability and auditability are identical regardless of which model selected the worker.

**Composability within a case:** A case can use choreography for emergent sections (context-driven), a workflow for a complex sub-process with explicit control flow, and a plan for a prescribed sequence. Transitions between models are transparent — they all produce `WorkerScheduleEvent`.

**FlowWorker must gain WorkOrchestrator access:** For workflow-based orchestration to be first-class, a quarkus-flow step must be able to dispatch a casehub worker and receive its result. This requires a `casehub-dispatch` function bridge (design in casehubio/engine#200). Until this is built, workflow-based orchestration is not fully first-class.

**PlanExecutor must be designed:** Planning requires a `Plan` data model (ordered `WorkRequest`s, or goal-decomposed steps), a `PlanExecutor` handler, and a `PlanSource` SPI (human input, LLM generation, algorithmic). Design work required before implementation.

**Rules-based orchestration:** This model composes existing primitives (`WorkOrchestrator.submit/submitAndWait`) driven by external rules engine output. No engine changes needed — the rules engine produces a sequence and the caller dispatches it. First-class in principle; the integration layer (rules engine → WorkOrchestrator) is a consumer concern.

## Alternatives Rejected

**Choreography as the canonical model, others as extensions.** Rejected because orchestration and planning represent fundamentally different coordination strategies, not refinements of the Blackboard pattern. Treating them as extensions would cause both design pressure (forcing them to conform to binding/context semantics) and conceptual confusion.

**Separate case types per execution model.** Rejected because a single case often needs multiple models for different stages — emergent exploration followed by prescribed execution, or workflow-driven processing with choreographed exception handling.

## Context Propagation Across Execution Model Boundaries

Context propagation across the chain `Case → FlowWorker → Sub-workflow → SubCase` requires distinguishing two orthogonal concerns:

**Data context** flows via explicit mapping expressions at each boundary. The developer controls what data enters and exits each layer:
```
CaseContext
  → inputSchema JQ        → FlowWorker input
    → flow internal state
      → sub-workflow inputExpressions  → sub-workflow state
        → SubCase inputMapping JQ      → child CaseContext
```

**Propagation context** (`traceId`, `causedByEntryId`, deadline) flows implicitly through every boundary with no developer mapping. Every child unit inherits the parent's `traceId` and sets its own `causedByEntryId` automatically.

**The output (up) path is the hard problem.** When a SubCase completes, its output currently flows back to the **parent case's** `CaseContext` via `outputMapping`. This is correct when the SubCase was spawned from a case-level binding. It is incorrect when the SubCase was spawned from inside a sub-workflow step — in that case the output should flow back into the **sub-workflow's working state** so subsequent steps can use it, not directly into the case context that the sub-workflow has already consumed.

**Consequence: every boundary needs both a down-mapping and an up-mapping**, and the engine must track where completed output should be routed. This is a context return-path problem: each unit of work needs a `returnTo` reference — "when I complete, route my output here." Currently only SubCaseBinding has `outputMapping`; sub-workflow steps have no defined return path for child output.

**Design principle:** the return path must be tracked at spawn time, not inferred at completion time. When a sub-workflow step starts a SubCase, it must record `returnTo: sub-workflow-step-N` alongside `SUBCASE_STARTED`. The completion listener routes output to the correct layer using this record, not by guessing from which case called which.

This principle extends to all four execution models. A plan step that starts a case must record where the output goes. A workflow step that dispatches a worker must record the same. Propagation context wires the lineage automatically; data context routing is always explicit.

## Lineage Constraint

**All four execution models must produce a complete, traversable causal graph.**

Every unit of work — case, sub-case, workflow run, sub-workflow invocation, plan step — must produce a `CaseLedgerEntry` node. The `causedByEntryId` field on each node points to the ledger entry that caused it. This creates a directed acyclic graph of causation that is fully traversable regardless of which execution model produced each node.

Current partial state:
- ✅ Case → SubCase: `SUBCASE_STARTED` ledger entry in parent; child's `CASE_STARTED` ledger entry carries `causedByEntryId` pointing to it
- ❌ Workflow step → dispatched worker/sub-case: quarkus-flow step emits no ledger entry; lineage breaks at the workflow boundary
- ❌ Sub-workflow invocation: has no ledger node; cannot serve as `causedByEntryId` anchor
- ❌ Plan step: no ledger node; cases started by plan steps have no recorded cause

The design implication: the `FlowWorker` ↔ `WorkOrchestrator` bridge (and sub-workflow invocations) must emit ledger entries before dispatching child work. Similarly, `PlanExecutor` must write a ledger entry for each plan step it begins.

The `traceId` on every ledger entry (already populated via `LedgerTraceIdProvider`) provides the distributed tracing connection. The `causedByEntryId` chain provides the causal lineage. Together they answer: *"show me everything that happened, in causal order, across all cases and sub-processes spawned by this workflow run."*

A sub-workflow that starts a new `CaseInstance` may or may not be treated as a `SubCase` in the CMMN sense — but it must always produce a ledger node that the child case's `CASE_STARTED` can point to. Whether to surface this in the `CasePlanModel` as a `SubCase` element is a separate decision.

## langchain4j-agentic Integration Constraint

**References (read before designing):**
- [langchain4j Agents — technical reference](https://github.com/langchain4j/langchain4j/blob/main/docs/docs/tutorials/agents.md)
- [Quarkus Flow ↔ AgenticScope integration](https://github.com/quarkiverse/quarkus-flow/blob/main/docs/modules/ROOT/pages/concepts-agentic-langchain4j.adoc)

### What AgenticScope is

`langchain4j-agentic` (`quarkus-langchain4j-agentic`) introduces `AgenticScope` — the shared mutable context between all agents in an agentic system. The architecture is two-layered: a `Planner` determines the sequence of agent invocations; the execution layer runs the agents. `AgenticScope` is the shared state between both layers.

Each agent declares:
- **`outputKey`** — string key (or `TypedKey<T>`) where it writes its result into `AgenticScope`
- **Preconditions** — input keys it reads (method parameters annotated `@V("key")`)
- **Postconditions** — the `outputKey` it produces

```java
CreativeWriter writer = AgenticServices
    .agentBuilder(CreativeWriter.class)
    .chatModel(model)
    .outputKey("story")         // writes to AgenticScope["story"]
    .build();

// type-safe variant
public static class StoryOutput implements TypedKey<String> {}

String story = scope.readState(StoryOutput.class);
```

The `Planner` interface:
```java
public interface Planner {
    default void init(InitPlanningContext context) {}
    default Action firstAction(PlanningContext context) { return nextAction(context); }
    Action nextAction(PlanningContext context);
    // Action is either call(agents) or done()
}
```

`PlanningContext` carries current `AgenticScope` state, available agents, and prior execution results. The `GoalOrientedPlanner` builds a dependency graph from agents' preconditions/postconditions automatically — identical in structure to casehub's binding evaluation graph.

`AgentListener` provides non-invasive observation hooks:
```java
.listener(new AgentListener() {
    void beforeAgentInvocation(AgentRequest req) {}
    void afterAgentInvocation(AgentResponse resp) {}
    void onAgentInvocationError(AgentInvocationError err) {}
})
```

### What Quarkus Flow already does

Quarkus Flow is an AoT compiler: at build time it scans `@RegisterAiService` interfaces and translates agentic annotations (`@SequenceAgent` → linear task sequence, `@ParallelAgent` → fork/join) into CNCF Serverless Workflow definitions. At runtime it executes through the CDI proxy of the annotated method.

Critically: Quarkus Flow treats `AgenticScope` as a first-class citizen by implementing an **AgenticScope-aware workflow data model**. It intercepts the underlying `AgenticScope` created by langchain4j and maps `AgenticScope.state()` directly to its Global Context (the workflow's working data document). Standard workflow tasks and jq expressions can read/write variables directly into the AI's memory scope — zero manual marshaling. The state is one document, not two.

### What this means for casehub

**The structural alignment is exact:**

| langchain4j-agentic | casehub-engine |
|---|---|
| `AgenticScope` (shared mutable state) | `CaseContext` (shared blackboard) |
| `outputKey` / `TypedKey<T>` | capability `outputSchema` JQ keys |
| Agent preconditions (`@V("key")` params) | capability `inputSchema` JQ expressions |
| Agent postconditions (`outputKey`) | capability `outputSchema` keys |
| `Planner.nextAction(PlanningContext)` | `LoopControl.select(PlanExecutionContext, bindings)` |
| `GoalOrientedPlanner` dependency graph | binding evaluator execution graph |
| `Goal` (desired output keys) | `Goal` expression |
| `AgentListener` hooks | EventLog writer (WORKER_EXECUTION_STARTED/COMPLETED) |

**The three contexts are genuinely distinct — none is "the same as" another:**

- **`CaseContext`** — casehub's append-via-EventLog blackboard. Authoritative, immutable-history record of a case. Always present.
- **`WorkflowContext`** (Quarkus Flow Global Context) — working data document for a single workflow execution. Live JSON. Scoped to the FlowWorker run. Exists only when a FlowWorker is active.
- **`AgenticScope`** — langchain4j's internal state for a multi-agent system. Also tracks invocation sequences and error recovery state. Exists only when langchain4j agentic patterns are active.

These have different structure, different lifetimes, different concerns. Conflating any two is a design error.

**Quarkus Flow's mapping approach is correct.** `AgenticScope.state()` ↔ `WorkflowContext` is bidirectional projection — the right relationship between two execution-scoped working state models at the same level of abstraction. `WorkflowContext` → `CaseContext` happens once at FlowWorker completion via outputSchema JQ. This layering must be preserved.

**The required abstraction is a `ContextBridge` protocol** — a composable adapter that each execution model registers. This enables any external context model (AgenticScope, any future AI framework's context, a rules engine's working memory) to integrate with casehub without either model imposing its structure on the other:

```java
interface ContextBridge<T> {
    // Down: create this context from the enclosing context's current state
    T initialise(Object enclosingContext, Map<String, Object> inputMapping);
    
    // Optional write-through: when T writes a key, propagate up immediately?
    void onWrite(String key, Object value, Object enclosingContext);
    
    // Up: extract output and merge back into the enclosing context
    void complete(T context, Object enclosingContext, Map<String, Object> outputMapping);
}
```

Implementations: `WorkflowContextBridge` (casehub owns), `AgenticScopeBridge` (Quarkus Flow owns — it already does this internally), `SubCaseBridge` (casehub owns).

**The full three-level chain:**

```
CaseContext (parent case)
  ── inputSchema JQ ──▶  WorkflowContext  (FlowWorker)
                              ◀──▶ AgenticScope  (Quarkus Flow bridge, optional)
                              ── inputMapping JQ ──▶  SubCase CaseContext
                                                         ── inputSchema JQ ──▶  WorkflowContext
                                                                                     ◀──▶ AgenticScope (optional)
                                                         ◀── outputSchema JQ ──
                              ◀── outputMapping JQ ── (routes to WorkflowContext, NOT parent CaseContext)
  ◀── outputSchema JQ ──
```

At every boundary the bridge is explicit. The return-path rule applies: output from a SubCase spawned inside a FlowWorker step routes to the **WorkflowContext** (the enclosing context), not the root `CaseContext`. Only when the FlowWorker itself completes does its output reach `CaseContext`.

**Context model selection is worker-level, not case-level.** Within a single case, different workers may use different context models:

```
Case (CaseContext)
 ├─ Worker A: lambda          → no bridge → CaseContext directly (current behaviour)
 ├─ Worker B: FlowWorker      → WorkflowContextBridge → WorkflowContext
 │               └─ (optional) AgenticScopeBridge → AgenticScope
 ├─ Worker C: lc4j agent      → AgenticScopeBridge → AgenticScope
 └─ Worker D: rules worker    → WorkingMemoryBridge → working memory
```

The `ContextBridge` is declared on the `Worker` definition. The engine applies it when that worker is selected — the `CaseDefinition` does not fix the context model. This keeps the design adaptive: a FlowWorker and a plain lambda in the same case each get the context model appropriate to what they do.

A case-level default bridge is also supported as a convenience — useful when a case is entirely composed of one context type (e.g. an agentic pipeline where all workers use `AgenticScope`). Worker-level declaration overrides the case default. A plain lambda worker in an agentic case can opt out and use `CaseContext` directly with no bridge.

The `Worker` model gains an optional `contextBridge` field:
```java
Worker.builder()
    .name("classifier")
    .capabilities(classifyCapability)
    .contextBridge(new AgenticScopeBridge())  // null = use CaseContext directly
    .function(classifyFn)
    .build();
```

Null bridge is the default and is backward-compatible with all existing workers. The `WorkerScheduleEventHandler` applies the bridge when scheduling; `WorkerExecutionTask` receives the appropriately prepared context.

**Two improvements casehub should add over Quarkus Flow's current approach:**

1. **Context stack registration.** Quarkus Flow currently has no way to know it's nested inside a casehub SubCase. A `ContextBridge` registration point in casehub allows Quarkus Flow to register itself and the engine to compose the full stack without coupling.

2. **Selective write-through via `onWrite()`.** Currently WorkflowContext writes reach CaseContext only at FlowWorker completion. For observability, significant intermediate writes (agent outputs, plan steps completing) should optionally produce EventLog entries immediately. The `onWrite()` hook provides this without coupling the write source to casehub.

**`AgentListener` is the lineage capture hook.** Every `beforeAgentInvocation` writes `WORKER_EXECUTION_STARTED` to the case EventLog; every `afterAgentInvocation` writes `WORKER_EXECUTION_COMPLETED`. Agent invocations appear in the casehub audit trail. This requires no changes to langchain4j — listeners are standard API.

**casehub's `LoopControl` can implement `Planner`.** `LoopControl.select(ctx, bindings)` and `Planner.nextAction(PlanningContext)` perform the same computation over the same data. A `CasehubPlanner` implementing the langchain4j `Planner` interface and delegating to `LoopControl` makes casehub's binding evaluator a first-class langchain4j Planner — enabling agentic systems to use casehub's choreography and planning strategies directly.

**Quarkus Flow's AoT compilation is reusable.** The mapping of `@SequenceAgent` → linear sequence and `@ParallelAgent` → fork/join is already done. casehub's FlowWorker integration should consume these compiled Serverless Workflow definitions, not re-implement the compilation step.

**Sub-workflow `AgenticScope` follows the context return-path rule.** Quarkus Flow's Global Context mapping already enforces this: a sub-workflow's scope is initialised from the parent's working data and writes route back to it. casehub must not break this invariant — the `returnTo` record described above applies to `AgenticScope` boundaries too.

## Open Questions

- What is the precise `Plan` data model? (ordered `WorkRequest`s? goal graph? capability list?)
- Should `PlanExecutor` live in the engine module or in a separate `casehub-planning` module?
- How does the `FlowWorker` ↔ `WorkOrchestrator` bridge handle case WAITING semantics — does the flow step block, or does the flow suspend and resume?
- Rules-based orchestration: should the engine provide a `RulesOrchestrator` wrapper, or is it purely a consumer concern?
- Should sub-workflow invocations be surfaced as `SubCase` elements in the Blackboard plan model, or tracked only via ledger lineage?
- Who is responsible for setting `causedByEntryId` on a child case's first ledger entry — the spawning mechanism, the engine, or the ledger capture listener?
- Should `CaseContextAgenticScope` live in `casehub-engine` (as an integration bridge) or in a separate `casehub-langchain4j` module?
- Can casehub's `LoopControl` / binding evaluator serve as a langchain4j `Planner` implementation, or do the two models diverge enough to require separate implementations that are composed?
- When a langchain4j agent tool call dispatches to another casehub worker (via MCP or direct invocation), how does that tool call appear in the casehub EventLog / lineage?
