# casehub-engine — Consumer Guide

> Hybrid choreography+orchestration coordination engine for multi-agent work.

**GitHub:** [casehubio/engine](https://github.com/casehubio/engine)
**Tier:** Foundation

---

## Purpose

Implements the Blackboard Architecture (Hayes-Roth, 1985) with CMMN terminology. Coordinates workers (AI agents, humans) via case definitions, binding rules, and optional synchronous orchestration. All handlers run on virtual threads (Java 21).

**Getting started:** Case definitions can be authored in [YAML or Java](three-pathways.md) (TypeScript planned). The pathways guide covers when to use each, a side-by-side walkthrough, and the full YAML DSL reference.

---

## Modules to Depend On

| Module | Artifact | When to use |
|---|---|---|
| `casehub-engine-api` | `io.casehub:casehub-engine-api` | SPI interfaces, domain model (`Binding`, `CaseDefinition`, `Goal`, `Milestone`), `Agent` wrapper, `AgentRoutingStrategy` SPI, routing SPIs, mesh SPIs, context types, DAG plan types, HTN decomposition SPIs |
| `casehub-engine-common` | `io.casehub:casehub-engine-common` | Domain objects (`CaseMetaModel`, `CaseInstance`), persistence SPIs (`CaseInstanceRepository`, `EventLogRepository`, `CaseMetaModelRepository`, `PlanItemStore`), `CaseDefinitionRegistry` |
| `casehub-engine` | `io.casehub:casehub-engine` | Runtime — choreography handlers, orchestration, worker scheduling, expression engine, `CaseHubRuntime` |
| `casehub-engine-planning` | `io.casehub:casehub-engine-planning` | CMMN planning orchestration — `PlanningStrategy`, `CasePlanModel`, `PlanItem`, `CompletionSemantics`, `Compound` lifecycle, sub-case orchestration |
| `casehub-engine-schema` | `io.casehub:casehub-engine-schema` | `CaseDefinition.yaml` JSON Schema generated Java model via jsonschema2pojo |
| `casehub-engine-rest` | `io.casehub:casehub-engine-rest` | Opt-in JAX-RS REST surface for case lifecycle, context queries, signals, and event log. Consumers who only need the Java SPI do not pay JAX-RS coupling cost |

**Optional modules** (activated by classpath presence):

| Module | What it adds |
|---|---|
| `casehub-engine-resilience` | Dead Letter Queue with auto-replay, PoisonPill detection with sliding-window circuit breaker, backoff strategies (`FIXED`, `EXPONENTIAL`, `EXPONENTIAL_WITH_JITTER`), case timeout enforcement |
| `casehub-engine-ledger` | Tamper-evident case lifecycle ledger; `TrustWeightedImplementationRoutingStrategy`; `TrustSignalProvider` for composable routing; `WorkerDecisionEntry` recording |
| `casehub-engine-ai` | `AgentEmbeddingProvider` SPI, `SemanticSignalProvider` — cosine-similarity agent routing, `EmbeddingCache` (LRU, default 500 entries) |
| `casehub-engine-actor-state` | Unified actor workload view (`GET /actors/{actorId}/state`) via `ActorStateContributor` SPI. Aggregates trust scores, capability scores, work items, commitments, active cases |
| `casehub-engine-flow` | Serverless Workflow execution — `FlowWorkerFunction`, `CallableDispatcher` SPI, `CasehubFlow` static utility for dispatching capabilities from FuncDSL workflow steps |
| `casehub-engine-inbound` | Bridges inbound messages to case signals (`InboundSignalBridge`) and to casehub-work WorkItems (`InboundWorkItemBridge`) via `InboundWorkItemPolicy` SPI |
| `casehub-engine-scheduler-quartz` | Quartz-based worker execution (RAM store). `WorkerExecutionManager`, scheduled/conditional trigger jobs, milestone SLA timeout jobs |

**Test modules:**

| Module | Purpose |
|---|---|
| `casehub-engine-persistence-memory` | In-memory thread-safe persistence for `@QuarkusTest` without Docker. Includes `DefaultTestPrincipal` |
| `casehub-engine-testing` | `@Alternative @Priority(1)` wrappers over in-memory repos for automatic selection in `@QuarkusTest`. Includes `WorkResultSubmitter` test helper |

---

## Key Abstractions

### CaseDefinition (YAML DSL)

Cases are defined declaratively: namespace, name, version, capabilities, workers, bindings, goals, milestones, completion conditions. Additional fields: `types` (Set<Path>), `labels` (Set<Path>), `defaultWorkerBridge` (ContextBridge), `contextStoreFactory` (string key for NamedStrategy resolution), `signals` (List<SignalType>), `authorization` (ACL grants), `cognitiveDemand` (per-capability cognitive function demand profile), `episodicMemory` (EpisodicMemoryConfig), `cbr` (CbrConfig), `routingSignalWeights` (per-case routing signal provider weights), `humanTaskRouting` (strategy ID).

**Classification:** `types` (hierarchical case classification paths, e.g. `casehubio/devtown/pr-review`) and `labels` (arbitrary tags). Both validated against vocabulary at registration time when vocabulary is configured.

**Binding target types** (mutually exclusive per binding):
- `capability` — routes to a worker by capability match
- `subCase` — spawns a child case (with `SubCaseCompletionStrategy` and `SubCaseMapping`)
- `humanTask` — creates a WorkItem in casehub-work (inline or template mode). Supports `scope`, `inputMapping`/`outputMapping` (JQ), `candidateGroups`, `candidateUsers`, `expiresIn`, `outcomes`
- `signal` — engine-internal context mutation. Writes a static payload to the case context and publishes `CONTEXT_CHANGED`. No worker dispatch. Use for case-level SLA deadlines, escalation flags, phase transitions. `LifecycleScope.BINDING` only

**Binding fields:** `contextWrite` is a JQ expression whose result is merged into case context after the worker completes. `outcomePolicy` controls REROUTE vs FAULT behavior on worker DECLINED/FAILED/EXPIRED outcomes. `lifecycleScope` governs worker lifetime.

**Trigger types:** `contextChange` (with optional `filter` and binding-level `when` guard), `schedule` (YAML: `every:` for one-shot ISO-8601 duration, `cron:` for periodic Quartz expression), `scopeActivated` (fires when a compound scope becomes ACTIVE).

### CaseCompletion

`CaseCompletion` is sealed: `GoalBasedCompletion` and `PredicateBasedCompletion`.

- `GoalBasedCompletion<K extends GoalKind>` — maps goal kinds to goal expressions. `GoalKind.SUCCESS`, `GoalKind.FAILURE`
- `PredicateBasedCompletion` — a JQ predicate evaluated against case context

### GoalExpression

Composed goal trees for case completion — replaces flat goal lists with recursive boolean expressions.

- `GoalExpression` (sealed) — `AllOfGoalExpression`, `AnyOfGoalExpression`, `SingleGoalExpression`. Factory methods: `allOf(Goal...)`, `anyOf(Goal...)`, `goal(String)`
- `CaseDefinition.Builder.completion()` overloads: success only, success+failure, full GoalBasedCompletion, JQ predicate

Goals referenced by `GoalExpression` but not declared in `CaseDefinition.getGoals()` are rejected at registration time.

### CasePlanModel and PlanItem Lifecycle

`BlackboardRegistry` tracks `CasePlanModel` per case. Each binding creates a `PlanItem` that transitions through:

```
PENDING --> DELEGATED (control handed to external system, e.g. human task)
        \-> RUNNING   (Quartz-executed capability worker)
            --> terminal (COMPLETED, FAULTED, REJECTED, OBSOLETE, CANCELLED)
```

`SubCase` lifecycle: parent PlanItem stays `DELEGATED` until child case completes; `SubCaseCompletionService` handles the callback.

### Compound PlanItemDefinition Hierarchy

`PlanItemDefinition` is sealed: `Primitive` (leaf) and `Compound` (container with children, planning strategy, `CompletionSemantics`, `DispatchMode`). `CompletionSemantics` is sealed: `All`, `MofN`, `FirstWins`. `DispatchMode`: `ORCHESTRATED` or `CHOREOGRAPHED`. Stage is fully retired — replaced by `Compound`.

### Lifecycle Scopes

`LifecycleScope` governs worker lifetime:
- `BINDING` — single dispatch (default)
- `COMPOUND` — lives for the duration of a compound plan item
- `CASE` — lives for the duration of the entire case

`Participation`: `PARTICIPANT` (blocks completion) or `COMPANION` (sidecar).

`ExecutionMode`: `TRANSIENT` (fire and forget), `PERSISTENT` (long-running with mailbox — virtual thread spawned), `REINVOKED` (re-invoked with accumulated state threaded between invocations).

### Worker and Worker Functions

Workers are declared in the CaseDefinition. Workers declare supported capabilities by name; the engine resolves authoritative `Capability` instances from `CaseDefinition.getCapabilities()`.

`YamlCaseHub.getDefinition()` is `final` with `protected void augment(CaseDefinition)` hook for subclasses to add programmatic workers backed by CDI-injected services.

Three worker function types:
- **Function** — `Function<Map<String, Object>, Map<String, Object>>`
- **Agent** — AI-powered worker using LangChain4j. Transforms input via JQ, invokes LLM with system prompt, transforms response back. Supports `plannedActionExtractor` for `PlannedAction` support
- **Workflow** — Serverless Workflow execution via `FlowWorkerFunction` (requires `casehub-engine-flow`)

`WorkerFunctionProvider` SPI enables pluggable worker function construction from raw YAML nodes. `WorkerFunctionProviderRegistry` iterates all providers until one handles the node.

### WorkerResult and PlannedAction

All worker functions return `WorkerResult`:
```java
// No consequential action
.function(input -> WorkerResult.of(Map.of("result", "done")))

// Declares a consequential action (triggers oversight gate)
.function(input -> WorkerResult.of(
    Map.of("output", "value"),
    PlannedAction.of("File SAR report", "sar.file", Map.of("accountId", "ACC-123"))))
```

### Worker Rights and Scoped Credentials

Privileged external workers (with their own service-account identity) receive case-scoped credentials and auto-managed ACL grants at dispatch time.

**Declaration** — on the CaseDefinition YAML:

```yaml
workers:
  - name: risk-agent
    serviceAccountId: "agent:pool-risk@acme.io"
    capabilities: [assess-risk]

bindings:
  - name: assess
    capability: assess-risk
    worker: risk-agent
    permissionIntent:
      - read-context
      - signal-case
      - read-event-log
```

`permissionIntent` (List of `WorkerAction`) declares what the worker needs. Default when omitted: `[read-context]` (fail-closed for writes). `serviceAccountId` is optional — when absent, the engine mints an ephemeral identity (`agent:worker-<caseId>-<shortUuid>`).

**Available actions:** `read-context`, `write-context`, `signal-case`, `read-event-log`, `read-plan-items`, `spawn-sub-case`, `admin`. All map to `AclResourceType.CASE` — per-resource-type enforcement deferred.

**What happens at dispatch:**
1. `WorkerIdentityResolver` resolves or mints the actorId
2. `WorkerGrantOrchestrator.grantAndMint()` creates ACL grants via `AccessControlProvider.grantBatch()` and stores a scoped `WorkerCredential` token
3. Token is delivered to the worker via `ProvisionContext.workerCredentialToken` or COMMAND payload

**What happens at completion:**
- `revokeForWorker()` — revokes the credential and removes ACL grants. For shared service accounts with concurrent bindings, differential revocation ensures only unneeded grants are removed
- Case terminal state triggers `revokeForCase()` — sweeps all surviving credentials

**REST enforcement:** Workers present the token via `X-Worker-Credential` header. `WorkerCredentialFilter` validates the token and enforces structural case isolation (403 if the request targets a different case than the credential is scoped to).

**Persistent credential store:** `InMemoryWorkerCredentialStore` (`@DefaultBean`) works out of the box for single-node deployments. Clustered deployments should provide a persistent `WorkerCredentialStore` implementation.

### Worker Outcome Handling

`OutcomeKind` tracks semantic outcomes: `Completed`, `Declined`, `Failed`, `Expired`. `OutcomePolicy` (per-binding) maps each outcome to an `OutcomeAction`: `REROUTE` (re-dispatch to a different agent) or `FAULT` (mark case FAULTED immediately). Default: REROUTE for all outcomes, max 3 reroute attempts.

### Binding and Execution Paths

Two execution paths:
- **Choreography** — evaluates bindings on context change; `CaseContextChangedEventHandler` evaluates `contextChange.filter` AND `binding.when()` to find eligible bindings
- **Orchestration** — suspends case, awaits worker completion, resumes

### Unified Execution Model (`api/model/`)

Shared abstractions for any coordination model's unit of work:

| Type | Purpose |
|---|---|
| `TaskStatus` | Shared lifecycle states: active (`PENDING`, `RUNNING`, `DELEGATED`, `SUSPENDED`) and terminal (`COMPLETED`, `FAULTED`, `REJECTED`, `OBSOLETE`, `CANCELLED`) |
| `TaskDescriptor` | Behavioral contract: `id()`, `description()`, `executor()`, `status()`, `createdAt()` |
| `ExecutorRef` | Shared executor identity: `name()`, `description()`. Factory: `of(name)`, `fromWorker(Worker)` |
| `TaskSnapshot` | Immutable read model projected from `TaskDescriptor` |

### ContextBridge Protocol (`api/context/`)

Typed context translation for Case-to-Worker, Signal, and SubCase boundaries:
- `initialise(CaseContext, JsonNode narrowedInput) -> T` — creates typed context from case state
- `extractOutput(T) -> Map<String, Object>` — projects worker output back to case context
- `serialise(T) / deserialise(JsonNode)` — persistence round-trip
- Known implementations: `MapBridge`, `JsonNodeBridge`, `JacksonPojoBridge`

### CaseContext Layers (`api/context/`)

`CaseContext` supports named layers via `ContextLayer`. `ReadableLayer` and `WritableLayer` provide typed access. `MutableCaseContext` extends `CaseContext` for write operations. `CaseContextStore` SPI backs each layer with pluggable storage. `CaseContextStoreFactory extends NamedStrategy` creates stores per layer per case. `isDurable()` signals whether stores survive JVM restarts. Default: `InMemoryCaseContextStoreFactory`.

### PropagationContext (`api/context/`)

Tracing, budget, and inherited attributes value object. Carries trace IDs and execution budget for timeout enforcement. Passed through the dispatch chain.

### DAG Parallel Execution (`engine/plan/`)

Dependency-graph-aware parallel execution driver:

| Type | Purpose |
|---|---|
| `DagPlan<T>` | Immutable validated DAG. Factories: `singleton`, `sequence`, `parallel`, `fromNodes` |
| `DagNode<T>` | `id`, `task`, `dependsOn`, `joinType` (`ALL_OF` or `ANY_OF`) |
| `TaskNode<T>` | Concrete `DagNode` implementation |

### HTN Decomposition (`engine/plan/`)

Hierarchical Task Network decomposition SPIs:

| Type | Purpose |
|---|---|
| `DecompositionStrategy` | SPI — selects a `DecompositionMethod` for a given task |
| `DecompositionMethod` | Declares how to decompose a task into sub-tasks with a `DagPlan` |
| `DecompositionContext` | Context for decomposition: case state, available capabilities |

### Case-Based Reasoning (`api/model/cbr/`)

CBR enables experience-driven routing and planning. Configured per case definition via `CbrConfig`:

| Field | Purpose |
|---|---|
| `featureExtractor` | `JqFeatureExtractor` or `LambdaFeatureExtractor` — extracts case features |
| `topK` | Number of similar cases to retrieve |
| `minSimilarity` | Minimum similarity threshold |
| `weights` | Per-feature scoring weights |
| `vectorWeight` | Balance between vector similarity and feature similarity |
| `timing` | `PER_EVALUATION` or `CASE_LIFETIME` retrieval timing |
| `temporalDecayHalfLifeDays` | Decay factor for older experiences |

`CbrCaseTypeRegistration` registers case types for CBR retention.

### Oversight Gate (`api/spi/`)

Platform-level oversight for consequential worker actions:

**ActionRiskClassifier SPI** — classifies planned actions by risk. Implement with `@RiskClassifier @ApplicationScoped`:
```java
@RiskClassifier @ApplicationScoped
public class MyClassifier implements ActionRiskClassifier {
    @Override public RiskDecision classify(PlannedAction action, ClassificationContext ctx) { ... }
}
```

Multiple classifiers compose via `ChainedActionRiskClassifier` ("most restrictive wins"). `RiskDecision` is sealed: `Autonomous` | `GateRequired(reason, reversible, candidateGroups, expiresIn, scope, resolutionType, quorum)`.

**Multi-approver gates:** `QuorumConfig` supports M-of-N approval for high-risk actions. `OversightGateService` manages gate lifecycle with `openGate()` and `fulfill()`.

**ActionGatePolicy** — policy for gate lifecycle decisions.

---

## SPIs to Implement

### Worker Provisioner SPIs (`api/spi/`)

Four operational SPIs. All ship with `@DefaultBean @ApplicationScoped` no-op defaults:

| SPI | Purpose |
|---|---|
| `WorkerProvisioner` | Provision and terminate workers when no pre-defined workers match a capability |
| `WorkerStatusListener` | Worker lifecycle callbacks: `started()`, `completed()`, `stalled()` |
| `CaseChannelProvider` | Open/close/post to backend-agnostic channels. `postToChannel` takes a `MessageType` parameter from `casehub-qhorus-api` |
| `WorkerContextProvider` | Build worker startup context from ledger lineage — includes prior worker summaries, causal chain metadata |

### AgentRoutingStrategy SPI (`api/spi/routing/`)

Selects which worker instance handles a task. Returns `RoutingResult` (sealed: `Selected`, `Unresolvable`, `Escalated`). Resolved via CDI priority.

### Composable Routing Architecture (`api/spi/routing/`)

`ComposableAgentRoutingStrategy` (`@DefaultBean`, id=`"composable"`) blends scores from independent `RoutingSignalProvider` implementations.

**RoutingSignalProvider SPI** — `signal(AgentRoutingContext, List<AgentCandidate>) -> @Nullable RoutingSignal`. Scores in [0.0, 1.0]. `CandidateSignal` is sealed: `Score` | `Exclude` | `Escalate`. Thread-safe.

Built-in signal providers: `WorkloadSignalProvider`, `TrustSignalProvider`, `ExperienceSignalProvider`, `PersonalitySignalProvider`, `SemanticSignalProvider`.

**RoutingPromptSection SPI** — pluggable LLM prompt enrichment for agent routing. `render(AgentRoutingContext, List<AgentCandidate>) -> @Nullable String`.

**RoutingOutcomeRecorder SPI** — records routing outcomes for CBR learning.

### Strategy SPIs (`api/spi/routing/`)

All follow the `NamedStrategy` convention and are resolved at dispatch time via `StrategyResolver`:

| SPI | Purpose |
|---|---|
| `ImplementationRoutingStrategy` | Selects between implementation bindings for the same capability |
| `HumanTaskRoutingStrategy` | Enriches human task candidate sets with scores and experiences |
| `CandidateMatchingStrategy` | Matches workers to capabilities |
| `CandidateSetStrategy` | Determines the candidate set (static or JQ-based) |
| `DecompositionStrategy` | HTN task decomposition |

### Other SPIs (`api/spi/`)

| SPI | Purpose |
|---|---|
| `ActionRiskClassifier` | Classifies planned actions by risk level for oversight gates |
| `CaseOutcomeObserver` | Lifecycle hook called when a case reaches a terminal state |
| `CaseContextStore` / `CaseContextStoreFactory` | Pluggable storage backend for context layers |
| `ContextDiffStrategy` | Strategy for computing context diffs between writes |
| `WorkerExecutionGuard` | Guards worker execution (used by PoisonPill detection) |
| `WorkerFunctionProvider` | Constructs `WorkerFunction` from raw YAML worker nodes |
| `CaseEventRecorder` | Records case events for external consumption |
| `CaseCorrelationResolver` | Resolves case correlation for inbound signals |
| `DataRefResolver` | Resolves `DataRef` references to external data |
| `OversightGateService` | Multi-approver oversight gate lifecycle |
| `ActionGatePolicy` | Gate lifecycle policy |
| `ProvisionerConfigRegistry` | Configuration registry for worker provisioners |

### Routing Data SPIs (`api/spi/routing/`)

| SPI | Purpose |
|---|---|
| `ExperienceAnalyser` | Analyses past experiences for routing decisions |
| `WorkloadDataProvider` / `WorkloadSnapshot` | Provides workload data for load-balanced routing |
| `TrustRoutingPolicyProvider` | Provides trust routing policies per capability |

### Actor State Contributor SPI (`actor-state`)

`ActorStateContributor` — contributes data to the unified actor state view. Built-in contributors: `EngineActorStateContributor` (active Quartz jobs), `LedgerActorStateContributor` (trust scores), `QhorusActorStateContributor` (commitments), `WorkActorStateContributor` (work items).

### Inbound SPIs (`inbound`)

`InboundWorkItemPolicy` — decides whether and how to create a WorkItem from an inbound qhorus message. No default bean — the bridge is inert without an implementation.

### Flow SPIs (`flow`)

`CallableDispatcher` — dispatches named calls with arguments from Serverless Workflow steps. Registered via `CallableDispatchRegistry`.

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

---

## REST API (`casehub-engine-rest`)

Opt-in JAX-RS module — add to classpath to expose REST endpoints. All endpoints use `@RunOnVirtualThread` and include OpenAPI annotations.

### Endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/cases` | List case instances (paginated, filterable by status/namespace/name) |
| `POST` | `/api/v1/cases` | Start a new case instance |
| `GET` | `/api/v1/cases/{caseId}` | Get case instance by ID |
| `GET` | `/api/v1/cases/{caseId}/context` | Get full case context |
| `GET` | `/api/v1/cases/{caseId}/context/{path}` | Get case context by dot-notation path |
| `GET` | `/api/v1/cases/{caseId}/plan-items` | Get plan items for a case |
| `GET` | `/api/v1/cases/{caseId}/goals` | Evaluate goals against live case context |
| `POST` | `/api/v1/cases/{caseId}/suspend` | Suspend a running case |
| `POST` | `/api/v1/cases/{caseId}/resume` | Resume a suspended case |
| `POST` | `/api/v1/cases/{caseId}/cancel` | Cancel a running case |
| `GET` | `/api/v1/cases/{caseId}/events` | Get case event log (paginated, filterable by eventType/streamType) |
| `POST` | `/api/v1/cases/{caseId}/signals` | Send signal to a case (path + value) |
| `GET` | `/api/v1/case-definitions` | List all registered case definitions (paginated) |
| `GET` | `/api/v1/case-definitions/{ns}/{name}` | Get definitions by namespace and name |
| `GET` | `/api/v1/case-definitions/{ns}/{name}/{version}` | Get definition by exact key |
| `GET` | `/api/v1/cases/{caseId}/plan/model` | Live case plan model snapshot |
| `GET` | `/api/v1/cases/{caseId}/plan/definitions` | Plan item definition hierarchy |
| `GET` | `/api/v1/cases/{caseId}/plan/decomposition` | Captured HTN decomposition tree |
| `GET` | `/api/v1/cases/{caseId}/plan/dag` | Captured DAG plan snapshot |
| `GET` | `/api/v1/cases/{caseId}/plan/dag/result` | Captured DAG execution result |
| `GET` | `/api/v1/cases/{caseId}/plan/state` | Composed execution state for orchestration workbench |
| `GET` | `/actors/{actorId}/state` | Unified actor workload view (from actor-state module) |

### Error Handling

All errors return RFC 7807 `ProblemDetail` records. Exception mappers: `EntityNotFoundExceptionMapper` (404), `IllegalStateExceptionMapper` (409), `ConstraintViolationExceptionMapper` (400), `AccessDeniedExceptionMapper` (403), `CatchAllExceptionMapper` (500).

---

## Configuration

### YAML Case Definition

Cases are configured via `CaseDefinition.yaml`. Key configuration blocks:

- `spec:` — namespace, name, version, capabilities, workers, bindings, goals, milestones
- `context: { storeFactory: "<id>" }` — selects CaseContextStore implementation
- `cbr:` — Case-Based Reasoning retrieval configuration (`topK`, `weights`, `vectorWeight`, `timing`, `temporalDecayHalfLifeDays`)
- `routingSignalWeights:` — per-case routing signal provider weights
- `authorization:` — ACL grants (`read`, `write`, `admin`, `claim`)
- `humanTaskRouting:` — strategy ID for human task candidate enrichment
- `cognitiveDemand:` — per-capability cognitive function demand profile
- `episodicMemory:` — domain, entityId (JQ), and recent count for episodic memory injection

#### Case-Level SLA Example

A timer-triggered signal binding that writes an SLA expiry flag to case context, enabling a failure goal:

```yaml
spec:
  goals:
    - name: review-timed-out
      when: ".caseSla.expired == true"
  completion:
    failure:
      anyOf: [review-timed-out]
  bindings:
    - name: case-timeout
      on:
        schedule:
          every: PT48H
      when: ".caseSla.expired == null"
      signal:
        caseSla:
          expired: true
```

The `when` guard prevents re-firing after the first write. The `signal:` payload is static — written as-is to the case context. The `schedule:` trigger supports `every:` (ISO-8601 duration, one-shot) and `cron:` (Quartz expression, periodic).

### JQ Expression Evaluation

All JQ expressions evaluate against the **working layer** (`context.layer(ContextLayer.WORKING).asJsonNode()`), NOT the full layer document. YAML definitions use unqualified field paths (`.transaction`, `.entityResolution`).

### NamedStrategy Resolution

`CaseDefinition` strategy fields (`agentRouting`, `implementationRouting`, `candidateMatching`, `humanTaskRouting`, `decompositionStrategy`, `contextStoreFactory`) are nullable string IDs resolved at dispatch time via `StrategyResolver`. When absent, the `@DefaultBean` fallback is used.

### Expression Engine

Engine expression evaluation is unified with the platform `ExpressionEngine` and `ExpressionEvaluator` hierarchy. `ExpressionEngineRegistry` resolves evaluators.

**Supported languages:**

| Language | Evaluator | Evaluation target | Default for |
|---|---|---|---|
| JQ | `JQExpressionEvaluator` | `JsonNode` (working layer) | All expression sites when no `contextType` declared |
| MVEL | `TypedMvelExpressionEvaluator` | Typed POJO (`contextType` class) | Inferred when `contextType` is set |
| Lambda | `LambdaExpressionEvaluator` | Java DSL only (not YAML) | Programmatic predicates |

**`expressionLang` field** — declares the default expression language for ALL expressions in a case definition:

```yaml
spec:
  expressionLang: mvel   # all when/filter/inputProjection/etc use MVEL
```

When omitted, JQ is the default. When `contextType` is set, MVEL is inferred automatically (no explicit `expressionLang` needed).

**`contextType` declaration** — declares a typed Java context class. The engine auto-constructs a `JacksonPojoBridge` for the type and infers MVEL as the expression language:

```yaml
spec:
  contextType: com.example.LoanApplication
```

JQ expressions evaluate against `JsonNode`. MVEL expressions evaluate against the typed POJO instance — property access uses natural Java syntax (e.g., `amount > 10000`) instead of JQ paths (`.amount > 10000`).

**Per-expression language override** — any expression site supports inline language selection via the `{lang: expr}` map syntax. This overrides the definition-level `expressionLang` for a single expression:

```yaml
bindings:
  - name: high-value
    on:
      contextChange:
        when: { mvel: "amount > 10000" }
    inputProjection: { jq: ".amount" }

labelRules:
  - name: urgent
    when: { jq: '.priority == "URGENT"' }
    actions:
      - add: "priority/urgent"
```

Supported at: binding `when`, trigger `contextChange`/`filter`, `inputProjection`, `outputProjection`, `inputMapping`, `outputMapping`, goal/milestone conditions, `doneWhen`, label rule `when`.

### Resilience Configuration

```properties
# Dead Letter Queue auto-replay
casehub.dlq.auto-replay.enabled=false
casehub.dlq.auto-replay.interval=PT30M
casehub.dlq.auto-replay.delays=PT30M,PT2H,PT8H
casehub.dlq.auto-replay.max-attempts=3
```

### Runtime

```properties
# Quartz (RAM store, no JDBC)
quarkus.quartz.store-type=ram

# RLS (default off)
casehub.rls.enabled=false
```

---

## What This Repo Does NOT Do

- Manage human task inboxes (that is casehub-work)
- Handle agent-to-agent messaging protocols (that is casehub-qhorus)
- Provide a terminal/session UI (that is claudony)
- Implement worker provisioner SPIs — only defines the contracts
- Agent identity/discovery/vocabulary (that is casehub-eidos)
- Case queue management — see casehub-engine-queue (in development, not yet in reactor build)
