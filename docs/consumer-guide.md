# casehub-engine — Consumer Guide

> Hybrid choreography+orchestration coordination engine for multi-agent work.

**GitHub:** [casehubio/engine](https://github.com/casehubio/engine)
**Tier:** Foundation

---

## Purpose

Implements the Blackboard Architecture (Hayes-Roth, 1985) with CMMN terminology. Coordinates workers (AI agents, humans) via case definitions, binding rules, and optional synchronous orchestration.

---

## Modules to Depend On

| Module | Artifact | When to use |
|---|---|---|
| `casehub-engine-api` | `io.casehub:casehub-engine-api` | SPI interfaces, domain model (`Worker`, `Binding`, `Capability`, `HumanTaskTarget`), `Agent` wrapper, `AgentRoutingStrategy` SPI |
| `casehub-engine-common` | `io.casehub:casehub-engine-common` | Domain objects (`CaseMetaModel`, `CaseInstance`), persistence SPIs, `JQEvaluator`, `EventLog` |
| `casehub-engine` | `io.casehub:casehub-engine` | Runtime — choreography handlers, orchestration, worker scheduling, expression engine |
| `casehub-engine-planning` | `io.casehub:casehub-engine-planning` | CMMN planning orchestration — `PlanningRegistry`, `PlanItem`, `SubCase` lifecycle |
| `casehub-engine-schema` | `io.casehub:casehub-engine-schema` | `CaseDefinition.yaml` JSON Schema generated Java model |

**Optional modules** (activated by classpath presence):

| Module | What it adds |
|---|---|
| `casehub-engine-resilience` | Dead Letter Queue, PoisonPill detection, backoff strategies, case timeout |
| `casehub-engine-ledger` | Tamper-evident case lifecycle ledger; `TrustWeightedAgentStrategy` |
| `casehub-engine-ai` | `AgentEmbeddingProvider` SPI + `SemanticAgentRoutingStrategy` — semantic agent routing |
| `casehub-engine-actor-state` | Unified actor workload view (`GET /actors/{actorId}/state`) |
| `casehub-engine-flow` | `Worker(Workflow)` — dispatch casehub workers from Serverless Workflow steps |
| `casehub-engine-inbound` | Bridges qhorus `MessageReceivedEvent` to casehub-work WorkItems via `InboundWorkItemPolicy` SPI |

**Test modules:**

| Module | Purpose |
|---|---|
| `casehub-engine-persistence-memory` | In-memory thread-safe persistence for `@QuarkusTest` without Docker |
| `casehub-engine-testing` | Shared test utilities |

---

## Key Abstractions

### CaseDefinition (YAML DSL)

Cases are defined declaratively: namespace, name, version, capabilities, workers, bindings, goals, milestones, completion conditions. Additional fields: `types` (Set<Path>), `labels` (Set<Path>), `defaultWorkerBridge` (ContextBridge), `contextStoreFactory` (string key for NamedStrategy resolution), and `signals` (List<SignalType>). `CaseDefinitionYamlMapper` converts the JSON Schema-generated model to the runtime API model.

**Classification:** `types` (hierarchical case classification paths, e.g. `casehubio/devtown/pr-review`) and `labels` (arbitrary tags). Both validated against vocabulary at registration time when vocabulary is configured.

**Binding target types** (mutually exclusive per binding):
- `capability` — routes to a worker by capability match
- `subCase` — spawns a child case
- `humanTask` — creates a WorkItem in casehub-work (inline or template mode). Supports `scope`, `inputMapping`/`outputMapping` (JQ), `candidateGroups`, `candidateUsers`, `expiresIn`, `outcomes`

**Binding fields:** `inputSchemaOverride` overrides the capability's default input schema for this binding only. `contextWrite` is a JQ expression whose result is merged into case context after the worker completes.

**Trigger types:** `contextChange` (with optional `filter` and binding-level `when` guard), `schedule`/`timer`.

### CasePlanModel and PlanItem Lifecycle

`BlackboardRegistry` tracks `CasePlanModel` per case. Each binding creates a `PlanItem` that transitions through:

```
PENDING --> DELEGATED (control handed to external system, e.g. human task)
        \-> RUNNING   (Quartz-executed capability worker)
            --> terminal (COMPLETED, FAULTED, REJECTED, OBSOLETE, CANCELLED)
```

`SubCase` lifecycle: parent PlanItem stays `DELEGATED` until child case completes; `SubCaseCompletionService` handles the callback.

### Worker

Workers are declared in the CaseDefinition. `Worker` record carries `Set<String> capabilityNames`. Workers declare support by name; the engine resolves authoritative `Capability` instances from `CaseDefinition.getCapabilities()`.

`YamlCaseHub.getDefinition()` is `final` with `protected void augment(CaseDefinition)` hook for subclasses to add programmatic workers backed by CDI-injected services.

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

### GoalExpression + GoalBasedCompletion

Composed goal trees for case completion — replaces flat goal lists with recursive boolean expressions.

- `GoalExpression` (sealed) — `AllOfGoalExpression`, `AnyOfGoalExpression`, `SingleGoalExpression`. Factory methods: `allOf(Goal...)`, `anyOf(Goal...)`, `goal(String)`
- `GoalBasedCompletion<K extends GoalKind>` — maps goal kinds to goal expressions. `GoalKind.SUCCESS`, `GoalKind.FAILURE`
- `CaseDefinition.Builder.completion()` overloads: success only, success+failure, full GoalBasedCompletion, JQ predicate

### ContextBridge Protocol (`api/context/`)

Typed context translation for Case-to-Worker, Signal, and SubCase boundaries:
- `initialise(CaseContext, JsonNode narrowedInput) -> T` — creates typed context from case state
- `extractOutput(T) -> Map<String, Object>` — projects worker output back to case context
- `serialise(T) / deserialise(JsonNode)` — persistence round-trip
- Known implementations: `MapBridge`, `JsonNodeBridge`, `JacksonPojoBridge`

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

### Worker Outcome Handling

`WorkerOutcome` is sealed: `Success`, `Failure(reason)`, `Expired(reason)`. `DefaultOutcomePolicy` re-queues for retry up to `maxRetries`, then escalates.

### Lifecycle Scopes

`LifecycleScope` governs worker lifetime: `BINDING` (single dispatch), `COMPOUND` (compound duration), `CASE` (case duration). `Participation`: `PARTICIPANT` (blocks completion) or `COMPANION` (sidecar). `ExecutionMode`: `TRANSIENT`, `PERSISTENT` (long-running with mailbox), `REINVOKED` (re-invoked with accumulated state).

---

## SPIs to Implement

### Worker Provisioner SPIs (`api/spi/`)

Eight operational SPIs (4 blocking + 4 reactive mirrors). All ship with `@DefaultBean @ApplicationScoped` no-op defaults:

| SPI | Purpose |
|---|---|
| `WorkerProvisioner` / `ReactiveWorkerProvisioner` | Provision and terminate workers |
| `WorkerStatusListener` / `ReactiveWorkerStatusListener` | Worker lifecycle callbacks (started, completed, stalled) |
| `CaseChannelProvider` / `ReactiveCaseChannelProvider` | Open/close/post to backend-agnostic channels |
| `WorkerContextProvider` / `ReactiveWorkerContextProvider` | Build worker startup context from ledger lineage |

### AgentRoutingStrategy SPI (`api/spi/`)

Selects which worker instance handles a task. Resolved via CDI priority. Composable routing via `RoutingSignalProvider` implementations that contribute scores to `ComposableAgentRoutingStrategy`.

Built-in signal providers: `WorkloadSignalProvider`, `TrustSignalProvider`, `ExperienceSignalProvider`, `PersonalitySignalProvider`, `SemanticSignalProvider`.

### ActionRiskClassifier SPI (`api/spi/`)

Platform-level oversight gate for consequential worker actions. Implement with `@RiskClassifier @ApplicationScoped`:
```java
@RiskClassifier @ApplicationScoped
public class MyClassifier implements ActionRiskClassifier {
    @Override public RiskDecision classify(PlannedAction action, ClassificationContext ctx) { ... }
}
```

Multiple classifiers compose via "most restrictive wins". `RiskDecision` is sealed: `Autonomous` | `GateRequired(reason, reversible, candidateGroups, expiresIn, scope, resolutionType, quorum)`.

### CaseContextStore SPI (`api/context/`)

Pluggable storage backend for context layers. `CaseContextStoreFactory extends NamedStrategy` creates stores per layer per case. `isDurable()` signals whether stores survive JVM restarts. Default: `InMemoryCaseContextStoreFactory`.

### CaseOutcomeObserver SPI

Lifecycle hook called when a case reaches a terminal state (COMPLETED, FAULTED, CANCELLED). Implement as `@ApplicationScoped`; discovered automatically via CDI.

### RoutingSignalProvider SPI (`api/spi/routing/`)

Structured enrichment signals for routing strategies. `signal(AgentRoutingContext, List<AgentCandidate>) -> @Nullable RoutingSignal`. Scores must be in [0.0, 1.0]. Thread-safe.

### RoutingPromptSection SPI (`api/spi/routing/`)

Pluggable LLM prompt enrichment for agent routing. `render(AgentRoutingContext, List<AgentCandidate>) -> @Nullable String`. Implement as `@ApplicationScoped` with optional `@Priority(N)`.

---

## Configuration

### YAML Case Definition

Cases are configured via `CaseDefinition.yaml`. Key configuration blocks:

- `spec:` — namespace, name, version, capabilities, workers, bindings, goals, milestones
- `context: { storeFactory: "<id>" }` — selects CaseContextStore implementation
- `cbr:` — Case-Based Reasoning retrieval configuration (`topK`, `weights`, `vectorWeight`, `timing`)
- `routingSignalWeights:` — per-case routing signal provider weights
- `authorization:` — ACL grants (`read`, `write`, `admin`, `claim`)
- `humanTaskRouting:` — strategy ID for human task candidate enrichment
- `cognitiveDemand:` — per-capability cognitive function demand profile

### JQ Expression Evaluation

All JQ expressions evaluate against the **working layer** (`context.layer(ContextLayer.WORKING).asJsonNode()`), NOT the full layer document. YAML definitions use unqualified field paths (`.transaction`, `.entityResolution`).

### NamedStrategy Resolution

`CaseDefinition` strategy fields (`agentRouting`, `implementationRouting`, `candidateMatching`, `humanTaskRouting`, `decompositionStrategy`, `contextStoreFactory`) are nullable string IDs resolved at dispatch time via `StrategyResolver`. When absent, the `@DefaultBean` fallback is used.

---

## What This Repo Does NOT Do

- Manage human task inboxes (that is casehub-work)
- Handle agent-to-agent messaging protocols (that is casehub-qhorus)
- Provide a terminal/session UI (that is claudony)
- Implement worker provisioner SPIs — only defines the contracts
- Agent identity/discovery/vocabulary (that is casehub-eidos)
