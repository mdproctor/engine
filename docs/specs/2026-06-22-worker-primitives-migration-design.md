# Worker Primitives Migration — engine#543

**Date:** 2026-06-22
**Issue:** casehubio/engine#543
**Status:** Design approved (round 3)

## Summary

Migrate Worker primitives from `casehub-engine-api` to the foundation-tier `casehub-worker-api` dependency. Governance types (`ExecutionPolicy`, `RetryPolicy`, `BackoffStrategy`) move to `casehub-platform-api`. This eliminates parallel type definitions and aligns with PLATFORM.md: "The canonical worker identity and capability vocabulary lives in `casehub-worker-api`."

## Prerequisites

1. **casehub-platform-api** — governance types in `io.casehub.platform.api.governance` (platform#104 — done)
2. **casehub-worker-api** — four changes required before engine migration:
   - Add `PlannedAction(String action, String actionType, Map<String, Object> parameters)` record
   - Change `WorkerOutcome.Success` from `record Success()` to `record Success(PlannedAction plannedAction)` (nullable)
   - Add `WorkerResult.of(Map<String, Object> output, PlannedAction action)` convenience factory — constructs `new WorkerResult(output, new Success(action))`
   - Add partial-output factory methods to `WorkerResult`: `declined(reason, partialOutput)`, `failed(reason, partialOutput)`, `expired(reason, partialOutput)` — these are overloads that put partial output in the existing `output` field (no new fields on outcome variants, no structural change to the sealed hierarchy)
3. **casehubio/parent** — update PLATFORM.MD worker-api type names (see Follow-Up Issues)

## Design Decisions

### PlannedAction placement — on Success, not on WorkerResult

Action declaration is a universal worker concern (correct dependency direction: foundation ← orchestration). PlannedAction belongs in `casehub-worker-api`.

The key insight: a PlannedAction is only valid with a Success outcome. The current engine enforces this with a runtime validation rule in WorkerResult's compact constructor. The right design makes this structurally impossible:

```java
sealed interface WorkerOutcome {
    record Success(PlannedAction plannedAction) implements WorkerOutcome {}
    record Declined(String reason) implements WorkerOutcome {}
    record Failed(String reason) implements WorkerOutcome {}
    record Expired(String reason) implements WorkerOutcome {}
}
```

WorkerResult stays `(output, outcome)` — two components. No nullable PlannedAction field, no runtime validation. The type system enforces the constraint.

**Factory methods on WorkerResult:**

```java
// Success without action (dominant case)
WorkerResult.of(output)
    → new WorkerResult(output, new Success(null))

// Success with planned action
WorkerResult.of(output, PlannedAction.of("File SAR", "sar.file", params))
    → new WorkerResult(output, new Success(action))

// Non-success with partial output
WorkerResult.declined(reason, partialOutput)
    → new WorkerResult(partialOutput, new Declined(reason))

// Non-success without output (uses Map.of())
WorkerResult.declined(reason)
    → new WorkerResult(Map.of(), new Declined(reason))
```

### WorkflowExecutionCompleted — remove redundant plannedAction field

With PlannedAction on `WorkerOutcome.Success`, the `plannedAction` field on `WorkflowExecutionCompleted` becomes derivable from `outcome`. Keeping both fields creates two copies that can disagree.

```java
// Old (7 components)
public record WorkflowExecutionCompleted(
    CaseInstance caseInstance, Worker worker, String idempotency,
    Map<String, Object> output, PlannedAction plannedAction,
    String bindingName, WorkerOutcome outcome) {}

// New (6 components)
public record WorkflowExecutionCompleted(
    CaseInstance caseInstance, Worker worker, String idempotency,
    Map<String, Object> output, String bindingName, WorkerOutcome outcome) {}
```

**Affected sites:**

- `QuartzWorkerExecutionJob.onSuccess()` — stops extracting and enriching PlannedAction; constructs the event without it. The `withIdentity()` call is eliminated entirely (identity moves to `ClassificationContext` at the classifier call site)
- `QhorusMessageSignalBridge` — event construction sites drop the `plannedAction` argument
- `WorkflowExecutionCompletedHandler` — extracts PlannedAction from `event.outcome()` instead of `event.plannedAction()`:
  ```java
  PlannedAction action = event.outcome() instanceof WorkerOutcome.Success s
      ? s.plannedAction() : null;
  ```
- `approved()` factory — removes PlannedAction parameter; uses `WorkerOutcome.success()` (Success with null PlannedAction)

### WorkerFunction — open interface with engine-owned variants

Worker-api's `WorkerFunction` is an open interface with `execute(Map<String, Object>) -> WorkerResult`. The engine's sealed variants (`Sync`, `AgentExec`, `Flow`) are replaced by:

| Type | Module | execute() behaviour |
|------|--------|---------------------|
| `WorkerFunction.Sync` | worker-api | Runs the lambda |
| `AgentWorkerFunction(Agent)` | engine-api | Runs `agent.execute()` |
| `FlowWorkerFunction(Workflow)` | engine-api | Throws — dispatch handled by `FlowWorkerExecutor` |

**FlowWorkerFunction stays in engine-api**, not engine-flow. engine-api already has a compile dependency on `serverlessworkflow-experimental-fluent-func` — `Worker.java` and `WorkerFunction.java` (both being deleted) import `Workflow`. `CaseDefinitionYamlMapper` (in engine-api) constructs Workers from YAML including flow workers, and must be able to reference `FlowWorkerFunction`. Moving it to engine-flow would create a dependency cycle (engine-api cannot depend on engine-flow).

After this migration, `FlowWorkerFunction` is the only file in engine-api importing `Workflow` — down from two. A follow-up issue should track removing the serverless-workflow SDK from engine-api entirely, which requires restructuring the mapper and `YamlCaseHub`.

**Tradeoff — loss of exhaustive dispatch:** The sealed → open change loses compile-time exhaustive pattern matching in `DefaultWorkerExecutor`. This is a deliberate tradeoff: consumer extensibility (any `WorkerFunction` implementation works via `execute()` without engine changes) is gained at the cost of compile-time completeness. `FlowWorkerFunction.execute()` throwing `UnsupportedOperationException` is a code smell that comes with this trade — a type that says "I'm a WorkerFunction" but whose `execute()` doesn't work. This is the cost of keeping `Worker.function()` typed as `WorkerFunction` while Flow dispatch requires `FlowWorkerExecutor`. Accepted given the constraint.

`DefaultWorkerExecutor` dispatch:
```java
if (function instanceof FlowWorkerFunction flow) {
    return workflowExecutor.execute(flow.workflow(), input);
}
return Uni.createFrom().item(() -> function.execute(input));
```

Flow checked first; everything else goes through `execute()`. Custom consumer `WorkerFunction` implementations work automatically. The dispatch is a single instanceof check — simple enough that loss of exhaustiveness has minimal practical risk.

### PlanElement removal

Worker no longer implements `PlanElement`. Zero runtime code casts to, queries on, or branches based on `PlanElement`. Workers are executed BY plan items — they don't participate in the plan model directly. Stage and SubCase are the real plan elements.

Update `PlanElement` Javadoc to remove the Worker reference.

### AgentDescriptor — moved from Worker to CaseDefinition

Worker drops the `agentDescriptor` field and `hasDescriptor()` method. The association between Worker and AgentDescriptor moves to `CaseDefinition`, decoupling Worker identity (foundation tier) from agent identity (eidos, optional module).

**Production call sites affected** (4 sites, 2 files):
- `AgentCandidateFactory.java:88` — `w.hasDescriptor()` gates capability health probing
- `AgentCandidateFactory.java:90` — `w.agentDescriptor()` passed to `capabilityHealth.probe()`
- `AgentCandidateFactory.java:118` — `w.agentDescriptor()` passed to `AgentCandidate` constructor
- `SemanticAgentRoutingStrategy.java:165,171` — `cc.candidate().agentDescriptor()` for null-check and embedding

**Replacement mechanism:**

`CaseDefinition` gains a `Map<String, AgentDescriptor>` keyed by worker name:

```java
// CaseDefinition gains:
public Optional<AgentDescriptor> agentDescriptorFor(String workerName)

// CaseDefinition.Builder gains:
public Builder agentDescriptor(String workerName, AgentDescriptor descriptor)
```

`AgentCandidateFactory.buildCandidates()` gains a `CaseDefinition` parameter. Both production call sites already have access:
- `CaseContextChangedEventHandler.java:144` — calls `caseDefinitionRegistry.getCaseDefinition(caseMetaModel)`
- `DefaultWorkOrchestrator.java:130` — already holds `definition`

The factory changes from reading the descriptor off the Worker to looking it up from the CaseDefinition:

```java
// Old
AgentDescriptor desc = w.hasDescriptor() ? w.agentDescriptor() : null;

// New
AgentDescriptor desc = caseDefinition.agentDescriptorFor(w.name()).orElse(null);
```

`AgentCandidate` record retains `agentDescriptor` as a field — only the source changes. `SemanticAgentRoutingStrategy` is unaffected (it reads from `AgentCandidate`, not `Worker`).

**AgentDescriptor association is programmatic, not YAML.** The YAML mapper does not set `agentDescriptor` on Workers today. `AgentConverter` produces `io.casehub.api.model.ai.Agent` objects (not `AgentDescriptor`). Descriptors are populated programmatically by CaseHub DSL subclasses or Eidos integration at registration time. The YAML mapper is unaffected by this change.

**Why not AgentRegistry lookup:** `AgentRegistry.findById(agentId, tenancyId)` uses agentId as the key, which is not the same as worker name. The descriptor-to-worker association is a build-time concern set by the case definition author, not a runtime registry query.

### ActionRiskClassifier — ClassificationContext record

Following the `ProvisionContext` precedent (which carries 7 fields for the same reason), the classifier receives a context record instead of individual parameters. Zero consumers have implemented the SPI, so this is the one chance to get the signature right:

```java
// In engine-api
public record ClassificationContext(
    String workerId,
    UUID caseId,
    String tenancyId,
    String caseDefinitionName,
    String capabilityName,
    String bindingName
) {}

// Old
RiskDecision classify(PlannedAction action)

// New
RiskDecision classify(PlannedAction action, ClassificationContext context)
```

`capabilityName` and `bindingName` follow the `ProvisionContext.taskType` precedent — different risk thresholds per binding in the same case. Added now (zero consumers) rather than deferred via SPI evolution.

Both `ActionRiskClassifier` (blocking) and `ReactiveActionRiskClassifier` (reactive) change. `ChainedReactiveActionRiskClassifier` constructs the context from available state and passes it through. No consumers have implemented the SPI yet (exploration issues only: life#20, devtown#56, aml#42, clinical#47, openclaw#6).

## Type Deletion and Replacement Map

Nine files deleted from engine-api:

| Delete | Replacement | Notes |
|--------|------------|-------|
| `api/.../model/Worker.java` | `io.casehub.worker.api.Worker` | record; `getName()` → `name()` etc. |
| `api/.../model/WorkerFunction.java` | `io.casehub.worker.api.WorkerFunction` | open interface, not sealed |
| `api/.../model/WorkerResult.java` | `io.casehub.worker.api.WorkerResult` | 2 components (output, outcome) |
| `api/.../model/WorkerOutcome.java` | `io.casehub.worker.api.WorkerOutcome` | Success gains PlannedAction |
| `api/.../model/Capability.java` | `io.casehub.worker.api.Capability` | record; `getName()` → `name()` etc. |
| `api/.../model/ExecutionPolicy.java` | `io.casehub.platform.api.governance.ExecutionPolicy` | gains `noRetry()` factory |
| `api/.../model/RetryPolicy.java` | `io.casehub.platform.api.governance.RetryPolicy` | gains `maxDelayMs` 4th field |
| `api/.../model/BackoffStrategy.java` | `io.casehub.platform.api.governance.BackoffStrategy` | identical enum |
| `api/.../spi/PlannedAction.java` | `io.casehub.worker.api.PlannedAction` | declaration only, no identity fields |

## New Types Created in Engine

| Type | Module | Purpose |
|------|--------|---------|
| `AgentWorkerFunction(Agent)` | engine-api | `implements WorkerFunction`; `execute()` delegates to `agent.execute()` |
| `FlowWorkerFunction(Workflow)` | engine-api | `implements WorkerFunction`; `execute()` throws (dispatch by `FlowWorkerExecutor`) |
| `ClassificationContext` | engine-api | Context record for `ActionRiskClassifier.classify()` |

## Dependencies

- `casehub-worker-api` added as compile dependency to `engine-api/pom.xml` — flows transitively to all downstream engine modules
- `casehub-platform-api` already a dependency — governance types come through existing path, import changes only
- engine-api retains existing `serverlessworkflow-experimental-fluent-func` dependency — `FlowWorkerFunction` is the sole remaining consumer (follow-up issue to remove)

## Accessor Rename Impact (production code)

| Old | New | Sites |
|-----|-----|-------|
| `worker.getName()` | `worker.name()` | ~35 |
| `worker.getCapabilities()` | `worker.capabilities()` | ~10 |
| `worker.getFunction()` | `worker.function()` | 1 |
| `worker.getExecutionPolicy()` | `worker.executionPolicy()` | 4 |
| `worker.getDescription()` | `worker.description()` | 0 production |
| `worker.agentDescriptor()` | `caseDefinition.agentDescriptorFor(worker.name())` | 2 (AgentCandidateFactory) |
| `worker.hasDescriptor()` | `caseDefinition.agentDescriptorFor(worker.name()).isPresent()` | 2 (AgentCandidateFactory) |
| `result.plannedAction()` | `result.outcome() instanceof Success s ? s.plannedAction() : null` | handler sites |
| `event.plannedAction()` | `event.outcome() instanceof Success s ? s.plannedAction() : null` | WorkflowExecutionCompletedHandler |
| `capability.getName()` | `capability.name()` | many |
| `capability.getInputSchema()` | `capability.inputSchema()` | several |
| `capability.getOutputSchema()` | `capability.outputSchema()` | several |
| `capability.getDescription()` | `capability.description()` | several |
| `capability.setDescription(...)` | builder chain | 1 (CaseDefinitionYamlMapper) |
| `worker.setDescription(...)` | builder chain | 1 (CaseDefinitionYamlMapper) |
| `worker.setExecutionPolicy(...)` | builder chain | 1 (CaseDefinitionYamlMapper) |

## Non-Trivial Production Changes

Beyond mechanical accessor renames, these sites have behavioural changes:

| File | Change | Why non-trivial |
|------|--------|-----------------|
| `WorkflowExecutionCompleted.java` | Remove `plannedAction` field (7 → 6 components) | All construction and consumption sites change arity |
| `QuartzWorkerExecutionJob.onSuccess()` | Remove PlannedAction extraction + `withIdentity()` enrichment | `withIdentity()` is eliminated; identity moves to `ClassificationContext` at classifier call site |
| `WorkflowExecutionCompletedHandler` | Extract PlannedAction from `event.outcome()` instead of `event.plannedAction()` | Gate path entry condition changes |
| `AgentCandidateFactory.buildCandidates()` | Gains `CaseDefinition` parameter; descriptor lookup changes source | Method signature change; both call sites pass the definition |
| `CaseDefinition` / `CaseDefinition.Builder` | Gains `Map<String, AgentDescriptor>` + `agentDescriptorFor()` | New field and API surface |
| `ActionRiskClassifier` / `ReactiveActionRiskClassifier` | `classify(PlannedAction)` → `classify(PlannedAction, ClassificationContext)` | SPI signature change (zero consumers) |

## CaseDefinitionYamlMapper Changes

The mapper is the most significant single-file change.

**Worker construction** — constructor calls and setter chains become builder chains:

```java
// Old
new Worker(name, caps, workflow);
worker.setDescription(desc);
worker.setExecutionPolicy(policy);

// New
Worker.builder()
    .name(name)
    .capabilities(caps)
    .function(new FlowWorkerFunction(workflow))
    .executionPolicy(new ExecutionPolicy(timeout, new RetryPolicy(max, delay, strategy)))
    .description(desc)
    .build()
```

**Capability construction** — mutable class with setters becomes record:

```java
// Old
Capability cap = new Capability(name, inputSchema, outputSchema);
cap.setDescription(desc);

// New
Capability.builder()
    .name(name)
    .inputSchema(inputSchema)
    .outputSchema(outputSchema)
    .description(desc)
    .build()
// or: Capability.of(name, inputSchema, outputSchema) when no description
```

**AgentDescriptor** — the YAML mapper is unaffected. It does not set `agentDescriptor` on Workers today. Descriptors are populated programmatically by CaseHub DSL subclasses or Eidos integration at registration time.

`RetryPolicy` gains a 4th field (`maxDelayMs`) in the platform version. The mapper passes `null` for it unless the YAML schema adds a `maxDelayMs` field (separate issue).

## Blast Radius

- ~60 production source files — import changes + accessor renames
- ~30 test files — same mechanical changes
- **Non-trivial changes** (see table above): `WorkflowExecutionCompleted` (field removal), `QuartzWorkerExecutionJob` (enrichment elimination), `WorkflowExecutionCompletedHandler` (gate path entry), `AgentCandidateFactory` (signature + descriptor source), `CaseDefinition` (new API), `ActionRiskClassifier` (SPI signature)
- Heaviest mechanical consumers: `WorkflowExecutionCompletedHandler` (~25 `name()` calls), `WorkerScheduleEventHandler` (~15), `QuartzWorkerExecutionJob`, `DefaultWorkerExecutor`
- `CaseDefinitionYamlMapper` — Worker construction, Capability construction
- **Test mock-to-construction rewrite**: tests mocking `Worker.class` or `Capability.class` with Mockito need real object construction — records are final and can't be mocked. Replace `mock(Worker.class)` + `when(worker.getName())` with `Worker.builder().name(...).capabilities(...).function(...).build()`. Same for Capability: `Capability.of(name, input, output)` replaces the mock. This is a different kind of change from accessor renames.
- Modules with zero references (unaffected): `persistence-memory`, `persistence-hibernate`, `casehub-engine-actor-state`, `casehub-engine-ledger`

## Protocol Alignment

- **PP-20260512-module-tiers**: `FlowWorkerFunction` stays in `engine-api` (existing SDK dependency retained; follow-up to remove). `AgentWorkerFunction` in `engine-api` (Agent is already in engine-api)
- **PP-20260514-engine-spi-noops-defaultbean**: no new no-op defaults needed
- **PP-20260601-spi-evolution**: ActionRiskClassifier signature is a breaking change, but no consumers exist yet
- **PP-20260529-engine-api-scope**: engine-api dependency on worker-api is correct (Tier 1 → Foundation)
- **PP-20260531-worker-func-exec**: worker function execution model unchanged — `FlowWorkerFunction` preserves the workflow execution pipeline
- **PP-20260512-arename**: cross-repo propagation needed — consumer repos importing `io.casehub.api.model.Worker` etc. must update imports. File issues on affected repos.

## Follow-Up Issues

| Issue | Repo | Description |
|-------|------|-------------|
| TBD | casehubio/parent | Update PLATFORM.MD — worker-api types are Worker/Capability/WorkerFunction/WorkerResult/WorkerOutcome, not WorkerContext/WorkerSpec/WorkerCapability |
| TBD | casehubio/engine | Remove `serverlessworkflow-experimental-fluent-func` from engine-api — requires restructuring CaseDefinitionYamlMapper and YamlCaseHub |
| TBD | affected consumer repos | Import propagation — `io.casehub.api.model.Worker` → `io.casehub.worker.api.Worker` etc. |

## Out of Scope

- YAML schema changes for `maxDelayMs` on RetryPolicy
- Serverless-workflow SDK removal from engine-api (tracked as follow-up)
- Consumer repo import propagation (tracked via separate issues)
- `PlanElement` interface refactoring beyond Javadoc update
