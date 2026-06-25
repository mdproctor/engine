# Design: Remove serverlessworkflow SDK from engine-api

**Issue:** engine#567
**Date:** 2026-06-25
**Status:** Approved

## Problem

The serverlessworkflow SDK's `Workflow` type leaks through five engine modules:
schema, api, common, runtime, and flow. Only flow actually executes workflows.
The other four carry `Workflow` as transportation, coupling them to the SDK and
violating the `module-tier-structure` protocol ("SPI method signatures must not
expose heavy external SDK types" — `io.serverlessworkflow.*` is explicitly listed).

The root cause is `DefaultWorkerExecutor`'s switch-based dispatch: it hardcodes
knowledge of every function type and execution technology. Adding or changing a
function type requires modifying the executor, importing the type, and taking the
SDK dependency.

Secondary problem: both `FlowWorkerFunction.execute()` and
`AgentWorkerFunction.execute()` throw `UnsupportedOperationException` — only
`WorkerFunction.Sync` genuinely implements the `execute()` contract. Two of
three subtypes violate Liskov substitution. The `execute()` method on
`WorkerFunction` is dead in production — the executor pattern-matches and
calls type-specific methods.

## Design

Five coordinated changes eliminate the SDK leak, fix the LSP violation, and
replace the hardcoded dispatch with a pluggable handler model.

### 1. `WorkerFunction` becomes a marker interface

**Module:** `casehub-worker-api` (foundation tier)

Remove `execute(Map<String, Object>)` from the `WorkerFunction` interface. It
becomes a pure marker — "this is something a worker runs."

- `WorkerFunction.Sync` keeps `fn()` accessor. The `@Override execute()`
  method is deleted — callers use `sync.fn().apply(input)`.
- `AgentWorkerFunction` stays in api, keeps `agent()` accessor. The
  `@Override execute()` that throws `UnsupportedOperationException` is
  deleted — the record simplifies to
  `record AgentWorkerFunction(Agent agent) implements WorkerFunction {}`.
- `FlowWorkerFunction` moves to the flow module (see section 4)

No production code calls `WorkerFunction.execute()` — `DefaultWorkerExecutor`
pattern-matches and calls type-specific methods. The break is compile-only and
mechanical.

### 2. `WorkerFunctionProvider` SPI

**Module:** `api/src/main/java/io/casehub/api/spi/`

```java
public interface WorkerFunctionProvider {
    boolean handles(JsonNode rawWorkerNode);
    WorkerFunction create(JsonNode rawWorkerNode);
}
```

The `JsonNode` here is a parse-boundary parameter (same pattern as
`ExpressionEngineRegistry.create(String)`). Raw data goes in, domain type comes
out. The `JsonNode` is not carried at runtime.

**`WorkerFunctionProviderRegistry`** (in `api/spi/`) orchestrates providers:

```java
public interface WorkerFunctionProviderRegistry {
    WorkerFunction createFunction(JsonNode rawWorkerNode);
}
```

Returns `null` when no provider handles the node (mapper falls through to
API-local construction). `DefaultWorkerFunctionProviderRegistry` in runtime
injects `Instance<WorkerFunctionProvider>`, iterates, and delegates to the
first provider whose `handles()` returns true.

`CaseDefinitionYamlMapper.load()` gains a `WorkerFunctionProviderRegistry`
parameter (same pattern as `ExpressionEngineRegistry`). `YamlCaseHub` injects
and passes it.

**Dispatch logic after the change:**

```java
// Try providers first (for SDK-dependent types like flow)
WorkerFunction function = providerRegistry.createFunction(rawWorkerNode);
if (function == null) {
    // API-local construction (no external SDK dependency)
    if (sw.getAgent() != null) {
        Agent apiAgent = AgentConverter.toApiAgent(sw.getAgent());
        function = new AgentWorkerFunction(apiAgent);
    } else {
        function = new WorkerFunction.Sync(input -> WorkerResult.of(input));
    }
}
workerBuilder.function(function);
```

The YAML mapper delegates SDK-dependent worker function construction to
providers. Agent and Sync construction remains inline in the mapper — they
carry no external SDK dependency. The Java DSL path bypasses providers
entirely — developers import `FlowWorkerFunction` from the flow module and
construct it directly.

**Non-CDI fallback:** The no-arg `CaseDefinitionYamlMapper.load(InputStream)`
overload uses a static empty `WorkerFunctionProviderRegistry` that always
returns `null` (same pattern as `JQ_ONLY` for `ExpressionEngineRegistry`).
With no providers registered, workers with `do:` blocks fall through to the
mapper's default `Sync` passthrough — the definition loads but flow execution
is not possible. For strict validation, a `STRICT_NO_FLOW` registry variant
throws at load time: "Workflow workers require CDI context." Tests that only
use sync/agent workers are unaffected.

**Schema module changes:**

`WorkerMarshaller.Deserializer` stops calling
`WorkflowReader.readWorkflowFromString()`. Embedded workflows (`do:` block)
are stored as raw `JsonNode`. `Worker.isEmbeddedWorkflow()` /
`getWorkflowAsEmbedded()` replaced by `hasWorkflowDefinition()` /
`getWorkflowDefinition()` returning `JsonNode`. Schema drops `quarkus-flow`
dependency.

`WorkerMarshaller.Serializer` simplifies: the current serializer accesses
`Workflow`-specific methods (`workflow.getDocument()`, `workflow.getDo()`,
`workflow.getInput()`, etc.). With `JsonNode` storage, the serializer writes
the raw JSON node directly — the SDK-specific field-by-field extraction is
replaced by a single node write.

### 3. `WorkerFunctionHandler` SPI

**Module:** `common/src/main/java/io/casehub/engine/common/internal/executor/`

```java
public interface WorkerFunctionHandler {
    boolean supports(WorkerFunction function);
    Uni<WorkerResult> execute(
        WorkerFunction function,
        Map<String, Object> inputData,
        WorkerContext context,
        int timeoutMs,
        ExecutionMetadata metadata);
}
```

`outputSchema` is deliberately absent. Output schema evaluation is a
cross-cutting concern owned by the composite executor, not the handler —
it applies identically to all function types. Including it would force each
handler to either duplicate the JQ evaluation infrastructure or ignore the
parameter.

Engine-internal SPI (not consumer-facing). Visible to runtime and flow — both
depend on common. References `ExecutionMetadata` which is already in
`common/internal/executor/`.

`DefaultWorkerExecutor` becomes a composite that dispatches to handlers and
applies output schema evaluation as a post-processing step:

```java
@Inject Instance<WorkerFunctionHandler> handlers;

public Uni<WorkerResult> execute(WorkerFunction function, ..., String outputSchema, ...) {
    for (WorkerFunctionHandler handler : handlers) {
        if (handler.supports(function)) {
            return handler.execute(function, inputData, context, timeoutMs, metadata)
                .map(result -> applyOutputSchema(result, outputSchema));
        }
    }
    throw new UnsupportedOperationException(
        "No handler for: " + function.getClass().getName());
}
```

The outer `WorkerExecutor` interface (called by `QuartzWorkerExecutionJob`)
still takes `outputSchema` — unchanged. Only the handler interface omits it.

Runtime provides `SyncAgentWorkerFunctionHandler` (`@ApplicationScoped`) —
handles `Sync` and `AgentWorkerFunction`. Contains the current `executeSync()`
logic (virtual thread, timeout, `WorkerExecutionContext` set/clear).

**Deleted from runtime:** `executeSync()`, `executeFlow()` private methods,
`NoOpWorkflowExecutor`.

**Deleted from common:** `WorkflowExecutor` SPI, `serverlessworkflow-api` and
`serverlessworkflow-impl-core` dependencies.

### 4. Flow module provides function, provider, and handler

`FlowWorkerFunction` moves from api to the flow module. Carries `Workflow`
directly — the SDK never leaves the module.

Three new/moved classes in flow (alongside existing `FlowExecutionRegistry`,
`CasehubCallableTaskBuilder`, `CasehubDispatch`, `CasehubFlow`):

- **`FlowWorkerFunction`** — implements `WorkerFunction` (marker). Accessor:
  `workflow()`.
- **`FlowWorkerFunctionProvider`** — `@ApplicationScoped`, implements
  `WorkerFunctionProvider`. Handles workers with a `do:` block. Receives raw
  `JsonNode`, deserializes to `Workflow` via `WorkflowReader`. Workflow DSL
  validation happens here — at case definition load time, not deferred.
- **`FlowWorkerFunctionHandler`** — `@ApplicationScoped`, implements
  `WorkerFunctionHandler`. Replaces `FlowWorkerExecutor` (which is deleted).
  Contains the current `FlowWorkerExecutor` logic: `WorkflowApplication`
  singleton, `FlowExecutionRegistry`, `WorkerExecutionContext` set/clear,
  async `CompletableFuture` to `Uni` pipeline. No timeout enforcement
  (workflow manages its own).

Handlers are complementary (`@ApplicationScoped`), not competing alternatives.
No `@DefaultBean` pattern needed. No flow module on classpath = no flow handler
registered = flow functions get "no handler" error at runtime.

### 5. Dependency cleanup

| Module  | Before                                                      | After   |
|---------|-------------------------------------------------------------|---------|
| schema  | `quarkus-flow`                                              | none    |
| api     | `serverlessworkflow-experimental-fluent-func`               | none    |
| common  | `serverlessworkflow-api`, `serverlessworkflow-impl-core`    | none    |
| runtime | `quarkus-flow`, `serverlessworkflow-experimental-fluent-func` | none  |
| flow    | `quarkus-flow`, `serverlessworkflow-experimental-types`     | unchanged |

## Runtime Test Migration

Runtime test CaseHub beans (`SimpleCaseHubBean`, `MultiWorkerPipelineBean`,
`AgentPipelineBean`) construct `FlowWorkerFunction(workflow(...))` using the
fluent DSL. These tests verify engine behavior (case lifecycle, SPI wiring,
multi-worker dispatch) — not flow execution.

**Migration:** Rewrite to use `WorkerFunction.Sync` directly. The worker
function body (the lambda inside the workflow step) moves to the `Sync`
constructor. Flow execution is covered by the flow module's own tests
(`FlowWorkerExecutorTest`, integration tests).

**Exception:** `YamlSimpleCaseHubBeanTest` tests YAML loading including `do:`
blocks. This test needs the flow module's `FlowWorkerFunctionProvider` to
construct the flow function. Add `casehub-engine-flow` as a test-scoped
dependency for this single test.

## Consumer Impact

**Foundation tier (`casehub-worker-api`):** `execute()` removed from
`WorkerFunction`. Compile-only break — no consumer calls it in production.

**Consumer repos (aml, clinical, devtown, life, claudony):** No additional
impact beyond engine#543 import migration (issues already filed).

**Java DSL users:** Import change from `io.casehub.api.model.FlowWorkerFunction`
to `io.casehub.engine.flow.FlowWorkerFunction`. Mechanical.

## Tradeoffs

**Discoverability:** Switch statement in one file vs handlers scattered across
modules. Standard plugin-architecture tradeoff.

**Exhaustiveness:** Compile-time switch coverage replaced by runtime "no handler"
error. Mitigated by integration tests.

**Two SPIs per function type:** `WorkerFunctionProvider` (YAML construction) and
`WorkerFunctionHandler` (execution) are separate. Correct separation — the Java
DSL path doesn't need a provider.

## What Gets Deleted

- `WorkerFunction.execute(Map)` from `WorkerFunction` interface (worker-api)
- `WorkerFunction.Sync.execute()` override (worker-api)
- `AgentWorkerFunction.execute()` override (api)
- `FlowWorkerFunction` from api (moves to flow)
- `WorkflowExecutor` SPI from common
- `FlowWorkerExecutor` from flow (replaced by `FlowWorkerFunctionHandler`)
- `NoOpWorkflowExecutor` from runtime
- `serverlessworkflow-api` dep from common
- `serverlessworkflow-impl-core` dep from common
- `serverlessworkflow-experimental-fluent-func` dep from api and runtime
- `quarkus-flow` dep from schema and runtime

## Deferred

- engine#570: output schema evaluation should use `ExpressionEngineRegistry`
