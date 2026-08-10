# Worker Data Coordination — DataExchange and DataChannel Design

> **Issue:** casehubio/engine#633
> **Status:** Design approved
> **Date:** 2026-08-08
> **Scope:** casehubio/worker (foundation tier), casehubio/engine (all modules), casehubio/workers (camel adapter)

## Overview

Workers currently have one data coordination path: the Blackboard (CaseContext) — many-to-many shared mutable state. This design adds two orthogonal patterns:

| # | Pattern | Direction | What it solves |
|---|---------|-----------|----------------|
| 1 | Blackboard (CaseContext) | Many ↔ Many | Shared mutable state (exists) |
| 2 | DataExchange | 1 → 1 | Discrete payload handoff with metadata |
| 3 | DataChannel | 1 → 1 | Continuous streaming pipe with backpressure |

DataExchange operates across all three tiers: Tier 1 (in-worker composition), Tier 2 (binding-driven), and Tier 3 (cross-case SubCase delegation). DataChannel operates at Tiers 1 and 2; cross-case channels (Tier 3) require EndpointRegistry-backed transport, deferred to the distributed channel spec. Workers that don't use DataExchange or DataChannel work exactly as before.

### Scope reconciliation with engine#633

Issue #633 references `EndpointRegistry (casehub-platform) provides physical addressing for distributed DataChannels` and brainstorming origin `casehubio/casehub-desiredstate#28`. This spec covers the core patterns (Exchange, DataChannel) with in-memory transport. Explicitly deferred:

- **EndpointRegistry-backed transport** — a `DataChannelFactory` implementation that uses `EndpointRegistry` for physical addressing of distributed DataChannels. Tracked separately; the SPI (`DataChannelFactory extends NamedStrategy`) is designed to accommodate it without changes.
- **Kafka/AMQP transport** — additional `DataChannelFactory` implementations. Same SPI extensibility.

The `DataChannelFactory` SPI is the extension point for all transport backends. This spec delivers the in-memory implementation and the SPI contract; distributed transports are additive and don't require design changes to the core model.

## Design Decisions

1. **Native types, not Camel.** Exchange/Channel are built native to casehub's execution model (immutable records, WorkerResult outcomes, EventLog audit, NamedStrategy transport). A Camel adapter bridges at the boundary for route-backed workers. Rationale: Camel's mutable Exchange fights platform conventions; Camel's routing engine overlaps with casehub's binding model; the gap is inter-worker coordination, not intra-worker integration.

2. **Exchange is a pure data envelope.** No ID (value type), no exception state (WorkerOutcome handles this), no separate Message type (Exchange IS the message). Cleaner separation than Camel — data and outcome are distinct.

3. **New WorkerFunction variant.** `ExchangeProcessor<T, R>` implements `ExchangeAware<T, R>` — a marker interface with `bodyInputType()`/`bodyOutputType()` for ContextBridge resolution at serialization boundaries. The engine detects `instanceof ExchangeAware` to thread Exchange metadata across bindings. `CamelExchangeWorkerFunction` also implements `ExchangeAware`, giving a single detection point for all Exchange-aware function types.

4. **Channel as blocking `Exchange<T>`.** Exchange is the universal atom; Channel streams them via a blocking virtual-thread-safe API. No reactive framework dependency in the foundation tier.

5. **Blackboard projection is a composable strategy.** Per-binding `ExchangeProjectionStrategy` (NamedStrategy) determines what reaches the Blackboard. Default `DualWrite` — backward compatible.

6. **Channel lifecycle: declared (Tier 2/3) vs ad-hoc (Tier 1).** Mirrors how Tier 1 workers call `scope.execute()` freely while Tier 2 is engine-managed via bindings.

## Exchange Type

**Module:** `casehub-worker-api` (foundation tier)
**Package:** `io.casehub.worker.api`

