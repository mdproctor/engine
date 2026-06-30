# Hybrid Sync/Async Analysis — casehub-poc, engine, and blocks

**Date:** 2026-06-30
**Author:** Cross-repo analysis (casehub-poc Claude session)
**Scope:** Architectural comparison of three codebases and recommendations for achieving the hybrid orchestration+choreography design

---

## Purpose

In April 2026, two design specs were written to plan the merger of casehub-poc
(synchronous Blackboard/CMMN) and casehub-engine (async reactive choreography)
into a unified hybrid system. This document analyses what was planned, what was
actually built, and what remains — including the casehub-blocks patterns library
which has since emerged as a third relevant codebase.

---

## The Three Codebases

### casehub-poc (`~/claude/casehub-poc`)

Synchronous Blackboard architecture (Hayes-Roth 1985) with CMMN terminology.

**Control loop** (`CaseEngine.runControlLoop`):
```
while (caseFile.status == RUNNING) {
    for each PlanningStrategy:
        if activation condition matches: strategy.reason(casePlanModel, caseFile)
    topPlanItems = casePlanModel.getTopPlanItems(1)    // ONE at a time
    if empty and quiescent: status = WAITING; break
    for each planItem:
        taskDefinition.execute(caseFile)               // BLOCKING call
        newPlanItems = listenerEvaluator.evaluate(...)  // re-evaluate
    for each strategy with ON_TASK_COMPLETION: strategy.reason(...)
}
```

**Key components:**
- `CaseEngine` — blocking while-loop on a thread pool (`Executors.newCachedThreadPool`)
- `ListenerEvaluator` — evaluates TaskDefinition entry criteria (key presence + `canActivate()` Java predicate), produces PlanItems, detects quiescence
- `PlanningStrategy` — 4 activation conditions: `ON_NEW_PLAN_ITEMS`, `ON_CASE_FILE_CHANGE`, `ON_TASK_COMPLETION`, `ALWAYS`
- `TaskDefinition` — fuses declaration (entry criteria, producedKeys) and execution into one CDI bean
- `CasePlanModel` — agenda (PriorityBlockingQueue), focus area, strategy tracking, resource budget, stage management, milestone management
- `CaseFileItem` — per-key provenance (writtenBy, writtenAt, version)
- `ConflictResolver` — multi-writer coordination (LAST_WRITER_WINS, FIRST_WRITER_WINS, MERGE, FAIL)
- Cycle detection via DFS on entry criteria → producedKeys graph

**Strengths:** Deliberative reasoning, central control authority, rich control state, per-key provenance.

**Weaknesses:** Blocking threads during execution, no clean way to receive external stimuli (CloudEvents, signals, sub-case completions). `notifyAutonomousWork()` is a workaround that couples autonomous workers to engine internals.

### casehub-engine (`~/claude/casehub/engine`)

Async reactive choreography on Vert.x EventBus + Mutiny.

**Control loop** (`CaseContextChangedEventHandler.onCaseStateContextChangedEventHandler`):
```
CaseFile changes (any source) →
  CaseContextChangedEvent on Vert.x EventBus →
    rules(): evaluate binding trigger conditions (JQ/Lambda) →
      loopControl.select(planCtx, eligible) →
        publish WorkerScheduleEvent / SubCaseScheduleEvent / HumanTaskScheduleEvent →
          worker executes (async, non-blocking) →
            result written to CaseFile →
              CaseContextChangedEvent → cycle repeats
    goals(): evaluate goal conditions → GoalReachedEvent → completion
```

**Key components:**
- `CaseHubReactor` — case lifecycle (start, signal, cancel, suspend, resume)
- `CaseContextChangedEventHandler` — the reactive evaluation loop (~630 lines)
- `LoopControl` SPI — determines which eligible bindings fire
  - `ChoreographyLoopControl` (default) — fires ALL eligible for RUNNING cases
  - `PlanningStrategyLoopControl` (`@Alternative @Priority(10)`) — deliberative selection via blackboard module
