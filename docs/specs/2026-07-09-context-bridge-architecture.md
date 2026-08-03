# ContextBridge — Typed Context Protocol for CaseHub

## Overview

CaseHub's execution model passes data through multiple boundaries: from case
context to workers, from workers to sub-cases, from connectors to signals, from
cases to human work items. Every one of these boundaries currently uses
`Map<String, Object>` or raw `JsonNode` — untyped, unsafe, and a source of
silent failures when the sender's data shape doesn't match the receiver's
expectations.

ContextBridge introduces a pluggable typed context protocol that applies
uniformly across all five boundary types in CaseHub. Each boundary declares
what type it works with. A bridge translates between the raw `CaseContext` and
that typed context. The protocol is locally scoped — each level handles only
its own translation — which means bridges compose through arbitrarily deep
nesting chains without global coordination.

The design draws on a Java generics technique we call the **Reified Varargs
Type Token**, which captures runtime type information despite erasure, giving
both compile-time type safety in lambdas and runtime bridge resolution from a
single zero-argument method call.

## Motivation

Three context models coexist in the CaseHub ecosystem:

- **CaseContext** — the engine's JSON-backed key-value context with layered
  reads and versioned writes
- **WorkflowContext** — Serverless Workflow SDK's scoped execution state,
  used by `FlowWorkerFunction` via `casehub-engine-flow`
- **AgenticScope** — LangChain4j's agent memory scope, used by agentic
  workers via the langchain4j integration

Today, all three are shoe-horned through `Map<String, Object>`. Workers
receive untyped maps, transform them with JQ or manual key lookups, and
return untyped maps. The engine applies the output back to `CaseContext`
with no compile-time guarantee that keys or value types match what the
binding expects.

This creates four problems:

1. **No compile-time safety.** A worker that expects `input.get("transaction")`
   gets `null` at runtime if the inputSchema JQ produces a different key. The
   compiler cannot catch this.

2. **Context models are second-class.** WorkflowContext and AgenticScope are
   live, scoped views with their own state management. Serialising them to
   `Map<String, Object>` and back destroys their semantics — they become
   awkward snapshots of inherently live things.

3. **Every boundary repeats the same mistake.** Worker input, WorkItem
   payload, SubCase context passing, signal payloads, and connector inbound
   messages all use the same untyped pattern. Each boundary independently
   suffers the same class of bugs.

4. **Nested chains multiply the risk.** A Case → Flow → SubCase → Flow →
   SubCase chain has four translation boundaries. One untyped mismatch at
   any level silently corrupts the downstream chain.

ContextBridge addresses all four by making the typed context the primary
abstraction at every boundary.

## The Reified Varargs Type Token Pattern

Java erases generic type parameters at runtime. A method declared as
`<T> void process()` has no way to know what `T` is. The standard
workaround — passing `Class<T>` explicitly — can't express parameterised
types like `Map<String, List<Transaction>>`.

The Reified Varargs Type Token exploits a subtlety of Java's array
reification: when a method declares `T... varargs`, the call site creates a
reified array even when zero arguments are passed. The array's component type
is the erased-but-concrete class of `T`, recoverable via
`getClass().getComponentType()`.

```java
static class Builder {
    @SafeVarargs
    final <T> TypedBuilder<T> fn(T... typeToken) {
        Class<?> runtimeType = typeToken.getClass().getComponentType();
        return new TypedBuilder<>(runtimeType);
    }
}

static class TypedBuilder<T> {
    private final Class<?> runtimeType;

    TypedBuilder(Class<?> runtimeType) {
        this.runtimeType = runtimeType;
    }

    WorkerBuilder apply(Function<T, WorkerResult> fn) {
        // runtimeType is available for bridge resolution
        // fn is typed — compiler enforces T on the lambda parameter
    }
}
```

A single declaration gives two things:

1. **Compile-time:** `T` propagates through the return type into the lambda,
   so `apply(txn -> txn.riskScore())` is type-checked by the compiler.
2. **Runtime:** `runtimeType` holds the concrete class (e.g., `Map.class`,
   `AmlTransaction.class`), available for bridge resolution and
   deserialisation.

### Usage at the DSL surface

```java
// Simple POJO — runtime knows AmlTransaction.class
Worker.builder()
    .capabilityName("assess-risk")
    .<AmlTransaction>fn()
    .apply(txn -> WorkerResult.of(Map.of("risk", txn.riskScore())))

// Parameterised type — runtime knows Map.class
Worker.builder()
    .capabilityName("enrich")
    .<Map<String, Transaction>>fn()
    .apply(input -> WorkerResult.of(Map.of("enriched", input.get("txn").id())))

// Live view — runtime knows WorkflowContext.class
Worker.builder()
    .capabilityName("orchestrate")
    .<WorkflowContext>fn()
    .apply(wfCtx -> WorkerResult.of(wfCtx.getResult()))

// Backward compatible — no type declaration, defaults to Map<String, Object>
Worker.builder()
    .capabilityName("legacy")
    .function(input -> WorkerResult.of(input))
```

### Comparison with alternatives

| Technique | Runtime type info | Nested generics | Allocation | Compile-time safety |
|-----------|------------------|-----------------|------------|-------------------|
| `Class<T>` parameter | Yes | No (`Map.class` loses `<K,V>`) | None | Yes |
| Super Type Token (Gafter) | Yes | Yes (full reification) | Anon class per call | Yes |
| Reified Varargs Type Token | Raw class only | Raw class (`Map.class`) | Zero-length array | Yes |

The Reified Varargs Type Token trades full nested generic reification for
zero allocation and a cleaner call-site syntax. The raw class is sufficient
for bridge resolution — the bridge itself knows how to handle the inner
types.

### Known limitation: parameterised container types

`.<Map<String, Transaction>>fn()` gives `Map.class` at runtime — the inner
type `Transaction` is erased. For `JacksonPojoBridge`, this means Jackson
produces `Map<String, LinkedHashMap>` instead of `Map<String, Transaction>`,
leading to a `ClassCastException` when the lambda accesses Transaction
methods.

**Guidance:** Use concrete POJO types for typed workers. If you need a map
of domain objects, wrap it in a POJO:

```java
// Wrong — inner type lost at runtime
.<Map<String, Transaction>>fn()
.apply(input -> input.get("key").amount())  // ClassCastException

// Right — POJO preserves full type info in field declarations
record TransactionInput(Map<String, Transaction> transactions) {}
.<TransactionInput>fn()
.apply(input -> input.transactions().get("key").amount())  // works
```

Jackson handles POJO field types correctly because the parameterised type
information is preserved in the class's field declarations, not in the
bridge's runtime type token. A `TypeReference`-accepting overload could
be added as a future extension for cases where a POJO wrapper is
undesirable.

## Core Abstractions

### ContextBridge\<T\>

The SPI that translates between `CaseContext` and a typed context `T`.
Separate from the context type itself — the bridge knows how to build,
serialise, and decompose a `T`, but `T` has no knowledge of bridges.

```java
public interface ContextBridge<T> {

    T initialise(CaseContext context, JsonNode narrowedInput);

    Map<String, Object> extractOutput(T context);

    JsonNode serialise(T context);

    T deserialise(JsonNode payload);

    default void onWrite(String key, Object value, CaseContext enclosing) {}

    default boolean isLiveView() { return false; }

    Class<T> contextType();
}
```