```java
public record Exchange<T>(
    T body,
    Map<String, Object> headers,
    Map<String, Object> properties
) {
    public Exchange {
        headers = headers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        properties = properties == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    // --- Factories ---
    public static <T> Exchange<T> of(T body) { return new Exchange<>(body, Map.of(), Map.of()); }
    public static <T> Exchange<T> of(T body, Map<String, Object> headers) {
        return new Exchange<>(body, headers, Map.of());
    }

    // --- Immutable transforms ---
    public <U> Exchange<U> withBody(U newBody) { return new Exchange<>(newBody, headers, properties); }

    public Exchange<T> withHeader(String key, Object value) {
        var h = new LinkedHashMap<>(headers); h.put(key, value);
        return new Exchange<>(body, h, properties);
    }

    public Exchange<T> withHeaders(Map<String, Object> newHeaders) {
        return new Exchange<>(body, newHeaders, properties);
    }

    public Exchange<T> withProperty(String key, Object value) {
        var p = new LinkedHashMap<>(properties); p.put(key, value);
        return new Exchange<>(body, headers, p);
    }

    public Exchange<T> withoutHeader(String key) {
        var h = new LinkedHashMap<>(headers); h.remove(key);
        return new Exchange<>(body, h, properties);
    }

    // --- Typed access ---
    @SuppressWarnings("unchecked")
    public <V> V header(String key) { return (V) headers.get(key); }

    public <V> V header(String key, V defaultValue) {
        @SuppressWarnings("unchecked")
        V value = (V) headers.get(key);
        return value != null ? value : defaultValue;
    }

    @SuppressWarnings("unchecked")
    public <V> V property(String key) { return (V) properties.get(key); }
}
```

### Semantics

- **Headers** propagate across binding dispatches (Tier 2/3). Metadata about the data: source system, content type, correlation IDs, timestamps.
- **Properties** do NOT propagate across bindings. Scoped to Tier 1 chains (`WorkerFunctions.exchangeSequence()`). Pipeline-local state: loop counters, accumulated results, routing decisions.
- **Body** is the payload. Any type: POJO, `Path` (file reference), `URI` (remote reference), `ChannelRef<T>` (stream reference).

### Null body semantics

A null body is valid. An Exchange with null body and non-empty headers serves as a signal-only envelope — the metadata IS the data. `withBody(null)` is legal; ContextBridge serialization skips body serialization when body is null; JQ expressions against `.body` yield `null`. The compact constructor does NOT reject null bodies — this is intentional, not an omission.

### What Exchange is NOT

- Not an entity — no ID, no lifecycle. Pure value type. Two Exchanges with same fields are equal.
- Not a result — no exception state. `WorkerResult<Exchange<R>>` carries outcome semantics.
- Not a message container — no IN/OUT split. Workers receive Exchange, return `WorkerResult<Exchange<R>>`.

## ExchangeAwareFunction — Engine Detection Interface

**Module:** `casehub-worker-api` (foundation tier)
**Package:** `io.casehub.worker.api`

All Exchange-typed `WorkerFunction` variants implement this interface. The engine uses `instanceof ExchangeAwareFunction` to gate Exchange-aware behavior: building Exchange from input projection, serializing Exchange metadata to EventLog, threading headers across bindings, and applying projection strategy.

```java
public interface ExchangeAwareFunction<T, R> extends WorkerFunction<Exchange<T>, Exchange<R>> {
    Class<T> bodyInputType();
    Class<R> bodyOutputType();
}
```

This separates the detection mechanism from any specific variant. Both `WorkerFunction.ExchangeProcessor` (core, `casehub-worker-api`) and `CamelExchangeWorkerFunction` (Camel adapter, `workers-camel`) implement `ExchangeAwareFunction`, ensuring all Exchange-typed workers get engine-level Exchange handling regardless of their dispatch mechanism.

## WorkerFunction.ExchangeProcessor

**Module:** `casehub-worker-api` (foundation tier)

New variant on `WorkerFunction` alongside `Sync`, `Persistent`, and `None`:

```java
public interface WorkerFunction<T, R> {
    // ... existing variants ...

    record ExchangeProcessor<T, R>(
        Class<T> bodyInputType,
        Class<R> bodyOutputType,
        BiFunction<Exchange<T>, WorkerScope, WorkerResult<Exchange<R>>> fn
    ) implements ExchangeAwareFunction<T, R> {

        public ExchangeProcessor {
            Objects.requireNonNull(bodyInputType);
            Objects.requireNonNull(bodyOutputType);
            Objects.requireNonNull(fn);
        }

        @Override @SuppressWarnings("unchecked")
        public Class<Exchange<T>> inputType() { return (Class) Exchange.class; }

        @Override @SuppressWarnings("unchecked")
        public Class<Exchange<R>> outputType() { return (Class) Exchange.class; }
    }
}
```

### Why a new variant (not `Sync<Exchange<T>, Exchange<R>>`)

