# casehub-engine — Contributor Guide

> Internal architecture, module structure, and extension points for platform builders.

**GitHub:** [casehubio/engine](https://github.com/casehubio/engine)

---

## Module Structure

The reactor POM declares 18 modules. Two orphaned directories (`blackboard/`, `queue/`) exist on disk but are **not** in the reactor build.

| Module | Folder | Type | Purpose |
|---|---|---|---|
| `casehub-engine-api` | `api` | Pure Java + langchain4j | SPI interfaces, domain model (`Binding`, `CaseDefinition`, `Goal`, `Milestone`), `Agent` wrapper, routing SPIs, mesh SPIs, context types, DAG plan types, HTN decomposition SPIs |
| `casehub-engine-common` | `common` | Pure Java (no CDI) | Domain objects (`CaseMetaModel`, `CaseInstance`), persistence SPIs, scheduler SPIs, `JQEvaluator`, `EventLog`, internal event records, scoped worker sessions, config/secret manager SPIs |
| `casehub-engine` | `runtime` | Quarkus module | Choreography handlers, orchestration, worker scheduling, expression engine, `CaseHubRuntime`, `EngineStrategyResolver` |
| `casehub-engine-planning` | `planning` | Quarkus module | CMMN planning orchestration — `PlanningStrategy`, `CasePlanModel`, `PlanItem`, `Compound` lifecycle, `CompletionSemantics`, sub-case orchestration |
| `casehub-engine-rest` | `rest` | Quarkus module | Opt-in JAX-RS REST surface. 5 resources, 14 DTOs, 6 exception mappers, 1 service. All endpoints `@RunOnVirtualThread`. OpenAPI-annotated |
| `casehub-engine-resilience` | `resilience` | Optional module | Dead Letter Queue with auto-replay, PoisonPill sliding-window circuit breaker, `BackoffDelayCalculator`, `CaseTimeoutEnforcer`, `ConflictResolver` SPI |
| `casehub-engine-ledger` | `ledger` | Optional module | Tamper-evident case lifecycle ledger; `TrustCandidateClassifier`; `TrustSignalProvider`; `TrustWeightedImplementationRoutingStrategy`; `WorkerDecisionEventCapture` |
| `casehub-engine-ai` | `engine-ai` | Optional module | `AgentEmbeddingProvider` SPI, `SemanticSignalProvider` (cosine similarity), `EmbeddingCache` (LRU, 500 entries) |
| `casehub-engine-actor-state` | `actor-state` | Optional module | Unified actor workload view (`GET /actors/{actorId}/state`) via `ActorStateContributor` SPI. 4 built-in contributors: engine, ledger, qhorus, work |
| `casehub-engine-scheduler-quartz` | `scheduler-quartz` | Module | Quartz-based worker execution (RAM store). `QuartzWorkerExecutionManager`, `QuartzJobScheduler`, retry service, conditional/scheduled trigger jobs, milestone SLA timeout |
| `casehub-engine-schema` | `schema` | Build-time | `CaseDefinition.yaml` JSON Schema generated Java model via jsonschema2pojo. `CasehubRuleFactory` for typed `additionalProperties` |
| `casehub-engine-persistence-hibernate` | `persistence-hibernate` | Module | JPA/Panache persistence (PostgreSQL). 7 repos, 5 entities, RLS policy applicator, tenant-aware base |
| `casehub-engine-persistence-memory` | `persistence-memory` | Test module | In-memory thread-safe persistence for `@QuarkusTest` without Docker. 6 implementations + `DefaultTestPrincipal` |
| `casehub-engine-codegen` | `codegen` | Build-time | `CasehubCodegen` CLI + `CasehubRuleFactory` for jsonschema2pojo |
| `casehub-engine-flow` | `flow` | Optional module | Serverless Workflow execution. `FlowWorkerFunction`, `CallableDispatcher` SPI, `FlowExecutionRegistry`, `CasehubFlow` static utility |
| `casehub-engine-testing` | `testing` | Test module | `@Alternative @Priority(1)` wrappers for auto-selection in `@QuarkusTest`. `WorkResultSubmitter` test helper |
| `casehub-engine-inbound` | `casehub-engine-inbound` | Optional module | `InboundSignalBridge` (connectors to case signals), `InboundWorkItemBridge` (qhorus to work items), `InboundWorkItemPolicy` SPI |
| `casehub-examples-typed-context` | `examples/typed-context` | Example module | Demonstrates `CaseContextStore` pluggability. Not published (`deploy.skip=true`) |

**Orphaned directories (not in reactor):**

| Directory | Status | Notes |
|---|---|---|
| `blackboard/` | Dead code | Replaced by `planning` module. Issue #812 tracked cleanup. Source exists but is never compiled |
| `queue/` | In development | Label-driven case queue system. Not yet activated in reactor. Contains `CaseQueueService`, `CaseLabelEvaluator`, `CaseQueueEntryStore` SPI |

**Removed:** `casehub-engine-work-adapter` — relocated to `casehub-work` as `casehub-work-engine-adapter`.

---

## Internal Architecture

### Engine Handlers (Virtual Threads)

All engine handlers run on virtual threads via `@RunOnVirtualThread` or CDI `@ObservesAsync`. Reactive Mutiny pipelines were removed during the virtual thread migration (issue #770). `synchronized` blocks on hot paths were converted to `ReentrantLock` to avoid virtual thread pinning (issue #774).

Two execution paths:

- **Choreography** (`CaseContextChangedEventHandler`) — evaluates `contextChange.filter` AND `binding.when()` to find eligible bindings for RUNNING and WAITING cases, selects via `LoopControl`, dispatches by target type. `PlanningStrategyLoopControl` handles WAITING (planning active); `ChoreographyLoopControl` restricts to RUNNING only.
- **Orchestration** (`WorkOrchestrator`, interface in `common/spi/`, implemented by `DefaultWorkOrchestrator` in runtime) — synchronous dispatch path; integrates `CapabilityHealth` probe to filter/sort agent-backed candidates before selection.

### Event-Driven Architecture

Internal engine events use CDI `@ObservesAsync` for cross-module decoupling. All 28 event record types are defined in `common/internal/event/`. Lifecycle events for optional modules (ledger, etc.) use `CaseLifecycleEvent` fired via `Event.fireAsync()` — zero overhead when no observer is registered.

The planning module uses Vert.x event bus for a small number of internal addresses (`BlackboardEventBusAddresses`) with locally-registered codecs (`BlackboardEventCodecRegistrar`).

### EngineStrategyResolver

`@Alternative @Priority(1) @ApplicationScoped` — overrides platform's `DefaultStrategyResolver` because Quarkus ARC build-time pruning doesn't reliably discover all beans with `@Any Instance<NamedStrategy>`. Registers strategies from typed Instance injections plus a catch-all. Methods: `resolve(Class<T>, String id)`, `find(Class<T>, String id)`, `defaultStrategy(Class<T>)`, `available(Class<T>)`. Detects `@DefaultBean` via `InjectableBean.isDefaultBean()`. Has `forTest()` factory for unit testing without CDI.

### Routing Architecture

**Pipeline:** Binding eligibility -> Compound gating -> ImplementationRouting -> PlanningStrategy -> AgentRouting (or HumanTaskRouting) -> Worker scheduling.

**Composable routing:** `ComposableAgentRoutingStrategy` (`@DefaultBean`, id=`"composable"`) blends scores from independent `RoutingSignalProvider` implementations. Each provider scores candidates independently; the compositor computes a weighted sum. `CandidateSignal` is sealed: `Score` | `Exclude` | `Escalate`.

Built-in signal providers:
- `WorkloadSignalProvider` — load-balanced routing via `WorkloadDataProvider`
- `TrustSignalProvider` (ledger) — trust-phase-based scoring (QUALIFIED->score, BOOTSTRAP->neutral, others->Exclude/Escalate)
- `ExperienceSignalProvider` — CBR experience-based scoring via `ExperienceAnalyser`
- `PersonalitySignalProvider` — JPAF personality-adaptive routing with cognitive function alignment and disposition tracking
- `SemanticSignalProvider` (engine-ai) — cosine-similarity routing via `AgentEmbeddingProvider`

**Strategy SPIs:** `AgentRoutingStrategy`, `ImplementationRoutingStrategy`, `HumanTaskRoutingStrategy`, `CandidateMatchingStrategy`, `CandidateSetStrategy`, `DecompositionStrategy` — all follow the `NamedStrategy` convention.

**Routing outcome recording:** `RoutingOutcomeRecorder` records `RoutingOutcome` (enum: `COMPLETED`, `DECLINED`, `FAILED`, `EXPIRED`, `REROUTED`, `ESCALATED`) for CBR learning and personality adaptation.

### Worker Execution Lifecycle

Full sequence from dispatch to case context update:

```
WorkerScheduleEvent
  -> WorkerScheduleEventHandler
      -> WorkerContextProvider.buildContext()
      -> WorkerExecutionManager.submit()
  -> Quartz fires job
      -> QuartzWorkerExecutionJobListener.jobToBeExecuted()
          -> WorkerStatusListener.onWorkerStarted()
          -> EventLog: WORKER_EXECUTION_STARTED
      -> QuartzWorkerExecutionJob.execute()
          -> WorkerExecutor.execute()
          -> publish WorkflowExecutionCompleted
      -> QuartzRetryService (on failure)
          -> evaluate RetryDecision (Retry or Exhaust)
          -> reschedule or publish WorkerRetriesExhaustedEvent
  -> WorkflowExecutionCompletedHandler
      -> apply output with ContextDiffStrategy
      -> EventLog: WORKER_EXECUTION_COMPLETED
      -> resumeIfWaiting()
      -> WorkerStatusListener.onWorkerCompleted()
      -> CaseLifecycleEvent: WorkerExecutionCompleted
      -> publish CONTEXT_CHANGED -> bindings re-evaluate
```

### Worker Execution Manager Routing

`WorkerExecutionRoutingStrategy` selects which `WorkerExecutionManager` backend handles a dispatch. The `@WorkerBackend` CDI qualifier distinguishes backend implementations from the composite manager. Default: `QuartzWorkerExecutionManager`.

### Scoped Worker Sessions (`common/internal/worker/scope/`)

`ScopedWorkerRegistry` tracks active scoped workers keyed by `(caseId, bindingName)`. `ScopedWorkerSession` is sealed:
- `Persistent` — long-running virtual thread with a mailbox of `ContextEvent` objects. Receives context changes until SHUTDOWN sentinel
- `Reinvoked` — accumulates state between invocations. State threaded between each dispatch

`ScopedWorkerOutputEvent` carries scoped worker output for context application. `CompoundActivatedEventHandler` dispatches `scopeActivated` trigger bindings when a compound becomes ACTIVE.

### Compound PlanItemDefinition Hierarchy

`PlanItemDefinition` is sealed: `Primitive` (leaf) and `Compound` (container with children, planning strategy, `CompletionSemantics`, `DispatchMode`).

`CompletionSemantics` is sealed: `All` (all children must complete), `MofN` (M out of N children), `FirstWins` (first terminal child completes the compound).

`CompoundLifecycleEvaluator` evaluates entry/exit conditions each planning cycle. `CompoundCompletionEvaluator` walks the compound parent chain after child state changes and propagates completion up the tree. `CompoundStrategyDispatcher` routes bindings to the correct `PlanningStrategy` per compound.

### Planning Module Architecture

`PlanningStrategyLoopControl` is the core `LoopControl` implementation that replaces `ChoreographyLoopControl` when planning is active. It orchestrates:
1. Plan model creation via `BlackboardPlanConfigurer`
2. Compound lifecycle evaluation (`CompoundLifecycleEvaluator`)
3. Strategy dispatch (`CompoundStrategyDispatcher`)
4. Implementation routing (`ImplementationRoutingStrategy`)

Built-in strategies: `ChoreographyStrategy` (fire all eligible), `SequentialPlanningStrategy` (one at a time in declared order).

`BlackboardRegistry` is the `@ApplicationScoped` singleton tracking per-case `CasePlanModel` instances and the worker-to-PlanItemId completion index. Supports lazy hydration from `PlanItemStore` for restart recovery.

Sub-case orchestration: `SubCaseExecutionHandler` spawns child cases, `SubCaseCompletionService` handles child completion with grouped M-of-N threshold logic via `SubCaseGroupPolicy`.

### Oversight Gate Architecture

Multi-approver oversight for consequential worker actions:

1. Worker returns `PlannedAction` via `WorkerResult`
2. `ActionRiskClassifier` evaluates risk — `ChainedActionRiskClassifier` composes multiple classifiers ("most restrictive wins")
3. If `GateRequired`: `ActionGateScheduleEvent` creates approval WorkItem(s)
4. `QuorumConfig` supports M-of-N approval with dynamic instance count from candidate group membership
5. Gate lifecycle events: `ActionGateApprovedEvent`, `ActionGateRejectedEvent`, `ActionGateExpiredEvent`, `ActionGateCancelledEvent`
6. On approval: PlanItem resumes execution
7. On rejection/expiry: `ActionGateExpiredPlanItemHandler` / `ActionGateRejectedPlanItemHandler` marks PlanItem FAULTED

### Case-Based Reasoning Integration

CBR enables experience-driven routing and planning:

1. `CbrConfig` on `CaseDefinition` configures feature extraction, similarity thresholds, and retrieval timing
2. `CbrCaseRetainObserver` captures case outcomes with trust scores for retention
3. `CbrRetrievalService` retrieves similar past cases at case startup, injecting `RetrievedExperience` into `CaseContext`
4. `ExperienceSignalProvider` and adaptation-aware CBR scoring influence routing decisions
5. `CbrHumanTaskRoutingStrategy` uses CBR for human task candidate scoring

### Expression Engine

Engine expression evaluation is unified with the platform hierarchy:
- `ExpressionEngine` extends `platform-api ExpressionEngine`
- `ExpressionEvaluator` extends `platform-api ExpressionEvaluator`
- `LambdaExpressionEvaluator` extends `platform LambdaExpression<CaseContext, Boolean>`
- `ExpressionEngineRegistry` delegates to `platform ExpressionEngineRegistry`

This replaced the engine-specific expression engine (issue #747-750).

### Agent Mesh SPIs (`api/spi/mesh/`)

Platform-level agent mesh primitives (pure Java, no CDI):

| Type | Purpose |
|---|---|
| `CaseChannelLayout` | SPI: declares channel topology for an agent case |
| `NormativeChannelLayout` | Canonical 4-channel impl: work / observe / oversight / coordination |
| `SimpleLayout` | 2-channel impl: work + observe, no governance gate |
| `MeshParticipationStrategy` | SPI: `strategyFor(workerId, caseId)` returns participation stance |
| `ActiveParticipationStrategy` | Active participation (contributes to case) |
| `ReactiveParticipationStrategy` | Reactive participation (responds on demand) |
| `SilentParticipationStrategy` | Silent participation (monitors only) |

### DAG Parallel Execution (`common/plan/`, `api/plan/`)

Dependency-graph-aware parallel execution driver:

| Type | Location | Purpose |
|---|---|---|
| `DagPlan<T>` | api | Immutable validated DAG. Factories: `singleton`, `sequence`, `parallel`, `fromNodes` |
| `DagNode<T>` | api | `id`, `task`, `dependsOn`, `joinType` (ALL_OF or ANY_OF) |
| `TaskNode<T>` | api | Concrete `DagNode` implementation |
| `DagDriver<T, R>` | common | Single-use executor. `STREAMING` or `BARRIER` dispatch modes. Virtual threads |
| `NodeState<R>` | common | Sealed: `Pending`, `Dispatched`, `Completed`, `Failed`, `Skipped`, `Cancelled` |
| `DagResult<R>` | common | `nodeStates`, `completedResults`, `allSucceeded`, `elapsed` |
| `DagEventListener<T, R>` | common | Callback for node lifecycle events |
| `DispatchMode` | common | `STREAMING` (fire as ready) or `BARRIER` (wave-by-wave) |

### CaseDefinitionRegistry

`DefaultCaseDefinitionRegistry` stores definitions in `Map<CaseKey, RegistryEntry>` where `CaseKey` is an immutable record `(namespace, name, version)`. `RegistryEntry` is an inner record `(CaseDefinition, CaseMetaModel)`. Self-healing: if registry lookup returns null (can happen after restart), re-registers from the persisted `CaseMetaModel` (issue #798).

Goals referenced in `GoalExpression` but not declared in `CaseDefinition.getGoals()` are rejected at registration time (issue #84).

### CapabilityHealth Integration

Optional integration with `casehub-eidos-api`. `WorkOrchestrator` probes agent-backed workers via `CapabilityHealth.probe()` before candidate selection:
- `Unavailable` — hard filter (removed from candidates)
- `EpistemicallyWeak` — preference demotion (sorted last)
- `Degraded` — keep, sort after `Ready`
- No descriptor — skip probe, assume capable

`NoOpCapabilityHealth` `@DefaultBean` returns `Ready` for all probes when eidos is not on the classpath.

### Config and Secret Resolution (`common/internal/config/`)

`ConfigManager` and `SecretManager` SPIs enable JQ expressions to reference runtime configuration via `$config` and `$secret` scope variables in `JQEvaluator`. `NoOpConfigManager` and `NoOpSecretManager` provide defaults. Exceptions: `ConfigResolutionException`, `ConfigMapNotFoundException`, `SecretNotFoundException`.

### Tenancy Enforcement (persistence-hibernate)

All JPA repositories extend `TenantAwareRepository`:
- `withTenantTransaction(tenancyId, work)` — sets `SET LOCAL "casehub.tenancy_id"` for RLS
- `withCrossTenantTransaction(work)` — sets `SET LOCAL ROLE casehub_crosstenancy` (BYPASSRLS)

Config: `casehub.rls.enabled` (default false). `@CrossTenant` CDI qualifier gates access to cross-tenant SPIs. `RlsPolicyApplicator` creates RLS policies on engine tables at startup.

### Persistence Entities (persistence-hibernate)

| Entity | Table | Purpose |
|---|---|---|
| `CaseInstanceEntity` | `case_instance` | Case state, parent refs, labels, tenancy, lifecycle scope column |
| `CaseMetaModelEntity` | `case_meta_model` | Case type definition metadata, JSON definition |
| `EventLogEntity` | `event_log` | Case events with type, payload, metadata, DB-generated sequence |
| `PlanItemEntity` | `plan_item` | Binding status, target type, executor metadata, compound parent ref, lifecycle scope |
| `SubCaseGroupEntity` | `subcase_group` | Parent-child tracking, completion/rejection counts, threshold policy |

### Schema Management

No Flyway for engine tables — Hibernate `drop-and-create` only. `casehub-engine-ledger` uses Flyway migrations from `casehub-ledger` plus its own `V2000__case_ledger_entry.sql`. Quartz uses RAM store, not JDBC.

### SPI Placement Rules

- Operational SPIs (worker provisioning, lifecycle, channels, routing, risk classification) go in `api/spi/`
- Persistence SPIs (`CaseMetaModelRepository`, `CaseInstanceRepository`, `EventLogRepository`, `PlanItemStore`, `SubCaseGroupRepository`) go in `common/spi/`
- Scheduler SPIs (`JobScheduler`, `WorkerExecutionManager`, `WorkerExecutionRoutingStrategy`) go in `common/spi/scheduler/`
- CDI events for optional observers go in `common/spi/event/`
- Query builders go in `common/spi/query/`
- Exception: if an operational SPI takes `CaseInstance` or other `common/internal/` types as parameters, it goes in `common/spi/` to avoid circular dependency (`api` <-- `common` <-- `api`). `WorkOrchestrator` is the current example.

### REST Module Architecture

`casehub-engine-rest` provides an opt-in JAX-RS surface. Key design decisions:
- All dependencies on quarkus-rest-jackson, CDI, and JAX-RS are `<scope>provided</scope>` — consumers supply the runtime
- All endpoints use `@RunOnVirtualThread` for virtual thread execution
- All endpoints annotated with MicroProfile OpenAPI annotations
- `CaseService` is a shared `@ApplicationScoped` CDI bean handling case start and existence checks
- Error handling via 5 exception mappers returning RFC 7807 `ProblemDetail` records
- `CurrentPrincipal` injection for tenant scoping

### Queue Module Architecture (Not Yet in Reactor)

The `queue/` directory contains a complete case queue management system, not yet activated in the reactor build:

- `CaseLabelEvaluator` — observes `CaseLifecycleEvent`, evaluates label rules against case context, fires `CaseQueueEvent`
- `CaseQueueEntryManager` — materializes queue events into `CaseQueueEntry` records
- `CaseQueueService` — claim/release/escalate operations with tenancy verification
- `CaseQueueEntryStore` SPI — persistence abstraction with `InMemoryCaseQueueEntryStore` default
- `CaseQueueViewManager` — creates/deletes queue views with deterministic UUID generation
- `CaseLabelReconciler` — startup reconciliation for label and view membership consistency

### Qhorus Message Signal Bridge

`QhorusMessageSignalBridge` (CDI `@ObservesAsync MessageReceivedEvent`) bridges commitment-resolving Qhorus messages (RESPONSE, DONE, DECLINE, FAILURE) on `case-{caseId}/{purpose}` channels to `CaseHubRuntime.signal()`. DECLINE and FAILURE trigger the worker failure cascade.

### Agent Mesh — Layer 4 (Enforcement)

casehub-engine is Layer 4 in the Qhorus normative accountability framework:
- `FULFILLED` commitment -> case continues
- `FAILED` / `EXPIRED` commitment -> recovery policy triggers

---

## Dependencies

### Depends On

| Repo | How |
|---|---|
| `casehub-ledger` | Optional, via `casehub-engine-ledger` module |
| `casehub-qhorus-api` | `MessageType` enum for channel messaging |
| `casehub-platform-api` | `ActorType`, `PreferenceProvider`, `Path`, `CurrentPrincipal`, `ExpressionEngine`, `ExpressionEvaluator` |
| `casehub-platform-expression` | `JQEvaluator` for expression evaluation (transitively) |
| `casehub-platform-view` | Queue module (SubjectViewSpec for queue views) |
| `casehub-eidos-api` | Optional — `AgentDescriptor`, `CapabilityHealth` for agent health probing |
| `casehub-worker-api` | Foundation — `WorkerFunction<T, R>` type, `WorkerResult`, `PlannedAction` |
| `casehub-work-api` | Compile scope — `CaseSignalSink` injection |

### Depended On By

| Repo | Module | How |
|---|---|---|
| `claudony` | `claudony-casehub` | Implements the 4 worker provisioner SPIs, provides `ClaudonyReactiveCaseChannelProvider` |
| `devtown` | `app` | Runtime dep — `casehub-work-engine-adapter` + `casehub-engine-planning` for HITL |
| `casehub-clinical` | `runtime` | Runtime dep — adverse event case coordination |

---

## Current State

- Core choreography and orchestration: done
- WAITING state durability (restart-safe): done
- `casehub-ledger` integration: done
- Human worker integration (`humanTask` YAML binding): done — inline + template modes
- Unified execution model (TaskStatus, TaskDescriptor, ExecutorRef): done
- ContextBridge protocol + CaseContextStore SPI: done
- DAG parallel execution driver: done
- RoutingSignalProvider + RoutingPromptSection SPIs: done (promoted to engine-api)
- GoalExpression + GoalBasedCompletion (multi-kind goals): done
- RoutingOutcome + RoutingOutcomeRecorder: done
- NamedStrategy + EngineStrategyResolver: done
- `AgentRoutingStrategy` SPI: done
- `ActionRiskClassifier` SPI + multi-approver oversight gate: done
- Resilience module (DLQ, PoisonPill, timeout, auto-replay): done
- Compound PlanItemDefinition hierarchy (Stage fully retired): done
- Virtual thread migration (handlers, persistence, SPIs): done
- Expression engine platform unification: done
- Lifecycle scopes (BINDING, COMPOUND, CASE): done
- Scoped worker dispatch, registration, output application: done
- PERSISTENT worker virtual thread spawning: done
- REINVOKED accumulated state threading: done
- CBR routing + human task routing: done
- Personality-adaptive routing (JPAF): done
- Composable routing signal architecture: done
- REST module (case lifecycle, context, signals, events): done
- Milestone/Goal alignment + unreferenced goal rejection: done
- CaseDefinition.yaml JSON Schema verification: done
- Queue module: in development (not yet in reactor)
- A2A/MCP interop: ahead
- Drools CEP integration: ahead
- Memory-informed action selection: ahead

---

## Design Documents

- [docs/DESIGN.md](https://raw.githubusercontent.com/casehubio/engine/main/docs/DESIGN.md) — choreography+orchestration models, worker SPI contracts, blackboard lifecycle
- [docs/adr/INDEX.md](https://raw.githubusercontent.com/casehubio/engine/main/docs/adr/INDEX.md) — architectural decision records (10 ADRs)