**`initialise`** — produces a `T` from the case's context data.
`narrowedInput` is the result of JQ evaluation against the working layer
using the binding's `inputSchema` expression — the engine pipeline evaluates
JQ before calling the bridge, so bridge implementations do not need access
to `JQEvaluator`. If no `inputSchema` is specified, `narrowedInput` is the
full working layer as `JsonNode`. Snapshot bridges (like `JacksonPojoBridge`)
deserialise from `narrowedInput`; live-view bridges (like
`WorkflowContextBridge`) create a scoped view backed by the `CaseContext`
and may ignore `narrowedInput`.

`PropagationContext` is deliberately absent from the `initialise()` signature.
This is a departure from epic #203, which specified `PropagationContext` as a
parameter of `initialise()`. The spec achieves the same guarantee — no bridge
can accidentally drop the causal chain — through engine-level threading
rather than bridge-level passing. For snapshot bridges (the common case),
propagation context is meaningless — they simply deserialise data. For
live-view bridges that need it (e.g., deadline budget tracking), it is
accessible via `CaseInstance.getPropagationContext()` through the engine
pipeline. `PropagationContext` threading remains an engine-level invariant
(see Nested Context Propagation), not a bridge-level concern.

Epic #203 also defined `initialise(Object enclosingContext, ...)` with
`Object` as the first parameter to allow bridges to translate between any
two context types. This spec narrows to `CaseContext` because the engine
bridge always operates on `CaseContext` — it is the only source at the
engine boundary. The `Object` generality was a premature abstraction that
would force every bridge implementation to cast its input. The connector
boundary uses `deserialise(JsonNode)` directly because its source genuinely
IS different (raw JSON, not `CaseContext`) — these are two legitimate entry
paths for two different boundary types, not an inconsistency in the
protocol.

**`extractOutput`** — extracts the worker's output as a Map for the engine to
apply back to `CaseContext` via the existing conflict resolution path
(`ConflictResolver`). For snapshot bridges this returns `null` — output comes
from `WorkerResult` directly. For live-view bridges this captures the
mutations made through the view.

**`serialise` / `deserialise`** — convert `T` to and from JSON for EventLog
storage. This is the durability boundary. For snapshot bridges,
`deserialise()` reconstructs a self-contained `T` from the payload — it
does not need `CaseContext`. For live-view bridges, `deserialise()` can
only produce a detached snapshot (it lacks `CaseContext`). The normal
execution path for live-view bridges uses `initialise(caseContext, payload)`
instead (see §QuartzWorkerExecutionJob), re-creating a live view from the
current `CaseContext`. `deserialise()` on live-view bridges exists for API
symmetry and crash recovery replay. EventLog metadata includes the bridge
type identifier so the correct bridge implementation is used even if the
deployment changes between scheduling and execution.

**`onWrite`** — optional write-through callback for live-view bridges. Called
when the typed context is mutated, allowing real-time propagation back to the
enclosing `CaseContext`. Default is a no-op for snapshot bridges.

**`isLiveView`** — returns `true` for bridges that produce a mutable view
backed by `CaseContext` (e.g., `WorkflowContextBridge`, `AgenticScopeBridge`).
The engine pipeline uses this to determine whether to call `extractOutput()`
when the worker's `WorkerResult` output is null. Default is `false`.

**`contextType`** — returns the runtime `Class<T>` for CDI discovery and
bridge resolution.

### Failure mode contract

Bridge operations can fail. The engine pipeline defines explicit behavior
for each failure point:

| Operation | Call site | Failure cause | Engine behavior |
|-----------|-----------|--------------|-----------------|
| `initialise()` | Scheduling (`WorkerScheduleEventHandler`) | Jackson deserialization fails, JQ evaluation error | Worker scheduling fails. `WORKER_EXECUTION_FAILED` event emitted. Worker is NOT submitted. |
| `initialise()` | Execution (`QuartzWorkerExecutionJob`, live-view only) | CaseContext state inconsistent, bridge internal error | Quartz job execution fails. Triggers retry via `QuartzRetryService`. After retries exhausted, `WORKER_RETRIES_EXHAUSTED` event. Same error path as `deserialise()` — both occupy the same pipeline position. |
| `serialise()` | Scheduling | POJO contains non-serialisable types | EventLog entry cannot be created. Scheduling fails atomically — no Quartz job is submitted. Same `WORKER_EXECUTION_FAILED` event. |
| `deserialise()` | Execution (snapshot bridges only) | Schema evolution mismatch, corrupted payload | Quartz job execution fails. Triggers retry via `QuartzRetryService`. After retries exhausted, `WORKER_RETRIES_EXHAUSTED` event. |
| `extractOutput()` | Execution (live-view bridges only) | Bridge fails to capture mutations | Worker execution completes but output application fails. `WORKER_EXECUTION_FAILED` event with the extraction exception. |

The guiding principle: bridge failures are **never silent**. The current
pipeline's behavior for JQ evaluation failure (catch, log warning, return
`Map.of()`) is explicitly NOT carried forward — silent degradation to empty
input is a source of subtle bugs. A bridge failure means the worker cannot
run correctly, so it should fail fast with a clear exception.

### Serialisation versioning

Between `serialise()` at scheduling time and `deserialise()` at execution
time, the bridge implementation or the POJO class can change: a hot
redeploy adds a field, a bug fix alters the serialisation format, or the
`WorkflowContextBridge` snapshot schema evolves. The spec does not introduce
a formal bridge versioning mechanism. Instead, it relies on two existing
properties:

1. **Jackson's forward compatibility.** `JacksonPojoBridge` uses
   `objectMapper.convertValue()` with `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES`
   disabled (the Jackson default). Added fields deserialise as `null`;
   removed fields are silently ignored. This handles the common case of
   incremental POJO evolution without requiring version metadata.

2. **EventLog metadata carries the bridge type.** The `serialise()` call
   site writes the bridge's `contextType()` class name into EventLog
   metadata under the key `"contextBridgeType"` (e.g.,
   `"io.casehub.aml.AmlTransaction"`). At execution time,
   `QuartzWorkerExecutionJob` reads this key and resolves the bridge via
   `BridgeResolver.resolveByTypeName(contextBridgeType)`, which calls
   `bridge.deserialise()` on the correct implementation. Pre-bridge
   EventLog entries lack this key; the resolver falls back to `MapBridge`
   for backward compatibility. This ensures that even if bridge resolution
   logic changes between scheduling and execution, the EventLog records
   which bridge produced the payload.

For bridges with incompatible schema evolution (e.g., renaming a field in
a POJO, or a `WorkflowContextBridge` snapshot format change), the
`deserialise()` failure routes through the existing retry mechanism. If
the new bridge cannot read old payloads, the worker retries exhaust and
the entry enters the dead letter queue — the same path as any other
unrecoverable execution failure. Bridge authors who need migration should
handle it inside `deserialise()` (e.g., check for old field names and map
them).

A formal versioning protocol (version field in serialised payload,
bridge-level compatibility negotiation) is deferred until a concrete need
arises. The Jackson forward-compatibility default covers the expected
evolution patterns.

### WorkerFunction\<T\>

The current `WorkerFunction` is unparameterised — `Sync` hardcodes
`Function<Map<String, Object>, WorkerResult>`. The generic version carries
the input type through the pipeline:

```java
public interface WorkerFunction<T> {

    WorkerFunction<Void> NONE = new None();

    Class<T> inputType();

    record Sync<T>(Class<T> inputType,
                   Function<T, WorkerResult> fn) implements WorkerFunction<T> {}

    record None() implements WorkerFunction<Void> {
        public Class<Void> inputType() { return Void.class; }
    }
}
```