- `bodyInputType()` / `bodyOutputType()` → ContextBridge resolution at EventLog serialization boundaries. Engine serializes the body via the appropriate bridge, not the whole Exchange.
- `instanceof ExchangeAwareFunction` → engine branches to thread Exchange headers across binding dispatches, apply projection strategy, store Exchange metadata in EventLog. The detection uses `ExchangeAwareFunction` (not `ExchangeProcessor` directly) so that all Exchange-typed variants — including `CamelExchangeWorkerFunction` — get the same engine treatment.
- Builder DSL distinguishes Exchange and non-Exchange paths at construction time.

### Builder DSL

```java
// Simple (Map body, Map output)
Worker.builder()
    .name("enricher")
    .capabilityName("enrichment")
    .exchange((exchange, scope) ->
        WorkerResult.of(exchange.withBody(Map.of("enriched", true))))
    .build();

// Typed input and output body
Worker.builder()
    .name("enricher")
    .<RawTransaction>exchange()
    .returning(EnrichedTransaction.class)
    .apply((exchange, scope) -> {
        var enriched = enrich(exchange.body());
        return WorkerResult.of(
            exchange.withBody(enriched)
                    .withHeader("enrichedAt", Instant.now().toString()));
    })
    .build();
```

`Worker.Builder.exchange()` returns `ExchangeProcessorBuilder<T>` (new class, mirrors `TypedFunctionBuilder<T>`).

### Tier 1 Composition

`WorkerFunctions` gains an Exchange-aware sequence:

```java
public static <T> WorkerFunction.ExchangeProcessor<T, T> exchangeSequence(
    WorkerFunction.ExchangeProcessor<?, ?>... steps) {
    // Construction-time validation: for i in 0..n-2, asserts
    //   steps[i].bodyOutputType() == steps[i+1].bodyInputType()
    // Throws IllegalArgumentException with step indices and mismatched types on failure.
    //
    // Runtime behavior:
    // Chains steps: output Exchange body feeds next step's input.
    // Headers accumulate (merge across steps).
    // Properties accumulate (merge across steps) — pipeline-local state.
    // Short-circuits on non-Success outcome.
}
```

Type-safe binary composition for compile-time checking:

```java
// On ExchangeProcessor<T, R>:
public <S> ExchangeProcessor<T, S> andThen(ExchangeProcessor<R, S> next) {
    // Returns a composed ExchangeProcessor<T, S> that chains this → next.
    // bodyInputType = this.bodyInputType, bodyOutputType = next.bodyOutputType.
    // Compile-time type-safe: R must match at both sites.
    // Headers accumulate (merge across steps) — same semantics as exchangeSequence().
    // Properties accumulate (merge across steps) — same semantics as exchangeSequence().
    // Short-circuits on non-Success outcome.
}
```

`andThen()` is preferred for pipelines where types vary between steps. `exchangeSequence()` remains for homogeneous pipelines (`ExchangeProcessor<T, T>`) and for dynamic step lists, with construction-time validation as a safety net.

## DataChannel

### Distinction from CaseChannel

`CaseChannel` (`io.casehub.api.model`) is a Qhorus-backed messaging reference for human–worker communication — oversight messages, governance decisions, structured messages with types, correlation IDs, deadlines, and targets. Managed by `CaseChannelProvider` SPI. These are communication channels, not data pipes.

`DataChannel` (`io.casehub.worker.api`) is a typed streaming pipe for worker-to-worker data flow — continuous records with backpressure. Different concept, different package, different lifecycle. The names are intentionally distinct: `CaseChannel` (communication) vs `DataChannel` (data streaming).

### Foundation tier (`casehub-worker-api`)

Minimal interface — transport-agnostic:

```java
public interface DataChannel<T> extends AutoCloseable {
    void send(Exchange<T> exchange);
    Exchange<T> receive();
    boolean isClosed();
    @Override void close();
}
```

`send()` blocks under backpressure (virtual-thread-safe). `receive()` blocks until the next Exchange is available; returns `null` when the channel is closed (virtual-thread-safe). `close()` terminates both ends: pending `send()` calls get `ChannelClosedException`, blocking `receive()` calls return `null`.

No reactive framework dependency — both `send()` and `receive()` are blocking calls, natural on virtual threads. The engine's `InMemoryDataChannel` implementation uses a bounded `BlockingQueue` internally; alternative transports (Kafka, AMQP) can implement `DataChannel<T>` with their own blocking receive.

### ChannelRef — serializable channel reference

```java
public record ChannelRef<T>(String name, Class<T> recordType) implements Serializable {
    public ChannelRef {
        Objects.requireNonNull(name);
        Objects.requireNonNull(recordType);
    }
    public static <T> ChannelRef<T> of(String name, Class<T> type) {
        return new ChannelRef<>(name, type);
    }
}
```

