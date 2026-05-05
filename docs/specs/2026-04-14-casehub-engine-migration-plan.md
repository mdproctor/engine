# CaseHub — Migration Plan (casehub-engine as home)
**Original date:** 2026-04-14
**Last reviewed:** 2026-04-26
**Overall status:** Phase 2 largely complete. casehub-quarkus and examples not started. Broker subsystem in progress via quarkus-work. Ready to name the next sprint.

---

## Design Principle

Nothing is locked in. Each PR is an opportunity to improve the design. If a better approach becomes apparent — simpler structure, dropped abstraction, merged concept — take it.

---

## Direction

casehub-engine is the project home. casehub's CMMN/Blackboard/Resilience features are ported in as Maven modules on top of casehub-engine's reactive core (Vert.x + Quartz, EventLog, Signal, JQ). casehub will be archived once migration is complete.

GitHub repo: currently `casehubio/engine`. Rename to `casehubio/casehub-engine` pending (agreed, not yet done — see Deferred). Ultimate rename to `casehubio/casehub` is the final end state.

---

## Current Module Status (as of 2026-04-26)

```
casehub-engine/
├── api/                          ✅ complete
├── engine-model/                 ✅ complete
├── engine/                       ✅ complete
├── casehub-blackboard/           ✅ complete (ListenerEvaluator missing — see gaps)
├── casehub-resilience/           ✅ complete (IdempotencyService missing — see gaps)
├── casehub-ledger/               ✅ complete (quarkus-ledger integration)
├── casehub-persistence-memory/   ✅ complete
├── casehub-persistence-hibernate/ ✅ complete
├── scheduler-quartz/             ✅ exists (replacement under #130)
├── schema/                       ✅ complete
├── codegen/                      ✅ complete
├── casehub-quarkus/              ❌ NOT STARTED
└── casehub-examples/             ❌ NOT STARTED
```

---

## What Is Done

### Phase 0 — Decisions (all resolved)

| Decision | Resolution |
|---|---|
| Module structure | ✅ Agreed and built |
| `CaseState`/`CaseStatus` alignment | ✅ `CaseStatus`: `RUNNING / WAITING / SUSPENDED / COMPLETED / FAULTED / CANCELLED` — casehub's set wins. No `PENDING`, no `ACTIVE`. |
| `ContextChangeTrigger` naming | ✅ Kept as-is in `api/` |
| `ProgressMarker` naming | ✅ Kept as `Milestone` — see Milestone design below |

### Phase 1 — Low-hanging fruit (all done)

| Item | Where |
|---|---|
| `LoopControl` SPI | `api/` |
| `ChoreographyLoopControl` default impl | `engine/` |
| `PlanningStrategyLoopControl` | `casehub-blackboard/` |
| `LambdaExpressionEvaluator`, unsealed `ExpressionEvaluator` | `api/` |
| Rename `DispatchRule`→`Binding`, `CaseHubDefinition`→`CaseDefinition` | PR #38 |
| `evalObjectTemplate()` on `CaseContext` interface | `api/` (also CaseContextImpl, WorkOrchestrator, WorkerExecutionTask) |

### Phase 2 — Modules (all except casehub-quarkus and examples)