### FlowWorkerFunction and AgentWorkerFunction

Both `FlowWorkerFunction` and `AgentWorkerFunction` implement
`WorkerFunction` and must be parameterised. However, unlike `Sync<T>`
where the user chooses the type parameter, these variants have fixed input
types determined by their execution model:

```java
public record FlowWorkerFunction(Workflow workflow)
    implements WorkerFunction<Map<String, Object>> {

    @Override public Class<Map<String, Object>> inputType() {
        return (Class) Map.class;
    }
}

public record AgentWorkerFunction(Agent agent)
    implements WorkerFunction<Map<String, Object>> {

    @Override public Class<Map<String, Object>> inputType() {
        return (Class) Map.class;
    }
}
```

**Why `Map<String, Object>`?** These variants don't expose a user-supplied
lambda — the "function" is a Workflow definition or Agent configuration.
Their handlers (`FlowWorkerFunctionHandler`, `SyncAgentWorkerFunctionHandler`)
manage their own input processing:

- `FlowWorkerFunctionHandler` passes `inputData` to
  `app.workflowDefinition(workflow).instance(inputData)` — the SW SDK
  receives a Map and manages its own `WorkflowContext` internally.
- `SyncAgentWorkerFunctionHandler` passes `inputData` to
  `agent.execute(Map<String, Object>)` — agents receive Maps.

The bridge for these workers is always `MapBridge` (identity). The context
model adaptation happens inside the handler, not at the bridge level. This
is intentional: `FlowWorkerFunctionHandler` creates `WorkflowContext`
internally from the input data — it does not use the bridge SPI for this.

`WorkflowContextBridge` in the built-in bridges table is forward-looking in
the initial implementation. It serves two future use cases: (a) a `Sync`
worker that explicitly declares `.<WorkflowContext>fn()` to receive a live
`WorkflowContext` backed by `CaseContext`, and (b) potential refactoring of
`FlowWorkerFunctionHandler` to delegate context creation to the bridge SPI.
It is included for architectural completeness — the protocol should have a
bridge for every context model, even if the initial implementation does not
exercise all of them.

Future work could parameterise `AgentWorkerFunction<T>` if the Agent
interface gains typed input support.

### Built-in bridges

| Bridge | Context type | Behaviour | Scope |
|--------|-------------|-----------|-------|
| `MapBridge` | `Map<String, Object>` | Identity — today's behaviour | Default fallback |
| `JacksonPojoBridge<T>` | Any POJO class | Jackson `convertValue` from working layer | Automatic for unknown classes |
| `JsonNodeBridge` | `JsonNode` | Direct `asJsonNode()` from working layer | Explicit opt-in |
| `WorkflowContextBridge` | `WorkflowContext` | Live scoped view, CDI-discovered | `casehub-engine-flow` |
| `AgenticScopeBridge` | `AgenticScope` | Live scoped view, CDI-discovered | langchain4j integration |

### Bridge resolution

`BridgeResolver` is a CDI bean that resolves the bridge for a given worker
or context type. Resolution is type-based — the resolver uses
`WorkerFunction.inputType()` (a `Class<?>` in worker-api) to determine
which bridge to apply. Resolution follows a priority chain:

1. **Default bridge on CaseDefinition** — `definition.defaultWorkerBridge()`,
   applied only when its `contextType()` matches the worker's `inputType()`
2. **CDI discovery** — `Instance<ContextBridge<?>>` matched by `contextType()`
   against the worker's `inputType()`
3. **`Map.class`** — `MapBridge` (identity, backward compatible)
4. **Any other class** — `JacksonPojoBridge<T>` (automatic Jackson
   deserialisation)

There is no `contextBridge()` method on the Worker builder. `Worker` is in
`casehub-worker-api` (tier 1) and must not reference `ContextBridge` in
`casehub-engine-api` (tier 2). The type token (`Class<?>`) crosses tier
boundaries cleanly; the bridge instance stays in engine-api. Per-worker
bridge customisation is achieved by registering a CDI `ContextBridge<T>`
bean for the desired type — it is discovered at step 2.

Most users never touch bridges directly. Declaring `.<AmlTransaction>fn()`
triggers step 4 automatically — Jackson deserialises the narrowed input data
into the POJO. Complex bridges (WorkflowContext, AgenticScope) register once
via CDI and are resolved at step 2.

### SPI location

`ContextBridge<T>` and the built-in bridges live in **`casehub-engine-api`**
— the module that owns `CaseContext`. The bridge SPI references `CaseContext`
in its `initialise()` signature, so it must live
at the same tier. Placing it in `casehub-platform-api` (tier 0) would
create a circular dependency: platform-api → engine-api → platform-api.

The type parameterisation of `WorkerFunction<T>` and the `fn()` builder
method stay in `casehub-worker-api` — they carry the type token (`Class<T>`)
but do not reference `ContextBridge`. Bridge resolution happens in the
engine pipeline, not in worker-api.

```
casehub-platform-api (tier 0)
  io.casehub.platform.api.governance.ExecutionPolicy
  (no bridge types — no engine-api dependency)

casehub-worker-api (tier 1 — depends on platform-api)
  io.casehub.worker.api.WorkerFunction<T>
  io.casehub.worker.api.Worker (builder with fn() method)
  io.casehub.worker.api.WorkerResult, Capability

casehub-engine-api (tier 2 — depends on worker-api, platform-api)
  io.casehub.api.context.ContextBridge<T>
  io.casehub.api.context.MapBridge
  io.casehub.api.context.JacksonPojoBridge<T>
  io.casehub.api.context.JsonNodeBridge
  io.casehub.api.context.CaseContext
  io.casehub.api.context.PropagationContext

casehub-engine-flow
  provides WorkflowContextBridge (CDI-discovered)

langchain4j integration
  provides AgenticScopeBridge (CDI-discovered)
```

`BridgeResolver` implementations are module-specific — the engine runtime
has its own resolver with the five-step chain above. The SPI is in
engine-api; the wiring is local to each runtime module.

## DSL Surface

### Worker builder — `fn().apply()`

The two-step chain uses the Reified Varargs Type Token on `fn()` and the
typed lambda on `apply()`:

```java
public class Worker {

    public static class WorkerBuilder {

        // Existing untyped path — backward compatible
        public WorkerBuilder function(
                Function<Map<String, Object>, WorkerResult> fn) {
            this.workerFunction =
                new WorkerFunction.Sync<>(Map.class, fn);
            return this;
        }

        // Typed path — Reified Varargs Type Token
        @SafeVarargs
        public final <T> TypedFunctionBuilder<T> fn(T... typeToken) {
            Class<?> runtimeType =
                typeToken.getClass().getComponentType();
            return new TypedFunctionBuilder<>(this, runtimeType);
        }
    }

    public static class TypedFunctionBuilder<T> {
        private final WorkerBuilder parent;
        private final Class<?> runtimeType;

        TypedFunctionBuilder(WorkerBuilder parent, Class<?> runtimeType) {
            this.parent = parent;
            this.runtimeType = runtimeType;
        }

        @SuppressWarnings("unchecked")
        public WorkerBuilder apply(Function<T, WorkerResult> fn) {
            parent.workerFunction =
                new WorkerFunction.Sync<>(runtimeType, fn);
            return parent;
        }
    }
}
```

### CaseDefinition-level default