- `ExpressionEngineRegistry` — pluggable evaluation (JQ + Lambda)
- `WorkerScheduleEventHandler` → `WorkerExecutionManager` → worker execution
- `EventLog` — full ordered event history with `seq` DB identity column
- Quartz integration — durable worker execution, survives restarts
- Agent routing — `AgentRoutingStrategy`, `AgentCandidateFactory`, capability health
- Worker provisioning — `ReactiveWorkerProvisioner` for on-demand capability

**Strengths:** Non-blocking, handles external stimuli naturally, durable execution, rich event history, agent routing and provisioning.

### casehub-blocks (`~/claude/casehub/blocks`)

Reusable agentic control patterns composing from engine, qhorus, and work primitives.

**Two execution drivers:**
- `OrchestratedDriver` — imperative loop: route → activate → dispatch → aggregate → terminate
- `ChoreographedDriver` — event-driven reactive: starts in WaitingForEvent, returns between cycles

**Five composable SPIs:**

| SPI | Interface | Purpose |
|-----|-----------|---------|
| Routing | `RoutingStrategy<T>` | Who handles the next task — `FirstMatch`, `RoundRobin`, `Sequential` |
| Activation | `ActivationRule<T>` | When to fire — `OnExplicitDispatch`, `MaxIterationsGuard` |
| Aggregation | `AggregationStrategy<T>` | How to combine results — `PassThrough`, `CollectAll`, `MajorityVote` |
| Termination | `TerminationCondition<T>` | When to stop — `GoalReached`, `MaxIterations` |
| Decomposition | `DecompositionStrategy<T>` | HTN task breakdown — `Identity`, `Static` |

**Eight built-in pattern builders:**
- `Supervisor` — oversight pattern
- `Sequence` — sequential execution
- `Loop` — iterative execution
- `Parallel` — concurrent execution
- `Voting` — multi-agent consensus
- `Debate` — adversarial reasoning
- `Conditional` — branching
- `HTN` — Hierarchical Task Network decomposition

**Key types:**
- `AgentRef` — sealed interface: Worker, Channel, Human, External, Composed
- `ExecutionState` — sealed: Idle, Running, WaitingForAgent, WaitingForEvent, Complete, Faulted, Cancelled
- `ExecutionResult` — sealed: Completed, Failed, Escalated, Cancelled
- `TaskNode<T>` — sealed: PrimitiveTask (agent + precondition + effect), CompoundTask (name + decomposition methods)
- `AggregationResult` — sealed: Resolved, Partial, Deadlocked

**Strengths:** Higher-level control patterns, composable SPIs, both orchestrated and choreographed drivers, HTN planning.

---

## What the Design Docs Planned

Two specs from April 2026 defined the hybrid approach:
- `docs/superpowers/specs/scratch-merge-design.md` (casehub-poc)
- `docs/superpowers/specs/2026-04-09-casehub-unified-design.md` (casehub-poc)

### Core insight

"Synchronous control loop vs async is a false dichotomy. The real question is two separate things:
1. Who decides what fires next? (Central PlanningStrategy vs decentralised choreography)
2. Does execution block threads? (Always no — non-blocking implementation)"

### Planned architecture

- **Choreography by default** — workers observe CaseFile via Bindings and self-trigger
- **Optional deliberative overlay** — PlanningStrategy for cases requiring central reasoning
- **Physically always non-blocking** — the control loop becomes a reactive event cycle
- **Nine-phase migration** — Expression abstraction → Binding model → Goals → EventLog → Async event cycle → Pluggable CaseFile → Sub-cases → YAML schema → Java DSL

---

## What Was Actually Built

### Successfully ported to engine (in `casehub-blackboard` module)

