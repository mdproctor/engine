# CaseHub Unified Design — Working Notes
> Scratch document. Updated incrementally during brainstorming. Will be compiled into final spec.
> Last revised: full systematic review of both codebases completed.

---

## Context

Two independent CaseHub implementations exist and must be merged into one.

| | **casehub** (`/Users/mdproctor/claude/casehub`) | **casehub-engine** (`/Users/mdproctor/dev/casehub-engine`) |
|---|---|---|
| Owner | Mark | Co-worker |
| Architecture | Blackboard (Hayes-Roth 1985) / CMMN | Reactive event-driven choreography |
| Core abstraction | `CaseFile` (typed key-value, pluggable interface) | `StateContext` (JSON document, pluggable interface) |
| Task dispatch | `TaskDefinition` (entry criteria keys + `canActivate()`) | `Worker` (lambda/SWF/File) + `DispatchRule` + `Trigger` |
| Worker execution | CDI bean `execute(caseFile)` or `FlowWorker` polling | Lambda, SWF Workflow, or File — via Quartz |
| Expression language | Java lambda (`canActivate`, change listeners); no JQ | JQ strings (conditions, sealed) + Java lambda (Worker functions) |
| Execution model | Synchronous loop, 1 task at a time | Async reactive (Vert.x EventBus + Mutiny + Quartz) |
| Persistence | SPI: InMemory + Hibernate (blocking JPA) | Hibernate Reactive only |
| Resilience | RetryPolicy, DLQ, PoisonPillDetector, Idempotency, ConflictResolver, TimeoutEnforcer | RetryPolicy only (Quartz retry TODO) |
| Lineage | `PropagationContext` (traceId + budget) + POJO object graph | None |
| Autonomous workers | Yes — `WorkerRegistry.notifyAutonomousWork()`, `TaskOrigin.AUTONOMOUS` | Implicit (pure choreography = self-triggering) |
| Control reasoning | `PlanningStrategy` (pluggable, 4 activation conditions) | None — pure choreography |
| Stage lifecycle | Full CMMN stages (nested, autocomplete, manual activation, entry/exit criteria) | None |
| Milestone | CMMN achievement marker (key presence → PENDING/ACHIEVED) | Named JQ predicate for observability — **DIFFERENT CONCEPT, same name** |
| Goal model | Not yet (issue #7 planned) | `Goal` + `GoalExpression` (allOf/anyOf) + `GoalKind` (SUCCESS/FAILURE) |
| Completion | Quiescence detection | `GoalBasedCompletion` or `PredicateBasedCompletion` (JQ) |
| Event history | None — per-key `CaseFileItem` provenance only | `EventLog` (full ordered sequence, `seq` DB identity column) |
| Durable execution | None | Quartz — persisted jobs survive restarts |
| Input/output mapping | None — TaskDefinition reads/writes CaseFile directly | `evalObjectTemplate()` mini-DSL + capability inputSchema/outputSchema |
| Triggers | Entry criteria only | `contextChange`, `cloudEvent`, `schedule` |
| CloudEvents | No | Yes |
| Per-key versioning | Yes — `putIfVersion`, `getKeyVersion`, `CaseFileItem` with writtenBy/writtenAt | No |
| Conflict resolution | `ConflictResolver` (LAST_WRITER_WINS, FIRST_WRITER_WINS, MERGE, FAIL) | Last-write-wins implicit |
| Cycle detection | Yes — DFS on entry criteria → producedKeys graph at registration | No |
| Schema format | None | YAML/JSON schema + codegen (jsonschema2pojo) |
| Serverless Workflow | Via `FlowWorker` polling bridge + `FlowWorkflowRegistry` | Native — `Worker.function(Workflow)` |
| Sub-cases | POJO graph modelled (`getParentCase/getChildCases`) — engine not yet wired | None (`CaseInstance` has no parent reference) |
| Module count | 5 modules | 4 modules (api, engine, schema, codegen) |

---

## Systematic Deep Review — Key Findings

### casehub-engine — what was missed in initial review

1. **Worker DOES support Java lambdas** — `Worker.builder().function(Function<StateContext, Map<String,Object>>)` is a plain Java lambda. The sealed `ExpressionEvaluator` is the JQ-only constraint for *conditions*, not for worker execution. Workers support three execution backends: lambda function, `Workflow` (SWF), `File` (workflow file path).

2. **`evalObjectTemplate()` is a POJO mini-DSL** — `StateContextImpl` has a full template evaluator that parses `{ key: .path, "quoted": "value", num: 123 }` syntax without JQ. Handles nested structures, JSON literals, dot-path traversal. This is the mechanism for capability `inputSchema` extraction and `outputSchema` merge — not JQ. A genuine POJO DSL.

3. **Quartz for durable execution** — workers are not executed directly. They are submitted as Quartz jobs persisted to the DB (`QRTZ_*` tables, Flyway-managed). If the server crashes mid-execution, Quartz resumes on restart. Idempotency key = SHA256(workerName + capabilityName + inputDataHash). casehub has nothing equivalent.

4. **Full event bus execution chain** — on every `CaseStateContextChangedEvent`:
   - Dispatch rules evaluated (JQ filter on context)
   - Eligible bindings → `WorkerScheduleEvent` published
   - `WorkerScheduleEventHandler` → extracts input via `evalObjectTemplate` → creates `EventLog(WORKER_SCHEDULED)` → submits to Quartz
   - Quartz job (`WorkflowExecutionTask`) → `EventLog(WORKER_EXECUTION_STARTED)` → executes worker → `EventLog(WORKER_EXECUTION_COMPLETED)` → merges output into StateContext → publishes `CaseStateContextChangedEvent`
   - Simultaneously: milestones evaluated, goals evaluated, `GoalReachedEvent` published if triggered
   - `GoalReachedEventHandler` queries `EventLog` for all `GOAL_REACHED` entries → evaluates `GoalExpression` → publishes `CASE_STATUS_CHANGED` if satisfied

5. **`CaseDefinitionRegistry` is a shim** — `CaseHubDefinition` may contain Java lambdas/agents (not serialisable). The registry holds in-memory references keyed by `CaseDefinition` JPA entity. Known limitation — the merge must address how YAML-defined cases (no Java code) and Java-defined cases (with lambdas) coexist in the same registry.

6. **Two completion models** — `GoalBasedCompletion` (success/failure `GoalExpression` — declarative) and `PredicateBasedCompletion` (raw JQ string — direct). Both fully implemented.

7. **EventLog `seq` column** — DB identity sequence, auto-generated on insert. Total ordering of all events per case. Foundation for event sourcing / replay. Not yet implemented as replay but the infrastructure is intentional and ready.

8. **`CaseState` in casehub-engine** — ACTIVE, COMPLETED, FAILED, SUSPENDED, TERMINATED, WAITING. Slightly different from casehub's CNCF OWL states (PENDING, RUNNING, WAITING, SUSPENDED, COMPLETED, FAULTED, CANCELLED). Needs alignment in the merge.

9. **YAML Worker schema is polymorphic** — Worker's `workflow` field is either a string reference OR an inline embedded SWF workflow document. `WorkerMarshaller` detects by presence of `do:`, `document:` fields at deserialisation. Clean polymorphism in the schema.

### casehub — what was missed or understated

1. **CMMN Stage lifecycle** — full `Stage` class: nested stages, autocomplete (when all required items complete), manual activation flag, entry criteria (Set<String> keys), exit criteria (Set<String> keys, triggers termination not completion), containment of PlanItems and sub-stages, `StageStatus` (PENDING, ACTIVE, SUSPENDED, COMPLETED, TERMINATED, FAULTED). Evaluation methods exist in `ListenerEvaluator` but not fully wired into the control loop. casehub-engine has nothing equivalent.

2. **casehub `Milestone` ≠ casehub-engine `Milestone`** — **name clash, different concepts**:
   - casehub: CMMN achievement marker. `achievementCriteria` = Set<String> keys. `MilestoneStatus` = PENDING/ACHIEVED. Tracked in `CasePlanModel`. Lifecycle concept.
   - casehub-engine: Named JQ predicate for observability. Fires `MilestoneReachedEvent` when condition is true. No lifecycle — a signal only.
   - These must be renamed or reconciled in the merge. See naming table.

3. **`TaskDefinition.canActivate(caseFile)`** — custom activation condition *beyond* entry criteria key presence. Entry criteria are necessary but not sufficient — `canActivate()` adds arbitrary Java logic as a second gate (default: true). More powerful than casehub-engine's JQ `when` guard on DispatchRule, which is only evaluated at trigger time not as a pre-condition.

4. **`TaskDefinition.producedKeys()`** — declared output keys (Set<String>). Used by `TaskDefinitionRegistry` for cycle detection: DFS graph where edges are entry criteria → produced keys. Prevents circular dependencies at registration time. `CircularDependencyException` thrown if cycle detected.

5. **`CasePlanModel`** — rich control state well beyond a simple agenda:
   - Agenda (prioritised `PlanItem` queue — `PriorityBlockingQueue`)
   - Focus area + rationale (what the engine is concentrating on)
   - Strategy tracking (which `PlanningStrategy` made the last decision)
   - Resource budget (extensible key-value for token budgets, cost caps, etc.)
   - General state (extensible key-value for strategy-specific data)
   - Stage management (all stages, active stages, root stages)
   - Milestone management (all milestones, pending, achieved)
   - casehub-engine has nothing equivalent.

6. **`PlanningStrategy` activation conditions** — 4 distinct trigger points: `ON_NEW_PLAN_ITEMS`, `ON_CASE_FILE_CHANGE`, `ON_TASK_COMPLETION`, `ALWAYS`. Strategies are invoked at precisely the right moment, not just "before each cycle". `DefaultPlanningStrategy` is a no-op baseline; real strategies subclass and override `reason()`.

7. **`CaseFileItem`** — every key in `CaseFile` is a versioned entry: value + version + writtenBy (TaskDefinition ID) + writtenAt + instanceId. Full key-level provenance. casehub-engine has no equivalent.

8. **`CaseFileContribution`** — immutable record of which TaskDefinition produced which keys and when. Additional audit trail at the key level (separate from CaseFileItem).

9. **`ConflictResolver`** — multi-writer coordination for concurrent writes to the same CaseFile key. Strategies: LAST_WRITER_WINS, FIRST_WRITER_WINS, MERGE, FAIL. Interface defined, not yet integrated. casehub-engine has no equivalent.

10. **Per-key optimistic concurrency** — `putIfVersion(key, value, expectedVersion)` throws `StaleVersionException` if version mismatch. `getKeyVersion(key)` for fine-grained read. Separate from entity-level Hibernate `@Version`.

11. **`CaseFile.onChange / onAnyChange`** — in-memory change listeners already exist on the interface. `onChange(key, Consumer<CaseFileItemEvent>)` and `onAnyChange(Consumer<CaseFileItemEvent>)`. This is the seed for event-driven re-evaluation — the async migration has a natural starting point.

12. **Sub-cases partially modelled** — `CaseFile.getParentCase()` / `getChildCases()` and `Task.getOwningCase()` / `getChildTasks()` already on the interfaces. POJO graph is there. The engine doesn't yet wire sub-case spawning or completion propagation.

13. **`FlowWorker`** — existing Quarkus Flow integration bridge. Polls `WorkerRegistry` for tasks every 5s, heartbeats every 20s. Looks up workflows by taskType in `FlowWorkflowRegistry`, executes via `FlowExecutionContext`, submits result via `WorkerRegistry.submitResult()`. Already connects casehub's request-response task model to Quarkus Flow — but via polling, not events.

14. **Control loop fires ONE task at a time** — `getTopPlanItems(1)`. Confirmed sequential-by-default. Concurrency would require changing this parameter + adding concurrency handling.

15. **Partial implementations** — Stage/Milestone evaluation in `ListenerEvaluator` not fully wired into CaseEngine control loop. `ConflictResolver` interface defined, not integrated. Dead letter replay marked TODO.

---

## Key Design Discussions

### CaseFile vs StateContext

`CaseFile` is the correct CMMN term — it stays. Pluggability is achieved by making it an interface with multiple implementations, same as Quarkus Flow's `withContextFactory` pattern:

- `JsonCaseFile` — JSON document backend (from casehub-engine's `StateContext`). JQ expressions work naturally. Portable, serialisable.
- `JavaBeanCaseFile<T>` — typed POJO backend. Lambdas work directly on `T`. Type-safe, IDE-friendly. Pattern: `agent("name", analyst::analyse, InvestmentPrompt.class)` as in Quarkus Flow.
- `MapCaseFile` — raw `Map<String, Object>` backend (casehub's current approach).

All implementations expose the full `CaseFile` interface. JQ evaluates against the JSON representation of any context type. Lambda expressions operate directly on the typed representation. casehub-engine's rich `StateContext` interface (diff, snapshot, version, path ops, compareAndSet, merge, `evalObjectTemplate`) informs what `CaseFile` impls should support.

### TaskDefinition vs Worker + Binding

**Decision: Option C** — `TaskDefinition` is syntactic sugar for Java developers that auto-generates a Worker+Binding pair. The internal model is always Worker+Binding. YAML and Java DSL both compile to the same `CaseDefinition` internal model.

casehub-engine separates:
- `Worker` — the implementation (capabilities + execution function: lambda, SWF Workflow, or File)
- `Binding` (formerly `DispatchRule`) — declaration of when to invoke: trigger + `when` guard + `inputFrom` mapping + `outputAs` mapping

casehub's `TaskDefinition` fuses declaration and execution into one class. That's ergonomic for Java CDI devs but conflates two concerns. The split is architecturally cleaner. `TaskDefinition` becomes a convenience builder that wraps both in one class and registers them as a matched pair.

`TaskDefinition.canActivate(caseFile)` is more powerful than casehub-engine's JQ `when` guard — it runs arbitrary Java logic. In the merged model, `canActivate()` maps to a `LambdaExpressionEvaluator` on the Binding's `when` condition.

`TaskDefinition.producedKeys()` enables cycle detection — valuable, must be preserved. Workers in the merged model should optionally declare `producedKeys()` for the same purpose.

### Expression Language

Both approaches are needed and non-exclusive:
- **JQ strings** — portable, serialisable, required for YAML format, works against any JSON-representable CaseFile
- **Java lambdas** — type-safe, IDE-friendly, required for Java DSL, works directly on typed CaseFile or POJO context

`ExpressionEvaluator` becomes a **non-sealed interface** with two implementations:
- `JQExpressionEvaluator(String expression)` — for YAML and Java string shorthand
- `LambdaExpressionEvaluator(Predicate<CaseFile>)` — for Java lambda shorthand
- Optionally `LambdaExpressionEvaluator(String description, Predicate<CaseFile>)` for human-readable descriptions in tooling

Both casehub-engine's Worker function lambdas AND casehub's `canActivate()` are already Java lambdas — the infrastructure is there, just not unified under `ExpressionEvaluator` yet.

### Sync vs Async — The Real Analysis

**Common misconception:** "synchronous control loop" vs "async" is a false dichotomy. The real question is two separate things:

**1. Who decides what fires next?**

| | casehub (Blackboard) | casehub-engine (Choreography) |
|---|---|---|
| Coordination | Central — `PlanningStrategy` decides | Decentralised — workers react to triggers |
| Task ordering | Explicit, controlled | Emergent from trigger conditions |
| Concurrent execution | One at a time by default (configurable) | All eligible bindings fire concurrently |
| Reasoning | Easy — `PlanningStrategy` has full view | Hard — distributed across bindings |
| Scalability | Lower | Higher |
| Best for | Deliberative AI agents | Microservice pipelines |

**2. Does execution block threads?**

casehub conflates "sequential task logic" with "blocking threads" — they are different things. The merged system separates them:
- **Sequential logic** (optional, via `PlanningStrategy`) — one binding fires, waits for result before deciding what's next. The *logic* is sequential.
- **Non-blocking execution** — while a worker is running (30-second LLM call), the event loop is completely free. No thread blocks. The *implementation* is always async.

**Why async is architecturally necessary (not just a performance choice):**

casehub is already a hybrid — it has BOTH orchestrated workers (Blackboard control loop) AND autonomous workers (self-initiating, `TaskOrigin.AUTONOMOUS`). In a hybrid model, external things change CaseFile state the engine didn't initiate:
- Autonomous workers update CaseFile from their own scheduler/thread
- CloudEvents arrive from external systems
- Schedule triggers fire on a timer
- Sub-cases complete and propagate results back

The synchronous control loop has no clean way to receive these external stimuli. `notifyAutonomousWork()` is a workaround — the autonomous worker reaches back into the engine to announce itself. This is backwards: thread-safety concerns, tight coupling, no clean separation.

With async event bus:
- Any CaseFile change, from any source, fires `CaseFileChangedEvent`
- `ListenerEvaluator` re-evaluates bindings reactively
- `notifyAutonomousWork()` disappears — autonomous workers just write to CaseFile

**The merged event-driven cycle:**
```
CaseFile changes (any source: orchestrated worker, autonomous worker, CloudEvent, schedule, sub-case)
  → CaseFileChangedEvent on Vert.x event bus
    → PlanningStrategy evaluates eligible bindings (optional deliberative step)
      → selects binding(s) to fire (one at a time, or all, depending on strategy)
        → WorkerScheduleEvent published (async, non-blocking)
          → Quartz job scheduled (durable, survives restarts)
            → worker executes → result written to CaseFile
              → CaseFileChangedEvent → loop repeats
```

`PlanningStrategy` remains: it reasons within the reactive cycle, not as a blocking loop. Without a strategy (default), all eligible bindings fire concurrently — exactly casehub-engine's behaviour. With a strategy, execution is logically sequential but physically always non-blocking.

**In short:** the "hybrid sync + control loop" framing is wrong. It's a **logically configurable (sequential or concurrent), physically always async** design. The control loop becomes a reactive event cycle.

### Hybrid Orchestration + Choreography

This is an explicit design goal, not a compromise.

casehub already has two worker modes that map directly to the two models:
- **Orchestrated workers** (`TaskDefinition`, `BROKER_ALLOCATED`) — the engine decides when they fire. PlanningStrategy controls priority and sequence.
- **Autonomous workers** (`notifyAutonomousWork()`, `AUTONOMOUS`) — workers self-initiate. The engine observes and tracks, doesn't orchestrate.

casehub-engine's pure choreography model (all workers self-trigger via DispatchRules) ≈ casehub's autonomous worker model. The difference is casehub *also* supports the deliberate orchestration mode. The merged system keeps both:

- **Choreography by default** — workers observe CaseFile via Bindings and self-trigger (DispatchRule/Binding model from casehub-engine)
- **Optional deliberative overlay** — `PlanningStrategy` for cases where the engine should reason centrally about what fires next
- **Both modes coexist** in the same case instance; the engine observes all of them

If a developer uses only Bindings with no PlanningStrategy, they get pure choreography (casehub-engine's model). If they add a PlanningStrategy, they get hybrid. The architecture contains both without forcing either.

### Sub-Cases

**Neither system has full sub-case support**, but casehub has the POJO foundation:
- `CaseFile.getParentCase()` / `getChildCases()` — on the interface
- `Task.getOwningCase()` / `getChildTasks()` — on the interface
- `PropagationContext.createChild()` — budget/tracing inheritance ready

A sub-case in the merged model is a **Worker variant** — specifically a worker whose "execution" is spawning a new `CaseInstance` with its own ID, its own CaseFile, its own Bindings and PlanningStrategy.

Two worker types (inline vs sub-process — as described by the user):
- **Inline workers** — process data within the current CaseInstance's CaseFile (rules worker, transformer, LLM call). Result merged back into parent CaseFile.
- **Sub-process workers** — spawn a child `CaseInstance` with its own lifecycle, goals, workers. Parent CaseInstance tracks child via POJO graph. Child completion propagates result back to parent CaseFile via `CaseFileChangedEvent`.

`PropagationContext.createChild()` handles budget/tracing inheritance across case boundaries. Parent's `GoalExpression` can reference child case completion.

### Persistence + EventLog

casehub-engine's `EventLog` does several distinct jobs simultaneously:

1. **Audit trail** — complete ordered sequence: `CASE_STARTED`, `WORKER_SCHEDULED`, `WORKER_EXECUTION_STARTED/COMPLETED/FAILED`, `MILESTONE_REACHED`, `GOAL_REACHED`, `CASE_STATUS_CHANGED`
2. **Goal completion source of truth** — `GoalReachedEventHandler` queries EventLog (not in-memory state) to find which goals have been reached, then evaluates `GoalExpression`
3. **Idempotency** — Quartz job key = SHA256(workerName + capabilityName + inputDataHash). Duplicate scheduling is automatically deduplicated by Quartz
4. **Replay foundation** — `seq` DB identity column provides total ordering. Event sourcing / state reconstruction not yet implemented but clearly designed for

casehub has complementary mechanisms that are different, not competing:

| Mechanism | casehub | casehub-engine |
|---|---|---|
| Structural lineage | POJO graph: `CaseFile.getParentCase/ChildCases`, `Task.getChildTasks` | None |
| Tracing metadata | `PropagationContext`: W3C traceId, inherited attributes, deadline/budget | None |
| Key-level provenance | `CaseFileItem`: writtenBy, writtenAt, version per key | None |
| Event history | None | `EventLog` full ordered sequence |
| Change notification | `CaseFile.onChange/onAnyChange` in-memory listeners | Vert.x EventBus |
| Idempotency | `IdempotencyService` SPI (TTL-based, per key) | SHA256 hash as Quartz job key |
| Durable execution | None | Quartz persisted jobs |
| Replay | None | Foundation laid (`seq`), not implemented |

**The merged system adopts all of it.** POJO graph + PropagationContext + CaseFileItem provenance + EventLog + Quartz + IdempotencyService. They serve different concerns and don't overlap.

---

## Decisions Made

### Architecture

| Decision | Choice | Reason |
|---|---|---|
| Merge direction | casehub as base | casehub-engine's contributions are interface/format — additive. casehub's Blackboard, resilience, lineage, autonomous workers cannot be retrofitted. |
| Migration approach | Option B — evolve casehub in-place | Existing test coverage, module structure, persistence SPI. Incremental, each phase shippable. |
| Orchestration model | Hybrid — choreography default + optional deliberative overlay | casehub already has both modes. Neither replaces the other. |
| Async | Fully async infrastructure, always | Architecturally necessary for hybrid model — not just a performance choice. `notifyAutonomousWork()` workaround disappears. |
| Control loop | Logically configurable (sequential or concurrent), physically always non-blocking | Separates "who decides" from "how it's executed". |

### API & DSL

| Decision | Choice | Reason |
|---|---|---|
| TaskDefinition vs Worker | Option C — `TaskDefinition` sugar over Worker+Binding | Ergonomic Java DSL + clean internal model. Both compile to same representation. |
| Schema vs code | Option C — both are first-class | Validated by Quarkus Flow (`DiscoveredWorkflowBuildItem.fromSpec/fromSource`). Build-time discovery of both. |
| Target runtime | Quarkus-first, non-Quarkus optional later | Don't weaken Quarkus DX. Extension architecture. |
| Quarkus Flow depth | Option A — one backend among several | Inline workers (lambdas), I/O workers (Flow), sub-process workers (sub-cases). Option C (all Flow) is just a usage pattern of A. |
| Expression language | Pluggable — JQ + Lambda, both first-class | YAML needs JQ; Java devs want lambdas. `ExpressionEvaluator` unsealed. |
| Context pluggability | Pluggable `CaseFile` impls — `JsonCaseFile`, `JavaBeanCaseFile<T>`, `MapCaseFile` | Modelled on Quarkus Flow's `withContextFactory` pattern. |
| Naming — dispatch | `bindings` | Evolved from `rules` → `dispatch-rules` → `bindings`. Quarkus Flow has no equivalent to align with. |

---

## Naming / Terminology Mapping

> Every change must be justified. No rename without a reason.

### casehub terms — keep or change?

| casehub name | Action | Merged name | Reason |
|---|---|---|---|
| `CaseFile` | **Keep — make pluggable interface** | `CaseFile` | Correct CMMN term. Pluggability via impls: `JsonCaseFile`, `JavaBeanCaseFile<T>`, `MapCaseFile`. |
| `CaseEngine` | Keep | `CaseEngine` | CMMN term, correct. |
| `CasePlanModel` | Keep | `CasePlanModel` | CMMN term. Rich control state with no equivalent in casehub-engine. |
| `PlanItem` | Keep | `PlanItem` | CMMN term. |
| `PlanningStrategy` | Keep | `PlanningStrategy` | Core differentiator. 4 activation conditions. No equivalent in casehub-engine. |
| `ListenerEvaluator` | Keep | `ListenerEvaluator` | Core control loop component. Evaluates entry criteria, canActivate, stages, milestones. |
| `TaskDefinition` | Keep as syntactic sugar | `TaskDefinition` | Java DSL convenience — compiles to Worker+Binding internally (Option C). |
| `Worker` | Keep | `Worker` | Exists in both systems, same concept. |
| `CaseStatus` / `TaskStatus` | Keep — align states | `CaseStatus` / `TaskStatus` | CNCF OWL lifecycle. casehub-engine has slightly different states — align in merge. |
| `PropagationContext` | Keep (already reduced in issue #6) | `PropagationContext` | Now: W3C traceId + inherited attributes + deadline/budget only. Structural lineage is POJO graph. |
| `TaskBroker` / `TaskScheduler` | Keep | `TaskBroker` / `TaskScheduler` | Request-response model. No equivalent in casehub-engine. |
| `Stage` | Keep | `Stage` | Full CMMN stage lifecycle. No equivalent in casehub-engine. |
| `Milestone` (casehub CMMN) | Keep — **rename casehub-engine's** | `Milestone` | CMMN achievement marker (PENDING/ACHIEVED). casehub-engine's "Milestone" is a different concept — see below. |
| `CaseFileItem` | Keep | `CaseFileItem` | Key-level provenance (writtenBy, writtenAt, version). No equivalent in casehub-engine. |
| `ConflictResolver` | Keep | `ConflictResolver` | Multi-writer coordination. No equivalent in casehub-engine. |
| `IdempotencyService` | Keep | `IdempotencyService` | TTL-based deduplication. Complementary to casehub-engine's Quartz hash idempotency. |
| `PoisonPillDetector` | Keep | `PoisonPillDetector` | Circuit breaker for repeated failures. No equivalent in casehub-engine. |
| `DeadLetterQueue` | Keep | `DeadLetterQueue` | Failed work storage + replay. No equivalent in casehub-engine. |
| `RetryPolicy` | Keep — merge with casehub-engine's | `RetryPolicy` | casehub's is richer (backoff strategies, error codes, budget-aware). casehub-engine's is simpler. |
| entry criteria (`Set<String>`) | **Replace** | `Binding` | Entry criteria replaced by Binding model (trigger + when + inputFrom + outputAs). Richer. |
| `rules` field on `CasePlanModel` | **Rename** | `bindings` | Agreed. Avoids rules-engine connotation. |
| `FlowWorker` | Keep — evolve | `FlowWorker` | Already bridges casehub task model to Quarkus Flow. Replace polling with event-driven. |

### casehub-engine terms — adopt or rename?

| casehub-engine name | Action | Merged name | Reason |
|---|---|---|---|
| `StateContext` | **Discard name, keep concept** | `CaseFile` (interface + impls) | `CaseFile` is the correct CMMN term. `StateContextImpl` becomes `JsonCaseFile`. Rich interface methods (diff, snapshot, evalObjectTemplate) inform `CaseFile` impl. |
| `DispatchRule` | **Rename** | `Binding` | Agreed naming decision. |
| `ContextChangeTrigger` | Keep (name TBD) | `ContextChangeTrigger` | Keep concept. Name TBD — `StateChangeTrigger`? |
| `CloudEventTrigger` | Adopt | `CloudEventTrigger` | New trigger type. No change needed. |
| `ScheduleTrigger` | Adopt | `ScheduleTrigger` | New trigger type. No change needed. |
| `Trigger` | Adopt | `Trigger` | Sealed interface with three variants. |
| `ExpressionEvaluator` | **Unseal + extend** | `ExpressionEvaluator` | Remove `sealed`. Add `LambdaExpressionEvaluator`. |
| `JQExpressionEvaluator` | Adopt | `JQExpressionEvaluator` | No change. |
| `Capability` | Adopt | `Capability` | Clean concept: name + inputSchema + outputSchema contract. |
| `CaseHubDefinition` | **Rename** | `CaseDefinition` | More concise. Consistent with `CaseDefinition` entity in casehub-engine. |
| `GoalKind` | Adopt | `GoalKind` | SUCCESS/FAILURE enum. Clean. |
| `GoalExpression` | Adopt | `GoalExpression` | `allOf`/`anyOf`. Clean. |
| `GoalBasedCompletion` | Adopt | `GoalBasedCompletion` | Declarative completion via goal expressions. |
| `PredicateBasedCompletion` | Adopt | `PredicateBasedCompletion` | JQ/lambda predicate for completion. |
| `CaseCompletion` | Adopt | `CaseCompletion` | Parent interface for both completion types. |
| `EventLog` | Adopt | `EventLog` | Full ordered event history. Adopt with `seq` column intact. |
| `Milestone` (casehub-engine observability) | **Rename** | `ProgressMarker` | Avoid clash with casehub's CMMN `Milestone`. casehub-engine's concept = named condition for observability, fires event when true. `ProgressMarker` or `ObservabilityMarker` — TBD with co-worker. |
| `evalObjectTemplate()` | Adopt | `evalObjectTemplate()` | Mini-DSL for input/output mapping. Keep on `CaseFile` interface. |
| Quartz integration | Adopt | — | Durable worker execution. Quartz jobs for all Worker executions. |

### New concepts (not fully present in either system)

| Name | What it is |
|---|---|
| `Goal` | Named condition over CaseFile with `GoalKind` (SUCCESS/FAILURE). Evaluated on every CaseFile change. Issue #7. |
| `ProgressMarker` | Renamed casehub-engine `Milestone` — named JQ/lambda predicate for observability. Fires event when condition is true. No lifecycle state. |
| `LambdaExpressionEvaluator` | `Predicate<CaseFile>` impl of `ExpressionEvaluator`. For Java DSL. |
| `JsonCaseFile` | JSON-document-backed `CaseFile` impl (casehub-engine's `StateContextImpl` renamed). |
| `JavaBeanCaseFile<T>` | Typed-POJO-backed `CaseFile` impl. Lambdas operate directly on `T`. |
| `SubCaseBinding` | `Binding` variant whose execution spawns a child `CaseInstance`. Result propagates back to parent CaseFile. |
| `casehub-quarkus` | New Quarkus extension module: CDI integration, `@Inject CaseHub`, build-time discovery (Jandex + classpath scan), Dev Services, health checks, live reload for YAML. |

---

## Module Structure (merged)

```
casehub/
├── casehub-api/              # Pure Java, zero framework deps — all interfaces + model
│                             # CaseFile, Worker, Binding, Trigger, Goal, ProgressMarker,
│                             # CaseDefinition, ExpressionEvaluator, Capability, GoalExpression,
│                             # PropagationContext, RetryPolicy, ErrorInfo, CaseStatus, TaskStatus
├── casehub-engine/           # Core logic, minimal deps — runs anywhere (unergonomic alone)
│                             # CaseEngine, ListenerEvaluator, PlanningStrategy, CasePlanModel,
│                             # EventLog, Quartz integration, JQEvaluator, PoisonPillDetector,
│                             # DeadLetterQueue, IdempotencyService, ConflictResolver
├── casehub-quarkus/          # Quarkus extension — where the great DX lives
│                             # CDI, @Inject CaseHub, build-time discovery, Dev Services,
│                             # health checks, live reload, @CaseType qualifier
├── casehub-schema/           # YAML/JSON schema + codegen (jsonschema2pojo)
│                             # CaseHubDefinition.yaml, WorkerMarshaller, generated models
├── casehub-persistence-memory/   # In-memory SPI impls (fast unit tests)
├── casehub-persistence-hibernate/ # Hibernate Reactive SPI (production)
└── casehub-examples/         # Examples using casehub-quarkus
```

---

## Open Items (still to resolve)

- [ ] **Fluent Java DSL** — exact API design. Sketch: `CaseHub<T>.definition()` → `caseOf(T.class).worker(...).bind(...).milestone(...).goal(...).completeWhen(...)`. User has changes coming.
- [ ] **`ProgressMarker` naming** — confirm with co-worker. Alternatives: `ObservabilityMarker`, `Signal`, `Indicator`.
- [ ] **`ContextChangeTrigger` naming** — keep or rename to `StateChangeTrigger`?
- [ ] **CaseState alignment** — casehub (PENDING/RUNNING/WAITING/SUSPENDED/COMPLETED/FAULTED/CANCELLED) vs casehub-engine (ACTIVE/COMPLETED/FAILED/SUSPENDED/TERMINATED/WAITING). Which wins?
- [ ] **`CaseDefinitionRegistry` shim** — how do YAML-defined cases (no Java lambdas) and Java-defined cases (with lambdas) coexist cleanly in the same registry?
- [ ] **Stage lifecycle** — partially implemented in `ListenerEvaluator`. Full wiring into async event cycle needed.
- [ ] **ConflictResolver** — interface defined, not integrated. Does it belong in async model?
- [ ] **Dead letter replay** — marked TODO in casehub. Design needed.
- [ ] **evalObjectTemplate on CaseFile** — adopt fully. Does it replace JQ for input/output mapping, or do both coexist?