```java
CaseDefinition.builder()
    .name("aml-screening")
    .defaultWorkerBridge(new JacksonPojoBridge<>(AmlContext.class))
    .worker(Worker.builder()
        .capabilityName("assess")
        .<AmlContext>fn()
        .apply(ctx -> ...))       // uses default bridge (inputType matches)
    .worker(Worker.builder()
        .capabilityName("legacy")
        .function(input -> ...))  // MapBridge — default NOT applied
```

The CaseDefinition default bridge applies only when the worker's
`inputType()` matches the bridge's `contextType()`. If a worker uses
the untyped `function()` path (`inputType() == Map.class`), the default
is skipped and `MapBridge` is used. This prevents a bridge from
producing a typed object that the worker's lambda does not expect.

### YAML definitions

```yaml
workers:
  - name: assess-risk
    contextType: io.casehub.aml.AmlTransaction
    capabilities: [assess-risk]
```

`CaseDefinitionYamlMapper` resolves the class via `Class.forName()` and
creates a `WorkerFunction.Sync<AmlTransaction>` with
`inputType() == AmlTransaction.class`. The bridge resolver uses this type
to select the bridge via the resolution chain.

`contextType` is only effective for workers whose `WorkerFunction.inputType()`
is determined by the YAML mapper — i.e., workers resolved by a
`WorkerFunctionProvider` or mapped to `Sync<T>`. For agent and flow
workers, `inputType()` is hardcoded to `Map.class` by
`AgentWorkerFunction` and `FlowWorkerFunction` respectively, so YAML
`contextType` has no effect and should not be specified. Future
parameterisation of `AgentWorkerFunction<T>` (see §FlowWorkerFunction
and AgentWorkerFunction) would make `contextType` applicable to agent
workers.

## Engine Pipeline Changes

### Current pipeline (Map-only)

```
CaseContext
  → JQ inputSchema → Map<String, Object>
  → EventLog.payload (JSON)
  → QuartzWorkerExecutionJob deserialises → Map<String, Object>
  → WorkerExecutor.execute(fn, inputData, ...)
  → fn.apply(inputData) → WorkerResult(Map)
  → WorkflowExecutionCompletedHandler → context.setAll(output)
```

### Bridge-aware pipeline

```
CaseContext
  → JQ inputSchema → narrowedInput (JsonNode)
  → ContextBridge<T>.initialise(context, narrowedInput) → T
  → bridge.serialise(T) → EventLog.payload (JSON)
  → QuartzWorkerExecutionJob:
      if liveView: bridge.initialise(caseContext, payload) → T (live view)
      else:        bridge.deserialise(payload) → T (detached snapshot)
  → WorkerExecutor.execute(fn, typedInput, ...)
  → fn.apply(typedInput) → WorkerResult(Map)
  → if (liveView && output empty) bridge.extractOutput(typedInput) → Map
  → WorkflowExecutionCompletedHandler → context.setAll(output)
```

The bridge is invoked at two points:

1. **WorkerScheduleEventHandler** — evaluates JQ to produce `narrowedInput`,
   calls `bridge.initialise()` to produce the typed input, then
   `bridge.serialise()` for EventLog storage.
2. **QuartzWorkerExecutionJob** — reconstructs the typed input from the
   EventLog payload. For snapshot bridges, calls `bridge.deserialise()`.
   For live-view bridges, calls `bridge.initialise(caseContext, payload)`
   to re-create a live view backed by the current `CaseContext` (which is
   already loaded at this point). After execution, calls
   `bridge.extractOutput()` for live-view bridges when `WorkerResult`
   output is empty.

### Signature changes

**`WorkerFunctionHandler.execute()`** — `Map<String, Object> inputData`
becomes `Object inputData`:

```java
public interface WorkerFunctionHandler {
    boolean supports(WorkerFunction<?> function);
    Uni<WorkerResult> execute(WorkerFunction<?> function, Object inputData,
                              WorkerContext context, int timeoutMs,
                              ExecutionMetadata metadata);
}
```

The handler casts internally — it already knows the concrete `WorkerFunction`
type via `supports()`. A runtime type check at the handler entry point
provides a fail-fast safety net:

```java
if (!function.inputType().isInstance(inputData)) {
    throw new BridgeTypeMismatchException(
        "Expected " + function.inputType().getName()
        + " but received " + inputData.getClass().getName());
}
```

This catches bridge resolution bugs or serialise/deserialise mismatches
with a clear diagnostic rather than a generic `ClassCastException` deep
in the lambda body.

**`WorkerExecutor.execute()`** — same change:

```java
Uni<WorkerResult> execute(WorkerFunction<?> function, Object inputData,
                          WorkerContext context, int timeoutMs,
                          String outputSchema, ExecutionMetadata metadata);
```

**`WorkerScheduleEventHandler`** — JQ evaluation stays in the handler;
bridge receives the narrowed result:

```java
// Before:
Map<String, Object> inputData = evalJqAsMap(
    instance.getCaseContext().layer(ContextLayer.WORKING).asJsonNode(),
    event.effectiveInputSchema());

// After:
JsonNode narrowedInput = evalJq(
    instance.getCaseContext().layer(ContextLayer.WORKING).asJsonNode(),
    event.effectiveInputSchema());
ContextBridge<?> bridge = bridgeResolver.resolve(event.worker());
Object typedInput = bridgeResolver.initialise(bridge,
    instance.getCaseContext(), narrowedInput);
JsonNode serialised = bridgeResolver.serialise(bridge, typedInput);
// serialised goes to EventLog; narrowedInput (as Map) goes to submit/dispatch
```

**`QuartzWorkerExecutionJob`** — bridge-aware deserialisation and output
extraction:

```java
// Before:
Map<String, Object> inputData =
    OBJECT_MAPPER.convertValue(eventLog.getPayload(), Map.class);

// After:
ContextBridge<?> bridge = bridgeResolver.resolveByTypeName(
    eventLog.getMetadata().path("contextBridgeType").asText(null));

// Live-view bridges re-initialise from CaseContext to get a live view;
// snapshot bridges deserialise from the EventLog payload directly.
Object typedInput;
if (bridge.isLiveView()) {
    typedInput = bridgeResolver.initialise(bridge,
        instance.getCaseContext(), eventLog.getPayload());
} else {
    typedInput = bridgeResolver.deserialise(bridge, eventLog.getPayload());
}

workerExecutor
    .execute(worker.function(), typedInput, workerContext, timeoutMs, ...)
    .subscribe().with(
        workerResult -> {
            Map<String, Object> output = workerResult.output();
            if ((output == null || output.isEmpty()) && bridge.isLiveView()) {
                output = bridgeResolver.extractOutput(bridge, typedInput);
            }
            onSuccess(instance, worker, inputDataHash, output, ...);
        },
        failure -> onFailure(retryCtx, failure));
```

The `typedInput` reference is captured in the closure, making it available
in `onSuccess()` for `extractOutput()`. For live-view bridges, `typedInput`
is a mutable view backed by `CaseContext` — the worker's mutations
accumulate there during execution, and `extractOutput()` reads them after
the worker returns.

### Wildcard capture in pipeline code

`ContextBridge<?>` with wildcard capture means methods like `serialise(?)`
and `extractOutput(?)` only accept `null` — not `Object`. This is a
fundamental Java generics constraint. Pipeline code works with
`ContextBridge<?>` because the concrete type is not known statically.

`BridgeResolver` provides type-safe pipeline methods that encapsulate the
required unchecked cast via a capture helper:

