# CaseHub Unified Design
**Date:** 2026-04-09
**Status:** Approved — ready for implementation planning
**Authors:** Mark Proctor + co-worker (casehub-engine)

---

## Summary

Two independent CaseHub implementations are being merged into one. This document captures the complete design for the unified system — what comes from each, what gets renamed, what gets added, and how they fit together architecturally.

**Merge direction: casehub as the base.** casehub-engine's contributions are interface design, format, and reactive infrastructure — all additive. casehub's Blackboard control loop, PlanningStrategy, CMMN stage lifecycle, resilience machinery, and lineage model are architectural and cannot be easily retrofitted onto casehub-engine's reactive core. Starting from casehub-engine would require rebuilding everything casehub already has.

**Migration approach: Option B — evolve casehub in-place.** Incremental refactoring of existing modules. Each phase shippable. Existing test coverage preserved throughout.

---

## Both Systems — Full Comparison

| Dimension | **casehub** | **casehub-engine** |
|---|---|---|
| Architecture | Blackboard (Hayes-Roth 1985) / CMMN | Reactive event-driven choreography |
| Core abstraction | `CaseFile` — typed key-value, pluggable interface | `StateContext` — JSON document, pluggable interface |
| Task dispatch | `TaskDefinition` — entry criteria keys + `canActivate()` | `Worker` (lambda/SWF/File) + `DispatchRule` + `Trigger` |
| Worker execution | CDI bean `execute(caseFile)` or `FlowWorker` polling | Lambda, SWF Workflow, or File — scheduled via Quartz |
| Expression language | Java lambda (`canActivate`, change listeners) — no JQ | JQ strings (sealed, for conditions) + Java lambda (Worker functions) |
| Execution model | Synchronous loop, 1 task at a time | Async reactive — Vert.x EventBus + Mutiny + Quartz |
| Persistence | SPI: InMemory + Hibernate (blocking JPA) | Hibernate Reactive only |
| Resilience | RetryPolicy, DLQ, PoisonPillDetector, Idempotency, ConflictResolver, TimeoutEnforcer | RetryPolicy only (Quartz retry TODO) |
| Lineage | `PropagationContext` (traceId + budget) + POJO object graph | None |
| Autonomous workers | Yes — `WorkerRegistry.notifyAutonomousWork()`, `TaskOrigin.AUTONOMOUS` | Implicit — choreography model = self-triggering |
| Control reasoning | `PlanningStrategy` — pluggable, 4 activation conditions | None — pure choreography |
| Stage lifecycle | Full CMMN stages — nested, autocomplete, manual activation, entry/exit criteria | None |
| Milestone (CMMN) | Achievement marker — key presence → PENDING/ACHIEVED, tracked in `CasePlanModel` | **Not present** (different concept, same name — see naming section) |
| Goal model | Not yet — issue #7 planned | `Goal` + `GoalExpression` (allOf/anyOf) + `GoalKind` (SUCCESS/FAILURE) |
| Completion | Quiescence detection | `GoalBasedCompletion` or `PredicateBasedCompletion` (JQ) |
| Event history | None — per-key `CaseFileItem` provenance only | `EventLog` — full ordered sequence, `seq` DB identity column |
| Durable execution | None | Quartz — persisted jobs survive restarts |
| Input/output mapping | None — TaskDefinition reads/writes CaseFile directly | `evalObjectTemplate()` mini-DSL + capability inputSchema/outputSchema |
| Triggers | Entry criteria only | `contextChange`, `cloudEvent`, `schedule` |
| CloudEvents | No | Yes |
| Per-key versioning | Yes — `putIfVersion`, `getKeyVersion`, `CaseFileItem` with writtenBy/writtenAt | No |
| Conflict resolution | `ConflictResolver` — LAST_WRITER_WINS, FIRST_WRITER_WINS, MERGE, FAIL | Last-write-wins implicit |
| Cycle detection | Yes — DFS on entry criteria → producedKeys graph at registration | No |
| Schema format | None | YAML/JSON schema + codegen (jsonschema2pojo) |
| Serverless Workflow | Via `FlowWorker` polling bridge + `FlowWorkflowRegistry` | Native — `Worker.function(Workflow)` |
| Sub-cases | POJO graph modelled (`getParentCase/getChildCases`) — engine not yet wired | None — `CaseInstance` has no parent reference |

