# Typed In-Process Composition + Context Isolation — Design Spec

Covers engine#693 (typed in-process composition), engine#698 (context isolation per task), and ThreadLocal elimination.

## Execution Order

1. **Foundation** — `WorkerFunction<T, R>`, `WorkerResult<R>`, `WorkerOutcome<R>` type parameterization
2. **Runtime parameter** — BiFunction with explicit WorkerRuntime, remove WorkerExecutionContext ThreadLocal
3. **Typed execute** — `WorkerRuntime.execute()` typed overloads, bridge conversion
4. **Typed sequence** — `WorkerFunctions.sequence()` with bridge-mediated conversion
5. **Context isolation** — per-task diagnostic namespacing
6. ~~**SWF SDK update**~~ — Already implemented (no changes needed)

---

## 1. WorkerFunction\<T, R\> — Two Type Parameters

### Problem

`WorkerFunction<T>` declares the input type but not the output type. `WorkerResult` forces `Map<String, Object>` as the universal output. Workers must wrap POJOs in Maps even when the natural output is a typed object.

### Design

`WorkerFunction` gains a second type parameter `R` for the output type:

```java
public interface WorkerFunction<T, R> {
    Class<T> inputType();
    Class<R> outputType();

    WorkerFunction<Void, Void> NONE = new None();

    record Sync<T, R>(Class<T> inputType, Class<R> outputType,
                       BiFunction<T, WorkerScope, WorkerResult<R>> fn)
        implements WorkerFunction<T, R> {}

    record None() implements WorkerFunction<Void, Void> {
        public Class<Void> inputType() { return Void.class; }
        public Class<Void> outputType() { return Void.class; }
    }
}
```

`WorkerResult<R>` — parameterized by output type. Output is a top-level record component, present on all outcomes (nullable for non-success — supports partial output on failures):

```java
public record WorkerResult<R>(R output, WorkerOutcome<R> outcome) {
    public static <R> WorkerResult<R> of(R output) { ... }
    public static <R> WorkerResult<R> of(R output, PlannedAction action) { ... }
    public static <R> WorkerResult<R> failed(String reason) { ... }
    public static <R> WorkerResult<R> failed(String reason, R partialOutput) { ... }
    public static <R> WorkerResult<R> declined(String reason) { ... }
    public static <R> WorkerResult<R> declined(String reason, R partialOutput) { ... }
    public static <R> WorkerResult<R> expired(String reason) { ... }
    public static <R> WorkerResult<R> expired(String reason, R partialOutput) { ... }
}
```

`WorkerOutcome<R>` — parameterized (phantom on non-success):

```java
public sealed interface WorkerOutcome<R> {
    record Success<R>(PlannedAction plannedAction) implements WorkerOutcome<R> {}
    record Declined<R>(String reason) implements WorkerOutcome<R> {}
    record Failed<R>(String reason) implements WorkerOutcome<R> {}
    record Expired<R>(String reason) implements WorkerOutcome<R> {}
}
```

**`Async<T>` — removed.** The current `WorkerFunction.Async<T>` variant (storing `Function<T, CompletionStage<WorkerResult>>`) is deleted. It has zero engine references — `SyncAgentWorkerFunctionHandler` and `DefaultWorkerRuntime` neither check for it nor handle it. The `CompletionStage` model is superseded by virtual threads: all sync workers already execute on virtual threads via `SyncAgentWorkerFunctionHandler`. `TypedFunctionBuilder.applyAsync()` is removed alongside it.

### DSL Surface

```java
// Typed worker — both types explicit and declarative:
Worker.builder()
    .capabilityName("assess-risk")
    .<AmlTransaction>fn()
    .returning(RiskAssessment.class)
    .apply((txn, runtime) -> WorkerResult.of(new RiskAssessment(txn.riskScore())))

// Untyped worker — shortcut for Map-typed workersible (T = Map, R = Map):
Worker.builder()
    .capabilityName("legacy")
    .function(input -> WorkerResult.of(Map.of("key", "value")))
```