| Component | Status | Notes |
|-----------|--------|-------|
| `PlanningStrategy` interface | **Done** | `select(CasePlanModel, PlanExecutionContext, List<Binding>)` returning `Uni` |
| `CasePlanModel` + `DefaultCasePlanModel` | **Done** | Agenda, stages, milestones, PlanItem tracking |
| `PlanItem` | **Done** | Full lifecycle: PENDING → RUNNING → COMPLETED/FAULTED/CANCELLED |
| `PlanningStrategyLoopControl` | **Done** | `@Alternative @Priority(10)` — replaces ChoreographyLoopControl when blackboard on classpath |
| `Stage` + `StageStatus` + `StageLifecycleEvaluator` | **Done** | Nested stages, autocomplete, entry/exit criteria, binding gating |
| `BlackboardRegistry` | **Done** | Per-case CasePlanModel management with tenancy |
| `PlanItemCompletionHandler` | **Done** | Worker completion → PlanItem status tracking |
| `MilestoneAchievementHandler` | **Done** | Milestone lifecycle evaluation |
| `SubCaseExecutionHandler` + `SubCaseCompletionService` | **Done** | Full sub-case orchestration with M-of-N policies |
| `StageAutocompleteEvaluator` | **Done** | Stage completion when all required items terminal |
| `BlackboardPlanConfigurer` | **Done** | Per-case plan configuration hook |
| `ConflictResolver` | **Done** | In resilience module |
| `DeadLetterQueue` + `DeadLetterEventHandler` | **Done** | In resilience module |
| Integration tests | **Done** | BasicBlackboard, StageBlackboard, SequentialStages, MixedWorkers, SubCase (parallel, M-of-N, propagation context), LambdaEntryCondition, ExitCondition |

### The LoopControl SPI — the hybrid integration seam

This is the key architectural piece, and it works:

```
CaseContextChangedEventHandler
  → evaluates binding trigger conditions (JQ/Lambda)
  → collects eligible bindings
  → calls loopControl.select(planCtx, eligible)
      ├── ChoreographyLoopControl: return all eligible (pure choreography)
      └── PlanningStrategyLoopControl:
            ├── stage gating (only active-stage bindings pass)
            ├── implementation routing (capability-based worker selection)
            ├── PlanItem creation
            ├── planningStrategy.select(plan, ctx, eligible)
            ├── filter to dispatchable (prevent re-dispatch)
            └── index for completion tracking
  → publish schedule events for selected bindings
```

The `PlanningStrategyLoopControl` is activated by CDI alternative when `casehub-blackboard` is on the classpath. No configuration needed — pure classpath composition.

### NOT ported from casehub-poc

| Component | Gap | Impact |
|-----------|-----|--------|
| `ListenerEvaluator` | Not present in engine | Entry criteria evaluation is done by JQ/Lambda expressions in `CaseContextChangedEventHandler`. The *declarative key-based entry criteria model* (Set<String> of required keys) and the `canActivate()` predicate gate are not ported. `LambdaExpressionEvaluator` bridges the lambda gap but the model is different. |
| Real `PlanningStrategy` implementations | `DefaultPlanningStrategy` is a pass-through (returns all eligible, deduped) | No deliberative strategies exist. No priority reasoning, no focus tracking, no resource budget, no one-at-a-time sequential mode. The infrastructure is present but the intelligence is not. |
| `TaskDefinition` syntactic sugar | Not present | Java devs must use lower-level Worker+Binding+Capability model. The ergonomic CDI bean pattern from casehub-poc (declare entry criteria + execute in one class) is not available. Planned for `casehub-quarkus` module (NOT STARTED). |
| `CaseFileItem` per-key provenance | Not present | No writtenBy/writtenAt/version per key. EventLog provides ordered event history but not key-level provenance. |
| `putIfVersion` / optimistic concurrency per key | Not present | No fine-grained optimistic locking on individual CaseFile keys. |
| `PoisonPillDetector` | **Done** — `io.casehub.resilience.poison.PoisonPillDetector` + `PoisonPillWorkerExecutionGuard` | Sliding window failure tracking, quarantine, auto-release. Ported with tests. |
| `BackoffStrategy` | **Done** — `io.casehub.resilience.backoff.BackoffDelayCalculator` | Renamed but equivalent. |
| `TimeoutEnforcer` | **Done** — `io.casehub.resilience.timeout.CaseTimeoutEnforcer` | Deadline enforcement via `PropagationContext.deadline`. Ported with integration tests. |
| `IdempotencyService` | **Done** (different mechanism) | Quartz job key hashing + `casehub.idempotency.window` config + EventLog dedup. No standalone class but concern is covered. |
| `FlowWorker` | **Done** — `flow/` module | Event-driven (`FlowWorkerFunction`). Better than casehub-poc's polling. |
| `@CaseType` CDI qualifier | Not present | Planned for casehub-quarkus. See #614. |
| `ErrorInfo` structured errors | Not present | Structured error model for DLQ triage and API responses. See #615. |
| `CaseFileContribution` | Not present | Key-level contribution audit. EventLog is coarser. See #616. |
| `RetryState` attempt history | Not present | Explicit per-task retry history object. See #617. |
| `TaskOrigin` provenance | Not present | Execution origin metadata (binding dispatch vs signal vs schedule). See #618. |
| `CaseFile.onChange` per-key listeners | Not present | Simpler per-key change API. Replaced by EventBus (correct but less ergonomic). See #619. |
| Cycle detection | Not present | No DFS on entry criteria → producedKeys graph to prevent circular dependencies at registration time. |
| `casehub-quarkus` extension | NOT STARTED | CDI integration, build-time discovery, Dev Services, live reload. |
| `casehub-examples` | NOT STARTED | Needed before old casehub can be archived. |

