# io.casehub.api.context.CaseContextStore

**Package:** `io.casehub.api.context`

**Kind:** `interface`

Pluggable storage backend for a single context layer.

<p>Implementations handle where key-value pairs are stored (in-memory, Redis, database).
CaseContextImpl adds versioning, CAS, change listeners, and layer management on top. Store
implementations do not need to understand those higher-level semantics.

## Methods

### `public abstract void clear()`

### `public default void close()`

### `public abstract boolean containsKey(java.lang.String key)`

#### Parameters

- `key` (`java.lang.String`)

### `public abstract java.lang.Object get(java.lang.String key)`

#### Parameters

- `key` (`java.lang.String`)

### `public abstract boolean isEmpty()`

### `public abstract java.util.Set<java.lang.String> keySet()`

### `public default io.casehub.api.context.Subscription onExternalChange(java.util.function.Consumer<io.casehub.api.context.ContextChangeEvent> listener)`

Registers a listener for external changes. Only called when
supportsExternalChangeNotification() returns true.

<p><b>Contract:</b> fires ONLY for changes NOT made through this store instance's
put/remove/clear methods. Self-echoing stores (e.g. Redis pub/sub where the writer's own
subscription receives the write) must filter their own echoes — the implementation strategy
(client-ID filtering, write-ID dedup, sequence comparison) is a store concern.

#### Parameters

- `listener` (`java.util.function.Consumer<io.casehub.api.context.ContextChangeEvent>`)

### `public abstract java.lang.Object put(java.lang.String key, java.lang.Object value)`

Stores the value and returns the previous value, or null.

#### Parameters

- `key` (`java.lang.String`)
- `value` (`java.lang.Object`)

### `public default void putAll(java.util.Map<java.lang.String,java.lang.Object> entries)`

Stores all entries from the map. Default iterates and calls put(). Persistent stores may
override with a batch implementation (e.g. Redis MSET, single database transaction).

#### Parameters

- `entries` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `public abstract java.lang.Object remove(java.lang.String key)`

#### Parameters

- `key` (`java.lang.String`)

### `public abstract int size()`

### `public abstract java.util.Map<java.lang.String,java.lang.Object> snapshot()`

Returns an immutable snapshot of all entries.

### `public default boolean supportsExternalChangeNotification()`

Returns true if this store can detect writes from external sources.