```java
public class BridgeResolver {

    // Resolution
    ContextBridge<?> resolve(Worker worker) { ... }
    ContextBridge<?> resolveByTypeName(String typeName) { ... }

    // Type-safe pipeline operations — capture helper pattern
    <T> Object initialise(ContextBridge<T> bridge,
                          CaseContext context, JsonNode narrowedInput) {
        return bridge.initialise(context, narrowedInput);
    }

    @SuppressWarnings("unchecked")
    <T> JsonNode serialise(ContextBridge<T> bridge, Object input) {
        return bridge.serialise((T) input);
    }

    <T> Object deserialise(ContextBridge<T> bridge, JsonNode payload) {
        return bridge.deserialise(payload);
    }

    @SuppressWarnings("unchecked")
    <T> Map<String, Object> extractOutput(ContextBridge<T> bridge,
                                          Object context) {
        return bridge.extractOutput((T) context);
    }
}
```

The `@SuppressWarnings("unchecked")` casts are safe because the pipeline
guarantees type consistency: the same bridge instance that produced the
typed input via `initialise()` or `deserialise()` is used for
`serialise()` and `extractOutput()`. The cast is verified at runtime by
the `BridgeTypeMismatchException` check at the handler entry point (see
§Signature changes).

### Pipeline surface coverage

The bridge protocol operates at the scheduling boundary —
`WorkerScheduleEventHandler` (bridge initialise/serialise) and
`QuartzWorkerExecutionJob` (bridge deserialise/extractOutput). Other
pipeline surfaces remain Map-based:

**`WorkerExecutionManager.submit()`** — continues to receive
`Map<String, Object> inputData`. The scheduler SPI dispatches jobs; it does
not participate in typing. The typed data is stored in EventLog via
`bridge.serialise()`; the scheduler reads it from EventLog at execution
time via `bridge.deserialise()`.

**`dispatchCommand()` (Qhorus channel path)** — continues to receive
`Map<String, Object>`. The Qhorus command is a parallel dispatch path for
external workers. External workers receive Map data via the channel; bridge
typing applies only to the in-process execution path via EventLog.

**`WorkerContextProvider.buildContext()`** — continues to receive
`WorkRequest.of(capabilityName, inputData)` with Map data. `WorkerContext`
is execution metadata (task description, channels, prior workers), not typed
business data. The typed input goes through the bridge path independently.

### Serialisation boundary

EventLog is the durability and recovery boundary. The bridge must serialise
`T` to JSON and reconstruct it. Different bridges have different strategies:

- **MapBridge** — identity; the Map is already JSON-compatible.
- **JacksonPojoBridge** — `objectMapper.valueToTree(pojo)` /
  `objectMapper.treeToValue(json, targetClass)`.
- **WorkflowContextBridge** — snapshots the mutable state for serialisation.
  At execution time, `deserialise()` is NOT called — the pipeline calls
  `initialise(caseContext, payload)` instead, re-creating a live view
  backed by the current `CaseContext`. The serialised snapshot serves as
  the `narrowedInput` for state recovery. `deserialise(JsonNode)` exists
  for API symmetry and crash recovery replay where CaseContext may not
  yet be available.

### Output path

The output path is largely unchanged. Workers still return `WorkerResult`
with a `Map<String, Object>` output. For live-view bridges where the output
lives in the typed context rather than the `WorkerResult`, the handler calls
`bridge.extractOutput()`:

```java
Map<String, Object> rawOutput = workerResult.output();
if ((rawOutput == null || rawOutput.isEmpty()) && bridge.isLiveView()) {
    rawOutput = bridge.extractOutput(typedContext);
}
```

**Live-view worker return contract:** Workers using live-view bridges
(WorkflowContext, AgenticScope) mutate the typed context directly. Their
output lives in the view, not in `WorkerResult`. These workers return
`WorkerResult.of(Map.of())` — an empty map signaling that the bridge
should extract output from the mutated context:

```java
.<WorkflowContext>fn()
.apply(wfCtx -> {
    wfCtx.put("result", computeResult());
    return WorkerResult.of(Map.of());  // output is in the context
})
```

The engine pipeline checks: if `workerResult.output()` is null or empty
AND the bridge is a live view, call `bridge.extractOutput(typedContext)`.
If the worker returns non-empty output AND uses a live-view bridge, the
explicit output takes precedence — the worker chose to return specific
output rather than relying on context extraction.

### In-process execution path

`WorkerRuntime.execute()` and `WorkerFunctions.sequence()` are the
in-process execution path — workers calling other workers directly, without
scheduling. This path remains Map-based:

```java
WorkerResult execute(WorkerFunction function, Map<String, Object> input);
```

The bridge protocol operates at the scheduling boundary, not the in-process
boundary. In-process execution bypasses EventLog, bypasses serialisation,
and passes data directly between worker lambdas. Bridges are not involved.

`sequence()` accepts only Map-typed workers. Typed workers
(`WorkerFunction<T>` where T ≠ Map) are scheduled workers, not in-process
steps. Typed in-process composition is a separate concern tracked in #693.

## Boundary Points

The ContextBridge protocol is designed to apply to every point where data
crosses a typed boundary in CaseHub. This spec fully designs the **engine
worker** boundary. The remaining four boundaries are design projections
showing how the same protocol extends — each will be fully designed in its
own tracking issue.

### 1. Engine Workers (fully specified)

The primary boundary. Workers receive typed input via `fn().apply()` and
return `WorkerResult`. The bridge translates between `CaseContext` and the
worker's declared type.

```
CaseContext → Bridge → T → worker fn → WorkerResult → CaseContext
```

Covered in detail in the Engine Pipeline section above.

### 2. WorkItems (design projection — tracked in #689)

WorkItems carry payload (what the human sees), resolution (what the human
produces), and input/output mappings. All currently untyped.

The bridge protocol extends to WorkItems by typing the payload and
resolution. `WorkItemCreateRequest` is proposed new API (does not exist in
the codebase today):

```java
public record WorkItemCreateRequest(
    // ... existing fields ...
    Class<?> payloadType,   // nullable — null means Map (backward compat)
    Object payload          // was Map<String, Object>, now Object
) {}
```

**Typed resolution:** `WorkItemRef.resolution()` currently returns `JsonNode`.
With a bridge, the resolution is deserialised to a typed object when the
consumer knows the expected type.

**Template mode:** Templates can declare `contextType` so all WorkItems
created from that template get typed payloads automatically.

**HumanTaskTarget in YAML:**

```yaml
bindings:
  - humanTask:
      title: "Review transaction"
      contextType: io.casehub.aml.AmlReviewPayload
      outputMapping: ".decision"
```

The input mapping produces typed payload via the bridge; the output mapping
processes typed resolution via the bridge.

### 3. SubCase Context Passing (design projection — tracked in #690)

SubCases receive context from their parent case via input mapping. The bridge
translates at the SubCase boundary:

```
Parent CaseContext
  → inputMapping (JQ) → extracted data
  → SubCaseExecutionHandler → child CaseInstance
  → child ContextBridge.initialise(childCaseContext, ...) → child's T
```

Each `CaseInstance` has its own `CaseContext`. The bridge always initialises
from the local case's data. Parent data flows into the child's `CaseContext`
via the existing SubCase input mapping mechanism, then the child's bridge
reads from its own `CaseContext`.

The parent and child can use different context types — the input mapping
handles the cross-type data transfer, and each side's bridge handles its own
typing independently.

### 4. Signals (design projection — tracked in #691)

