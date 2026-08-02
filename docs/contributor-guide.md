# casehub-engine — Contributor Guide

> Internal architecture, module structure, and extension points for platform builders.

**GitHub:** [casehubio/engine](https://github.com/casehubio/engine)

---

## Module Structure

| Module | Folder | Type | Purpose |
|---|---|---|---|
| `casehub-engine-api` | `api` | Pure Java + langchain4j | SPI interfaces, domain model (`Worker`, `Binding`, `Capability`, `HumanTaskTarget`), `Agent` wrapper, `AgentRoutingStrategy` SPI |
| `casehub-engine-common` | `common` | Pure Java (no CDI) | Domain objects (`CaseMetaModel`, `CaseInstance`), persistence SPIs, `JQEvaluator`, `EventLog` |
| `casehub-engine` | `runtime` | Quarkus module | Choreography handlers, orchestration, worker scheduling, expression engine |
| `casehub-engine-planning` | `planning` | Optional module | CMMN planning orchestration — `PlanningRegistry`, `PlanItem`, `SubCase` lifecycle |
| `casehub-engine-resilience` | `resilience` | Optional module | Dead Letter Queue, PoisonPill detection, backoff strategies, case timeout |
| `casehub-engine-ledger` | `ledger` | Optional module | Tamper-evident case lifecycle ledger; `TrustWeightedAgentStrategy` (`@Alternative @Priority(1)`) |
| `casehub-engine-ai` | `ai` | Optional module | `AgentEmbeddingProvider` SPI + `SemanticAgentRoutingStrategy` (`@Alternative @Priority(2)`) |
| `casehub-engine-actor-state` | `actor-state` | Optional module | Unified actor workload view (`GET /actors/{actorId}/state`) via `ActorStateContributor` SPI |
| `casehub-engine-scheduler-quartz` | `scheduler-quartz` | Module | Quartz-based worker execution (RAM store). `WorkerExecutor` SPI + `DefaultWorkerExecutor` |
| `casehub-engine-schema` | `schema` | Build-time | `CaseDefinition.yaml` JSON Schema generated Java model via jsonschema2pojo |
| `casehub-engine-persistence-hibernate` | `persistence-hibernate` | Module | JPA/Panache persistence (PostgreSQL) |
| `casehub-engine-persistence-memory` | `persistence-memory` | Test module | In-memory thread-safe persistence for `@QuarkusTest` without Docker |
| `casehub-engine-codegen` | `codegen` | Build-time | Code generation utilities |
| `casehub-engine-flow` | `flow` | Optional module | `Worker(Workflow)` dispatch from Serverless Workflow steps |
| `casehub-engine-testing` | `testing` | Test module | Shared test utilities |
| `casehub-engine-inbound` | `inbound` | Optional module | Bridges qhorus `MessageReceivedEvent` to casehub-work WorkItems via `InboundWorkItemPolicy` SPI |
| `casehub-examples-typed-context` | `examples/typed-context` | Example module | Demonstrates `CaseContextStore` pluggability. Not published (`deploy.skip=true`) |

**Removed:** `casehub-engine-work-adapter` — relocated to `casehub-work` as `casehub-work-engine-adapter`.

---

## Internal Architecture

### Engine Handlers

Two execution paths:

- **Choreography** (`CaseContextChangedEventHandler`) — evaluates `contextChange.filter` AND `binding.when()` to find eligible bindings for RUNNING and WAITING cases, selects via `LoopControl`, dispatches by target type. `PlanningStrategyLoopControl` handles WAITING; `ChoreographyLoopControl` restricts to RUNNING only.
- **Orchestration** (`WorkOrchestrator`, interface in `common/spi/`, implemented by `DefaultWorkOrchestrator` in runtime) — synchronous dispatch path; integrates `CapabilityHealth` probe to filter/sort agent-backed candidates before selection.

`Worker(Workflow)` execution uses a non-blocking path: Quartz fires `workflowExecutor.execute()` and returns immediately; success communicated via event bus (`WORKER_EXECUTION_FINISHED`); failure handled in-process by `QuartzRetryService`.

### EngineStrategyResolver

`@Alternative @Priority(1) @ApplicationScoped` — overrides platform's `DefaultStrategyResolver` because Quarkus ARC build-time pruning doesn't reliably discover all beans with `@Any Instance<NamedStrategy>`. Registers strategies from 7 typed Instance injections plus a catch-all. Methods: `resolve(Class<T>, String id)`, `find(Class<T>, String id)`, `defaultStrategy(Class<T>)`, `available(Class<T>)`. Detects `@DefaultBean` via `InjectableBean.isDefaultBean()`. Has `forTest()` factory for unit testing without CDI.

### Agent Mesh SPIs (`api/spi/mesh/`)

Platform-level agent mesh primitives (pure Java, no CDI):

| Type | Purpose |
|---|---|
| `CaseChannelLayout` | SPI: declares channel topology for an agent case |
| `NormativeChannelLayout` | Canonical 3-channel impl: work / observe / oversight |
| `SimpleLayout` | 2-channel impl: work + observe, no governance gate |
| `MeshParticipationStrategy` | SPI: `strategyFor(workerId, caseId)` returns ACTIVE/REACTIVE/SILENT |

### DAG Parallel Execution (`common/plan/`)

Dependency-graph-aware parallel execution driver:

| Type | Purpose |
|---|---|
| `DagPlan<T>` | Immutable validated DAG. Factories: `singleton`, `sequence`, `parallel`, `fromNodes` |
| `DagNode<T>` | `id`, `task`, `dependsOn`, `joinType` (ALL_OF or ANY_OF) |
| `DagDriver<T, R>` | Single-use executor. `STREAMING` or `BARRIER` dispatch modes. Virtual threads by default |
| `NodeState<R>` | Sealed: `Pending`, `Dispatched`, `Completed`, `Failed`, `Skipped`, `Cancelled` |
| `DagResult<R>` | `nodeStates`, `completedResults`, `allSucceeded`, `elapsed` |

### CaseDefinitionRegistry

`DefaultCaseDefinitionRegistry` stores definitions in `Map<CaseKey, RegistryEntry>` where `CaseKey` is an immutable record `(namespace, name, version)`. `RegistryEntry` is an inner record `(CaseDefinition, CaseMetaModel)`.

### CapabilityHealth Integration

Optional integration with `casehub-eidos-api`. `WorkOrchestrator` probes agent-backed workers via `CapabilityHealth.probe()` before candidate selection:
- `Unavailable` — hard filter (removed from candidates)
- `EpistemicallyWeak` — preference demotion (sorted last)
- `Degraded` — keep, sort after `Ready`
- No descriptor — skip probe, assume capable

`NoOpCapabilityHealth` `@DefaultBean` returns `Ready` for all probes when eidos is not on the classpath.

### Tenancy Enforcement (persistence-hibernate)

All JPA repositories extend `TenantAwareRepository`:
- `withTenantTransaction(tenancyId, work)` — sets `SET LOCAL "casehub.tenancy_id"` for RLS
- `withCrossTenantTransaction(work)` — sets `SET LOCAL ROLE casehub_crosstenancy` (BYPASSRLS)

Config: `casehub.rls.enabled` (default false). `@CrossTenant` CDI qualifier gates access to cross-tenant SPIs.

### Schema Management

No Flyway for engine tables — Hibernate `drop-and-create` only. `casehub-engine-ledger` uses Flyway migrations from `casehub-ledger` plus its own `V2000__case_ledger_entry.sql`. Quartz uses RAM store, not JDBC.

### SPI Placement Rules

- Operational SPIs (worker provisioning, lifecycle, channels) go in `api/spi/`
- Persistence SPIs (`CaseMetaModelRepository`, etc.) go in `casehub-engine-common/spi/`
- Exception: if an operational SPI takes `CaseInstance` or other `common/internal/` types as parameters, it goes in `common/spi/` to avoid circular dependency (`api` <-- `common` <-- `api`). `WorkOrchestrator` is the current example.

### Routing Architecture

**Pipeline:** Binding eligibility -> Compound gating -> ImplementationRouting -> PlanningStrategy -> AgentRouting (or HumanTaskRouting) -> Worker scheduling.

**Composable routing:** `ComposableAgentRoutingStrategy` (`@DefaultBean`, id=`"composable"`) blends scores from independent `RoutingSignalProvider` implementations. Each provider scores candidates independently; the compositor computes a weighted sum. `CandidateSignal` is sealed: `Score` | `Exclude` | `Escalate`.

**Strategy SPIs:** `AgentRoutingStrategy`, `ImplementationRoutingStrategy`, `HumanTaskRoutingStrategy`, `CandidateMatchingStrategy`, `CandidateSetStrategy` — all follow the `NamedStrategy` convention.

### Compound PlanItemDefinition Hierarchy

`PlanItemDefinition` is sealed: `Primitive` (leaf) and `Compound` (container with children, planning strategy, CompletionSemantics, DispatchMode). `CompoundLifecycleEvaluator` evaluates entry/exit conditions. `CompoundCompletionEvaluator` propagates completion up the compound tree. Stage is fully retired — replaced by `Compound`.

### Agent Mesh — Layer 4 (Enforcement)

casehub-engine is Layer 4 in the Qhorus normative accountability framework:
- `FULFILLED` commitment -> case continues
- `FAILED` / `EXPIRED` commitment -> recovery policy triggers

### Qhorus Message Signal Bridge

`QhorusMessageSignalBridge` bridges commitment-resolving Qhorus messages (RESPONSE, DONE, DECLINE, FAILURE) on `case-{caseId}/{purpose}` channels to `CaseHubRuntime.signal()`. DECLINE and FAILURE trigger the worker failure cascade.

---

## Dependencies

### Depends On

| Repo | How |
|---|---|
| `casehub-ledger` | Optional, via `casehub-engine-ledger` module |
| `casehub-qhorus-api` | `MessageType` enum for channel messaging |
| `casehub-platform-api` | `ActorType`, `PreferenceProvider`, `Path` (transitive via ledger) |
| `casehub-platform-expression` | `JQEvaluator` for expression evaluation |
| `casehub-eidos-api` | Optional — `AgentDescriptor`, `CapabilityHealth` for agent health probing |
| `casehub-worker` | Foundation — `Worker`, `Capability`, `WorkerFunction<T>` types |
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
- `casehub-ledger` integration: merged
- Human worker integration (`humanTask` YAML binding): done — inline + template modes
- Work-adapter: relocated to casehub-work as `engine-adapter` module
- Unified execution model (TaskStatus, TaskDescriptor, ExecutorRef): done
- ContextBridge protocol + CaseContextStore SPI: done
- DAG parallel execution driver: done
- RoutingSignalProvider + RoutingPromptSection SPIs: done (promoted to engine-api)
- GoalExpression + GoalBasedCompletion (multi-kind goals): done
- RoutingOutcome + RoutingOutcomeRecorder: done
- NamedStrategy + EngineStrategyResolver: done
- `AgentRoutingStrategy` SPI: done
- `ActionRiskClassifier` SPI — platform-level oversight gate: done
- Resilience module (DLQ, PoisonPill, timeout): done
- Worker<->Session<->Channel triple correlation: not yet stored
- Escalation rules, lineage-driven planning: ahead

---

## Design Documents

- [docs/DESIGN.md](https://raw.githubusercontent.com/casehubio/engine/main/docs/DESIGN.md) — choreography+orchestration models, worker SPI contracts, blackboard lifecycle
- [docs/adr/INDEX.md](https://raw.githubusercontent.com/casehubio/engine/main/docs/adr/INDEX.md) — architectural decision records