**api/** — core interfaces and value objects:
`CaseContext`, `CaseStatus`, `Binding`, `Trigger`, `ScheduleTrigger`, `Goal`, `GoalExpression`, `GoalKind`, `Milestone`, `MilestoneLifecycleStatus`, `LoopControl`, `ExpressionEvaluator`, `LambdaExpressionEvaluator`, `JQExpressionEvaluator`, `ExpressionEngine`, `Capability`, `Worker`, `WorkerContext`, `WorkerProvisioner`, `WorkerStatusListener`, `WorkerContextProvider`, `CaseChannelProvider`, `ReactiveCaseChannelProvider`, `CaseChannel`, `WorkRequest`, `WorkResult`, `WorkStatus`, `WorkOrchestrator`, `PendingWorkRegistry`, `ContextChangeTrigger`, `ExecutionPolicy`, `RetryPolicy`, `BackoffStrategy`, `CaseDefinition`

**engine/** — reactive orchestration core:
`CaseHubReactor`, `CaseHubRuntimeImpl`, `CaseContextImpl`, `CaseDefinitionRegistry`, `ChoreographyLoopControl`, event handlers (CaseStarted, CaseContextChanged, GoalReached, SignalReceived, MilestoneReached/Activated/Completed), JQ engine, recovery, diff strategies, `WorkerExecutionManager`, `WorkerExecutionScheduler`, `WorkflowExecutor`, `PendingWorkRegistry`

**casehub-blackboard/** — CMMN plan model layer:
`CasePlanModel`, `DefaultCasePlanModel`, `PlanItem`, `Stage`, `StageStatus`, `StageLifecycleEvaluator`, `PlanningStrategy`, `DefaultPlanningStrategy`, `PlanningStrategyLoopControl`, `SubCase`, `SubCaseCompletionStrategy`, `BlackboardRegistry`, stage events (Activated/Completed/Terminated), `MilestoneAchievementHandler`, `PlanItemCompletionHandler`

**casehub-resilience/** — fault tolerance:
`ConflictResolver`, `LastWriterWinsConflictResolver`, `ConflictException`, `DeadLetterQueue`, `DeadLetterEntry`, `DeadLetterStatus`, `DeadLetterEventHandler`, `DeadLetterQuery`, `PoisonPillDetector`, `PoisonPillWorkerExecutionGuard`, `CaseTimeoutEnforcer`, `BackoffDelayCalculator`

**casehub-ledger/** — immutable audit trail via quarkus-ledger:
`CaseLedgerEntry`, `CaseLedgerEntryRepository`, `CaseLedgerEventCapture` — observes `CaseLifecycleEvent` async, writes hash-chained ledger entries for all significant lifecycle transitions. Actor inference: system/null→SYSTEM, versioned persona→AGENT, other→HUMAN.

**casehub-persistence-memory/** — in-memory SPI impls (tests):
`InMemoryCaseInstanceRepository`, `InMemoryCaseMetaModelRepository`, `InMemoryEventLogRepository`

**casehub-persistence-hibernate/** — JPA/Panache SPI impls (production):
`JpaCaseInstanceRepository`, `JpaCaseMetaModelRepository`, `JpaEventLogRepository`, entity classes

### Casehub-only removals — all gone ✅

None of the old casehub-specific items were ported across:

| Item | Fate |
|---|---|
| `notifyAutonomousWork()` | Gone — Signal mechanism replaces it |
| `NotificationService` | Gone — Vert.x EventBus replaces it |
| `CaseFileItemEvent` | Gone — `CaseStateContextChangedEvent` replaces it |
| `TaskLifecycleEvent` | Gone — EventLog entries replace it |
| `CaseFileContribution` | Gone — absorbed into EventLog |
| `CaseFileItem` class | Dropped — enrich `WORKER_EXECUTION_COMPLETED` EventLog entries with key-level diff instead |
| `StaleVersionException` | Dropped — not needed without `putIfVersion` |

---

## What Remains

### Gaps in existing modules

| Item | Module | Notes |
|---|---|---|
| `IdempotencyService` TTL | casehub-resilience/ | ✅ `casehub.idempotency.window` config + `findSchedulingEvents(after)` cutoff. Closes #193. |
| Dead letter replay | casehub-resilience/ | ✅ DeadLetterReplayService (explicit) + DeadLetterAutoReplayJob (@Scheduled, disabled by default). Closes #194. |
| `SubCaseBinding` | casehub-blackboard/ | ✅ Resolved — SubCase moved to api, subCase field on Binding, SubCaseExecutionHandler, SubCaseCompletionListener, CaseResumptionService. Closes #195, closes #76. |

### New modules not started

**`casehub-quarkus/`** — the Quarkus extension. Biggest remaining chunk.
- CDI integration, `@CaseType` qualifier
- `TaskDefinition` syntactic sugar (compiles to `Worker` + `Binding`)
- Build-time discovery via Jandex + YAML classpath scan
- Dev Services, health checks, live reload for YAML definitions
- `CaseDefinitionRegistry` shim — clean coexistence of YAML-defined and Java-lambda cases

**`casehub-examples/`** — ported from casehub, updated to casehub-engine API.

### Phase 3 — needs design before PR

| Item | Notes |
|---|---|
| `FlowWorker` | Evolve from CDI polling to `WorkerScheduleEvent`-driven. Module home TBD. |
| Long-lived workers + lifecycle scopes | `CASE`/`STAGE`/`BINDING` scopes. Depends on nested stage events. |
| `JavaBeanCaseFile<T>` | Typed POJO-backed `CaseFile`. `engine/` or `api/`. |
| `MapCaseFile` | Migration path from current casehub. |

### Broker subsystem — via quarkus-work

`TaskBroker`/`TaskScheduler`/`WorkerRegistry`/`WorkerSelectionStrategy` are not being built as casehub-internal modules. Instead:
- The **SPI contracts** live in `api/` already: `WorkRequest`, `WorkResult`, `WorkStatus`, `WorkOrchestrator`, `WorkerProvisioner`, `WorkerStatusListener`
- The **implementation** is in **quarkus-work** — `WorkBroker`, `WorkerSelectionStrategy`, `WorkloadProvider`
- Active epics: #131 (WorkBroker integration), #152 (Worker Provisioner SPIs)

This is the right call — keeps casehub-engine as the coordination engine and quarkus-work as the work-routing layer.

---

## Open GitHub Issues (casehubio/engine)

### Active epics
- **#152** Worker Provisioner SPIs — `WorkerProvisioner`, `WorkerContextProvider`, `WorkerStatusListener`, `CaseChannelProvider` SPIs wired into the engine
- **#145** quarkus-ledger integration — casehub-ledger module (mostly done via PRs #147, #148)
- **#131** WorkBroker integration — strategy-based worker selection + orchestration model

### Active feature issues
- **#136** WAITING state — case suspension and resumption for orchestrated work
- **#135** Orchestration model — WorkRequest, WorkResult, WorkOrchestrator, PendingWorkRegistry
- **#134** Choreography worker selection via WorkBroker
- **#133** WorkBroker CDI producers and CasehubWorkloadProvider
- **#130** Replace Quartz with native Vert.x — eliminate heavyweight scheduler

### Backlog (feature ideas, not yet planned)
- **#116** Compliance and audit workflows
- **#115** Human escalation — first-class human workers with full provenance
- **#114** ReAct cycles with auditability
- **#113** Regulatory decision automation with traceable reasoning
- **#112** Sub-case orchestration — hierarchical case composition
- **#111** Multi-modal agent pipelines
- **#110** LLM goal decomposition

---

## Unified Milestone Design (decided, implemented)

`Milestone` is a single unified concept — no rename, no split.

**Milestone vs Goal:**
- **Milestones** are neutral waypoints passed through on the way to a goal. No success/failure polarity.
- **Goals** are terminal outcomes with `GoalKind` (SUCCESS/FAILURE). Drive case completion.

**Two depths, one class:**
- *Lightweight:* `MilestoneReachedEvent` fires, recorded in EventLog as `MILESTONE_REACHED`. Sufficient for observability.
- *Lifecycle-tracked:* With `CasePlanModel` present, milestones get PENDING → ACHIEVED lifecycle via `MilestoneActivatedEvent`/`MilestoneCompletedEvent`. No change to the `Milestone` class itself.

---

## Deferred

**Repo rename** — two steps:
1. `casehubio/engine` → `casehubio/casehub-engine` (agreed, pending — do this soon for consistency)
2. `casehubio/casehub-engine` → `casehubio/casehub` (final end state, after examples exist and old casehub is archived)

GitHub auto-redirects old clone URLs. Maven `distributionManagement` URL in `pom.xml` needs updating immediately after step 1. Any repo with a hardcoded `maven.pkg.github.com/casehubio/engine` reference also needs updating (currently: only `pom.xml` itself).

**Archive old `casehub` repo** — once casehub-engine has working examples and the migration is demonstrably complete.

---

## Priority Order for Next Sprint

1. **Rename** `engine` → `casehub-engine` on GitHub + update pom.xml distributionManagement URL
2. **#152 / #131** Worker Provisioner SPIs + WorkBroker integration — the active epics
5. **`casehub-quarkus/`** — plan the extension before writing it; several open decisions (registry shim, TaskDefinition sugar, Dev Services scope)
6. **`casehub-examples/`** — needed before archiving old casehub