Signals are the system boundary where external data enters cases. The
`signal()` method gains a typed overload:

```java
// Existing — backward compatible
CompletionStage<Void> signal(UUID caseId, Map<String, Object> data);

// New — typed signal
<T> CompletionStage<Void> signal(UUID caseId, T data);
```

The bridge serialises the typed signal to JSON for EventLog storage. The
binding's JQ conditions evaluate against the JSON representation — JQ always
operates on JSON regardless of the Java type that produced it. Existing JQ
conditions work unchanged.

### 5. Connectors — InboundSignalMapping (implemented in #692)

Connectors are the outermost bridge boundary — they receive raw external
data (JSON, email, Slack messages) and convert it into typed domain objects
before signalling the case.

```
External Message → InboundSignalBridge → Typed Signal → Case Bridge → Worker Bridge
```

Inbound connector messages are mapped to typed case signals via
`InboundSignalMapping` on `CaseDefinition`. Each mapping declares a
connector type, a JQ correlation expression (resolving to the target
case), and a JQ payload expression (extracting the signal payload):

```yaml
signals:
  - name: aml-alert
    contextType: io.casehub.aml.AmlAlert

inboundMappings:
  - signal: aml-alert
    connectorType: aml-system
    correlation: '.metadata.caseRef'
    payload: '.content | fromjson'
    correlationResolver: uuid
```

`InboundSignalBridge` (`casehub-engine-inbound`) observes
`@ObservesAsync InboundMessage`, evaluates the JQ expressions, deserialises
via `ContextBridge.deserialise()` (direct — no DataRef interception for
external data), and delivers typed signals via `CaseHubRuntime.signal()`.
`CaseCorrelationResolver` (NamedStrategy SPI) resolves correlation values
to case UUIDs — built-in `UuidCorrelationResolver` (id=`"uuid"`) parses
direct UUIDs.

The connector bridge uses `bridge.deserialise(jsonPayload)` directly
without going through `initialise()` — its input is raw (JSON/text)
rather than a `CaseContext`.

### Boundary summary

| Boundary | Direction | Bridge method | Translation |
|----------|-----------|--------------|-------------|
| Worker input | CaseContext → Worker | `initialise()` | Case data → typed T |
| Worker output | Worker → CaseContext | `extractOutput()` | Typed T → Map for context update |
| WorkItem payload | CaseContext → Human | `initialise()` | Case data → typed payload |
| WorkItem resolution | Human → CaseContext | `deserialise()` | Human output → typed resolution |
| SubCase input | Parent → Child | `initialise()` | Child case data → child's T |
| SubCase output | Child → Parent | `extractOutput()` | Child output → parent context update |
| Signal inbound | External → Case | `deserialise()` | Raw JSON → typed signal |
| Connector inbound | Raw → Signal | `deserialise()` | External format → typed domain object |

## Nested Context Propagation

### The compositional requirement

CaseHub supports arbitrary nesting of execution models. Each level can
independently choose its context type:

```
Case (AmlContext)
  → Worker via bridge (AmlContext)
    → Flow step (WorkflowContext)
      → SubCase (TransactionContext)
        → Flow step (WorkflowContext)
          → SubCase (VerificationContext)
            → Worker (Map<String, Object>)
```

At each boundary, a bridge translates from the enclosing context to the
child's declared type. The chain is arbitrarily deep and each level's type
is independently chosen.

### Local scoping — the key design property

Each bridge sees only two things: the **CaseContext of its own CaseInstance**
and the **type it produces**. It never reaches across levels.

This is possible because each `CaseInstance` has its own `CaseContext`. When
a SubCase is spawned, the parent's output flows into the child's
`CaseContext` via input mapping. The child's bridge then reads from its own
`CaseContext` — it doesn't know or care about the grandparent's type.

```
Level 1: CaseInstance₁.caseContext → Bridge₁ → T₁ → worker
Level 2:   CaseInstance₂.caseContext → Bridge₂ → T₂ → worker
Level 3:     CaseInstance₃.caseContext → Bridge₃ → T₃ → worker
```

Each bridge is locally scoped. Adding a new nesting level or changing a
context type at any level requires no changes to bridges at other levels.

### Data flow through the chain

Cross-level data flow uses existing mechanisms:

1. **Parent → Child (down):** Parent worker returns `WorkerResult`. The
   SubCase binding's `inputMapping` extracts data from the parent's context.
   `SubCaseExecutionHandler` creates the child `CaseInstance` with the mapped
   data in its `CaseContext`. The child's bridge reads from the child's own
   `CaseContext`.

2. **Child → Parent (up):** Child case completes. The SubCase binding's
   `outputMapping` extracts data from the child's context.
   `SubCaseCompletionService` applies the mapped output back to the parent's
   `CaseContext`. The parent's bridge (if it's a live view) sees the update.

3. **Flow → SubCase (lateral):** A flow step (`call: casehub:dispatch`)
   spawns a SubCase. The `CasehubCallableTaskBuilder` creates a new
   `CaseInstance`. The child case has its own `CaseContext` and its own
   bridge. The flow doesn't know or care what bridge the child uses.

### PropagationContext as invariant

`PropagationContext` (traceId, causedByEntryId, deadline budget) threads
through every level as a built-in invariant. It is managed by the engine
pipeline, not by bridges — `initialise()` deliberately omits it (see Core
Abstractions). The engine threads `PropagationContext` through
`WorkerScheduleEvent`, EventLog metadata, and `QuartzWorkerExecutionJob`
independently of the bridge. Live-view bridges that need deadline budget
access can retrieve it from `CaseInstance.getPropagationContext()` via the
engine pipeline. Every unit of work at every level maintains the causal
chain regardless of context type.

## Combinatorial Test Matrix

The nested propagation model creates a combinatorial space of chain patterns
that must be tested. Each pattern exercises different aspects of bridge
composition.

### Test patterns

| # | Chain | Context types | What it proves |
|---|-------|--------------|----------------|
| 1 | Case → Worker | A → A | Single type, bridge identity |
| 2 | Case → Worker | A → B | Bridge translates at worker level |
| 3 | Case → SubCase | A → A | SubCase inherits parent's type |
| 4 | Case → SubCase | A → B | Different types, mapping at boundary |
| 5 | Case → Flow → SubCase | A → WfCtx → B | Three-level chain, two transitions |
| 6 | Case → Flow → SubCase → Flow → SubCase | A → WfCtx → B → WfCtx → C | Five-level deep chain |
| 7 | Case → Worker + SubCase (parallel) | A → B, A → C | Fan-out with different bridges per branch |
| 8 | Repeatable stage with bridge | A → B (repeated) | Bridge re-initialises on stage reset |
| 9 | Case → SubCase (recursive, same def) | A → A → A (bounded) | Same bridge, bounded recursion |
| 10 | Signal → Case → Worker | Ext → A → B | Typed signal through to worker |
| 11 | Connector → Signal → Case → SubCase | Raw → Ext → A → B | Full inbound chain |
| 12 | Case → HumanTask → Worker | A → HT → B | WorkItem bridge in the middle |

### The canonical integration test: five-level chain

Pattern 6 is the canonical test. It exercises every aspect of bridge
composition:

```
Case (AmlContext)                    [Level 1]
  └─ Flow step (WorkflowContext)     [Level 2 — live view bridge]
       └─ SubCase (TxnContext)       [Level 3 — POJO bridge]
            └─ Flow step (WfCtx)     [Level 4 — live view bridge]
                 └─ SubCase (VCtx)   [Level 5 — POJO bridge]
                      └─ Worker      [Leaf — receives VCtx]
```