---

## Architecture

### Hybrid Orchestration + Choreography

This is an **explicit design goal**, not a compromise. The merged system supports both execution models in the same case instance, because different workers in the same case may operate differently:

**Choreography (default):** Workers observe CaseFile state via `Binding` conditions and self-trigger. The engine dispatches them reactively. This is casehub-engine's entire model, and also casehub's autonomous worker model (`TaskOrigin.AUTONOMOUS`).

**Orchestration (optional overlay):** A `PlanningStrategy` reasons centrally about which binding fires next, at what priority, with what focus. This is casehub's Blackboard control model — the deliberative layer that makes it suitable for agentic AI where you want the engine to reason, not just react.

A developer who never adds a `PlanningStrategy` gets pure choreography. One who adds a `PlanningStrategy` gets the hybrid. The architecture contains both without forcing either.

### Async Event Cycle — Always, Everywhere

The synchronous control loop is replaced by an event-driven cycle. This is **architecturally necessary** — not just a performance improvement.

**Why necessary:** casehub is already hybrid. External things change CaseFile state the engine didn't initiate:
- Autonomous workers update CaseFile from their own threads
- CloudEvents arrive from external systems
- Schedule triggers fire on a timer
- Sub-cases complete and propagate results back

A synchronous loop cannot cleanly receive these external stimuli. `notifyAutonomousWork()` is a workaround that couples autonomous workers to engine internals. The event bus makes all state changes first-class, from any source.

**The event cycle:**
```
CaseFile changes (any source: orchestrated worker, autonomous worker,
                  CloudEvent, schedule trigger, sub-case completion)
  → CaseFileChangedEvent on Vert.x event bus
    → PlanningStrategy evaluates eligible Bindings (optional deliberative step)
      → selects Binding(s) to fire — one at a time (with strategy) or all (without)
        → WorkerScheduleEvent published — async, non-blocking
          → Quartz job scheduled — persisted, survives restarts
            → worker executes → result written to CaseFile
              → CaseFileChangedEvent → cycle repeats
    → Milestones evaluated → ProgressMarkerReachedEvent if condition true
    → Goals evaluated → GoalReachedEvent if condition true
      → GoalReachedEventHandler evaluates CaseCompletion → COMPLETED or FAILED
```

**Key clarification — "logically sequential, physically always non-blocking":**
- Without `PlanningStrategy`: all eligible Bindings fire concurrently (pure choreography)
- With `PlanningStrategy`: the *logic* is sequential (one deliberate step at a time), but no thread ever blocks. The event loop is free while workers execute. Sequential logic ≠ blocking threads.

### Worker — Executor and Indirect Influencer, Never Decision-Maker

This is a deliberate and important departure from CMMN. In CMMN, a "worker" (typically a human) exercises discretion and their decision is a direct instruction — what they say happens. **In casehub, a Worker executes work and writes results to the CaseFile. That is all it does.** It has no direct control authority over what happens next.

However, Workers **can influence** what happens next — indirectly, through the CaseFile. A Worker's output becomes part of the shared state. The `PlanningStrategy` observes that state change and reasons about it. It may choose to act on the Worker's implicit suggestion, override it, combine it with other state, or ignore it entirely. The strategy retains full decision authority.

**A concrete example:** Several Workers could potentially run next. CaseHub invokes a "routing Worker" first — perhaps an LLM or a rules engine — which writes its recommendation into the CaseFile (`suggested_next: ["worker-A", "worker-B"]`). The PlanningStrategy observes this new state, reads the suggestion, and decides what to do: follow it, partially follow it, override it based on other conditions, or discard it. The Worker provided information. The strategy made the decision.

**The CMMN behaviour is a strategy, not the default.** A PlanningStrategy that always reads Worker output and executes on it faithfully is exactly the CMMN model — a "deference strategy". casehub supports this as one option among many, not as a hard constraint. The power is that the strategy can do anything with what Workers contribute.

**The CaseFile is the only communication channel.** Workers do not signal the engine, call back, or know what other Workers exist. They read input, execute, write output to CaseFile, and stop. The async event cycle then fires, and the engine reasons about what comes next — using the Worker's contribution as data, not as instruction.