A serializable pointer that the engine resolves to a live `DataChannel<T>` at runtime. Structurally simpler than `DataRef` (no source field, no JSON serialization protocol) — it is a name + type pair, not a cross-source reference. Can be an Exchange body type: Worker A creates a channel, passes `ChannelRef` to Worker B via Exchange or Blackboard, Worker B resolves it.

### WorkerScope channel access

```java
public interface WorkerScope {
    // ... existing methods ...

    default <T> DataChannel<T> channel(String name) {
        throw new UnsupportedOperationException("DataChannel requires engine context");
    }

    default <T> DataChannel<T> channel(ChannelRef<T> ref) {
        return channel(ref.name());
    }

    default <T> ChannelRef<T> createChannel(String name, Class<T> recordType) {
        throw new UnsupportedOperationException("DataChannel requires engine context");
    }
}
```

`WorkerRuntime` (engine-api) overrides with real implementations backed by the engine's channel registry.

### Engine tier — lifecycle and transport

**ChannelDeclaration** (`engine-api`, record on `CaseDefinition`):

```java
public record ChannelDeclaration(
    String name,
    Class<?> recordType,
    String transport,
    LifecycleScope scope
) {
    public ChannelDeclaration {
        Objects.requireNonNull(name);
        Objects.requireNonNull(recordType);
        if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        if (transport == null) transport = "in-memory";
        if (scope == null) scope = LifecycleScope.CASE;
        if (scope == LifecycleScope.BINDING) {
            throw new IllegalArgumentException(
                "BINDING scope is not valid for channels — channels must outlive a single binding execution. Use COMPOUND or CASE.");
        }
    }
}
```

The `transport` field maps to a `DataChannelFactory` ID (e.g., `"in-memory"`, `"kafka"`). The `scope` field uses the existing `LifecycleScope` enum but rejects `BINDING` at construction time — a channel scoped to a single binding execution would be useless since it couldn't span producer and consumer bindings.

**DataChannelFactory** (`engine-api`, extends `NamedStrategy`):

```java
public interface DataChannelFactory extends NamedStrategy {
    <T> DataChannel<T> create(String name, Class<T> recordType, UUID caseId);
}
```

**InMemoryDataChannelFactory** (`engine-common`, `@DefaultBean @ApplicationScoped`, id `"in-memory"`):
- Backed by bounded `BlockingQueue` (virtual-thread-safe)
- Configurable buffer size (default 1024) for backpressure
- Zero external dependencies

**DataChannelRegistry** (`engine-common`, `@ApplicationScoped`):
- `ConcurrentHashMap<ChannelKey, DataChannel<?>>` where `ChannelKey = record(UUID caseId, String name)`
- `getOrCreate(caseId, name, recordType, factoryId)` — idempotent creation. If a channel with the same `(caseId, name)` already exists, validates that the existing channel's `recordType` matches the requested type. Throws `IllegalArgumentException` on mismatch with a message identifying both types and the channel name — fails fast at resolution rather than producing a deferred `ClassCastException` at send/receive time.
- `closeByExecution(caseId, executionId)` — teardown ad-hoc channels created during a specific worker execution
- `closeByCase(caseId)` — bulk teardown on case terminal state
- `closeByScope(caseId, scopeId)` — teardown on compound completion

### Channel lifecycle

**Tier 1 (ad-hoc):** Worker calls `scope.createChannel("pipe", MyRecord.class)`. Registered in `DataChannelRegistry` scoped to the case. `DefaultWorkerRuntime` tracks all channels created via `scope.createChannel()` during a worker execution. When the execution completes — regardless of outcome (success, failure, exception) — the runtime closes all ad-hoc channels created during that execution via `channelRegistry.closeByExecution(caseId, executionId)`. This prevents resource leaks from failed or retried workers. Case-terminal cleanup via `closeByCase()` remains as a backstop.

**Tier 2/3 (declared):** Channel declared on `CaseDefinition`. Engine creates when the owning scope activates. Bindings declare produce/consume:

```java
// CaseDefinition builder
.channel("tx-stream", TransactionRecord.class)                    // default in-memory
.channel("events", AuditEvent.class, "kafka")                     // named transport

// Binding builder
.produces("tx-stream")    // this binding produces to tx-stream
.consumes("tx-stream")    // this binding consumes from tx-stream
```

YAML:

```yaml
spec:
  channels:
    - name: tx-stream
      recordType: io.example.TransactionRecord
      transport: in-memory
      scope: compound        # or case (default)

bindings:
  - name: extract
    capability: data-extraction
    produces: tx-stream
  - name: transform
    capability: data-transformation
    consumes: tx-stream
```

**Scope lifecycle** reuses `LifecycleScope` (BINDING excluded — rejected at `ChannelDeclaration` construction):
- `CASE` (default): channel lives for case duration
- `COMPOUND`: created when compound activates, closed when compound completes

**Engine handlers:**
- `CaseStatusChangedHandler` calls `channelRegistry.closeByCase(caseId)` on terminal state
- `ScopedWorkerTerminationHandler` extended to close compound-scoped channels

## Engine Integration

### Tier 1: In-Worker Composition

No engine involvement. Workers pass Exchanges directly:

```java
WorkerResult<Exchange<Enriched>> result = scope.execute(enricherFunction, inputExchange);

var pipeline = WorkerFunctions.exchangeSequence(validate, enrich, format);
WorkerResult<Exchange<Formatted>> result = scope.execute(pipeline, rawExchange);
```

Properties accumulate (merge) across steps — pipeline-local state persists within the sequence. Headers also accumulate (merge). Short-circuit on non-Success.

### Tier 2: Binding-Driven Dispatch

Changes are additive — non-Exchange bindings untouched.

```
CaseContext change
  → CaseContextChangedEventHandler.publishByTarget()
      → [NEW] if worker function instanceof ExchangeAwareFunction:
          1. Build Exchange from input projection (body) + accumulated headers
          2. Serialize Exchange to EventLog metadata (body via ContextBridge, headers as-is)
          3. Publish WorkerScheduleEvent with Exchange reference
      → [EXISTING] else: normal Map-based input projection

  → WorkerScheduleEventHandler
      → [NEW] read Exchange from EventLog metadata if present
      → store in Quartz job data (body serialized via ContextBridge<bodyInputType>)

  → QuartzWorkerExecutionJob
      → [NEW] if ExchangeAwareFunction: deserialize Exchange, invoke via WorkerFunctionHandler
      → [EXISTING] else: normal deserialization

  → WorkflowExecutionCompletedHandler
      → [NEW] if result is WorkerResult<Exchange<R>>:
          1. Apply ExchangeProjectionStrategy
          2. Store outbound Exchange headers for next binding
          3. Audit Exchange metadata in EventLog
      → [EXISTING] else: normal output merge
      → publish CONTEXT_CHANGED (if projection strategy wrote to Blackboard)
```

**Header threading across bindings:** When Binding A completes:
- Body → optionally projected to Blackboard (per strategy)
- Headers → merged into `CaseInstance` field `Map<String, Object> exchangeHeaders` under the same per-case `ReentrantLock` used by `ContextOutputApplier`. This ensures concurrent binding completions produce a consistent merge — header storage and context output application happen atomically per case. `exchangeHeaders` is JPA-persisted as a JSONB column on `CaseInstanceEntity` — the `pendingActionGate` transient pattern demonstrated that in-memory-only state causes stalling bugs on JVM restart. Next Exchange-aware binding inherits these when building its input Exchange. Headers from successive bindings merge (last writer wins per key).
- Properties → discarded (Tier 1 only)

**EventLog audit for Exchange-aware entries:** `exchangeHeaders`, `exchangeBodyType`, `projectionStrategy` added to EventLog metadata.

### Tier 3: SubCase Delegation

`SubCaseMapping` is unchanged — no new variant. Exchange data crosses the SubCase boundary through CaseContext, which the existing `SubCaseMapping.Expression` already operates on.

When an Exchange-aware binding completes before a SubCase dispatch:
1. The projection strategy writes Exchange body to CaseContext (under `DualWriteProjection` or `FullProjection`)
2. Exchange headers are stored in `CaseInstance.exchangeHeaders` (engine-managed, JPA-persisted)
3. `SubCaseMapping.Expression` evaluates against CaseContext — which now contains the projected body
4. Exchange headers propagate to the child case via `CaseInstance.exchangeHeaders` — the engine copies the parent's `exchangeHeaders` to the child's `CaseInstance` at case launch time. `PropagationContext` is NOT used for Exchange header propagation: `PropagationContext.inheritedAttributes` is `Map<String, String>` (tracing/budget context), while Exchange headers are `Map<String, Object>` — these are semantically and structurally distinct concerns