What this test verifies:

- **Bridge composition through depth.** Five levels, four bridge boundaries,
  no bridge has knowledge of any other.
- **Mixed bridge types.** POJO bridges (snapshot) and live-view bridges
  alternate. Both must compose cleanly.
- **PropagationContext threading.** The traceId and causedByEntryId chain
  must survive all five levels intact.
- **Output propagation upward.** The leaf worker's result must flow back
  through all five levels to the root case's context.
- **EventLog serialisation at each boundary.** Each level's bridge must
  serialise and deserialise independently for durability and recovery.
- **Recovery across levels.** If the system restarts mid-chain, each level
  must be able to reconstruct its typed context from EventLog without
  depending on parent state.

### Test implementation strategy

Each test pattern is a `@QuarkusTest` with:

- A `CaseHub` subclass defining the case structure (workers, bindings,
  SubCase references) with explicit context types at each level.
- Mock `ChatModel` or `WorkerFunction.Sync` lambdas that assert the received
  input type and return structured output.
- `Awaitility.await()` for the full chain to complete, then assertions on
  the root case's context for the propagated output.
- EventLog queries to verify serialisation at each boundary.
- PropagationContext assertions to verify causal chain integrity.

For the five-level canonical test, the leaf worker writes a marker value
(e.g., `{"verified": true, "depth": 5}`) that must surface in the root
case's context after all output mappings propagate upward. If any bridge
boundary drops or corrupts the value, the test fails.

### Fan-out test (pattern 7)

Fan-out tests two workers or SubCases running in parallel from the same
parent, each using a different bridge:

```
Case (AmlContext)
  ├─ Worker A (TransactionContext bridge)
  └─ SubCase B (ComplianceContext bridge)
```

Both run concurrently. The test verifies:

- Bridge A and Bridge B resolve independently.
- Output from both branches merges correctly in the parent context via
  `ConflictResolver`.
- No cross-contamination between bridge instances.

### Repeatable stage test (pattern 8)

When a repeatable stage autocompletes and resets, the bridge must
re-initialise for the new iteration:

```
Stage (repeatable)
  Iteration 0: Bridge produces T₀ from context state S₀
  Stage autocompletes → resetForRepetition()
  Iteration 1: Bridge produces T₁ from context state S₁
```

The test verifies that the bridge does not carry stale state from the
previous iteration. `StageAutocompleteEvaluator.resetForRepetition()` must
trigger bridge re-initialisation.

## Backward Compatibility

### Untyped workers

Existing workers using `Worker.builder().function(input -> ...)` continue to
work unchanged. The `function()` method creates a `WorkerFunction.Sync<Map>`
with `Map.class` as the input type. The `MapBridge` (identity bridge) is
resolved at step 4 of the resolution chain.

### Pipeline signature changes

`Map<String, Object>` → `Object` in `WorkerFunctionHandler.execute()` and
`WorkerExecutor.execute()`. Existing handler implementations that cast to
`Map` continue to work because the `MapBridge` still produces maps for
untyped workers.

### EventLog format

EventLog payloads remain JSON. The bridge controls serialisation, but the
storage format is unchanged. Existing EventLog entries are valid — they
were produced by the implicit `MapBridge`.

### JQ evaluation

JQ expressions continue to evaluate against JSON. The pipeline evaluates
JQ before calling the bridge, passing the narrowed result as `JsonNode`.
No JQ expressions need updating — the bridge protocol does not alter how
JQ is written or evaluated.

## Design Insights

### Bridges are locally scoped, not globally composed

The most important design property is that bridges never compose directly.
There is no `BridgeChain` or `CompositeBridge`. Each level translates
independently between its own `CaseContext` and its own `T`. Cross-level
data flow is handled by the existing input/output mapping infrastructure.

This means:

- Adding a new nesting level is purely additive.
- Changing a context type at one level requires no changes elsewhere.
- Testing a bridge requires only a single-level test harness.
- Bridge implementations are simple — they handle one type, not chains.

### The bridge decides snapshot vs live

The `ContextBridge<T>` interface does not distinguish between snapshot and
live-view bridges. Both implement the same methods. The distinction is
internal:

- **Snapshot bridges** (`JacksonPojoBridge`, `MapBridge`): `initialise()`
  produces a detached copy. Mutations to `T` do not affect `CaseContext`.
  Output flows back only via `WorkerResult`.
- **Live-view bridges** (`WorkflowContextBridge`, `AgenticScopeBridge`):
  `initialise()` produces a view backed by `CaseContext`. Mutations
  propagate via `onWrite()`. Output may also flow via `extractOutput()`.

Consumers don't need to know which kind they're using. The protocol handles
both transparently.

### JQ remains the data selection layer

The bridge does not replace JQ. JQ handles **data selection** — which fields
from the context are relevant for this worker. The bridge handles **typing**
— converting the selected data into a typed object. The two compose:

```
CaseContext → JQ selects fields → JsonNode subset → Bridge types it → T
```

The pipeline evaluates JQ and passes the narrowed `JsonNode` result to
`bridge.initialise()` as `narrowedInput`. This separation is deliberate:
`JQEvaluator` is in `casehub-engine-common` (internal), while bridges are
defined in `casehub-engine-api`. Keeping JQ evaluation in the pipeline
avoids a module-tier violation and means bridge implementations never need
JQ infrastructure — they only convert data.

### Output remains Map-based (for now)

`WorkerResult.output()` stays as `Map<String, Object>`. The bridge protocol
addresses the **input** side — what the worker receives. Output typing is a
separate concern: workers produce structured results that the engine applies
to `CaseContext` via `ConflictResolver`. The `extractOutput()` bridge method
exists for live-view bridges that accumulate output in the typed context, but
the engine's output application path (`WorkflowExecutionCompletedHandler`)
always works with Maps. A future extension could type the output side too,
but input typing delivers the majority of the safety benefit.

### The untyped path is not deprecated

`Map<String, Object>` remains a valid context type, backed by `MapBridge`.
Some workers genuinely operate on arbitrary key-value data where the schema
is not known at compile time. The bridge protocol makes this explicit rather
than implicit — `Map` is a deliberate choice, not a default-by-omission.

## Origins in Serverless Workflow

The ContextBridge concept originates from analyzing how the Serverless
Workflow SDK handles context and data flow. The SW SDK's architecture
informed the initial design, and several key concepts carry forward directly.
This section traces that lineage and documents where the CaseHub design
diverges.

### What we took from Serverless Workflow

**The bridge concept itself.** SW SDK's `WorkflowContext` is the prototype
for context bridging — it wraps a `WorkflowModel` (the SDK's universal
data carrier) and provides a scoped view for workflow execution. The idea
that different execution contexts sit atop a shared data substrate comes
directly from this architecture.

**TaskContext's input/output separation.** SW SDK's `TaskContext` carries
`input`, `output`, `rawInput`, and `rawOutput` as separate fields. This
clean separation between what a task receives and what it produces informed
our bridge's `initialise()` / `extractOutput()` split.

**WorkflowModelFactory's pluggable construction.** The SDK's
`WorkflowModelFactory` is a pluggable factory for creating `WorkflowModel`
instances from arbitrary objects. The `fromAny()` pattern — accepting any
Java object and converting it to the SDK's data model — directly influenced
our bridge resolution chain, where any POJO is automatically handled by
`JacksonPojoBridge`.