The builder chain:
1. `.<AmlTransaction>fn()` — captures `T` at runtime via Reified Varargs Type Token
2. `.returning(RiskAssessment.class)` — captures `R` at runtime
3. `.apply((txn, runtime) -> ...)` — compiler enforces `T` and `R`

The untyped `.function()` path creates `Sync<Map<String, Object>, Map<String, Object>>` — both types are Map, explicitly declared.

### Builder Changes

```java
public static class TypedFunctionBuilder<T> {
    private final WorkerBuilder parent;
    private final Class<?> runtimeInputType;

    public <R> TypedOutputBuilder<T, R> returning(Class<R> outputType) {
        return new TypedOutputBuilder<>(parent, runtimeInputType, outputType);
    }

    // Shortcut — R = Map (shortcut for Map-typed workers for typed-input, map-output workers):
    public WorkerBuilder apply(Function<T, WorkerResult<Map<String, Object>>> fn) {
        parent.workerFunction = new WorkerFunction.Sync<>(
            runtimeInputType, Map.class, (t, rt) -> fn.apply(t));
        return parent;
    }
}

public static class TypedOutputBuilder<T, R> {
    private final WorkerBuilder parent;
    private final Class<?> runtimeInputType;
    private final Class<R> outputType;

    // Two-arg (composing workers):
    public WorkerBuilder apply(BiFunction<T, WorkerScope, WorkerResult<R>> fn) {
        parent.workerFunction = new WorkerFunction.Sync<>(
            runtimeInputType, outputType, fn);
        return parent;
    }

    // Single-arg (simple workers):
    public WorkerBuilder apply(Function<T, WorkerResult<R>> fn) {
        parent.workerFunction = new WorkerFunction.Sync<>(
            runtimeInputType, outputType, (t, rt) -> fn.apply(t));
        return parent;
    }
}
```

### YAML — Three Levels

Same progressive ceremony as the Java DSL. Omitted fields default to `Map`:

```yaml
# Level 1 — Map→Map (no type declarations):
workers:
  - name: legacy-worker
    capabilities: [process]

# Level 2 — Typed input, Map output (contextType only):
workers:
  - name: assess-risk
    contextType: io.casehub.aml.AmlTransaction
    capabilities: [assess-risk]

# Level 3 — Fully typed (both declared):
workers:
  - name: assess-risk
    contextType: io.casehub.aml.AmlTransaction
    outputType: io.casehub.aml.RiskAssessment
    capabilities: [assess-risk]
```

`CaseDefinitionYamlMapper` defaults: no `contextType` → `Map.class`, no `outputType` → `Map.class`. Resolves declared types via `Class.forName()` and creates `Sync<T, R>` with both runtime types.

### AgentWorkerFunction and FlowWorkerFunction

Both parameterized with Map output (their execution models manage output internally):

```java
public record AgentWorkerFunction(Agent agent)
    implements WorkerFunction<Map<String, Object>, Map<String, Object>> {
    public Class<Map<String, Object>> inputType() { return (Class) Map.class; }
    public Class<Map<String, Object>> outputType() { return (Class) Map.class; }
}

public record FlowWorkerFunction(Workflow workflow)
    implements WorkerFunction<Map<String, Object>, Map<String, Object>> {
    public Class<Map<String, Object>> inputType() { return (Class) Map.class; }
    public Class<Map<String, Object>> outputType() { return (Class) Map.class; }
}
```

`AgentWorkerFunction` — future: `AgentWorkerFunction<R>` when agents gain typed output support. `FlowWorkerFunction` — always `<Map, Map>`: SWF workflows produce generic JSON output determined at runtime by the workflow definition, not at compile time.

### Engine Usage

The engine works with `WorkerFunction<?, ?>` — it doesn't need concrete types. The type parameters enforce correctness at the DSL surface (compile time) and provide runtime metadata via `inputType()`/`outputType()` for bridge resolution.

