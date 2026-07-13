# CaseContextStore — Pluggable Context Storage for CaseHub

## Overview

CaseContext is CaseHub's shared blackboard — the versioned, observable, layered
key-value store that all bindings, workers, goals, milestones, and signals
operate against. Today its storage is hardwired to `LinkedHashMap` protected by
`ReentrantReadWriteLock` inside `WritableLayerImpl`, and engine code bypasses
the `CaseContext` interface with `instanceof CaseContextImpl` checks in 8
production sites.

This design introduces `CaseContextStore` — a pluggable storage SPI that sits
below `CaseContextImpl` — and `MutableCaseContext` — an engine-internal
extension of `CaseContext` that eliminates all instanceof downcasts. Together
they make context storage truly pluggable (in-memory, Redis, database,
external framework state) while preserving CaseContextImpl's rich semantics
(versioning, CAS, change listeners, layer model) for free.

## Motivation

Three forces converge on the same design:

1. **External framework integration.** LangChain4j's `AgenticScope`, Serverless
   Workflow's `WorkflowContext`, and future AI frameworks have their own state
   models. The ContextBridge protocol (engine#203) handles type translation at
   worker boundaries. CaseContextStore handles where the bytes live — a
   complementary concern.

2. **Long-running case persistence.** Issue #108 tracks multi-day case
   management. Today, CaseContext is volatile — lost on JVM restart,
   reconstructed from EventLog replay. A persistent store (database, Redis)
   makes context durable as a property of the store, changing the recovery
   model from "replay all events" to "load from store."

3. **The `instanceof CaseContextImpl` code smell.** Engine code in 8
   production sites downcasts `CaseContext` to `CaseContextImpl` because the
   interface lacks `writableLayer()` and `freezeLayer()`. This makes
   CaseContext not truly pluggable — only `CaseContextImpl` works. Fixing
   this requires a two-tier interface.

## Relationship to ContextBridge (engine#203)

CaseContextStore and ContextBridge are complementary layers that compose
through CaseContext:

```
                         ContextBridge<T>          ← type translation at boundaries
                              ↕
    Worker ←→ Typed View (T) ←→ CaseContext ←→ CaseContextStore ←→ Backing
                                                    ↑
                                          storage delegation below CaseContext
```

The bridge sits ABOVE CaseContext (translates types at worker boundaries).
The store sits BELOW CaseContext (handles where bytes live). They never
reference each other. CaseContext mediates.

**Data flow — worker writes through a bridge to the store:**

```
Worker mutates typed view (e.g. AgenticScope.writeState("risk", 0.9))
  → bridge.onWrite("risk", 0.9, caseContext)
    → caseContext.set("risk", 0.9)
      → writableLayer.set("risk", 0.9)
        → store.put("risk", 0.9)
          → CaseContextImpl fires change listeners
            → engine publishes CONTEXT_CHANGED
```

**Data flow — external write hits the store:**

```
External system writes to backing store (e.g. Redis pub/sub)
  → store detects change, fires ContextChangeEvent
    → CaseContextImpl receives event (subscribed via onExternalChange)
      → fires change listeners (same path as normal set())
        → engine publishes CONTEXT_CHANGED
```

## Core SPI — CaseContextStore

A flat key-value storage contract. Intentionally minimal — any backing store
can implement it without understanding CaseContext's versioning, layers, CAS,
or listeners.

```java
package io.casehub.api.context;

public interface CaseContextStore extends AutoCloseable {

    Object get(String key);

    /** Stores the value and returns the previous value, or null. */
    Object put(String key, Object value);

    Object remove(String key);

    boolean containsKey(String key);

    Set<String> keySet();

    /** Returns an immutable snapshot of all entries. */
    Map<String, Object> snapshot();

    void clear();

    /** Stores all entries from the map. Default iterates and calls put().
     *  Persistent stores may override with a batch implementation
     *  (e.g. Redis MSET, single database transaction). */
    default void putAll(Map<String, Object> entries) {
        entries.forEach(this::put);
    }

    int size();

    boolean isEmpty();

    // --- Lifecycle ---

    /** Releases resources held by this store. Default no-op for in-memory stores.
     *  Persistent stores should release connections, subscriptions, etc.
     *  Called by the engine on case completion, eviction, or shutdown. */
    @Override
    default void close() {}

    // --- Hybrid observation (capability-based) ---

    /** Returns true if this store can detect writes from external sources. */
    default boolean supportsExternalChangeNotification() {
        return false;
    }

    /**
     * Registers a listener for external changes. Only called when
     * supportsExternalChangeNotification() returns true.
     *
     * <p><b>Contract:</b> fires ONLY for changes NOT made through this store
     * instance's put/remove/clear methods. Self-echoing stores (e.g. Redis
     * pub/sub where the writer's own subscription receives the write) must
     * filter their own echoes — the implementation strategy (client-ID
     * filtering, write-ID dedup, sequence comparison) is a store concern.
     */
    default Subscription onExternalChange(Consumer<ContextChangeEvent> listener) {
        return Subscription.NOOP;
    }
}
```

### CaseContextStoreFactory

Resolved per case definition via `StrategyResolver` (same `NamedStrategy`
pattern as routing strategies).

```java
package io.casehub.api.context;

public interface CaseContextStoreFactory extends NamedStrategy {

    /** Creates an empty store for a new case. */
    CaseContextStore createStore(String layerName, UUID caseId);

    /**
     * Loads a store for an existing case. For persistent stores, the returned
     * store is pre-populated with the persisted state. For volatile stores,
     * returns an empty store (same as createStore).
     */
    default CaseContextStore loadStore(String layerName, UUID caseId) {
        return createStore(layerName, caseId);
    }

    /**
     * Whether stores produced by this factory survive JVM restarts.
     * When true, recovery uses loadStore() directly — no EventLog replay.
     * When false (default), recovery replays EventLog to reconstruct state.
     */
    default boolean isDurable() { return false; }
}
```

`createStore()` — new case, empty store. `loadStore()` — existing case,
pre-populated from the backing store. For in-memory factories both are
identical. For persistent factories (database, Redis), `loadStore()` returns
a store containing the persisted state — no EventLog replay needed.

### Default implementation

```java
package io.casehub.engine.internal.context;

@DefaultBean
@ApplicationScoped
public class InMemoryCaseContextStoreFactory implements CaseContextStoreFactory {

    public static final InMemoryCaseContextStoreFactory INSTANCE =
        new InMemoryCaseContextStoreFactory();

    @Override public String id() { return "in-memory"; }

    @Override
    public CaseContextStore createStore(String layerName, UUID caseId) {
        return new InMemoryCaseContextStore();
    }
}
```

`InMemoryCaseContextStore` wraps `LinkedHashMap` — exactly today's
behavior behind the new interface.

### Hybrid observation model

The store SPI supports three integration modes via a single capability
mechanism:

| Store type | Write-through | Observation | Recovery |
|---|---|---|---|
| In-memory (default) | ✅ All writes via CaseContext.set() | ❌ No external changes possible | EventLog replay |
| AgenticScope-backed | ✅ Bridge routes writes through CaseContext | ❌ AgenticScope has no change listeners | EventLog replay |
| Redis-backed | ✅ All writes via CaseContext.set() | ✅ Pub/sub for writes from other nodes | Load from store |
| Database-backed | ✅ All writes via CaseContext.set() | Optional (change data capture) | Load from store |

Write-through is always active. Observation is capability-based — stores that
can detect external changes implement `supportsExternalChangeNotification()`
and `onExternalChange()`. CaseContextImpl subscribes if the capability is
present.

**Deduplication contract:** `onExternalChange()` fires ONLY for changes not
made through the store instance's own `put()`/`remove()`/`clear()` methods.
This is a store implementation responsibility, not a framework concern:

- **In-memory stores:** no external changes possible, no dedup needed.
- **Redis pub/sub stores:** filter echoes by Redis client ID or write-ID
  tracking (the Lettuce/Jedis client ID is available on every pub/sub message).
- **Database CDC stores:** filter by connection/session ID.

This contract means CaseContextImpl can trust every `onExternalChange` event
as genuinely external and fire listeners unconditionally — no thread-local
flags or secondary dedup needed at the framework level.

## Two-Tier Interface — MutableCaseContext

### The problem

8 production sites cast `CaseContext` to `CaseContextImpl`:

| Site | What it needs |
|------|--------------|
| `CaseHubReactor:179` | `writableLayer(SEMANTIC).setAll()` |
| `CaseHubReactor:196` | `writableLayer(EPISODIC).engineSet()` |
| `CaseHubReactor:225` | `freezeLayer(EPISODIC)` |
| `CaseHubReactor:234` | `writableLayer(userLayerName)` |
| `CaseHubReactor:254` | Method takes `CaseContextImpl` param |
| `WorkflowExecutionCompletedHandler:132` | `EpisodicLayerUpdater.recordWorkerCompletion()` |
| `WorkflowExecutionCompletedHandler:396` | `EpisodicLayerUpdater.recordWorkerCompletion()` |
| `DefaultWorkerExecutionRecoveryService:334` | `writableLayer(key).clear().setAll()` |

### The fix

```java
package io.casehub.api.context;

public interface MutableCaseContext extends CaseContext {

    WritableLayer writableLayer(String name);

    void freezeLayer(String name);
}
```

`CaseContextImpl` implements `MutableCaseContext`. All 8 production sites
change from `instanceof CaseContextImpl` to direct use of
`MutableCaseContext`. Engine-internal signatures (`CaseHubReactor`,
`EpisodicLayerUpdater`) accept `MutableCaseContext` instead of
`CaseContextImpl`.

### WritableLayer — unchanged

`WritableLayer` already exists as an interface in `api/context/`. It is
**not modified** by this design — no methods are added to it.

`engineSet()` and `engineUpdate()` remain on `WritableLayerImpl` (the
concrete class in `engine/internal/`), which is correct — these methods
bypass frozen checks and are engine-internal. Only code with a reference to
the concrete `WritableLayerImpl` can call them.

`EpisodicLayerUpdater` (the sole consumer of engine-bypass methods) accepts
`MutableCaseContext`, calls `writableLayer()` to get `WritableLayer`, and
casts to `WritableLayerImpl` for `engineSet()`/`engineUpdate()`. This cast
is safe — `EpisodicLayerUpdater` is engine-internal code, and the only
`WritableLayer` implementation is `WritableLayerImpl`. The cast is localized
to one class, not scattered across 8 sites.

### Complete interface hierarchy

```
ReadableLayer (api/)              — read-only view (consumer-facing)
  └─ WritableLayer (api/)         — adds write ops (set/remove/clear/CAS/etc.)
       └─ WritableLayerImpl       — implementation, delegates to CaseContextStore
                                    + engine-internal: engineSet/engineUpdate

CaseContext (api/)                — consumer API (get/set/snapshot/etc.)
  ├─ MutableCaseContext (api/)    — adds writableLayer()/freezeLayer()
  │    └─ CaseContextImpl         — implementation, delegates layers to stores
  └─ SnapshotCaseContext          — read-only snapshot (CBR), no writable layers

CaseContextStore (api/)           — storage SPI (get/put/remove + optional observation)
  └─ InMemoryCaseContextStore     — default (LinkedHashMap wrapper)

CaseContextStoreFactory (api/)    — creates stores per layer per case (NamedStrategy)
  └─ InMemoryCaseContextStoreFactory — @DefaultBean default
```

### Access path enforcement

| Caller | Gets | Can do |
|--------|------|--------|
| Worker lambda / consumer code | `CaseContext` | `set()`, `get()`, `layer()` → ReadableLayer |
| Engine handlers / reactor | `MutableCaseContext` | Above + `writableLayer()` → WritableLayer, `freezeLayer()` |
| `EpisodicLayerUpdater` | `MutableCaseContext` → `WritableLayer` → cast `WritableLayerImpl` | `engineSet()`, `engineUpdate()` |

## CaseContextImpl Integration

### Constructor change

```java
public CaseContextImpl() {
    this(InMemoryCaseContextStoreFactory.INSTANCE, null);
}

public CaseContextImpl(Map<String, Object> initial) {
    this(InMemoryCaseContextStoreFactory.INSTANCE, null, initial);
}

public CaseContextImpl(CaseContextStoreFactory storeFactory, UUID caseId) {
    this(storeFactory, caseId, null);
}

private CaseContextImpl(CaseContextStoreFactory storeFactory, UUID caseId,
                         Map<String, Object> initial) {
    this.storeFactory = storeFactory;
    this.caseId = caseId;
    if (initial != null) {
        initBuiltinLayers(initial);
    } else {
        initBuiltinLayers();
    }
}
```

The no-arg and `Map<String, Object>` constructors are preserved for backward
compatibility — they default to `InMemoryCaseContextStoreFactory.INSTANCE`.
`MapCaseFile` (which extends `CaseContextImpl` and calls `super()` /
`super(initial)`) continues to work without modification.

### Layer construction

```java
private void initBuiltinLayers() {
    layers.put(ContextLayer.WORKING,
        new WritableLayerImpl(ContextLayer.WORKING,
            storeFactory.createStore(ContextLayer.WORKING, caseId)));
    layers.put(ContextLayer.SEMANTIC,
        new WritableLayerImpl(ContextLayer.SEMANTIC,
            storeFactory.createStore(ContextLayer.SEMANTIC, caseId)));
    layers.put(ContextLayer.EPISODIC,
        new WritableLayerImpl(ContextLayer.EPISODIC,
            storeFactory.createStore(ContextLayer.EPISODIC, caseId)));
}
```

On-demand layer creation via `layer()` and `writableLayer()` also uses the
factory:

```java
@Override
public ReadableLayer layer(String name) {
    return layers.computeIfAbsent(name, n ->
        new WritableLayerImpl(n, storeFactory.createStore(n, caseId)));
}

public WritableLayerImpl writableLayer(String name) {
    return layers.computeIfAbsent(name, n ->
        new WritableLayerImpl(n, storeFactory.createStore(n, caseId)));
}
```

Built-in layers (WORKING, SEMANTIC, EPISODIC) are created eagerly in
`initBuiltinLayers()`. Custom layers are created on demand through the
factory. For in-memory stores, on-demand creation is trivial. For persistent
stores, on-demand creation opens a connection — this is acceptable because
custom layers are rare and their creation is explicit (only happens when
engine code references a non-builtin layer name).

### Hybrid observation wiring

```java
// In CaseContextImpl constructor, after layer creation:
CaseContextStore workingStore = working().getStore();
if (workingStore.supportsExternalChangeNotification()) {
    workingStore.onExternalChange(event ->
        fireListeners(event.key(), event.oldValue(), event.newValue()));
}
```

**Working layer only:** external change observation is wired only for the
working layer. This is consistent with the existing listener model —
`onChange()`/`onAnyChange()` fire only for working-layer changes via the
flat API. Engine-internal writes to semantic/episodic layers (via
`engineSet()`, `applyDiff()`) deliberately do NOT fire listeners. Wiring
observation for semantic/episodic stores would break this invariant by
surfacing engine-managed state changes to consumer listeners.

### modify() migration strategy

`WritableLayerImpl` has a package-private `modify()` method that
`CaseContextImpl` uses extensively for atomic read-modify-write with
listener notification. It currently exposes the raw `data` map to lambdas:

```java
// Current signature (package-private on WritableLayerImpl):
<R> R modify(BiFunction<Map<String, Object>, Runnable, R> action)
```

With pluggable stores, `modify()` changes to pass the store instead of the
raw map:

```java
// New signature:
<R> R modify(BiFunction<CaseContextStore, Runnable, R> action) {
    checkWritable();
    lock.writeLock().lock();
    try {
        boolean[] changed = {false};
        R result = action.apply(store, () -> changed[0] = true);
        if (changed[0]) { version++; }
        return result;
    } finally { lock.writeLock().unlock(); }
}
```

**Flat-key operations** — mechanical substitution. CaseContextImpl's lambdas
change `data.get(key)` → `store.get(key)`, `data.put(key, value)` →
`store.put(key, value)`:

```java
// Before:
working().modify((data, changed) -> {
    Object p = data.get(key);
    if (!Objects.equals(p, value)) {
        data.put(key, value);
        changed.run();
    }
    return p;
});

// After:
working().modify((store, changed) -> {
    Object p = store.get(key);
    if (!Objects.equals(p, value)) {
        store.put(key, value);
        changed.run();
    }
    return p;
});
```

The atomicity guarantee is preserved — WritableLayerImpl's write lock wraps
the entire lambda execution. The store receives individual `get()`/`put()`
calls, which every store must support. Multi-key atomicity (for `setAll()`,
`clear()`, `merge()`) comes from WritableLayerImpl's lock, not from the
store.

The 7 flat-key CaseContextImpl methods (`set`, `compareAndSet`, `update`,
`setAll`, `clear`, `remove`, `computeIfAbsent`, `putIfAbsent`, `merge`)
follow this mechanical pattern.

**Path-based operations** — require restructuring, not mechanical
substitution. Four WritableLayerImpl methods navigate the raw `data` map's
nested structure starting with `Map<String, Object> current = data`.
`CaseContextStore` is not a `Map`, so first-level access changes to
`store.get(parts[0])` and subsequent levels navigate within the returned
value.

More critically: `setPath()` and `applyAndDiff()` mutate nested Maps
in-place. For in-memory stores this works (reference semantics — the stored
object is mutated directly). For persistent stores that deserialize on
`get()`, the mutation modifies a local copy and the store never sees the
change. The fix: write back the root-level value after any nested mutation.

```java
// setPath() — before:
Map<String, Object> current = data;
for (int i = 0; i < parts.length - 1; i++) {
    Object next = current.get(parts[i]);
    // ... navigate ...
}
current.put(leaf, value);

// setPath() — after:
Object rootValue = store.get(parts[0]);
if (parts.length == 1) {
    store.put(parts[0], value);
} else {
    Map<String, Object> current = (Map<String, Object>) rootValue;
    for (int i = 1; i < parts.length - 1; i++) {
        Object next = current.get(parts[i]);
        // ... navigate, creating intermediate maps as needed ...
    }
    current.put(leaf, value);
    store.put(parts[0], rootValue);  // write-back — ensures persistent stores see the change
}
```

The write-back is a no-op for in-memory stores (the object is already stored
by reference) and essential for persistent stores that deserialize on read.

CaseContextImpl's `setPath()` through `modify()` follows the same pattern —
the lambda receives the store, navigates via `store.get()`, mutates nested
maps, and calls `store.put()` to write back:

```java
working().modify((store, changed) -> {
    if (parts.length == 1) {
        Object p = store.get(parts[0]);
        if (!Objects.equals(p, value)) {
            store.put(parts[0], value);
            changed.run();
        }
        return p;
    }
    Object rootValue = store.get(parts[0]);
    // ... navigate to leaf, capture prev, mutate ...
    store.put(parts[0], rootValue);  // write-back
    changed.run();
    return prev;
});
```

**Serialization operations** — use `store.snapshot()` instead of direct `data`
access. Methods that serialize the entire map (`asJsonNode()`, `getData()`,
`diff()`, `toString()`, `deepCopy()`) currently use
`MAPPER.convertValue(data, ...)` or `new LinkedHashMap<>(data)`. With the
store, these become `store.snapshot()`:

```java
// Before:
JsonNode before = MAPPER.convertValue(data, JsonNode.class);
// After:
JsonNode before = MAPPER.convertValue(store.snapshot(), JsonNode.class);
```

**`applyDiff()` — bulk repopulation.** Currently does `data.clear();
data.putAll(updated)`. With the store, uses `store.clear()` +
`store.putAll()`:

```java
// Before:
data.clear();
data.putAll(updated);
version++;

// After:
store.clear();
store.putAll(updated);
version++;
```

The `putAll()` default implementation iterates and calls `put()` per entry.
Persistent stores may override with a batch implementation (e.g., Redis
MSET, single database transaction) to avoid N individual round-trips.

**`getPathInternal()` — read path.** Currently starts navigation from the
raw map root (`Object current = data`). Restructured to start from the
store:

```java
// Before:
Object current = data;
for (String part : parts) {
    if (current instanceof Map<?, ?> map) { current = map.get(part); }
    else { return null; }
}

// After:
Object current = store.get(parts[0]);
for (int i = 1; i < parts.length; i++) {
    if (current == null) return null;
    if (current instanceof Map<?, ?> map) { current = map.get(parts[i]); }
    else { return null; }
}
```

### WritableLayerImpl change

```java
public class WritableLayerImpl implements WritableLayer {
    private final String layerName;
    private final CaseContextStore store;
    private final ReadWriteLock lock;
    private long version;
    private volatile boolean frozen;

    public WritableLayerImpl(String layerName, CaseContextStore store) {
        this.layerName = layerName;
        this.store = store;
        this.lock = new ReentrantReadWriteLock();
    }

    /** Exposes the underlying store for observation wiring. */
    CaseContextStore getStore() { return store; }

    @Override
    public Object get(String key) {
        lock.readLock().lock();
        try { return store.get(key); }
        finally { lock.readLock().unlock(); }
    }

    @Override
    public WritableLayer set(String key, Object value) {
        checkWritable();
        lock.writeLock().lock();
        try {
            Object prev = store.get(key);
            if (!Objects.equals(prev, value)) {
                store.put(key, value);
                version++;
            }
        } finally { lock.writeLock().unlock(); }
        return this;
    }

    /** Engine-internal: bypasses frozen check. Stays on concrete class only. */
    public WritableLayerImpl engineSet(String key, Object value) {
        lock.writeLock().lock();
        try {
            Object prev = store.get(key);
            if (!Objects.equals(prev, value)) {
                store.put(key, value);
            }
            return this;
        } finally { lock.writeLock().unlock(); }
    }

    /** Engine-internal: atomic read-modify-write bypassing frozen check. */
    public void engineUpdate(String key, UnaryOperator<Object> updater) {
        lock.writeLock().lock();
        try {
            Object current = store.get(key);
            Object updated = updater.apply(current);
            if (updated != null) {
                store.put(key, updated);
            } else if (store.containsKey(key)) {
                store.remove(key);
            }
        } finally { lock.writeLock().unlock(); }
    }

    // ... remaining methods delegate to store under lock
}
```

### snapshot() and fromLayerDocument() semantics

**`snapshot()`** creates a detached, read-only deep copy. Snapshots always
use in-memory stores regardless of the configured factory — a snapshot does
not need persistence, external observation, or connection management. The
deep copy is created via `WritableLayerImpl.deepCopy()`, which copies data
from the store into a new `InMemoryCaseContextStore`.

**`fromLayerDocument()`** is the EventLog replay recovery path. It is only
used for volatile stores (`isDurable() == false`), where recovery replays
EventLog events to reconstruct context state. The method creates a
`CaseContextImpl` with in-memory stores and populates them from the
serialized layer document — this is correct because EventLog replay always
produces in-memory state.

For persistent stores (`isDurable() == true`), recovery uses
`factory.loadStore()` directly — `fromLayerDocument()` is not involved.
The recovery service branching logic (see §Recovery Model) ensures the
right path is chosen based on factory durability.

### Production site migration

**10 `new CaseContextImpl(...)` sites → factory:**

| Site | Before | After |
|------|--------|-------|
| `CaseHubRuntimeImpl` (5 sites) | `new CaseContextImpl(data)` | Factory from CaseDefinition |
| `DefaultWorkerExecutionRecoveryService` (2 sites) | `new CaseContextImpl()` / `.fromLayerDocument()` | `factory.loadStore()` for persistent, `factory.createStore()` + EventLog replay for volatile |
| `DefaultExpressionEngineRegistry` (1 site) | `new CaseContextImpl(asNode)` | `new CaseContextImpl(asNode)` — stays (ephemeral evaluation context) |
| `CaseContextImpl` internal (2 sites) | `new CaseContextImpl()` in snapshot/fromLayerDocument | Internal — uses in-memory factory (detached copies) |

**8 `instanceof CaseContextImpl` sites → MutableCaseContext:**

All 8 sites change to `MutableCaseContext` — the interface provides
`writableLayer()` and `freezeLayer()` directly.

**4 `EpisodicLayerUpdater` methods → MutableCaseContext parameter:**

```java
// Before
public static void initBaseline(CaseContextImpl ctx)
// After
public static void initBaseline(MutableCaseContext ctx)
```

`EpisodicLayerUpdater` internally casts `writableLayer()` to
`WritableLayerImpl` for `engineSet()`/`engineUpdate()` — a safe, localized
cast within engine-internal code.

**`MapCaseFile` — no change required:**

`MapCaseFile` extends `CaseContextImpl` and calls `super()` /
`super(initial)`. Both constructors are preserved with default in-memory
factory behavior. `MapCaseFile` continues to work without modification.

### Store lifecycle

The engine calls `store.close()` at two lifecycle points:

1. **Case completion/eviction:** when a case instance is removed from the
   active cache, all layer stores are closed.
2. **Engine shutdown:** all active case stores are closed during graceful
   shutdown.

For in-memory stores, `close()` is a no-op (default). For persistent stores,
it releases connections, cancels pub/sub subscriptions, and returns
connection pool resources.

## CaseDefinition Integration

`CaseDefinition` gains a nullable String field for the store factory strategy
ID:

```java
private String contextStoreFactory;  // nullable — null = default in-memory
```

Resolved by `StrategyResolver` at context creation time in
`CaseHubRuntimeImpl`:

```java
CaseContextStoreFactory factory = strategyResolver.resolve(
    CaseContextStoreFactory.class,
    definition.getContextStoreFactory());
```

YAML schema:

```yaml
context:
  storeFactory: redis    # named strategy ID, or omit for default
```

## Recovery Model

The recovery strategy is a property of the store factory, not a global
setting. Different installations choose the model that fits their
performance and durability requirements:

| Store type | `isDurable()` | Recovery path | EventLog role |
|---|---|---|---|
| In-memory (default) | `false` | EventLog replay | Source of truth for state recovery AND audit |
| In-memory + snapshots | `false` | Replay from last snapshot | Source of truth, snapshots reduce replay window |
| Redis / distributed cache | `true` | `loadStore()` — pre-populated | Audit trail only |
| Database-backed | `true` | `loadStore()` — pre-populated | Audit trail only |

The in-memory + EventLog replay model is the default and often the best
choice for pure performance — writes are in-memory (zero I/O), durability
comes from the append-only EventLog journal. Persistent stores skip replay
but add I/O per write.

### Factory durability signal

```java
public interface CaseContextStoreFactory extends NamedStrategy {
    CaseContextStore createStore(String layerName, UUID caseId);
    CaseContextStore loadStore(String layerName, UUID caseId);

    /** Whether stores survive JVM restarts. When true, recovery uses
     *  loadStore() directly. When false, recovery replays EventLog. */
    default boolean isDurable() { return false; }
}
```

### Recovery service integration

`DefaultWorkerExecutionRecoveryService` uses the factory's durability signal:

```java
CaseContextStoreFactory factory = resolveFactory(definition);

if (factory.isDurable()) {
    // Persistent store — load pre-populated state, skip EventLog replay
    context = new CaseContextImpl(factory, caseId);
    // loadStore() is called internally by CaseContextImpl via the factory
} else {
    // Volatile store — replay EventLog to reconstruct context
    context = rebuildFromEventLog(caseId, factory);
}
```

This directly enables issue #108 (Long-Running Case Management) — persistent
stores make context durable across JVM restarts. The choice between
performance-optimized (in-memory + journal) and persistence-optimized
(database-backed) is per-case-definition via the `storeFactory` YAML
configuration.

## Example Module

A new module `casehub-examples-typed-context` demonstrates the full
integration.

**Store pluggability (governed by this spec):** custom `CaseContextStore`
implementation, factory configuration, recovery behavior, and store contract
tests.

**End-to-end integration (cross-spec):** the example also demonstrates
ContextBridge typed workers via FuncDSL, sub-case context propagation, and
goal evaluation. These behaviors are governed by the ContextBridge spec
(engine#203) and existing engine features respectively — this spec governs
only the store pluggability aspect. The combined example is intentional: it
validates that store pluggability composes correctly with the full engine
stack.

### Custom store

A simple `AuditingCaseContextStore` that wraps `InMemoryCaseContextStore`
and logs every write — demonstrates the store SPI without external
dependencies:

```java
public class AuditingCaseContextStore implements CaseContextStore {
    private final InMemoryCaseContextStore delegate = new InMemoryCaseContextStore();
    private final List<ContextChangeEvent> auditLog = new CopyOnWriteArrayList<>();

    @Override
    public Object put(String key, Object value) {
        Object prev = delegate.put(key, value);
        auditLog.add(new ContextChangeEvent(key, prev, value));
        return prev;
    }
    // ... remaining methods delegate
}
```

### Domain model

An investigation domain with a typed context:

```java
public record InvestigationContext(
    String suspectId,
    List<String> evidence,
    double riskScore,
    String verdict) {}
```

### FuncDSL typed workers

```java
Worker.builder()
    .capabilityName("assess-risk")
    .<InvestigationContext>fn()
    .apply(ctx -> WorkerResult.of(Map.of(
        "riskScore", computeRisk(ctx.evidence()))))

Worker.builder()
    .capabilityName("render-verdict")
    .<InvestigationContext>fn()
    .apply(ctx -> WorkerResult.of(Map.of(
        "verdict", ctx.riskScore() > 0.8 ? "HIGH_RISK" : "LOW_RISK")))
```

### Sub-case propagation

A parent case spawns a sub-case, passing part of the context. The sub-case
uses its own store (demonstrating per-case store independence):

```yaml
# Parent case
subCases:
  - name: detailed-analysis
    inputMapping: "{evidence: .evidence, suspectId: .suspectId}"
    context:
      storeFactory: auditing   # sub-case uses auditing store
```

### Tests

- **Store contract tests** — verify any `CaseContextStore` implementation
  handles all operations correctly (put/get/remove/snapshot/clear/close)
- **Propagation tests** — verify context flows from parent case through
  sub-case creation, worker execution (with FuncDSL typed input), and
  goal evaluation
- **Audit log tests** — verify the auditing store captures all writes
  including engine-internal writes (episodic layer updates)
- **Recovery tests** — verify persistent store avoids EventLog replay

## Module Placement

```
casehub-engine-api (api/)
  io.casehub.api.context.CaseContextStore
  io.casehub.api.context.CaseContextStoreFactory
  io.casehub.api.context.MutableCaseContext
  io.casehub.api.context.WritableLayer          (existing — unchanged)
  io.casehub.api.context.ReadableLayer          (existing — unchanged)
  io.casehub.api.context.CaseContext            (existing — unchanged)
  io.casehub.api.context.Subscription           (existing — reused for store observation)
  io.casehub.api.context.ContextChangeEvent     (existing — reused for store observation)

casehub-engine-runtime (runtime/)
  io.casehub.engine.internal.context.CaseContextImpl        (implements MutableCaseContext)
  io.casehub.engine.internal.context.WritableLayerImpl      (delegates to CaseContextStore)
  io.casehub.engine.internal.context.InMemoryCaseContextStore
  io.casehub.engine.internal.context.InMemoryCaseContextStoreFactory

casehub-examples-typed-context (new module)
  Custom store, FuncDSL workers, sub-case propagation, tests
```

## Relationship to Existing Issues

| Issue | Relationship |
|-------|-------------|
| #419 | CaseContextProvider SPI — this spec is the realization of #419 with an evolved design. The name changed from `CaseContextProvider` to `CaseContextStore`; typed access moved to ContextBridge (#203); the store focuses on raw key-value storage. Implementation of this spec closes #419. |
| #203 | ContextBridge — type translation at boundaries. Complementary: bridge sits above CaseContext, store sits below. |
| #108 | Long-Running Case Management — persistent stores enable durable context across JVM restarts. |
| #238 | JavaBeanCaseFile — live POJO façade. Could be a live-view ContextBridge backed by CaseContext (which is backed by the store). |
| #237 | Long-lived workers with lifecycle scopes — orthogonal: scope determines worker lifetime, store determines context durability. |
| #646 | Per-case CONTEXT_CHANGED serialization — hybrid observation (external change events) interacts with this concern. |

## Summary

CaseContextStore introduces a single storage SPI below CaseContextImpl.
MutableCaseContext eliminates all 8 `instanceof CaseContextImpl` checks.
Together they make context storage genuinely pluggable — any backing store
works, the engine's rich semantics (versioning, CAS, listeners, layers) are
preserved for free, and the design composes cleanly with ContextBridge above.
The same SPI enables long-running case persistence (#108) as a natural
consequence of using a persistent store.