### PlanningStrategy activation conditions — a design simplification

casehub-poc had four activation conditions (`ON_NEW_PLAN_ITEMS`, `ON_CASE_FILE_CHANGE`, `ON_TASK_COMPLETION`, `ALWAYS`). The engine's `PlanningStrategy.select()` is called once per context change cycle via `PlanningStrategyLoopControl.select()`. This is architecturally equivalent to `ON_CASE_FILE_CHANGE` — the reactive event cycle fires on every context change, so the strategy always has the opportunity to reason. The other conditions are implicit:
- `ON_NEW_PLAN_ITEMS` — the strategy sees new eligible bindings in each call
- `ON_TASK_COMPLETION` — completion causes a context change, which triggers re-evaluation
- `ALWAYS` — every context change is a re-evaluation

This simplification is valid in the reactive model. The four conditions were needed in the synchronous loop because the strategy needed to be told *why* it was being invoked. In the reactive model, the strategy just sees the current state and eligible bindings — the cause is irrelevant.

---

## How blocks Fits In

blocks sits above the engine in the dependency chain:

```
casehub-blocks
├── casehub-qhorus-api    (channels, commitments, speech acts)
├── casehub-work-api      (work items, task lifecycle)
└── casehub-engine-api    (cases, plans, routing)
```

### The key question: blocks patterns vs engine PlanningStrategy

blocks' five SPIs (Routing, Activation, Aggregation, Termination, Decomposition) map to the same concerns as the engine's PlanningStrategy — but at a different level of abstraction:

| Concern | Engine (PlanningStrategy) | Blocks |
|---------|--------------------------|--------|
| What fires next | `PlanningStrategy.select()` | `RoutingStrategy` + `ActivationRule` |
| When to stop | Case goal evaluation | `TerminationCondition` |
| How to combine results | Not modelled | `AggregationStrategy` |
| Task decomposition | Not modelled | `DecompositionStrategy` (HTN) |
| Execution model | `LoopControl` (Choreography vs PlanningStrategy) | `OrchestratedDriver` vs `ChoreographedDriver` |

blocks has concepts the engine doesn't:
- **Aggregation** — multi-agent result combination (MajorityVote, CollectAll, Deadlock detection)
- **HTN decomposition** — CompoundTask → DecompositionMethod → PrimitiveTask hierarchy
- **Debate/Voting patterns** — adversarial reasoning and consensus

The engine has concepts blocks doesn't:
- **CasePlanModel** — persistent control state (agenda, stages, milestones)
- **Stage lifecycle** — nested containers with entry/exit criteria and autocomplete
- **EventLog** — ordered event history for audit and replay
- **Durable execution** — Quartz-persisted jobs surviving restarts

### Two possible integration paths