This keeps `SubCaseMapping` Exchange-unaware. The sealed `permits Expression, Lambda` clause is unchanged.

**Forward-only limitation (v1):** Exchange headers propagate parent → child only. When the child case completes, `SubCaseMapping.outputMapping` maps CaseContext data back to the parent, but child `exchangeHeaders` are NOT merged back to the parent. Parent bindings that depend on Exchange headers accumulated by child workers require a return-path mechanism — deferred and tracked separately.

### What stays the same

- Binding trigger evaluation — still against CaseContext. Exchange changes HOW data flows, not WHEN bindings fire.
- PlanItem lifecycle — ExchangeProcessor bindings still create PlanItems (PENDING→RUNNING→COMPLETED).
- Retry/failure — `QuartzRetryService` replays Exchange from EventLog.
- Routing — `AgentRoutingStrategy` unaffected. Exchange data doesn't influence worker selection.
- Oversight gates — `ActionRiskClassifier` still sees `PlannedAction` on `WorkerResult`.

## Blackboard Projection Strategy

**Module:** `engine-api` (SPI), `engine-common` (implementations)

### SPI

```java
public interface ExchangeProjectionStrategy extends NamedStrategy {
    Set<String> project(Exchange<?> exchange, MutableCaseContext context);
}
```

### Built-in strategies

| Strategy | ID | Body → Blackboard | Headers → Blackboard | Exchange threaded |
|----------|-----|-------------------|---------------------|-------------------|
| `DualWriteProjection` | `"dual-write"` | Yes (via ConflictResolver) | No | Yes |
| `ExchangeOnlyProjection` | `"exchange-only"` | No | No | Yes |
| `FullProjection` | `"full"` | Yes | Yes (namespaced: `_exchange.<binding>.headers`) | Yes |
| `CustomJqProjection` | `"jq"` | JQ expression result | JQ expression result | Yes |

`DualWriteProjection` is `@DefaultBean @ApplicationScoped`. Non-Exchange workers completely unaffected.

### Binding declaration

```java
Binding.builder()
    .name("enricher")
    .capability(enrichmentCapability)
    .on(new ContextChangeTrigger(".raw != null"))
    .exchangeOnly()                          // shorthand
    .build();

Binding.builder()
    .name("auditor")
    .capability(auditCapability)
    .on(new ContextChangeTrigger(".enriched != null"))
    .projectWith("jq", "{ auditRecord: .body, source: .headers.sourceSystem }")
    .build();
```

YAML:

```yaml
bindings:
  - name: enricher
    capability: enrichment
    on: { contextChange: ".raw != null" }
    exchangeProjection: exchange-only

  - name: auditor
    capability: audit
    on: { contextChange: ".enriched != null" }
    exchangeProjection:
      strategy: jq
      expression: "{ auditRecord: .body, source: .headers.sourceSystem }"
```

### Projection failure semantics

When `ExchangeProjectionStrategy.project()` throws:

1. **Exchange threading proceeds** — headers are still merged into `CaseInstance.exchangeHeaders`. The Exchange is the primary data path; projection is secondary.
2. **Blackboard is NOT modified** — projection runs against a defensive copy of the context. Only on success are the changes applied. A thrown exception leaves the Blackboard in its pre-projection state.
3. **Binding is still marked complete** — the worker succeeded. Projection failure is a configuration error (bad JQ expression), not a worker error.
4. **EventLog audits the failure** — `projectionFailed: true`, `projectionError: <message>` added to EventLog metadata.
5. **CONTEXT_CHANGED is NOT published** (Blackboard unchanged) — downstream bindings don't fire from this projection. But the Exchange is threaded, so the next Exchange-aware binding still receives correct headers.
6. **PROJECTION_FAILED case event published** — a `CaseHubEventType.PROJECTION_FAILED` event is published via the event bus with metadata: binding name, strategy ID, error message. This is distinct from the EventLog audit entry (passive, per-binding) — the case-level event enables monitoring infrastructure to detect projection failures that could stall downstream processing, particularly SubCase triggers that depend on projected CaseContext data.

This ensures that a bad JQ expression in `CustomJqProjection` cannot discard a successful worker's Exchange output or corrupt the Blackboard. The PROJECTION_FAILED event provides active visibility into configuration errors that could otherwise cause silent pipeline stalls.

### Interaction with existing binding fields