**Scheduled path output handling:** `WorkflowExecutionCompletedHandler` reads `outputType()`. If `Map.class` — apply directly to CaseContext (current behavior). Otherwise — `objectMapper.convertValue(output, Map.class)` to get the key-value representation for context application. This uses Jackson's standard POJO-to-Map conversion, which respects `@JsonProperty` annotations and custom serializers.

**Output conversion error handling:** If `convertValue` throws (e.g., unserializable type, circular references), the handler treats it as a worker failure — logs the error, records a failed outcome with reason `"Output conversion failed: <message>"`, and routes through the existing semantic failure path (`handleSemanticFailure`). No partial output is applied to CaseContext on conversion failure.

---

## 2. Explicit WorkerRuntime — Remove ThreadLocal

### Problem

`WorkerExecutionContext` uses `ThreadLocal<WorkerContext>` and `ThreadLocal<WorkerRuntime>` as side channels. Workers call `WorkerExecutionContext.current()` to get ambient state. This creates invisible dependencies, fragile manual stack management, and testing difficulty.

### Design

The runtime becomes the second parameter of the worker function:

```java
// WorkerFunction.Sync<T, R> stores:
BiFunction<T, WorkerScope, WorkerResult<R>> fn
```

The engine passes the runtime (which IS-A WorkerScope) when calling the function — no ThreadLocal:

```java
// SyncAgentWorkerFunctionHandler:
WorkerResult<?> result = sync.fn().apply(typedInput, workerRuntime);
```

**`WorkerScope`** — minimal interface in `casehub-worker-api`. References only worker-api types, breaking the circular dependency with engine-api:

```java
// In casehub-worker-api:
public interface WorkerScope {
    UUID caseId();
    String taskId();
    <T, R> WorkerResult<R> execute(WorkerFunction<T, R> function, T input);
    WorkerResult<?> execute(String workerName, Map<String, Object> input);
}
```

**`WorkerRuntime`** — full interface in `casehub-engine-api`, extends `WorkerScope` with engine-api types:

```java
// In casehub-engine-api:
public interface WorkerRuntime extends WorkerScope {
    WorkerContext context();
    UUID spawnCase(String caseType, Map<String, Object> input);
    Map<String, Object> awaitCase(UUID childCaseId, Duration timeout);
    Map<String, Object> spawnAndAwaitCase(String caseType, Map<String, Object> input, Duration timeout);
}
```

Workers that only compose sub-workers use `WorkerScope` directly. Workers that need context, case spawning, or other engine features cast to `WorkerRuntime` (safe — the engine always passes a `WorkerRuntime`):

```java
// Before (ThreadLocal):
var channels = WorkerExecutionContext.current().channels();

// After — composing worker (WorkerScope suffices):
.apply((input, scope) -> {
    var sub = scope.execute(otherFunction, input);
    ...
})

// After — worker needing context (cast to WorkerRuntime):
.apply((input, scope) -> {
    var runtime = (WorkerRuntime) scope;
    var channels = runtime.context().channels();
    ...
})
```

### WorkerExecutionContext — Removed

`WorkerExecutionContext` is deleted entirely. Both ThreadLocal fields gone. All callers migrate:

| Caller | Before | After |
|--------|--------|-------|
| Worker lambdas needing context | `WorkerExecutionContext.current()` | `runtime.context()` via BiFunction |
| `WorkerFunctions.sequence()` | `WorkerExecutionContext.currentRuntime()` | Runtime received as BiFunction parameter |
| `SyncAgentWorkerFunctionHandler` | `WorkerExecutionContext.set(ctx)` / `.clear()` | Pass runtime as BiFunction arg |
| `QuartzWorkerExecutionJob` | `WorkerExecutionContext.set(ctx)` / `.clear()` | Pass runtime as BiFunction arg |
| `DefaultWorkerRuntime.executeSync()` | Save/restore parent ThreadLocal | Pass `this` as runtime — no save/restore |