**Path A — blocks patterns AS PlanningStrategy implementations:**
blocks' patterns (Sequence, Loop, Supervisor, Voting) could be implemented as `PlanningStrategy` subclasses that plug into the engine's `PlanningStrategyLoopControl`. The strategy's `select()` method would use blocks' RoutingStrategy, ActivationRule, and TerminationCondition internally to decide which bindings fire. The engine's reactive event cycle provides the execution substrate; the blocks pattern provides the decision logic.

Pros: Single execution model (engine's reactive cycle), no parallel control loops, CasePlanModel state and Stage lifecycle naturally available.

Cons: PlanningStrategy.select() returns `List<Binding>` — it can only filter/reorder bindings. It cannot express aggregation (waiting for multiple results before deciding), HTN decomposition (dynamically generating new bindings), or multi-round debate (injecting new evaluation cycles). The interface is too narrow for blocks' richer patterns.

**Path B — blocks as an orchestration layer ABOVE the engine:**
blocks' `OrchestratedDriver` / `ChoreographedDriver` use the engine's case lifecycle (start case, signal, query) as the execution substrate. A blocks pattern creates/manages one or more engine cases, coordinating them via signals and context changes. The engine handles durable execution, event logging, and stage lifecycle. blocks handles the higher-level control pattern.

Pros: Each layer does what it's good at. blocks can express aggregation, decomposition, and multi-round patterns that `PlanningStrategy.select()` cannot. No engine API changes needed.

Cons: Two control loops (blocks driver + engine reactor), coordination overhead, state lives in two places (blocks' ExecutionModel + engine's CasePlanModel).

**Recommendation: Both, at different scales.**

For single-case deliberative control (one case, multiple bindings, decide which fires next), a real `PlanningStrategy` implementation is the right answer. This is the gap identified above — `DefaultPlanningStrategy` is a pass-through, and the engine needs at least `SequentialPlanningStrategy` and `PriorityPlanningStrategy` to demonstrate the hybrid value.

For multi-case coordination patterns (supervisor overseeing multiple agent cases, voting across independent assessments, HTN decomposition spawning sub-cases), blocks is the right layer. These patterns are inherently above the engine — they coordinate cases, not bindings within a case.

The bridge between the two is the engine's sub-case model: a blocks `OrchestratedDriver` can manage a parent case that spawns child cases via `SubCaseTarget` bindings. The `SubCaseCompletionService` and `SubCaseGroupPolicy` (already in the engine) handle M-of-N completion semantics, which maps directly to blocks' `AggregationStrategy`.

---

## Recommendations — Priority Order

### 1. Write real PlanningStrategy implementations in engine

**What:** `SequentialPlanningStrategy` — selects one binding at a time, tracks completion via CasePlanModel, selects next on re-evaluation.

**Why:** This is the simplest strategy that proves the hybrid works end-to-end. The engine fires all eligible bindings, the strategy says "just this one", the result comes back via EventBus, context changes, the strategy picks the next one. casehub-poc's `getTopPlanItems(1)` pattern, non-blocking.

**Second:** `PriorityPlanningStrategy` — uses CasePlanModel's agenda with priority scoring. Demonstrates focus tracking and resource budget awareness.

**Where:** `blackboard/src/main/java/io/casehub/blackboard/control/`

**Interface already supports it:**
```java
public interface PlanningStrategy {
    String getId();
    String getName();
    Uni<List<Binding>> select(CasePlanModel plan, PlanExecutionContext context, List<Binding> eligible);
}
```

The `Uni` return type means strategies can do async I/O (EventLog queries, LLM calls) before deciding.

### 2. Bridge blocks patterns to engine sub-cases

**What:** Verify that blocks' `OrchestratedDriver` can drive an engine case via `CaseHubRuntimeImpl.startCase()` + signals. Map blocks' `AggregationStrategy` to engine's `SubCaseGroupPolicy`. Ensure blocks' `TerminationCondition` can observe engine goal events.

**Why:** blocks has the patterns (Supervisor, Voting, Debate, HTN). engine has the execution substrate (durable, audited, reactive). The integration is through sub-cases and signals — both already exist.

### 3. TaskDefinition syntactic sugar (casehub-quarkus)

**What:** A CDI bean pattern where developers declare entry criteria and execution in one class, and the framework generates Worker+Binding pairs at build time.

**Why:** This is what makes the hybrid accessible to Java developers who think in the Blackboard model. Without it, using the deliberative layer requires manually wiring Workers, Bindings, Capabilities, and a BlackboardPlanConfigurer.

**Depends on:** casehub-quarkus extension (NOT STARTED). This is the biggest remaining piece of work from the migration plan.

### 4. Consider which casehub-poc concepts are still needed

| Concept | Recommendation |
|---------|---------------|
| `ListenerEvaluator` | **Probably not needed.** The engine's ExpressionEngineRegistry (JQ + Lambda) combined with PlanningStrategyLoopControl covers most of what it did. The gap is cycle detection — worth porting as a registration-time validation, not as a runtime evaluator. |
| `CaseFileItem` per-key provenance | **Lower priority.** EventLog provides event-level provenance. Key-level provenance (who wrote which key when) matters for conflict resolution and audit but isn't blocking adoption. Port when ConflictResolver integration is needed. |
| `putIfVersion` optimistic concurrency | **Port with CaseFileItem.** These are companion concepts — per-key versioning enables optimistic concurrency. |
| Cycle detection | **Worth porting.** DFS on binding trigger conditions → worker produced keys. Registration-time validation prevents infinite loops. Small, self-contained, high value. |
| Remaining gaps | **See #612.** Six additional concepts tracked as individual issues: `@CaseType` (#614), `ErrorInfo` (#615), `CaseFileContribution` (#616), `RetryState` (#617), `TaskOrigin` (#618), per-key change listeners (#619). |

### 5. Validate the hybrid with a worked example

**What:** Build an end-to-end example that uses:
- Engine with `PlanningStrategyLoopControl` active (blackboard on classpath)
- A custom `PlanningStrategy` that does sequential execution
- Stages that gate binding activation
- Sub-cases for delegation
- blocks' `Supervisor` pattern orchestrating the top-level flow

**Why:** The hybrid architecture exists in pieces across three codebases. A worked example proves it composes correctly and identifies integration gaps.

---

## Reference: Source Design Docs

These documents in casehub-poc contain the original analysis:

| Document | Location | Content |
|----------|----------|---------|
| Unified Design | `docs/superpowers/specs/2026-04-09-casehub-unified-design.md` | Approved design spec — module structure, naming, phased migration |
| Scratch Merge Design | `docs/superpowers/specs/scratch-merge-design.md` | Working notes — detailed comparison tables, sync vs async analysis |
| Migration Plan | `docs/superpowers/specs/2026-04-14-casehub-engine-migration-plan.md` | Module status, gaps, priority order |
| Blackboard Design | `docs/superpowers/specs/2026-04-18-casehub-blackboard-design.md` | Blackboard module design |

## Reference: Key Files in Engine

| File | Role |
|------|------|
| `api/src/main/java/io/casehub/api/engine/LoopControl.java` | The hybrid SPI — select bindings to fire |
| `runtime/.../ChoreographyLoopControl.java` | Default — fires all eligible (pure choreography) |
| `blackboard/.../PlanningStrategyLoopControl.java` | Alternative — deliberative selection via CasePlanModel |
| `blackboard/.../PlanningStrategy.java` | Strategy interface — `select(plan, ctx, eligible)` |
| `blackboard/.../DefaultPlanningStrategy.java` | Pass-through (returns all, deduped) — needs replacement |
| `runtime/.../handler/CaseContextChangedEventHandler.java` | The reactive evaluation loop |
| `runtime/.../CaseHubReactor.java` | Case lifecycle (start, signal, cancel) |

## Reference: Key Files in Blocks

| File | Role |
|------|------|
| `src/.../pattern/Patterns.java` | Factory for 8 pattern builders |
| `src/.../ExecutionModel.java` | Composes 5 SPIs into one config |
| `src/.../OrchestratedDriver.java` | Imperative loop driver |
| `src/.../ChoreographedDriver.java` | Reactive event-driven driver |
| `src/.../decomposition/TaskNode.java` | HTN task hierarchy |
| `src/.../aggregation/AggregationResult.java` | Multi-agent result combination |
