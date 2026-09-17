# casehub-engine — Contributor Guide

> Internal architecture, module structure, and extension points for platform builders.

**GitHub:** [casehubio/engine](https://github.com/casehubio/engine)

For high-level architecture and design decisions, see `ARC42STORIES.md` in the workspace.

---

## Module Structure

The reactor contains 60+ modules organised in five groups: framework layers, foundation, schedulers/persistence, optional modules, and examples.

### Multi-Framework Layers

Each core layer ships as three modules — `-core` (framework-neutral, all logic), Quarkus (thin CDI wiring), and Spring (thin Spring wiring). The `-core` module is the implementation; framework modules are binding shims.

| Layer | Core | Quarkus | Spring |
|-------|------|---------|--------|
| Common — domain objects, SPIs, event types | `common-core` | `common` | `common-spring` |
| Runtime — choreography handlers, orchestration, worker scheduling | `runtime-core` | `runtime` | `runtime-spring` |
| Planning — PlanningStrategy, CasePlanModel, PlanItem, Compound lifecycle | `planning-core` | `planning` | `planning-spring` |
| Resilience — DLQ, PoisonPill, timeout, auto-replay | `resilience-core` | `resilience` | `resilience-spring` |
| Ledger — tamper-evident lifecycle ledger, trust scoring | `ledger-core` | `ledger` | `ledger-spring` |
| Engine Support — framework-neutral POJOs for small engine modules | `engine-support-core` | — | `engine-support-spring` |

### Foundation

| Module | Folder | Purpose |
|--------|--------|---------|
| `casehub-engine-api` | `api` | SPI interfaces, domain model (`Binding`, `CaseDefinition`, `Goal`, `Milestone`), `Agent` wrapper, routing SPIs, mesh SPIs, context types, DAG/HTN plan types |
| `casehub-engine-schema` | `schema` | jsonschema2pojo generated model from `CaseDefinition.yaml` |
| `casehub-engine-codegen` | `codegen` | YAML record code generator (`CasehubRecordCodegen` CLI) |
| `casehub-engine-generator` | `generator` | JSON Schema generator from Java model types via reflection |
| `casehub-engine-annotations` | `annotations` | Annotation-driven `@Case` programming model (Quarkus extension: `runtime` + `deployment` sub-modules) |

### Schedulers & Persistence

| Module | Folder | Purpose |
|--------|--------|---------|
| `casehub-engine-scheduler-quartz` | `scheduler-quartz` | Quartz worker execution (RAM store). `QuartzWorkerExecutionManager`, retry service, scheduled/conditional trigger jobs |
| `casehub-engine-scheduler-dbscheduler` | `scheduler-dbscheduler` | db-scheduler alternative (`@WorkerBackend @Priority(10)` — wins when both present). H2 in-memory by default |
| `casehub-engine-persistence-hibernate` | `persistence-hibernate` | JPA/Panache (PostgreSQL). 7 repos, 5 entities, RLS policy applicator, tenant-aware base |
| `casehub-engine-persistence-memory` | `persistence-memory` | In-memory thread-safe persistence for `@QuarkusTest` without Docker |

### Optional Modules

Activated by adding to the consumer's classpath — same CDI/Spring discovery pattern.

| Module | Folder | Purpose |
|--------|--------|---------|
| `casehub-engine-rest` | `rest` | JAX-RS REST surface. All endpoints `@RunOnVirtualThread`, OpenAPI-annotated |
| `casehub-engine-graphql` | `graphql` | GraphQL resolvers — queries, mutations, subscriptions |
| `casehub-engine-flow` | `flow` | Serverless Workflow execution via `Worker(Workflow)` |
| `casehub-engine-a2a` | `a2a` | A2A remote agent invocation |
| `casehub-engine-mcp` | `mcp` | MCP server tool invocation |
| `casehub-engine-react` | `react` | ReAct (reason-act-observe) multi-turn worker loop |
| `casehub-engine-queue` | `queue` | Label-driven case queue operational layer |
| `casehub-engine-ai` | `engine-ai` | `AgentEmbeddingProvider` SPI, `SemanticSignalProvider` (cosine similarity) |
| `casehub-engine-inbound` | `casehub-engine-inbound` | Connector-to-signal bridge (`InboundSignalBridge`) |
| `casehub-engine-work-cloudevent` | `work-cloudevent` | CloudEvent bridge for distributed HumanTask/ActionGate/Judgment dispatch |
| `casehub-engine-actor-state` | `actor-state` | Unified actor workload view via `ActorStateContributor` SPI |

### Test Modules

| Module | Folder | Purpose |
|--------|--------|---------|
| `casehub-engine-testing` | `testing` | `@Alternative @Priority(1)` wrappers for auto-selection in `@QuarkusTest`. `WorkResultSubmitter` test helper |

### Examples

`examples/` contains paired `*-dsl` and `*-annotated` modules for each pattern: choreography, sequential, goap, cbr-ensemble, llm-decomposition, humantask, subcase, a2a, mcp. Plus scenario-specific annotated examples: incident-response, search-rescue, aircraft-maintenance, warehouse, wildfire-response. Not published (`deploy.skip=true`).

### Relocated Modules

- `casehub-work-adapter` → `casehub-work-engine-adapter` in the casehub-work repo
- `casehub-blocks-engine-adapter` → `casehub-blocks-engine-adapter` in the casehub-blocks repo

---

## Internal Architecture

### Engine Handlers (Virtual Threads)

All engine handlers run on virtual threads via `@RunOnVirtualThread` or CDI `@ObservesAsync`. Reactive Mutiny pipelines were removed during the virtual thread migration (issue #770). `synchronized` blocks on hot paths were converted to `ReentrantLock` to avoid virtual thread pinning (issue #774).

Two execution paths:

- **Choreography** (`CaseContextChangedEventHandler`) — evaluates `contextChange.filter` AND `binding.when()` to find eligible bindings for RUNNING and WAITING cases, selects via `LoopControl`, dispatches by target type. `PlanningStrategyLoopControl` handles WAITING (planning active); `ChoreographyLoopControl` restricts to RUNNING only.
- **Orchestration** (`WorkOrchestrator`, interface in `common-core/spi/`, implemented by `DefaultWorkOrchestrator` in `runtime`) — synchronous dispatch path; integrates `CapabilityHealth` probe to filter/sort agent-backed candidates before selection.

### Event-Driven Architecture

Internal engine events use CDI `@ObservesAsync` for cross-module decoupling. Event record types are defined in `common-core/internal/event/`. Lifecycle events for optional modules (ledger, etc.) use `CaseLifecycleEvent` fired via `Event.fireAsync()` — zero overhead when no observer is registered.

The planning module uses Vert.x event bus for a small number of internal addresses (`BlackboardEventBusAddresses`) with locally-registered codecs (`BlackboardEventCodecRegistrar`).

### EngineStrategyResolver

`@Alternative @Priority(1) @ApplicationScoped` — overrides platform's `DefaultStrategyResolver` because Quarkus ARC build-time pruning doesn't reliably discover all beans with `@Any Instance<NamedStrategy>`. Registers strategies from typed Instance injections plus a catch-all. Methods: `resolve(Class<T>, String id)`, `find(Class<T>, String id)`, `defaultStrategy(Class<T>)`, `available(Class<T>)`. Detects `@DefaultBean` via `InjectableBean.isDefaultBean()`. Has `forTest()` factory for unit testing without CDI.

**Adding a new strategy type requires updating the constructor** — add a typed `@Any Instance<YourStrategy>` parameter. See §CDI Conventions.

### Routing Architecture

**Pipeline:** Binding eligibility → Compound gating → ImplementationRouting → PlanningStrategy → AgentRouting (or HumanTaskRouting) → Worker scheduling.

**Composable routing:** `ComposableAgentRoutingStrategy` (`@DefaultBean`, id=`"composable"`) blends scores from independent `RoutingSignalProvider` implementations. Each provider scores candidates independently; the compositor computes a weighted sum. `CandidateSignal` is sealed: `Score` | `Exclude` | `Escalate`.

Built-in signal providers:
- `WorkloadSignalProvider` — load-balanced routing via `WorkloadDataProvider`
- `TrustSignalProvider` (ledger) — trust-phase-based scoring
- `ExperienceSignalProvider` — CBR experience-based scoring via `ExperienceAnalyser`
- `PersonalitySignalProvider` — JPAF personality-adaptive routing with cognitive function alignment
- `SemanticSignalProvider` (engine-ai) — cosine-similarity routing via `AgentEmbeddingProvider`

**Strategy SPIs:** `AgentRoutingStrategy`, `ImplementationRoutingStrategy`, `HumanTaskRoutingStrategy`, `CandidateMatchingStrategy`, `CandidateSetStrategy`, `DecompositionStrategy` — all follow the `NamedStrategy` convention.

### Worker Execution Lifecycle

Full sequence from dispatch to case context update:

```
WorkerScheduleEvent
  → WorkerScheduleEventHandler
      → WorkerContextProvider.buildContext()
      → WorkerExecutionManager.submit()
  → Scheduler fires job
      → WorkerStatusListener.onWorkerStarted()
      → EventLog: WORKER_EXECUTION_STARTED
      → WorkerExecutor.execute()
          → WorkerFunctionHandler.execute() → HandlerResult
      → publish WorkflowExecutionCompleted
      → On failure: RetryOrchestrator
          → evaluate RetryDecision (Retry or Exhaust)
          → reschedule or publish WorkerRetriesExhaustedEvent
  → WorkflowExecutionCompletedHandler
      → apply output with ConflictResolver
      → EventLog: WORKER_EXECUTION_COMPLETED
      → resumeIfWaiting()
      → WorkerStatusListener.onWorkerCompleted()
      → CaseLifecycleEvent: WorkerExecutionCompleted
      → publish CONTEXT_CHANGED → bindings re-evaluate
```

**Worker model:** Workers return `WorkerResult` (not `Map`). `WorkerScope` is a BiFunction parameter (not ThreadLocal) — cast to `WorkerRuntime` for engine-specific methods. `WorkerRuntime` provides domain-organized facets: `signals()` → `SignalSpace`, `interests()` → `InterestSpace`, `neighbors()` → `NeighborSpace`, `rules()` → `RuleSpace`. `WorkerFunction.None` models external workers with no in-process function — use `Worker.Builder.noFunction()`.

**Handler pipeline:** `DefaultWorkerExecutor` iterates `Instance<WorkerFunctionHandler>`, finds the first handler that `supports()` the function, delegates execution. Built-in handlers: `SyncAgentWorkerFunctionHandler` (runtime), `FlowWorkerFunctionHandler` (flow), `A2AWorkerFunctionHandler` (a2a), `McpWorkerFunctionHandler` (mcp), `ReActWorkerFunctionHandler` (react), `PatternWorkerFunctionHandler` (blocks-engine-adapter). All return `HandlerResult` (result + protocol metadata).

### Concurrency Budget

`CaseContextChangedEventHandler.applyDispatchBudget()` runs after `loopControl.select()`, before dispatch. Two layers:
- **Case-level:** `CaseDefinition.maxConcurrentDispatches` → counts active PlanItems (RUNNING/DISPATCHING/DELEGATED) via `PlanItemStore`. No TOCTOU race — `CaseEvaluationSerializer` serializes per-case.
- **External:** `DispatchBudget.availableCapacity()` SPI (engine-api). `NoOpDispatchBudget` (`@DefaultBean`) returns `MAX_VALUE`.

### Watchdog→Recovery Bridge

`WatchdogRecoveryBridge` (`runtime/internal/bridge/`) — `@ObservesAsync WatchdogAlertEvent` from qhorus CDI events. Three response actions per `WatchdogResponseAction`:
- **CANCEL_AFFECTED** (default for worker-hung conditions) — publishes synthetic `WorkflowExecutionCompleted(Expired)`, existing failure pipeline handles retry/reroute/recovery
- **SIGNAL** (default for case-level conditions) — writes alert to `.watchdogAlert` in case context
- **IGNORE** — no engine action

Per-condition policy configurable on `CaseDefinition.watchdogPolicy`.

### Scoped Worker Sessions (`common-core/internal/worker/scope/`)

`ScopedWorkerRegistry` tracks active scoped workers keyed by `(caseId, bindingName)`. `ScopedWorkerSession` is sealed:
- `Persistent` — long-running virtual thread with a mailbox of `ContextEvent` objects
- `Reinvoked` — accumulates state between invocations, cycle detection via `lastInputDataHash`

---

## CDI Conventions

### `@DefaultBean` for SPI No-Ops

All default SPI implementations use `@DefaultBean @ApplicationScoped` (`io.quarkus.arc.DefaultBean`). They yield automatically to any consumer-provided `@ApplicationScoped` implementation — no `selected-alternatives` configuration needed. This is the standard engine extension mechanism.

### Inject by SPI Interface

**Always inject by SPI interface**, never by concrete class. `@Inject CaseInstanceRepository`, never `@Inject InMemoryCaseInstanceRepository`. Concrete-class injection prevents `@Alternative @Priority(1)` test wrappers from being substituted — CDI creates two separate bean instances, causing silent tenant mismatches. Refs engine#663.

### `@CrossTenant` Qualifier

Cross-tenant SPIs (`CrossTenantEventLogRepository`, `CrossTenantCaseInstanceRepository`) are injectable only via `@CrossTenant`. `CrossTenantProducer` (in `runtime/internal/identity/`) produces both beans. Convention-based — CDI does not prevent unqualified injection; code review enforces it.

### `EngineStrategyResolver` Constructor

Adding a new strategy SPI type requires updating the `EngineStrategyResolver` constructor. Quarkus ARC build-time pruning doesn't reliably discover sub-interface beans via `@Any Instance<NamedStrategy>`, so each strategy type needs an explicit typed `@Any Instance<YourStrategyType>` constructor parameter.

### `NamedStrategy` Convention

All per-case-selectable strategies extend `NamedStrategy` (from `casehub-platform-api`), declare `id()`, and are resolved by `StrategyResolver`. Resolution precedence: YAML-specified ID → `@DefaultBean` fallback. `EngineStrategyResolver` detects `@DefaultBean` via `InjectableBean.isDefaultBean()`.

---

## SPI Architecture

### Placement Rules

- **Operational SPIs** (worker provisioning, lifecycle, channels, routing, risk classification) → `api/spi/`
- **Persistence SPIs** (`CaseMetaModelRepository`, `CaseInstanceRepository`, `EventLogRepository`, `PlanItemStore`, `SubCaseGroupRepository`) → `common-core/spi/`
- **Scheduler SPIs** (`JobScheduler`, `WorkerExecutionManager`, `WorkerExecutionRoutingStrategy`) → `common-core/spi/scheduler/`
- **CDI-specific SPIs** (CDI qualifiers like `@WorkerBackend`) → `common/spi/scheduler/` (Quarkus module, not `-core`)
- **CDI events** for optional observers → `common-core/spi/event/`
- **Optional module SPIs** (e.g. `AgentEmbeddingProvider` in `engine-ai`, `CaseQueueEntryStore` in `queue`) → within the optional module's own `spi/` package
- **Exception:** if an operational SPI takes `CaseInstance` or other `common-core/internal/` types as parameters, it must go in `common-core/spi/` to avoid circular dependency (`api` ← `common-core` ← `api`). `WorkOrchestrator` is the current example.

### Adding a New SPI

1. Define the interface in `api/spi/`
2. Add a `@DefaultBean @ApplicationScoped` no-op default in `runtime-core/internal/worker/` (or equivalent location)
3. Add contract tests in `api/src/test/java/io/casehub/api/spi/`
4. Add engine unit tests in `runtime-core/src/test/` (or `runtime/src/test/`)
5. If it extends `NamedStrategy`, update `EngineStrategyResolver` constructor (see §CDI Conventions)

---

## Test Conventions

### Naming

Test classes **MUST** be named `*Test.java` — never `*IT.java`. The `*IT` suffix is picked up by failsafe instead of surefire, producing `Tests run: 0` with no error message. This is a silent failure.

### CDI Event Testing

- **`@ObservesAsync` is unreliable in `@QuarkusTest`.** Observer methods are silently never invoked. Instead, inject the listener bean directly and call the observer method in the test.
- **`@ConsumeEvent` (Vert.x event bus) is async.** `eventBus.publish()` is fire-and-forget — tests need `Awaitility.await()` or direct bean invocation. Keep one wiring test per handler that uses `eventBus.publish()` + `await()` to confirm `@ConsumeEvent` routing.

### Index Dependencies

`casehub-engine-common-core` must be added to `quarkus.index-dependency` in any test `application.properties` that needs `JQEvaluator` discovered as a CDI bean — it is a library JAR, not a Quarkus application module:
```properties
quarkus.index-dependency.common-core.group-id=io.casehub
quarkus.index-dependency.common-core.artifact-id=casehub-engine-common-core
```

### casehub-ledger on Test Classpath

If `casehub-ledger` is a transitive dependency (via `engine`), its JPA entities require a datasource even in in-memory test suites. Fix:
1. Add `quarkus-jdbc-h2` + `casehub-ledger` as test dependencies
2. Configure H2 in test `application.properties`:
   ```properties
   quarkus.datasource.db-kind=h2
   quarkus.datasource.jdbc.url=jdbc:h2:mem:testdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1
   quarkus.hibernate-orm.schema-management.strategy=drop-and-create
   quarkus.flyway.migrate-at-start=false
   ```
3. Add a `NoOpLedgerEntryRepository` (`@Alternative @Priority(1) @ApplicationScoped`) to test sources
4. Exclude ledger capture beans to prevent silent timeouts:
   ```properties
   quarkus.arc.exclude-types=\
     io.casehub.ledger.service.CaseLedgerEventCapture,\
     io.casehub.ledger.service.WorkerDecisionEventCapture
   ```

### In-Memory Persistence

Add `casehub-persistence-memory` as a test dependency and activate via `quarkus.arc.selected-alternatives` in `src/test/resources/application.properties`. No Docker required.

### SPI Contract Tests

Abstract contract tests live in `common-core/src/test` (e.g. `PlanItemStoreContractTest`). Modules that provide concrete implementations extend the abstract class. To access these test-only classes:
```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-engine-common-core</artifactId>
    <type>test-jar</type>
    <scope>test</scope>
</dependency>
```

### Recording Pattern for SPI Wiring Tests

Use `@Alternative @Priority(1) @ApplicationScoped` static inner classes with `static` recording fields reset in `@BeforeEach`. This activates the recording bean globally without Mockito. See `SpiWiringIntegrationTest` for the pattern.

---

## Planning Module Architecture

`PlanningStrategyLoopControl` is the core `LoopControl` implementation that replaces `ChoreographyLoopControl` when planning is active. It orchestrates:
1. Plan model creation via `BlackboardPlanConfigurer`
2. Compound lifecycle evaluation (`CompoundLifecycleEvaluator`)
3. Strategy dispatch (`CompoundStrategyDispatcher`)
4. Implementation routing (`ImplementationRoutingStrategy`)

Built-in strategies: `ChoreographyStrategy` (fire all eligible), `SequentialPlanningStrategy` (one at a time in declared order).

`BlackboardRegistry` is the `@ApplicationScoped` singleton tracking per-case `CasePlanModel` instances and the worker-to-PlanItemId completion index. Supports lazy hydration from `PlanItemStore` for restart recovery.

### Compound PlanItemDefinition Hierarchy

`PlanItemDefinition` is sealed: `Primitive` (leaf) and `Compound` (container with children, planning strategy, `CompletionSemantics`, `DispatchMode`).

`CompletionSemantics` is sealed: `All` (all children must complete), `MofN` (M out of N children), `FirstWins` (first terminal child completes the compound).

`CompoundLifecycleEvaluator` evaluates entry/exit conditions each planning cycle. `CompoundCompletionEvaluator` walks the compound parent chain after child state changes and propagates completion up the tree. `CompoundStrategyDispatcher` routes bindings to the correct `PlanningStrategy` per compound.

Sub-case orchestration: `SubCaseExecutionHandler` spawns child cases, `SubCaseCompletionService` handles child completion with grouped M-of-N threshold logic via `SubCaseGroupPolicy`.

---

## Oversight Gate Architecture

Multi-approver oversight for consequential worker actions:

1. Worker returns `PlannedAction` via `WorkerResult`
2. `ActionRiskClassifier` evaluates risk — `ChainedActionRiskClassifier` composes multiple classifiers ("most restrictive wins")
3. If `GateRequired`: `ActionGateScheduleRequest` creates approval WorkItem(s)
4. `QuorumConfig` supports M-of-N approval with dynamic instance count from candidate group membership
5. Gate lifecycle events: `ActionGateApprovedEvent`, `ActionGateRejectedEvent`, `ActionGateExpiredEvent`, `ActionGateCancelledEvent`
6. On approval: PlanItem resumes execution
7. On rejection/expiry: PlanItem marked FAULTED

---

## Case-Based Reasoning Integration

CBR enables experience-driven routing and planning:

1. `CbrConfig` on `CaseDefinition` configures feature extraction, similarity thresholds, and retrieval timing
2. `CbrCaseRetainObserver` captures case outcomes with trust scores for retention
3. `CbrRetrievalService` retrieves similar past cases at case startup, injecting `RetrievedExperience` into `CaseContext`
4. `ExperienceSignalProvider` and adaptation-aware CBR scoring influence routing decisions
5. `CbrHumanTaskRoutingStrategy` uses CBR for human task candidate scoring

Unified resolution pipeline (engine#1081) adds mixed retrieval, feedback loops, and document ingestion.

---

## Expression Engine

Engine expression evaluation is unified with the platform hierarchy:
- `ExpressionEngine` extends `platform-api ExpressionEngine`
- `ExpressionEvaluator` extends `platform-api ExpressionEvaluator`
- `ExpressionEngineRegistry` delegates to `platform ExpressionEngineRegistry`

---

## Agent Mesh SPIs (`api/spi/mesh/`)

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

---

## DAG Parallel Execution (`common-core/plan/`, `api/plan/`)

Dependency-graph-aware parallel execution driver:

| Type | Location | Purpose |
|---|---|---|
| `DagPlan<T>` | api | Immutable validated DAG. Factories: `singleton`, `sequence`, `parallel`, `fromNodes` |
| `DagNode<T>` | api | `id`, `task`, `dependsOn`, `joinType` (ALL_OF or ANY_OF), optional `contingency` |
| `DagDriver<T, R>` | common-core | Single-use executor. `STREAMING` or `BARRIER` dispatch modes. Virtual threads |
| `NodeState<R>` | common-core | Sealed: `Pending`, `Dispatched`, `Completed`, `Failed`, `Skipped`, `Cancelled` |
| `DagResult<R>` | common-core | `nodeStates`, `completedResults`, `allSucceeded`, `elapsed` |
| `DagEventListener<T, R>` | common-core | Callback for node lifecycle events |

---

## CaseDefinitionRegistry

`DefaultCaseDefinitionRegistry` stores definitions in `Map<CaseKey, RegistryEntry>` where `CaseKey` is an immutable record `(namespace, name, version)`. `RegistryEntry` is an inner record `(CaseDefinition, CaseMetaModel)`. Self-healing: if registry lookup returns null (can happen after restart), re-registers from the persisted `CaseMetaModel`.

---

## CapabilityHealth Integration

Optional integration with `casehub-eidos-api`. `WorkOrchestrator` probes agent-backed workers via `CapabilityHealth.probe()` before candidate selection:
- `Unavailable` — hard filter (removed from candidates)
- `EpistemicallyWeak` — preference demotion (sorted last)
- `Degraded` — keep, sort after `Ready`
- No descriptor — skip probe, assume capable

`NoOpCapabilityHealth` `@DefaultBean` returns `Ready` for all probes when eidos is not on the classpath.

---

## Config and Secret Resolution (`common-core/internal/config/`)

`ConfigManager` and `SecretManager` SPIs enable JQ expressions to reference runtime configuration via `$config` and `$secret` scope variables in `JQEvaluator`. `NoOpConfigManager` and `NoOpSecretManager` provide defaults.

---

## Tenancy Enforcement (persistence-hibernate)

All JPA repositories extend `TenantAwareRepository`:
- `withTenantTransaction(tenancyId, work)` — sets `SET LOCAL "casehub.tenancy_id"` for RLS
- `withCrossTenantTransaction(work)` — sets `SET LOCAL ROLE casehub_crosstenancy` (BYPASSRLS)

Config: `casehub.rls.enabled` (default false). `@CrossTenant` CDI qualifier gates access to cross-tenant SPIs. `RlsPolicyApplicator` creates RLS policies on engine tables at startup.

### Persistence Entities

| Entity | Table | Purpose |
|---|---|---|
| `CaseInstanceEntity` | `case_instance` | Case state, parent refs, labels, tenancy, lifecycle scope |
| `CaseMetaModelEntity` | `case_meta_model` | Case type definition metadata, JSON definition |
| `EventLogEntity` | `event_log` | Case events with type, payload, metadata, DB-generated sequence |
| `PlanItemEntity` | `plan_item` | Binding status, target type, executor metadata, compound parent ref |
| `SubCaseGroupEntity` | `subcase_group` | Parent-child tracking, completion/rejection counts, threshold policy |

### Schema Management

No Flyway for engine tables — Hibernate `drop-and-create` only. `casehub-engine-ledger` uses Flyway migrations from `casehub-ledger` plus its own `V2000__case_ledger_entry.sql`. Quartz uses RAM store, not JDBC.

---

## Queue Module Architecture

Label-driven case queue operational layer:

- `CaseLabelEvaluator` — observes `CaseLifecycleEvent`, evaluates label rules against case context, fires `CaseQueueEvent`
- `CaseQueueEntryManager` — materializes queue events into `CaseQueueEntry` records
- `CaseQueueService` — claim/release/escalate operations with tenancy verification
- `CaseQueueEntryStore` SPI — persistence abstraction with `InMemoryCaseQueueEntryStore` default
- `CaseQueueViewManager` — creates/deletes queue views with deterministic UUID generation
- `CaseLabelReconciler` — startup reconciliation for label and view membership consistency

---

## Qhorus Message Signal Bridge

`QhorusMessageSignalBridge` (CDI `@ObservesAsync MessageReceivedEvent`) bridges commitment-resolving Qhorus messages (RESPONSE, DONE, DECLINE, FAILURE) on `case-{caseId}/{purpose}` channels to `CaseHubRuntime.signal()`. DECLINE and FAILURE trigger the worker failure cascade.

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
- Multi-framework split (core/Spring): done
- WAITING state durability (restart-safe): done
- `casehub-ledger` integration: done
- Human worker integration (`humanTask` YAML binding): done
- Unified execution model (TaskStatus, TaskDescriptor, ExecutorRef): done
- ContextBridge protocol + CaseContextStore SPI: done
- DAG parallel execution driver: done
- Composable routing signal architecture: done
- GoalExpression + GoalBasedCompletion (multi-kind goals): done
- `ActionRiskClassifier` SPI + multi-approver oversight gate: done
- Resilience module (DLQ, PoisonPill, timeout, auto-replay): done
- Compound PlanItemDefinition hierarchy (Stage fully retired): done
- Virtual thread migration: done
- Lifecycle scopes (BINDING, COMPOUND, CASE) + scoped worker dispatch: done
- CBR routing + human task routing: done
- Personality-adaptive routing (JPAF): done
- REST module: done
- GraphQL module: done
- Queue module: done
- A2A interop: done
- MCP interop: done
- ReAct worker loop: done
- Annotations module (`@Case`): done
- Signal/pheromone model: done
- Environment observation SPI: done
- Dynamic interest registration: done
- Agent discovery & neighbor awareness: done
- Local rule evaluation: done
- Convergence detection & termination: done
- Unified judgment scheduling: done

---

## Design Documents

- [docs/DESIGN.md](https://raw.githubusercontent.com/casehubio/engine/main/docs/DESIGN.md) — choreography+orchestration models, worker SPI contracts, blackboard lifecycle
- [docs/adr/INDEX.md](https://raw.githubusercontent.com/casehubio/engine/main/docs/adr/INDEX.md) — architectural decision records
- ARC42STORIES.md (workspace) — architecture documentation following Arc42Stories spec