This is why `notifyAutonomousWork()` is architecturally wrong: it bypasses the CaseFile and couples autonomous Workers directly to engine internals. In the async event cycle, autonomous Workers are just Workers — they write to the CaseFile, the engine reacts via the event bus, and the strategy decides what to do next, exactly as with any other state change.

### Worker Taxonomy

Workers vary across two dimensions: **execution duration** and **lifecycle scope**.

**By duration:**

| Kind | Duration | Mechanism | When to use |
|---|---|---|---|
| **Inline** | Milliseconds | Lambda or CDI bean | Rules, data transforms, simple predicates — no I/O |
| **I/O-bound** | Seconds to minutes | Quarkus Flow instance via Quartz | LLM agents, HTTP calls, multi-step workflows |
| **Long-lived** | Hours to case lifetime | Persistent event bus participant | Continuous monitoring, anomaly detection, ongoing LLM reasoning |
| **Sub-process** | Own lifecycle | `SubCaseBinding` → child `CaseInstance` | Delegating a complex sub-problem with its own goals and workers |

**Long-lived workers** run continuously across stage transitions, maintaining their own internal state. They do not fire-and-forget — they start on a lifecycle event (case or stage activation), subscribe to the event bus, observe CaseFile changes, write back observations periodically, and stop when their scope ends. Examples: a monitoring worker watching for anomalies throughout a case, a continuous reasoning worker that refines a recommendation as new evidence arrives.

**By lifecycle scope:**

| Scope | Starts when | Stops when | Typical use |
|---|---|---|---|
| `CASE` | Case starts | Case completes or fails | Monitoring, audit, cross-cutting concerns |
| `STAGE` | Specific stage activates | That stage exits | Stage-specific reasoning or observation |
| `BINDING` | Binding condition fires | Execution completes | Current per-invocation model — inline and I/O-bound |

Stage-scoped long-lived workers depend on nested stage lifecycle being properly wired — they must receive `StageActivatedEvent` and `StageExitedEvent` to know when to start and stop.

A developer who uses Quarkus Flow for all workers naturally gets the "CaseHub and Quarkus Flow as peers" pattern — but it is a choice, not a constraint.

### Nested Stages

Stages are first-class structural containers — they group related PlanItems and Workers, have their own entry/exit criteria, and can be nested arbitrarily deep. casehub already has the model (`Stage.containedStageIds`, `Stage.containedPlanItemIds`) but the engine wiring is incomplete. This must be a properly implemented concern.

**Stage lifecycle:**
- `PENDING` → `ACTIVE`: when entry criteria (key presence) are met, or manual activation
- `ACTIVE` → `COMPLETED`: when all required PlanItems complete (autocomplete), or exit criteria met
- `ACTIVE` → `TERMINATED`: when exit criteria for early exit are met
- Supports: suspension, resume, fault propagation from contained PlanItems

**Nesting semantics:**
- A parent stage activates when its own entry criteria are met
- Child stages activate independently when their own entry criteria are met, within an active parent
- Child stage completion/termination does not automatically complete the parent — the parent has its own completion criteria
- PlanItems and Workers inside a stage only run while that stage is `ACTIVE`
- Stage-scoped long-lived workers start on `StageActivatedEvent` and stop on `StageExitedEvent`

**Interaction with the async event cycle:**
```
CaseFileChangedEvent
  → evaluate stage entry criteria → StageActivatedEvent (if criteria met)
    → activate contained PlanItems and stage-scoped workers
  → evaluate stage exit/completion criteria → StageExitedEvent (if criteria met)
    → stop contained stage-scoped workers
    → propagate to parent stage completion evaluation
```

**Why nested stages matter for long-lived workers:** A stage-scoped long-lived worker needs reliable `StageActivatedEvent` / `StageExitedEvent` signals to manage its own lifecycle correctly. Without proper nested stage wiring, long-lived workers cannot scope themselves to stages.

### Sub-Cases

A sub-case is a **Worker variant** whose execution spawns a child `CaseInstance` with its own ID, CaseFile, Bindings, and PlanningStrategy. The parent CaseInstance tracks the child via the existing POJO graph (`CaseFile.getParentCase/getChildCases`).