- `conflictResolverStrategy` — applies to projected body under DualWrite/Full. ExchangeOnly bypasses.
- `producedKeys` — documents keys produced (Exchange headers for audit when ExchangeOnly).
- `outputSchema` (capability output projection) — applied to Exchange body before projection strategy. Headers pass through.

### Strategy × ConflictResolver composition

| exchangeProjection | conflictResolverStrategy applies to | Headers destination | Notes |
|---|---|---|---|
| `dual-write` | Projected body keys | `CaseInstance.exchangeHeaders` (not CaseContext) | Default strategy; body and Exchange coexist |
| `exchange-only` | N/A (no body projection) | `CaseInstance.exchangeHeaders` | ConflictResolver is bypassed entirely |
| `full` | Projected body keys only | `_exchange.<binding>.headers` namespace in CaseContext | Reserved namespace never conflicts; ConflictResolver applies only to body |
| `jq` | JQ expression result keys | `CaseInstance.exchangeHeaders` | JQ expression determines which keys are written; ConflictResolver applies to those keys |

**ContextBridge usage for Exchange body serialization:** Only `serialise(T)` and `deserialise(JsonNode)` from `ContextBridge<T>` are used at Exchange body serialization boundaries (EventLog persistence, Quartz job data). `initialise(CaseContext, JsonNode)` is not invoked in this path — Exchange bodies are standalone values, not CaseContext derivatives. The engine resolves the bridge via `bodyInputType()`/`bodyOutputType()` from `ExchangeAwareFunction`, not from the binding's context bridge configuration.

**ContextBridge resolution chain for Exchange bodies:** Resolution uses the existing `BridgeResolver.resolveByType(Class<?>)` mechanism — no new registry is introduced. The resolution order: (1) CDI-injected `ContextBridge<?>` beans whose `contextType()` matches the body type; (2) `MapBridge` for `Map.class`; (3) `JacksonPojoBridge<T>` as a universal fallback for any POJO type. Any class serializable by Jackson works out of the box without a registered bridge. Custom `ContextBridge` implementations are needed only when Jackson's default serialization is insufficient (e.g., types requiring CaseContext-aware initialization, which does not apply to Exchange body serialization since `initialise()` is not called in this path).

## Camel Adapter

**Module:** `casehubio/workers` → `workers-camel` (existing module, additions)

### CamelExchangeAdapter — static utility

```java
public final class CamelExchangeAdapter {

    public static void toCamel(Exchange<?> casehubExchange,
                                org.apache.camel.Exchange camelExchange) {
        camelExchange.getIn().setBody(casehubExchange.body());
        casehubExchange.headers().forEach((k, v) -> {
            if (!k.startsWith("Camel")) camelExchange.getIn().setHeader(k, v);
        });
        casehubExchange.properties().forEach(camelExchange::setProperty);
    }

    public static <R> Exchange<R> fromCamel(org.apache.camel.Exchange camelExchange,
                                             Class<R> bodyType) {
        R body = camelExchange.getIn().getBody(bodyType);
        Map<String, Object> headers = new LinkedHashMap<>(camelExchange.getIn().getHeaders());
        Map<String, Object> properties = new LinkedHashMap<>(camelExchange.getProperties());
        headers.entrySet().removeIf(e -> e.getKey().startsWith("Camel"));
        return new Exchange<>(body, headers, properties);
    }

    public static <R> WorkerResult<Exchange<R>> toResult(
            org.apache.camel.Exchange camelExchange, Class<R> bodyType) {
        if (camelExchange.getException() != null) {
            return WorkerResult.failed(camelExchange.getException().getMessage());
        }
        return WorkerResult.of(fromCamel(camelExchange, bodyType));
    }
}
```

### CamelExchangeWorkerFunction

```java
public record CamelExchangeWorkerFunction<T, R>(
    Class<T> bodyInputType,
    Class<R> bodyOutputType,
    String routeUri
) implements ExchangeAwareFunction<T, R> {
    @Override @SuppressWarnings("unchecked")
    public Class<Exchange<T>> inputType() { return (Class) Exchange.class; }
    @Override @SuppressWarnings("unchecked")
    public Class<Exchange<R>> outputType() { return (Class) Exchange.class; }
}
```

### CamelExchangeWorkerFunctionHandler

`@ApplicationScoped`, implements `WorkerFunctionHandler`. Dispatches to `ProducerTemplate.request()`, bridges via `CamelExchangeAdapter`, returns `HandlerResult` with Camel protocol metadata (`camelRouteUri`, `camelExchangeId`).

### YAML