**Schema validation at boundaries.** SW SDK validates data against JSON
Schema via `SchemaValidator` at input and output boundaries. We preserve
this capability — JSON Schema validation can still run on the serialised
form at the EventLog boundary — but layer compile-time type safety on top.

### Where we diverge

**Typed generics — shared intent, different mechanism.** SW SDK's FuncDSL
(experimental) already captures generic types at runtime for typed lambdas.
The original mechanism used serialized lambdas (`SerializableFunction`,
`SerializablePredicate`, `SerializableConsumer`) plus `ReflectionUtils
.inferInputType()` — recovering erased types via JVM serialization
internals (`writeReplace` → `SerializedLambda` → `getInstantiatedMethodType`).
This achieved the same goal as our Reified Varargs Type Token: compile-time
type safety in lambdas with runtime type knowledge for bridge resolution.

The serialized lambda approach was fragile (undocumented JVM behavior),
restrictive (all lambdas must implement `Serializable`), and heavyweight
(serialization roundtrip + reflection). PR
[serverlessworkflow/sdk-java#1524](https://github.com/serverlessworkflow/sdk-java/pull/1524)
replaces it with the Reified Varargs Type Token pattern across ~40 call
sites, deleting `ReflectionUtils` and the `Serializable*` interfaces
entirely.

The intent is identical — both approaches give typed lambdas in a fluent
DSL. The mechanism changed from serialization introspection to array
component type reification. CaseHub's ContextBridge design adopts the
improved mechanism directly.

At the core data model level, SW SDK still uses `WorkflowModel` as the
universal carrier for input/output/context. The FuncDSL provides typed
access at the task boundary, but the underlying data flow is
`WorkflowModel` throughout. CaseHub goes further by typing the data at
every boundary — worker input, WorkItem payload, SubCase context, signals,
connectors — and translating between different type systems at each
boundary via the bridge SPI.

**Local scoping vs parent access.** SW SDK's `TaskContext` carries
`Optional<TaskContext> parentContext` — a child task can reach up into its
parent's state. This creates implicit coupling between nesting levels: a
child task's behavior can depend on its parent's internal state, making
tasks harder to test independently and creating fragile chains where a
change at one level silently affects another.

We prohibit cross-level access. Each bridge sees only its own
`CaseContext`. Parent data flows into the child via input mapping — an
explicit, declared data transfer — not by reaching through a parent
reference. Each level is independently testable and swappable. This is a
deliberate departure from SW SDK's model, driven by CaseHub's need to
compose heterogeneous execution models (cases, flows, agents, human tasks)
where implicit coupling between levels would be especially dangerous.

**Multi-model integration.** SW SDK assumes a single execution model —
Serverless Workflow. Everything is a workflow task, everything uses
`WorkflowModel`, and the context is always `WorkflowContext`. There is
no concept of multiple context types coexisting.

CaseHub must integrate three first-class context models (CaseContext,
WorkflowContext, AgenticScope) plus arbitrary domain POJOs and human work
items. The bridge SPI is what makes this possible — each execution model
registers its own bridge, and the engine resolves the right one per worker.
SW SDK has no equivalent because it never needed one — it is the execution
model.

**Convention-based bridge resolution vs instanceof cascade.** SW SDK's
`WorkflowModelFactory.fromAny()` uses an instanceof cascade to convert
objects to `WorkflowModel`. Adding a new type means subclassing the factory
or implementing `fromOther()`.

Our bridge resolution uses a CDI discovery chain with convention fallbacks.
New bridges register as CDI beans and are discovered automatically. The
`JacksonPojoBridge` handles any POJO without registration. This reflects
CaseHub's modular architecture — the `WorkflowContextBridge` lives in
`casehub-engine-flow`, the `AgenticScopeBridge` lives in the langchain4j
integration module, and neither knows about the other.

### Comparison summary

| Aspect | Serverless Workflow SDK | CaseHub ContextBridge |
|--------|------------------------|----------------------|
| Data carrier | `WorkflowModel` (universal wrapper) | Generic `T` (typed per boundary) |
| Type safety (core) | Runtime (`as(Class)` → Optional) | Compile-time (generics + Reified Varargs) |
| Type safety (FuncDSL) | Compile-time (shared pattern — PR #1524) | Compile-time (same Reified Varargs pattern) |
| Cross-level access | `parentContext` — child reaches up | Prohibited — local scoping only |
| Context translation | None — single type system | `ContextBridge<T>` — translates between systems |
| New type support | Subclass `WorkflowModelFactory` | CDI discovery or automatic Jackson |
| Schema validation | JSON Schema at boundaries | Java type system + optional JSON Schema |
| Multi-model integration | Single model (workflows only) | Multiple models coexist natively |
| Scope of typed boundaries | FuncDSL task input only | All 5 boundaries (workers, work items, sub-cases, signals, connectors) |

### Cross-pollination with SW SDK

The type safety work flows in both directions. The Reified Varargs Type
Token pattern was developed for CaseHub's ContextBridge and contributed
back to SW SDK via
[PR #1524](https://github.com/serverlessworkflow/sdk-java/pull/1524),
replacing the serialized lambda mechanism across ~40 call sites in the
experimental FuncDSL.

The broader ContextBridge pattern — typed boundaries with pluggable
bridges between context systems — could benefit SW SDK's `CallableTask`
interface, which currently takes `WorkflowModel input` (untyped). A bridge
layer would let SW SDK integrate external execution models (LangChain4j
agents, rule engines, CaseHub workers) without flattening them to
`WorkflowModel` first.

A standalone article on the Reified Varargs Type Token pattern has been
prepared as a self-contained reference for adoption in other projects.

## Relationship to Existing Issues

| Issue | Relationship |
|-------|-------------|
| #203 | This design — ContextBridge protocol |
| #201 | Parent epic — adaptive execution architecture |
| #238 | `JavaBeanCaseFile<T>` — a live read/write POJO façade over CaseContext for the full case lifecycle. Distinct from `JacksonPojoBridge<T>`, which produces a detached snapshot for worker input. JavaBeanCaseFile could be implemented as a live-view bridge (`isLiveView() = true`) with write-through via `onWrite()`. |
| #419 | `CaseContextProvider` SPI — storage backend, separate concern |
| #446 | Drools `WorkingMemoryBridge` — a specific bridge implementation |
| #209 | langchain4j `AgenticScopeBridge` — a specific bridge implementation |
| #204 | Scope propagation — PropagationContext threading, complementary |
| #302 | `CaseHub.startCase(Object)` — accepts any serialisable input at the case entry point. Complementary: ContextBridge addresses worker-level input typing; #302 addresses case-level entry point typing. Both address the Map→Object tension at different boundaries. |
| #633 | Worker data coordination — DataExchange/DataChannel, orthogonal |
| #693 | Typed in-process composition — `WorkerRuntime.execute()` and `WorkerFunctions.sequence()` typed variants. Deferred: bridge protocol covers the scheduling boundary; in-process typing is a separate concern. |

## Summary

ContextBridge introduces a single protocol — `ContextBridge<T>` — that
applies uniformly to every data boundary in CaseHub: engine workers,
work items, sub-case context passing, signals, and connectors. The protocol
uses the Reified Varargs Type Token pattern for a near-zero-allocation,
type-safe DSL surface. Bridges are locally scoped and never compose directly,
which means the system scales to arbitrary nesting depth without global
coordination. The design is fully backward compatible — existing untyped
workers, signals, and work items continue to work unchanged via the identity
`MapBridge`.