- Parent passes data to child via CaseFile projection at spawn time
- Child completion propagates result back to parent CaseFile via `CaseFileChangedEvent`
- `PropagationContext.createChild()` handles budget and tracing inheritance
- Parent's `GoalExpression` can reference child case completion
- Sub-cases can themselves be orchestrated or choreographed

casehub already has the POJO graph modelled. The engine wiring is what's missing.

### Pluggable CaseFile

`CaseFile` stays as the primary term (correct CMMN). Pluggability is achieved via implementations, modelled on Quarkus Flow's `withContextFactory` pattern:

| Implementation | Backend | When to use |
|---|---|---|
| `JsonCaseFile` | JSON document (from casehub-engine's `StateContextImpl`) | JQ expressions, YAML-defined cases, interop |
| `JavaBeanCaseFile<T>` | Typed POJO | Type-safe Java, IDE autocomplete, LangChain4j agents |
| `MapCaseFile` | `Map<String, Object>` | Current casehub behaviour, migration path |

JQ evaluates against the JSON representation of any context type. Java lambdas operate directly on the typed representation. `evalObjectTemplate()` (casehub-engine's mini-DSL) is adopted on the `CaseFile` interface for input/output mapping.

### Pluggable Expression Language

`ExpressionEvaluator` becomes a **non-sealed interface** with two implementations:

| Implementation | Use case |
|---|---|
| `JQExpressionEvaluator(String)` | YAML-defined cases, Java string shorthand, portability |
| `LambdaExpressionEvaluator(Predicate<CaseFile>)` | Java DSL, type safety, IDE support |

Both are valid everywhere an `ExpressionEvaluator` is accepted: Binding `when` conditions, Goal conditions, ProgressMarker conditions, CaseCompletion predicates.

### Schema and Code — Both First-Class

Validated by Quarkus Flow's own design (`DiscoveredWorkflowBuildItem.fromSpec()` vs `.fromSource()`):

- **YAML/JSON** case definitions on the classpath → discovered at build time by the Quarkus deployment processor
- **Java DSL** (`CaseHub` subclasses) → discovered at build time via Jandex
- Both compile to the same internal `CaseDefinition` model
- YAML hot-reload comes free via Quarkus `HotDeploymentWatchedFileBuildItem`

### Persistence + Durability

The merged system adopts all mechanisms from both — they serve different concerns:

| Mechanism | Source | Purpose |
|---|---|---|
| POJO graph (`getParentCase`, `getChildCases`, `getChildTasks`) | casehub | Structural lineage — parent/child case and task hierarchy |
| `PropagationContext` (traceId, attributes, budget) | casehub | W3C tracing metadata + time budget across case boundaries |
| `CaseFileItem` (writtenBy, writtenAt, version) | casehub | Key-level provenance — who wrote what and when |
| `EventLog` (seq, eventType, payload) | casehub-engine | Full ordered event history — audit, goal completion, replay foundation |
| `CaseFile.onChange/onAnyChange` | casehub | In-memory change listeners — seed for event-driven re-evaluation |
| Vert.x EventBus | casehub-engine | Async event dispatch between engine components |
| Quartz | casehub-engine | Durable worker execution — jobs survive restarts |
| `IdempotencyService` | casehub | TTL-based deduplication at task submission level |
| Quartz idempotency (SHA256 hash) | casehub-engine | Deduplication at Quartz job scheduling level |

### Resilience

casehub has significantly richer resilience than casehub-engine. All of it is preserved:

| Component | What it does |
|---|---|
| `RetryPolicy` | Configurable backoff (FIXED, EXPONENTIAL, EXPONENTIAL_WITH_JITTER), error codes, budget-aware |
| `PoisonPillDetector` | Circuit breaker — sliding window failure tracking, quarantine, auto-release |
| `DeadLetterQueue` | Failed work storage with retry history, replay support (TODO), TTL purge |
| `IdempotencyService` | Duplicate prevention with TTL-based expiry |
| `TimeoutEnforcer` | Scheduled deadline enforcement via `PropagationContext.deadline` |
| `ConflictResolver` | Multi-writer coordination — LAST_WRITER_WINS, FIRST_WRITER_WINS, MERGE, FAIL |

---

## Naming / Terminology

### casehub terms — what changes

| Current name | Action | New name | Reason |
|---|---|---|---|
| `CaseFile` | **Keep — make pluggable interface** | `CaseFile` | Correct CMMN term. Pluggability via `JsonCaseFile`, `JavaBeanCaseFile<T>`, `MapCaseFile`. |
| `CaseEngine` | Keep | `CaseEngine` | CMMN term. |
| `CasePlanModel` | Keep | `CasePlanModel` | CMMN term. Rich control state — agenda, focus, strategy, resource budget, stages, milestones. |
| `PlanItem` | Keep | `PlanItem` | CMMN term. |
| `PlanningStrategy` | Keep | `PlanningStrategy` | Core differentiator. 4 activation conditions. No equivalent in casehub-engine. |
| `ListenerEvaluator` | Keep | `ListenerEvaluator` | Core control component. Evaluates entry criteria, `canActivate`, stages, milestones. |
| `TaskDefinition` | Keep as **syntactic sugar** | `TaskDefinition` | Java DSL convenience — compiles to `Worker` + `Binding` internally. |
| `Worker` | Keep | `Worker` | Exists in both systems. **Note:** casehub's Worker executes work and writes results to the CaseFile — it influences what happens next only indirectly, through shared state. The `PlanningStrategy` observes that state and makes its own decision. This differs from CMMN where a human worker's decision is a direct instruction. In casehub, CMMN-style deference ("do what the worker said") is just one possible `PlanningStrategy` implementation. |
| `Stage` | Keep | `Stage` | Full CMMN stage lifecycle. No equivalent in casehub-engine. |
| `Milestone` (CMMN) | Keep | `Milestone` | CMMN achievement marker — PENDING/ACHIEVED lifecycle. casehub-engine's "Milestone" is renamed. |
| `CaseStatus` / `TaskStatus` | Keep — align states | `CaseStatus` / `TaskStatus` | CNCF OWL lifecycle. casehub-engine has slightly different states — align in merge. |
| `PropagationContext` | Keep (already reduced) | `PropagationContext` | W3C traceId + inherited attributes + deadline/budget. Structural lineage is POJO graph. |
| `TaskBroker` / `TaskScheduler` | Keep | `TaskBroker` / `TaskScheduler` | Request-response model. No equivalent in casehub-engine. |
| `CaseFileItem` | Keep | `CaseFileItem` | Key-level provenance. No equivalent in casehub-engine. |
| `ConflictResolver` | Keep | `ConflictResolver` | Multi-writer coordination. |
| `IdempotencyService` | Keep | `IdempotencyService` | TTL deduplication. Complementary to Quartz-level idempotency. |
| `PoisonPillDetector` | Keep | `PoisonPillDetector` | Circuit breaker. |
| `DeadLetterQueue` | Keep | `DeadLetterQueue` | Failed work storage. |
| `RetryPolicy` | Keep — merge | `RetryPolicy` | casehub's is richer. Absorb casehub-engine's simpler model. |
| entry criteria (`Set<String>`) | **Replace** | `Binding` | Replaced by Binding model — trigger + when + inputFrom + outputAs. |
| `rules` field | **Rename** | `bindings` | Avoids rules-engine connotation. Evolved from `rules` → `dispatch-rules` → `bindings`. |
| `FlowWorker` | Keep — evolve | `FlowWorker` | Bridges casehub task model to Quarkus Flow. Replace polling with event-driven. |

### casehub-engine terms — adopt or rename

| casehub-engine name | Action | Merged name | Reason |
|---|---|---|---|
| `StateContext` | **Discard name, keep concept** | `CaseFile` (interface + impls) | `CaseFile` is the correct CMMN term. `StateContextImpl` becomes `JsonCaseFile`. |
| `DispatchRule` | **Rename** | `Binding` | Agreed. |
| `ContextChangeTrigger` | Keep (name TBD) | `ContextChangeTrigger` | Keep concept. Name under review — `StateChangeTrigger`? |
| `CloudEventTrigger` | Adopt | `CloudEventTrigger` | New trigger type. |
| `ScheduleTrigger` | Adopt | `ScheduleTrigger` | New trigger type. |
| `Trigger` | Adopt | `Trigger` | Three variants: contextChange, cloudEvent, schedule. |
| `ExpressionEvaluator` | **Unseal + extend** | `ExpressionEvaluator` | Remove `sealed`. Add `LambdaExpressionEvaluator`. |
| `JQExpressionEvaluator` | Adopt | `JQExpressionEvaluator` | No change. |
| `Capability` | Adopt | `Capability` | Name + inputSchema + outputSchema contract. |
| `CaseHubDefinition` | **Rename** | `CaseDefinition` | More concise. Consistent with existing `CaseDefinition` entity. |
| `GoalKind` | Adopt | `GoalKind` | SUCCESS/FAILURE enum. |
| `GoalExpression` | Adopt | `GoalExpression` | `allOf` / `anyOf`. |
| `GoalBasedCompletion` | Adopt | `GoalBasedCompletion` | Declarative completion. |
| `PredicateBasedCompletion` | Adopt | `PredicateBasedCompletion` | JQ/lambda predicate completion. |
| `CaseCompletion` | Adopt | `CaseCompletion` | Parent interface. |
| `EventLog` | Adopt | `EventLog` | Full ordered event history. Seq column intact. |
| `Milestone` (observability) | **Rename** | `ProgressMarker` | Avoids clash with casehub's CMMN `Milestone`. Named condition for observability — fires event when true, no lifecycle. |
| `evalObjectTemplate()` | Adopt | `evalObjectTemplate()` | Mini-DSL for input/output mapping. Moved to `CaseFile` interface. |
| Quartz integration | Adopt | — | Durable worker execution for all non-inline workers. |

### New concepts (not in either system yet)

| Name | What it is |
|---|---|
| `Goal` | Named condition over CaseFile with `GoalKind` (SUCCESS/FAILURE). Evaluated on every CaseFile change. |
| `ProgressMarker` | Renamed casehub-engine `Milestone`. Named JQ/lambda condition for observability. Fires event when true. No lifecycle state. |
| `LambdaExpressionEvaluator` | `Predicate<CaseFile>` implementation of `ExpressionEvaluator`. |
| `JsonCaseFile` | JSON-document-backed `CaseFile` impl — casehub-engine's `StateContextImpl` renamed. |
| `JavaBeanCaseFile<T>` | Typed-POJO-backed `CaseFile` impl. Lambdas operate on `T`. |
| `SubCaseBinding` | `Binding` variant whose execution spawns a child `CaseInstance`. |
| `casehub-quarkus` | New Quarkus extension module. |

---

## Module Structure

```
casehub/
├── casehub-api/
│   # Pure Java, zero framework deps
│   # CaseFile, Worker, Binding, Trigger, Goal, ProgressMarker,
│   # CaseDefinition, ExpressionEvaluator, Capability, GoalExpression,
│   # CaseCompletion, PropagationContext, RetryPolicy, ErrorInfo,
│   # CaseStatus, TaskStatus, CaseFileItem, EventLog (entity)
│
├── casehub-engine/
│   # Core logic, minimal deps — runs anywhere
│   # CaseEngine, ListenerEvaluator, PlanningStrategy, CasePlanModel,
│   # Vert.x EventBus wiring, JQEvaluator, Quartz integration,
│   # PoisonPillDetector, DeadLetterQueue, IdempotencyService,
│   # ConflictResolver, TimeoutEnforcer
│
├── casehub-quarkus/
│   # Quarkus extension — where the great developer experience lives
│   # CDI integration, @Inject CaseHub, @CaseType qualifier,
│   # Build-time discovery (Jandex for Java, classpath scan for YAML),
│   # Dev Services, health checks, live reload for YAML definitions
│
├── casehub-schema/
│   # YAML/JSON schema + codegen (jsonschema2pojo)
│   # CaseHubDefinition.yaml (adopted from casehub-engine, extended),
│   # WorkerMarshaller, generated model classes
│
├── casehub-persistence-memory/
│   # In-memory SPI impls — fast unit tests, no DB needed
│
├── casehub-persistence-hibernate/
│   # Hibernate Reactive SPI — production
│   # Already has H2 for tests (PostgreSQL compat mode)
│
└── casehub-examples/
    # Examples using casehub-quarkus
```

---

## Java DSL (sketch — subject to refinement)

```java
@ApplicationScoped
public class DocumentReviewCase extends CaseHub<DocumentContext> {

    @Inject InvestmentAnalystAgent analyst;

    @Override
    public CaseDefinition<DocumentContext> definition() {
        return caseOf(DocumentContext.class)

            .capability("analyzeSentiment")
                .inputFrom("{ documentId: .documentId, content: .data.content }")
                .outputAs("{ sentiment: ., step: \"analyzed\" }")

            .worker("sentiment-analyzer")
                .capabilities("analyzeSentiment")
                .execute(agent(analyst::analyse, SentimentRequest.class))

            // Java lambda condition
            .bind("analyze-on-fetch")
                .when(ctx -> ctx.getStep() == Step.FETCHED)
                .invoke("analyzeSentiment")

            // Or JQ string
            .bind("analyze-on-fetch")
                .when(".step == \"fetched\"")
                .invoke("analyzeSentiment")

            .progressMarker("fetched", ctx -> ctx.getData() != null)

            .goal("review-complete")
                .when(ctx -> ctx.getStep() == Step.SUMMARIZED)
                .kind(GoalKind.SUCCESS)
                .terminal(true)

            .completeWhen(GoalExpression.allOf("review-complete"))
            .build();
    }
}
```

---

## Phased Implementation

The merge is too large for a single issue. Proposed phases — each independently valuable and shippable:

| Phase | Work | Scope |
|---|---|---|
| 1 | **Expression abstraction** | Unseal `ExpressionEvaluator`, add `LambdaExpressionEvaluator`. Small, foundational. |
| 2 | **Binding + Trigger model** | Replace entry criteria with `Binding`/`Trigger`. `TaskDefinition` sugar over Worker+Binding. `Capability`. `evalObjectTemplate`. |
| 3 | **Goal + ProgressMarker** | Adopt casehub-engine's Goal/GoalExpression/GoalKind. Rename casehub-engine Milestone → ProgressMarker. Replaces issue #7 plan. |
| 4 | **EventLog + Quartz** | Adopt EventLog entity. Wire Quartz for durable worker execution. Idempotency hash. |
| 5 | **Async event cycle** | Replace synchronous control loop with Vert.x EventBus cycle. `CaseFileChangedEvent`. Autonomous workers write to CaseFile directly — `notifyAutonomousWork()` removed. |
| 6 | **Pluggable CaseFile** | `JsonCaseFile`, `JavaBeanCaseFile<T>`. `evalObjectTemplate` on interface. Hibernate Reactive. |
| 7 | **Sub-cases** | Wire sub-case spawning and completion propagation. `SubCaseBinding`. |
| 8 | **YAML schema + codegen** | Adopt and extend casehub-engine's schema. Build-time discovery. `casehub-quarkus` extension. |
| 9 | **Fluent Java DSL** | `casehub-quarkus` developer experience layer. |

---

## Open Items

- [ ] **`ProgressMarker` naming** — confirm with co-worker. Alternatives: `ObservabilityMarker`, `Signal`, `Indicator`.
- [ ] **`ContextChangeTrigger` naming** — keep or rename to `StateChangeTrigger`?
- [ ] **CaseState alignment** — casehub (PENDING/RUNNING/WAITING/SUSPENDED/COMPLETED/FAULTED/CANCELLED) vs casehub-engine (ACTIVE/COMPLETED/FAILED/SUSPENDED/TERMINATED/WAITING). Which wins?
- [ ] **`CaseDefinitionRegistry` shim** — how do YAML-defined cases (no lambdas) and Java-defined cases (with lambdas) coexist cleanly?
- [ ] **Nested stage lifecycle** — partially implemented in `ListenerEvaluator`. Full wiring into async event cycle required. Blocking dependency for stage-scoped long-lived workers. Must publish `StageActivatedEvent` and `StageExitedEvent`.
- [ ] **Long-lived worker lifecycle** — new concept. Needs: lifecycle scope (`CASE`, `STAGE`, `BINDING`), start/stop event subscription, internal state management across CaseFile interactions. Stage-scoped workers depend on nested stage events.
- [ ] **ConflictResolver** — interface defined, not integrated. Does it belong in async model?
- [ ] **Dead letter replay** — marked TODO. Needs design.
- [ ] **Java DSL finalisation** — user has refinements coming.
