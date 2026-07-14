# Typed Signal and SubCase Boundaries via ContextBridge

## Overview

Extends the ContextBridge protocol to the Signal and SubCase boundaries.
Both currently use untyped `Map<String, Object>`. This spec makes signals
first-class typed platform concepts and SubCase context passing
expression-engine-aware, applying the same ContextBridge protocol already
implemented for the worker boundary.

## Issues

- engine#691 — typed context for Signal boundary via ContextBridge
- engine#690 — typed context for SubCase boundary via ContextBridge
- Part of engine#203 (ContextBridge epic) and engine#201 (adaptive execution
  architecture)

## Design Principles

### Serialisation boundary rule

Objects pass as POJOs between internal boundaries. `bridge.serialise()` is
called only at storage boundaries (EventLog, database persistence) and wire
boundaries (Qhorus channels, HTTP). `bridge.deserialise()` is called only
when reconstructing from stored or received data.

| Boundary | Internal/External | Typing | Serialisation |
|----------|------------------|--------|---------------|
| Worker — scheduled (Quartz) | Internal, async recovery gap | `initialise()` at scheduling | `serialise()` → EventLog at scheduling. `deserialise()` at execution |
| Worker — in-process | Internal, same thread | Direct POJO pass | None |
| Signal — same JVM | Internal | Direct POJO through event bus | `serialise()` → EventLog only |
| Signal — external (Qhorus, HTTP) | External | `deserialise()` from wire | Deserialisation IS the entry point |
| SubCase — same JVM | Internal | POJO to `startCase()`, Jackson `convertValue` to Map at CaseContext boundary | `serialise()` → EventLog only |
| WorkItem (#689, future) | Internal (same JVM adapter) | Direct POJO to `WorkItemCreateRequest` | `serialise()` at WorkItem persistence |
| Connector (#692, future) | External by definition | `deserialise()` from raw input | Deserialisation IS the entry point |

The bridge has two distinct roles called at different points:

1. **Typing** (`initialise()`, `extractOutput()`) — at every boundary
   crossing. Always.
2. **Serialisation** (`serialise()`, `deserialise()`) — only at persistence
   and wire boundaries. Only when needed.

### Expression engine abstraction

SubCase input/output mappings dispatch by the mapping type:

- **`SubCaseMapping.Expression`** (JQ strings, YAML path) — the expression
  string is wrapped in a `JQExpressionEvaluator` and dispatched through
  `ExpressionEngineRegistry.transform()`. The registry resolves the engine
  by `ExpressionEvaluator.type()`, not by a field on `CaseDefinition`.
  (`CaseDefinition.expressionLang` exists only on the YAML schema model
  `io.casehub.model.CaseDefinition`, not on the API model
  `io.casehub.api.model.CaseDefinition`. It is consumed at definition load
  time by `CaseDefinitionYamlMapper` to create evaluators — it does not
  exist at runtime.)

- **`SubCaseMapping.Lambda`** (Java DSL path) — the lambda is evaluated
  directly against the `CaseContext`. It bypasses `ExpressionEngineRegistry`
  entirely because the lambda IS the implementation — there is no expression
  string to parse and no engine to dispatch to. This is architecturally
  consistent with how `LambdaExpressionEvaluator` works for boolean
  conditions (evaluated by `LambdaExpressionEngine.evaluate()` which calls
  the predicate directly), except that mapping lambdas wrap
  `Function<CaseContext, Object>` (a data transform) rather than
  `Predicate<CaseContext>` (a boolean test). These are fundamentally
  different function types serving different purposes — mapping cannot be
  expressed through the existing boolean evaluation path.

## SignalType — platform-level typed signals

### SignalType record

`SignalType<T>` is a typed declaration of a signal that cases can receive.
Lives in `casehub-engine-api` alongside `Capability` and `ContextBridge`.

```java
public record SignalType<T>(String name, Class<T> payloadType) {

    public static <T> SignalType<T> of(String name, Class<T> payloadType) {
        return new SignalType<>(name, payloadType);
    }

    public static SignalType<Map<String, Object>> untyped(String name) {
        return new SignalType<>(name, (Class) Map.class);
    }
}
```

### CaseDefinition declares accepted signals

Java DSL:
```java
CaseDefinition.builder()
    .name("aml-screening")
    .signal(SignalType.of("payment-received", PaymentEvent.class))
    .signal(SignalType.of("kyc-result", KycResult.class))
    .worker(...)
    .build();
```

YAML:
```yaml
signals:
  - name: payment-received
    contextType: io.casehub.aml.PaymentEvent
  - name: kyc-result
    contextType: io.casehub.aml.KycResult
```

`CaseDefinitionYamlMapper` resolves `contextType` via `Class.forName()` —
same pattern as worker `contextType`.

When `signals` is empty/null: all signals accepted (backward compat). When
populated, only declared signal names and payload types are validated on
the typed API — mismatches are rejected at `CaseHubRuntimeImpl.signal()`
before event publishing (see §Validation — Runtime signals).

### Divergence from parent ContextBridge spec

The parent ContextBridge architecture spec (§4 Signals) projected a
simpler typed signal API: `<T> CompletionStage<Void> signal(UUID caseId,
T data)`. This spec evolves the projection to
`signal(caseId, SignalType<T>, T payload)` because:

1. **Naming is required.** Without signal names, the handler doesn't know
   where to write the payload in the working layer.
2. **Disambiguation.** Multiple signals can share the same payload type
   (e.g., two different `PaymentEvent` signals). The name distinguishes.
3. **Declaration and validation.** `CaseDefinition.signals` declares the
   accepted signal contract. Without named signal types, there is nothing
   to validate against.

The parent spec's projection was a design sketch for protocol
extensibility, not a committed API surface. This spec is the full design.
The parent spec should be updated to reference this spec for the
definitive signal API.

### CaseHubRuntime typed signal overload

```java
// New — typed
<T> CompletionStage<Void> signal(UUID caseId, SignalType<T> signalType, T payload);

// Existing — backward compatible, unchanged
CompletionStage<Void> signal(UUID caseId, String path, Object value);
CompletionStage<Void> signal(UUID caseId, Map<String, Object> updates);
```

## Signal pipeline mechanics

### Sender path

1. `CaseHubRuntimeImpl.signal(caseId, signalType, payload)` resolves the
   bridge via `BridgeResolver.resolveByType(signalType.payloadType())`
2. Publishes a `TypedSignalReceivedEvent(caseId, signalType.name(),
   payload, payloadTypeName, tenancyId)` on the event bus — the typed
   payload passes as a Java object, not serialised
3. `SignalReceivedEventHandler` consumes the typed event, writes the
   payload to the working layer at path `.signals.{signalName}` — the
   `signals` namespace isolates typed signal payloads from business
   context keys, preventing collision (e.g., a signal named "status"
   writes to `.signals.status`, not `.status`). Binding trigger JQ
   expressions reference signal data as `.signals.{signalName}.field`.
4. EventLog entry is written — `bridge.serialise()` happens here, at the
   storage boundary. Metadata carries `signalTypeName` and `payloadType`
5. Publishes `CONTEXT_CHANGED` — binding triggers evaluate against the
   updated context

### BridgeResolver extension

`resolveByType(Class<?> payloadType)` — the canonical resolution method.
Resolution chain: CDI-discovered bridges by `contextType()` →
`Map.class` returns `MapBridge` → fallback `JacksonPojoBridge`. Same
chain as `resolve(Worker, CaseDefinition)` but keyed on class directly.

`resolveByTypeName(String)` delegates to `resolveByType()`:
`Class.forName(typeName)` → `resolveByType(clazz)`. This eliminates
resolution logic duplication — the chain is defined once in
`resolveByType()`.

### TypedSignalReceivedEvent

Distinct from `SignalReceivedEvent` to keep the untyped path untouched:

```java
public record TypedSignalReceivedEvent(
    UUID caseId,
    String signalName,
    Object payload,          // POJO — not serialised
    Class<?> payloadType,    // for bridge resolution — preserved from send site
    String payloadTypeName,  // for EventLog metadata and recovery
    String tenancyId) {}
```

The handler uses `payloadType` (the `Class<?>`) directly for bridge
resolution via `BridgeResolver.resolveByType()` — no reflective
`Class.forName()` needed on the hot path. `payloadTypeName` is written
to EventLog metadata for crash recovery, where the Class object is not
available and `Class.forName()` is acceptable.

### Binding triggers on typed signals

Bindings can reference signal names in their trigger conditions. The
expression evaluator receives the signal data from the working layer —
JQ evaluates against the JSON representation (stored as JsonNode in the
working layer at `.signals.{signalName}`), lambdas receive the stored
object directly. Existing `ContextChangeTrigger` works unchanged — it
already evaluates via `ExpressionEvaluator` against working layer state.

### Concurrency model

Typed signals follow the same concurrency model as existing untyped
signals. `SignalReceivedEventHandler` acquires a Vert.x local lock per
`(caseId, signalName)` before applying the signal to the working layer.
Two typed signals with different names to the same case proceed
concurrently. Two signals with the same name are serialized by the lock.
Binding trigger re-evaluation is published via `CONTEXT_CHANGED` after
each signal is applied — the event bus serializes delivery to
`CaseContextChangedEventHandler` per case.

### EventLog recovery for typed signals

The EventLog stores the context diff (patch) produced by applying the
signal, not the raw signal event. During crash recovery/replay, the
system applies stored patches to reconstruct state — it does not need to
reconstruct `TypedSignalReceivedEvent` or call `bridge.deserialise()`.

The bridge-serialised payload and `payloadTypeName` are stored in
EventLog metadata for audit and forensic purposes. If analytical replay
needs the original typed payload, it can reconstruct via
`BridgeResolver.resolveByTypeName(payloadTypeName)` →
`bridge.deserialise(payload)`. This is not on the crash recovery path.

## SubCase typed context passing

### Input path (parent → child)

1. `CaseContextChangedEventHandler.publishSubCaseSchedule()` evaluates
   the input mapping by dispatching on `SubCaseMapping` type:
   - **`SubCaseMapping.Expression`**: wraps the JQ string in a
     `JQExpressionEvaluator`, calls
     `ExpressionEngineRegistry.transform(evaluator, workingLayerNode)`.
     `transform()` returns `List<JsonNode>` — the first element is
     selected and converted to `Map<String, Object>` via
     `ObjectMapper.convertValue()` (same semantics as the current
     `evalJqAsMap()` helper). Empty/null results → `PlanItem` faulted
     (not silent `Map.of()`).
   - **`SubCaseMapping.Lambda`**: calls
     `fn.apply(caseInstance.getCaseContext())` directly. Returns `Object`
     (typically a Map or POJO). No ExpressionEngineRegistry involvement.
2. The mapping result passes as a Java object directly to
   `SubCaseScheduleEvent` — no `bridge.serialise()`.
3. `SubCaseExecutionHandler` passes the object to
   `caseHubRuntime.startCase()`. `startCase()` accepts `Object inputData`
   and converts it to `Map<String, Object>` via `toContextMap()` (Jackson
   `convertValue`). This is a CaseContext initialization conversion, not
   a serialisation boundary — `CaseContext` is inherently Map/JSON-backed.
   The child's workers get re-typed data through their own bridges'
   `initialise()` calls.
4. `bridge.serialise()` happens only at EventLog write time — the storage
   boundary.

### SubCaseScheduleEvent change

```java
public record SubCaseScheduleEvent(
    CaseInstance parentInstance,
    SubCase subCase,
    Object childInitialContext,    // was Map<String, Object> — now any POJO
    String contextBridgeType,      // nullable — for EventLog recovery
    String bindingName) {}
```

### Output path (child → parent)

1. `SubCaseCompletionService.applyOutputMappingToParent()` evaluates the
   output mapping by dispatching on `SubCaseMapping` type — same dispatch
   as the input path. For Expression: `ExpressionEngineRegistry.transform()`
   against the child's working layer. For Lambda: direct function call.
2. The mapping result must be `Map<String, Object>` or a POJO convertible
   to Map via Jackson `convertValue`. Each entry is set individually on
   the parent context — this is a multi-key merge, not a single-key write.
   Scalars and collections are not valid output mapping results.
3. EventLog metadata carries `contextBridgeType` for recovery.

### Output mapping recovery from EventLog

The current `SubCaseCompletionService.applyOutputMapping()` reads the
`outputMapping` string from the `SUBCASE_STARTED` EventLog metadata.
This works for Expression mappings but not for Lambda — a
`Function<CaseContext, Object>` cannot be serialised.

Recovery for Lambda output mappings uses the CaseDefinition as the
source of truth:

1. `SubCaseExecutionHandler` stores `bindingName` in `SUBCASE_STARTED`
   EventLog metadata (new field — currently not stored).
2. At completion time, `SubCaseCompletionService` reads `bindingName`
   from the EventLog metadata.
3. Looks up the parent's `CaseDefinition` via `CaseDefinitionRegistry`
   using the parent instance's namespace/name/version.
4. Finds the `Binding` by name → gets the `SubCase` → gets
   `outputMapping` (the `SubCaseMapping` object, including Lambda).
5. Dispatches by type as normal.

This requires injecting `CaseDefinitionRegistry` into
`SubCaseCompletionService`. The definition lookup is a cache hit (the
registry is in-memory) — no I/O on the completion path.

For Expression mappings, the `outputMapping` string continues to be
stored in EventLog metadata as before — the binding name lookup is only
needed for Lambda recovery. The Expression string in metadata is
redundant with the definition but provides self-contained recovery
without definition lookup.

### SubCase model — no contextType field

`SubCase` does NOT gain a `contextType` field. The child's context type
comes from the child's `CaseDefinition` — it already owns its own type
declaration. The SubCase reference identifies the child by
namespace/name/version. The bridge is resolved from the child definition
at dispatch time.

## SubCaseMapping — expression-engine-aware mappings

`SubCase.inputMapping` and `outputMapping` change from `String` to a
sealed interface supporting both JQ strings and typed lambdas:

```java
public sealed interface SubCaseMapping
    permits SubCaseMapping.Expression, SubCaseMapping.Lambda {

    record Expression(String expression) implements SubCaseMapping {}
    record Lambda(Function<CaseContext, Object> fn) implements SubCaseMapping {}

    static SubCaseMapping of(String expression) {
        return new Expression(expression);
    }

    static SubCaseMapping of(Function<CaseContext, Object> fn) {
        return new Lambda(fn);
    }
}
```

`SubCase.Builder.inputMapping(String)` continues to work (creates
`Expression`). New overload `inputMapping(SubCaseMapping)` for the typed
path. Same for `outputMapping`.

Java DSL surface:
```java
SubCase.builder()
    .namespace("aml").name("transaction-check").version("1.0")
    .inputMapping(SubCaseMapping.of(
        (CaseContext ctx) -> Map.of("transaction", ctx.get("transaction"))))
    .outputMapping(SubCaseMapping.of(
        (CaseContext ctx) -> Map.of("decision", ctx.get("decision"))))
    .build()
```

YAML: `inputMapping` and `outputMapping` remain strings → parsed to
`SubCaseMapping.Expression` by `CaseDefinitionYamlMapper`.

## CaseDefinition model changes

```java
public class CaseDefinition {
    // Existing
    private List<Capability> capabilities;
    private List<Worker> workers;
    private List<Binding> bindings;

    // New
    private List<SignalType<?>> signals;
}
```

Builder gains `.signal(SignalType<T>)`. YAML schema gains `signals:` array
with `name` and `contextType` fields.

## Validation

### Registration time

- Signal names unique within definition — duplicate → fail-fast
- Signal `contextType` classes loadable via `Class.forName()` (YAML) —
  unloadable → fail-fast
- `SubCaseMapping.Lambda` function non-null at build time
- `SubCaseMapping.Expression` string non-blank at build time

### Runtime — signals

Validation happens at `CaseHubRuntimeImpl.signal(caseId, signalType,
payload)` — fail-fast at the API layer, before event publishing:

1. Null payload → reject immediately
2. Look up `CaseInstance` via `caseInstanceCache.get(caseId)`
3. Resolve `CaseDefinition` via `CaseDefinitionRegistry` using the
   instance's namespace/name/version
4. If the definition declares signals:
   a. Signal name not in declared list → `SignalRejectedException`
   b. Signal name found but `signalType.payloadType()` does not equal
      the declared signal's `payloadType()` → `SignalRejectedException`.
      This closes the type erasure gap: Java generics provide no runtime
      guarantee on `<T>`, so raw-typed callers could bypass compile-time
      checks (e.g., `SignalType raw = SignalType.of("payment", String.class)`).
      The `Class<?>` equality check is the runtime enforcement.

This requires injecting `CaseDefinitionRegistry` into
`CaseHubRuntimeImpl`. The handler (`SignalReceivedEventHandler`) does
not need CaseDefinition access — validation is complete before the event
reaches the handler.

Rules:
- Typed signal with undeclared name (when definition has declared signals)
  → `SignalRejectedException` at `CaseHubRuntimeImpl`, context unchanged,
  `SIGNAL_REJECTED` EventLog entry
- No declared signals on definition → all signals accepted (backward
  compat)
- Untyped `signal(caseId, path, value)` → no validation against declared
  signals. The untyped path is a distinct API for dynamic/ad-hoc signal
  injection — it serves integration patterns where the signal schema is
  not known at compile time.
- Null payload on typed signal → reject

### Runtime — SubCase

- Input mapping evaluation failure (JQ error, lambda throws) → PlanItem
  faulted. Deliberate break from current behaviour where JQ failure
  silently produces `Map.of()`. Silent degradation to empty input is a
  source of subtle bugs.
- Output mapping evaluation failure → logged, parent continues. Output
  mapping failure should not block parent case progression.

## Testing strategy

### Contract test — no serialisation on internal paths

`SerializationDetectingBridge<T>` wraps a real bridge, delegates
`initialise()`/`extractOutput()`, throws `AssertionError` on
`serialise()`/`deserialise()`. Three test scenarios:

1. **In-process worker** — `WorkerRuntime.execute()` with typed function,
   assert no serialisation
2. **Same-JVM signal** — `signal(caseId, signalType, payload)` through to
   handler, assert no serialisation on the transfer path (EventLog write
   uses the real bridge)
3. **Same-JVM SubCase** — parent spawns child, typed POJO passes to
   `startCase()`, assert no `bridge.serialise()` on the transfer path.
   Note: `toContextMap()` performs Jackson `convertValue` to Map for
   CaseContext initialization — this is tested separately as a CaseContext
   concern, not a bridge concern

### Signal boundary tests

- Typed signal accepted when declared → context updated, EventLog records
  type metadata
- Typed signal rejected when name not declared →
  `SignalRejectedException`, context unchanged
- Untyped signal always accepted (backward compat)
- Typed signal with POJO payload → `JacksonPojoBridge` resolved, payload
  available as structured data
- Signal EventLog carries `signalTypeName` and `payloadType` in metadata

### SubCase boundary tests

- JQ input mapping (YAML path) → child starts with mapped context via
  `ExpressionEngineRegistry`
- Lambda input mapping (Java DSL path) → child starts with typed mapped
  context, no serialisation
- Output mapping via expression engine → parent context updated on child
  completion
- Input mapping failure → PlanItem faulted (not silent empty map)
- Output mapping failure → logged, parent continues
- Repeatable stage with SubCase → bridge re-initialises on each iteration

### Non-regression

All existing SubCase tests (`SubCaseExecutionHandlerTest`,
`SubCaseCompletionServiceTest`) continue to pass. They use `String` input
mappings — the `SubCaseMapping.Expression` path.

## Backward compatibility

- Untyped `signal(caseId, path, value)` unchanged
- Untyped `signal(caseId, Map)` unchanged
- `SubCase.Builder.inputMapping(String)` unchanged — creates
  `SubCaseMapping.Expression`
- `SubCase.Builder.outputMapping(String)` unchanged
- Definitions without declared signals accept all signals
- Existing EventLog entries without signal type metadata processed
  normally

## Migration path — key call site changes

This section describes the current code path and what changes at each
call site. The sections above describe the target state.

### Signal: `CaseHubRuntimeImpl`

**Current:** `signal(caseId, path, value)` publishes `SignalReceivedEvent`
directly. No validation against definition.

**Change:** Add typed overload `signal(caseId, signalType, payload)`.
Inject `CaseDefinitionRegistry`. Look up definition, validate signal
name, resolve bridge, publish `TypedSignalReceivedEvent`.

### Signal: `SignalReceivedEventHandler`

**Current:** Handles `SignalReceivedEvent` — applies `(path, value)` to
working layer via `applyAndDiff()`.

**Change:** Add handler for `TypedSignalReceivedEvent` — writes payload
to `.signals.{signalName}` via bridge, serialises for EventLog.

### SubCase input: `CaseContextChangedEventHandler.publishSubCaseSchedule()`

**Current:** Calls `evalJqAsMap()` directly — hardcoded JQ via
`jqEvaluator.eval()`. Returns `Map.of()` on failure (silent
degradation).

**Change:** Dispatch on `SubCaseMapping` type. Expression →
`ExpressionEngineRegistry.transform()` (first result, converted to Map).
Lambda → direct call. Failure → `PlanItem` faulted (not silent empty
map).

### SubCase output: `SubCaseCompletionService.applyOutputMappingToParent()`

**Current:** Reads `outputMapping` string from `SUBCASE_STARTED` EventLog
metadata. Calls `jqEvaluator.eval()` directly — hardcoded JQ.

**Change:** For Expression mappings: reads string from metadata as before,
dispatches through `ExpressionEngineRegistry.transform()`. For Lambda
mappings: reads `bindingName` from metadata, looks up `CaseDefinition`
via `CaseDefinitionRegistry`, finds the `SubCase` by binding, gets
the Lambda, calls directly. Requires injecting `CaseDefinitionRegistry`.

### SubCase EventLog: `SubCaseExecutionHandler`

**Current:** `SUBCASE_STARTED` metadata stores `childCaseId`,
`waitForCompletion`, `outputMapping` (string), and optionally `groupId`.

**Change:** Add `bindingName` to metadata. For Expression mappings,
continue storing `outputMapping` string. For Lambda mappings, store
`bindingName` only (function is not serialisable).

### Model: `SubCase`

**Current:** `inputMapping` and `outputMapping` are `String` (JQ).

**Change:** Change to `SubCaseMapping` (sealed interface). Existing
`Builder.inputMapping(String)` creates `SubCaseMapping.Expression`.
New overload `Builder.inputMapping(SubCaseMapping)`.

### Model: `CaseDefinition`

**Current:** No `signals` field.

**Change:** Add `List<SignalType<?>> signals`. Builder gains
`.signal(SignalType<T>)`. YAML schema gains `signals:` array.

### YAML: `CaseDefinitionYamlMapper`

**Current:** No signal parsing. `inputMapping`/`outputMapping` on SubCase
are strings passed through directly.

**Change:**
1. Parse the new `signals:` YAML array — each entry has `name` and
   `contextType`. Create `SignalType` objects via `Class.forName()` on
   `contextType`, same pattern as worker `contextType` resolution.
2. Wrap `inputMapping` and `outputMapping` strings in
   `SubCaseMapping.Expression` during SubCase construction.

### Model: `BridgeResolver`

**Current:** `resolveByTypeName(String)` does `Class.forName()` + inline
resolution chain.

**Change:** Extract resolution chain to `resolveByType(Class<?>)`.
`resolveByTypeName(String)` delegates via `Class.forName()`.

## Relationship to other issues

| Issue | Relationship |
|-------|-------------|
| #203 | Parent epic — ContextBridge protocol |
| #201 | Grandparent epic — adaptive execution architecture |
| #689 | WorkItem boundary — same protocol, future |
| #692 | Connector boundary — same protocol, future |
| #693 | Typed in-process composition — `WorkerRuntime.execute()` typed variants |
| #419 | CaseContextStore SPI — orthogonal storage concern |