### DefaultWorkerRuntime

No longer manages ThreadLocal. Constructor gains `taskId` and `context`:

```java
class DefaultWorkerRuntime implements WorkerRuntime {
    private final UUID caseId;
    private final String taskId;
    private final WorkerContext context;
    // ... existing dependencies (CaseHubRuntime, registries, etc.)

    DefaultWorkerRuntime(UUID caseId, String taskId, WorkerContext context, ...) {
        this.caseId = caseId;
        this.taskId = taskId;
        this.context = context;
    }

    @Override public String taskId() { return taskId; }
    @Override public WorkerContext context() { return context; }
}
```

`executeSync()` passes `this` as the runtime — no ThreadLocal save/restore:

```java
private <T, R> WorkerResult<R> executeSync(WorkerFunction.Sync<T, R> sync, T input) {
    try {
        return sync.fn().apply(input, this);
    } catch (Exception e) {
        return WorkerResult.failed(e.getMessage());
    }
}
```

**WorkerRuntimeFactory** updated to `create(UUID caseId, String taskId, WorkerContext context)`. `SyncAgentWorkerFunctionHandler` passes `metadata.bindingName()` as the taskId. `ExecutionMetadata` gains a `bindingName` field — already available in the scheduling event chain (`WorkerScheduleEvent.bindingName()` → `QuartzWorkerExecutionJob`).

No parent context save/restore — each runtime instance IS the scope.

---

## 3. Typed WorkerRuntime.execute()

### Design

```java
public <T, R> WorkerResult<R> execute(WorkerFunction<T, R> function, T input) {
    if (function instanceof WorkerFunction.Sync<T, R> sync) {
        return executeSync(sync, input);
    }
    if (function instanceof AgentWorkerFunction agent) {
        return executeAgent(agent, input);
    }
    return WorkerResult.failed("Unsupported function type: " + function.getClass().getName());
}
```