```yaml
workers:
  - name: file-enricher
    capabilities: [enrichment]
    camel:
      route: direct:enrich-file
      exchange: true
      bodyInputType: io.example.RawRecord
      bodyOutputType: io.example.EnrichedRecord
```

`exchange: true` triggers `CamelWorkerFunctionProvider` to create `CamelExchangeWorkerFunction` instead of the existing non-Exchange path.

## Module Placement Summary

### `casehubio/worker` (foundation tier — `casehub-worker-api`)

| Type | Description |
|------|-------------|
| `Exchange<T>` | Immutable data envelope |
| `ExchangeAwareFunction<T,R>` | Marker interface — `bodyInputType()`/`bodyOutputType()` |
| `ChannelRef<T>` | Serializable channel reference |
| `DataChannel<T>` | Minimal interface — send, receive, close |
| `ChannelClosedException` | Runtime exception |
| `WorkerFunction.ExchangeProcessor<T,R>` | New WorkerFunction variant (implements `ExchangeAwareFunction`) |
| `ExchangeProcessorBuilder<T>` | Builder (mirrors TypedFunctionBuilder) |

`WorkerScope` gains `channel(String)`, `channel(ChannelRef)`, `createChannel(String, Class)` as default methods.
`Worker.Builder` gains `exchange(BiFunction)` and `<T>exchange()`.
`WorkerFunctions` gains `exchangeSequence(ExchangeProcessor...)`.

### `casehubio/engine` — `casehub-engine-api`

| Type | Description |
|------|-------------|
| `ExchangeProjectionStrategy` | NamedStrategy SPI |
| `DataChannelFactory` | NamedStrategy SPI |
| `ChannelDeclaration` | Record on CaseDefinition |

### `casehubio/engine` — `casehub-engine-common`

| Type | Description |
|------|-------------|
| `DataChannelRegistry` | Per-case channel tracking |
| `InMemoryDataChannel<T>` | Bounded `BlockingQueue` + blocking receive |
| `InMemoryDataChannelFactory` | `@DefaultBean`, id `"in-memory"` |
| `DualWriteProjection` | `@DefaultBean`, id `"dual-write"` |
| `ExchangeOnlyProjection` | id `"exchange-only"` |
| `FullProjection` | id `"full"` |
| `CustomJqProjection` | id `"jq"` |
| `ExchangeSerializer` | Exchange ↔ EventLog serialization |

### `casehubio/engine` — `casehub-engine` (runtime)

Handler changes (additive — Exchange-aware branches alongside existing paths):
- `CaseContextChangedEventHandler` — build Exchange from input projection
- `WorkerScheduleEventHandler` — serialize Exchange to EventLog
- `QuartzWorkerExecutionJob` — deserialize Exchange, invoke ExchangeProcessor
- `WorkflowExecutionCompletedHandler` — apply projection strategy, store headers
- `CaseStatusChangedHandler` — channel teardown on terminal state
- `ScopedWorkerTerminationHandler` — compound-scoped channel teardown
- `DefaultWorkerRuntime` — `execute()` gains `ExchangeAwareFunction` branch for Tier 1 Exchange execution; `channel()`/`createChannel()` overrides
- `EngineStrategyResolver` — ExchangeProjectionStrategy + DataChannelFactory instances

### `casehubio/workers` — `workers-camel`

| Type | Description |
|------|-------------|
| `CamelExchangeAdapter` | casehub Exchange ↔ Camel Exchange |
| `CamelExchangeWorkerFunction<T,R>` | Route URI + body types |
| `CamelExchangeWorkerFunctionHandler` | WorkerFunctionHandler impl |
| `CamelWorkerFunctionProvider` | Detects `exchange: true` in YAML |

### Dependency flow

```
casehub-worker-api          ← Exchange, DataChannel, ChannelRef, ExchangeAwareFunction, ExchangeProcessor
    ↑
casehub-engine-api          ← ExchangeProjectionStrategy, DataChannelFactory
    ↑
casehub-engine-common       ← DataChannelRegistry, InMemoryDataChannel, projections
    ↑               ↑
    │               └─── casehub-workers-camel  ← CamelExchangeAdapter, CamelExchangeWorkerFunction
    │                    (depends on engine-common via workers-common, NOT engine-runtime)
    │
casehub-engine (runtime)    ← handler changes, WorkerRuntime overrides
```

No circular dependencies. Foundation tier has zero engine knowledge. `workers-camel` depends on `engine-api` and `engine-common` (via `workers-common`) — it does NOT depend on `engine (runtime)`.