For `execute(String workerName, Map<String, Object> input)` — looks up the worker, checks `inputType()`. If `inputType() != Map.class`, uses `JacksonPojoBridge` to convert the Map to the target POJO. Returns `WorkerResult<?>` (output type determined by the worker's `outputType()`).

---

## 4. Typed Sequence

### Design

`sequence()` receives the runtime from its own BiFunction (no ThreadLocal). Preserves **overlay accumulation semantics**: each step's output is merged onto a growing accumulator (matching current behavior). Between steps, uses `objectMapper.convertValue()` when types don't match:

```java
private static final ObjectMapper MAPPER =
    new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

@SafeVarargs
public static WorkerFunction.Sync<Map<String, Object>, Map<String, Object>>
    sequence(WorkerFunction<?, ?>... steps) {

    WorkerFunction<?, ?>[] copy = steps.clone();
    return new WorkerFunction.Sync<>(Map.class, Map.class, (input, runtime) -> {
        var acc = new LinkedHashMap<>((Map<String, Object>) input);
        for (var step : copy) {
            Object converted = convertIfNeeded(acc, step.inputType());
            @SuppressWarnings("unchecked")
            var result = runtime.execute((WorkerFunction) step, converted);
            if (!(result.outcome() instanceof WorkerOutcome.Success)) {
                return (WorkerResult) result;
            }
            acc.putAll(toMap(result.output()));
        }
        return WorkerResult.of(acc);
    });
}

private static Object convertIfNeeded(Object value, Class<?> targetType) {
    if (targetType.isInstance(value)) return value;
    if (targetType == Map.class) return MAPPER.convertValue(value, Map.class);
    return MAPPER.convertValue(value, targetType);
}

@SuppressWarnings("unchecked")
private static Map<String, Object> toMap(Object value) {
    if (value == null) return Map.of();
    if (value instanceof Map) return (Map<String, Object>) value;
    return MAPPER.convertValue(value, Map.class);
}
```

**MAPPER configuration:** `FAIL_ON_UNKNOWN_PROPERTIES = false` is required for typed steps to work with overlay accumulation. The accumulator is always a superset of any individual step's expected input — without lenient deserialization, `convertIfNeeded()` throws `UnrecognizedPropertyException` for every extra key. This is consistent with `JacksonPojoBridge` which uses the same configuration for input deserialization throughout the bridge protocol.

**Typed step accumulator semantics:** POJO-typed steps (where `inputType() != Map.class`) receive the full accumulated state deserialized to their input type, not just the prior step's output. Jackson maps all matching field names from the accumulator indiscriminately. If step 1 produces `{status: "APPROVED"}` and step 2's input POJO has a `status` field, it will be populated from step 1's output. This is inherent to overlay accumulation with typed deserialization — field-name collisions across steps are the sequence designer's responsibility. Design POJO input types with unique field names, or use Map-typed steps when field-name collision is expected.

The sequence itself is `WorkerFunction<Map, Map>` — it takes Map input and produces Map output. This is correct: sequences are scheduled workers that integrate with the Map-based CaseContext pipeline. Individual steps within the sequence can be fully typed.

---

## 5. Context Isolation Per Task (#698)

### Problem

When parallel tasks execute in a DAG, failure state written to `_outcomes.<bindingName>` in the shared case context is visible to sibling tasks. LLM-driven tasks are sensitive to this contamination — they see a sibling's failure history and alter their reasoning.

### Design — Namespaced Diagnostic Writes

Instead of writing to `_outcomes.<bindingName>` in the shared working layer, write to a per-task diagnostic namespace: `_diagnostics.<taskId>.*`. Only the task's own diagnostics are visible.

**`WorkerRuntime.taskId()`** — per-task identity from the DAG node ID. For non-DAG execution (single-worker bindings), `taskId` is the binding name. For DAG execution, it's the `DagNode.id()`.

**Diagnostic namespace convention:**

```
_diagnostics/
  <taskId>/
    outcomes: { status, attempts, history[], excludedAgents[] }
    retryState: { attemptCount, attempts[], firstAttemptTime, lastAttemptTime }
    errors: [ { message, timestamp, workerName } ]
```

**What changes:**

| Current | New |
|---------|-----|
| `_outcomes.<bindingName>` in working layer | `_diagnostics.<taskId>.outcomes` in working layer |
| No input filtering — all sibling `_outcomes` visible | Input projection filter strips sibling `_diagnostics` entries |
| LLM input projection includes sibling failures | LLM input projection contains only own diagnostics |

**Rerouting:** The agent exclusion mechanism (`excludedAgents` in `_outcomes`) moves to `_diagnostics.<taskId>.outcomes.excludedAgents`. The reroute path reads from the task's own diagnostic namespace — no change in behavior, just scoped storage.

### Input Projection Filter

Namespace consolidation alone does not achieve isolation — `_diagnostics` entries for all tasks still reside in the shared working layer. Actual isolation requires an **input projection filter** applied before worker input is constructed.

**Mechanism:** Before evaluating the input projection (JQ expression or live-view bridge initialisation), the engine creates a filtered view of the working layer that excludes `_diagnostics.<X>.*` for all X ≠ own taskId. This filtered view is what the JQ expression evaluates against and what `bridgeResolver.initialise()` receives.

**Where the filter applies:**

| Execution path | Location | What changes |
|----------------|----------|-------------|
| Scheduled (Quartz) | `QuartzWorkerExecutionJob` — before JQ eval and `bridgeResolver.initialise()` | Filter `_diagnostics` entries, keeping only own taskId |
| Direct (in-process) | `DefaultWorkerRuntime.executeSync()` — before passing input | Filter if input is derived from CaseContext |

**Implementation:** The filter is a shallow key-prefix operation on the working layer's top-level `_diagnostics` map. For a task with taskId T: iterate `_diagnostics` keys, remove all entries where key ≠ T. The filtered view is a snapshot — it does not modify the underlying CaseContext. Engine-internal reads (rerouting in `CaseContextChangedEventHandler`, stage reset in `StageResetOutcomesCleaner`) continue to read the unfiltered CaseContext directly because they need cross-task visibility for coordination.

**What the filter does NOT affect:** Engine-internal handlers that read `_diagnostics` for coordination (routing, stage reset) operate on the unfiltered CaseContext. The filter applies only to the worker-facing input projection — the data the worker function receives as its `T input` parameter.

### Engine Machinery Changes

**taskId identity (non-DAG):** For the current single-worker-per-binding execution model, `taskId` = `bindingName`. This is an identity mapping — no new index or lookup table is needed. The existing handler code already uses `bindingName` as the key for `_outcomes`; the change is renaming the namespace prefix from `_outcomes.<bindingName>` to `_diagnostics.<bindingName>`.

**Handler changes — all in runtime and blackboard modules:**

| Handler | Current | New |
|---------|---------|-----|
| `WorkflowExecutionCompletedHandler.handleSemanticFailure()` | Writes `_outcomes.<bindingName>.{status, attempts, history, excludedAgents}` | Writes `_diagnostics.<bindingName>.outcomes.{status, attempts, history, excludedAgents}` |
| `WorkflowExecutionCompletedHandler.recordSuccessOutcome()` | Writes `_outcomes.<bindingName>.status = COMPLETED` | Writes `_diagnostics.<bindingName>.outcomes.status = COMPLETED` |
| `CaseContextChangedEventHandler.publishWorkerSchedule()` | Reads `_outcomes.<bindingName>.excludedAgents` | Reads `_diagnostics.<bindingName>.outcomes.excludedAgents` |
| `CaseContextChangedEventHandler.handleAllCandidatesExhausted()` | Writes `_outcomes.<bindingName>.status = REROUTES_EXHAUSTED` | Writes `_diagnostics.<bindingName>.outcomes.status = REROUTES_EXHAUSTED` |
| `StageResetOutcomesCleaner.onStageActivated()` | Iterates `stage.getContainedBindingNames()`, removes from `_outcomes` | Iterates same binding names, removes from `_diagnostics` |

**Input projection:** The input projection pipeline gains a diagnostic filter (see "Input Projection Filter" above). Before JQ evaluation or bridge initialisation, the engine strips sibling `_diagnostics` entries from the working layer view. This ensures LLM-driven workers cannot see sibling failure state regardless of how their input projection is configured.

### Migration Scope

`_outcomes` is an engine-internal convention spanning two modules (runtime and blackboard) with references in 5 handler/cleaner classes and their tests:

- **runtime:** `WorkflowExecutionCompletedHandler` (write), `CaseContextChangedEventHandler` (read/write)
- **blackboard:** `StageResetOutcomesCleaner` (read/write)
- **tests:** `FailureCascadeIntegrationTest`, `StageResetOutcomesCleanerTest`, `CaseContextChangedEventHandlerRoutingTest`

All references are engine-internal — no public API surface. Consumer JQ expressions that reference `_outcomes` in binding definitions also need updating. Since this is pre-release, breaking changes are free. Document in CLAUDE.md.

---

## 6. CasehubCallableTaskBuilder — Already Implemented

This work has already been completed. `CasehubCallableTaskBuilder` already uses the `CallableTaskFactory` pattern — `init()` returns a factory, captures `callName` and `args` as local variables, has no ThreadLocal fields, and has no `build()` method. No changes needed. Retained here for completeness as it was part of the original ThreadLocal elimination scope.

---

## Cross-Cutting Concerns

### Blast Radius

`WorkerFunction`, `WorkerResult`, and `WorkerOutcome` are in `casehub-worker-api` (tier 1). Every module that references these types needs updating for the new type parameters. Key modules:

- `casehub-worker-api` — type definitions
- `casehub-engine-api` — `Agent`, `ContextBridge` integration, `CaseDefinition` builder
- `casehub-engine-common` — `WorkerExecutor`, handler interfaces
- `casehub-engine` (runtime) — handlers, scheduler, recovery
- `casehub-engine-flow` — `FlowWorkerFunction`
- `casehub-blackboard` — PlanItem completion handlers
- Consumer app repos — worker lambda definitions

All modules update to the new type parameters.

### Cross-Repo Dependency Ordering

`WorkerFunction`, `WorkerResult`, `WorkerOutcome`, `Worker`, `Worker.Builder`, and `TypedFunctionBuilder` are all defined in `casehub-worker-api` — a separately versioned artifact.

**Release order:** casehub-worker-api (type parameterization, Async removal, builder changes) → casehub-engine (all modules).

**Circular dependency resolution:** `WorkerFunction.Sync` stores `BiFunction<T, WorkerScope, WorkerResult<R>>`. `WorkerScope` is defined in `casehub-worker-api` — it references only worker-api types (`UUID`, `WorkerFunction`, `WorkerResult`). `WorkerRuntime` stays in `casehub-engine-api` and extends `WorkerScope`, adding engine-api methods (`context()`, `spawnCase()`, `awaitCase()`). No circular dependency: `casehub-engine-api` → `casehub-worker-api` remains one-way.

The engine always passes `DefaultWorkerRuntime` (which implements `WorkerRuntime extends WorkerScope`) as the BiFunction parameter. Workers in consumer apps (which depend on engine-api) can safely cast `WorkerScope` → `WorkerRuntime` to access `context()`, `spawnCase()`, etc.

**New type placement:**

| Type | Module |
|------|--------|
| `WorkerFunction<T, R>`, `WorkerResult<R>`, `WorkerOutcome<R>` | casehub-worker-api (updated) |
| `WorkerScope` | casehub-worker-api (new) |
| `TypedFunctionBuilder<T>`, `TypedOutputBuilder<T, R>` | casehub-worker-api (updated/new) |
| `WorkerRuntime extends WorkerScope` | casehub-engine-api (updated, stays) |
| `AgentWorkerFunction`, `FlowWorkerFunction` | casehub-engine-api / casehub-engine-flow (unchanged) |

**Builder backward compatibility:** `Worker.Builder.function(Function<Map, WorkerResult>)` wraps to `(t, rt) -> fn.apply(t)` — the runtime parameter is available but ignored. This preserves the simple single-arg shortcut for untyped workers.

### CLAUDE.md Updates

- `WorkerFunction<T, R>` replaces `WorkerFunction<T>` throughout
- `WorkerResult<R>` replaces unparameterized `WorkerResult`
- `WorkerExecutionContext` removed — document `runtime.context()` migration
- `_outcomes` convention replaced by `_diagnostics.<taskId>` namespace
- `outputType` field on `WorkerFunction` and in YAML

### Test Strategy

| Area | Test approach |
|------|--------------|
| WorkerFunction\<T, R\> | Unit: typed builder creates correct Sync with both types. Compile-time: wrong output type in lambda fails to compile. |
| WorkerResult\<R\> | Unit: factory methods, output extraction, outcome pattern matching. |
| Explicit runtime | Unit: worker receives runtime as second arg. Integration: sequence() composes without ThreadLocal. |
| Typed execute | Unit: Map input converted to POJO via bridge. Unit: POJO input passed directly. |
| Typed sequence | Unit: multi-step sequence with different types at each step. Unit: non-success short-circuits. |
| Context isolation | Unit: sibling task diagnostics not visible in input projection. Integration: parallel DAG tasks with one failure — sibling unaffected. |
| CallableTaskBuilder | Unit: init() returns factory with captured state. No ThreadLocal fields. |
| ThreadLocal elimination | Verify `WorkerExecutionContext` class is deleted. Grep for ThreadLocal — zero in engine. |
