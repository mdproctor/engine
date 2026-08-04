# io.casehub.api.context.CaseContext

**Package:** `io.casehub.api.context`

**Kind:** `interface`

## Methods

### `public abstract java.util.Optional<JsonNode> applyAndDiff(java.lang.String path, java.lang.Object value)`

Atomically sets the value at `path` and returns the JSON diff against the state before
the write. Returns `Optional.empty()` if the write produced no state change (idempotent
signal deduplication).

#### Parameters

- `path` (`java.lang.String`)
- `value` (`java.lang.Object`)

### `public abstract void applyDiff(JsonNode diff)`

#### Parameters

- `diff` (`JsonNode`)

### `public abstract JsonNode asJsonNode()`

### `public abstract io.casehub.api.context.CaseContext clear()`

### `public abstract boolean compareAndSet(java.lang.String key, java.lang.Object expected, java.lang.Object newValue)`

#### Parameters

- `key` (`java.lang.String`)
- `expected` (`java.lang.Object`)
- `newValue` (`java.lang.Object`)

### `public abstract java.lang.Object computeIfAbsent(java.lang.String key, java.util.function.Function<java.lang.String,java.lang.Object> mappingFunction)`

#### Parameters

- `key` (`java.lang.String`)
- `mappingFunction` (`java.util.function.Function<java.lang.String,java.lang.Object>`)

### `public abstract boolean contains(java.lang.String key)`

#### Parameters

- `key` (`java.lang.String`)

### `public abstract JsonNode diff(io.casehub.api.context.CaseContext other)`

#### Parameters

- `other` (`io.casehub.api.context.CaseContext`)

### `public abstract java.lang.Object get(java.lang.String key)`

#### Parameters

- `key` (`java.lang.String`)

### `public abstract java.util.Map<java.lang.String,java.lang.Object> getAll(java.lang.String[] keys)`

#### Parameters

- `keys` (`java.lang.String[]`)

### `public abstract T getAs(java.lang.String key, java.lang.Class<T> type)`

#### Parameters

- `key` (`java.lang.String`)
- `type` (`java.lang.Class<T>`)

### `public abstract java.lang.Boolean getBoolean(java.lang.String key)`

#### Parameters

- `key` (`java.lang.String`)

### `public abstract java.util.Map<java.lang.String,java.lang.Object> getData()`

### `public abstract java.lang.Double getDouble(java.lang.String key)`

#### Parameters

- `key` (`java.lang.String`)

### `public abstract java.lang.Integer getInt(java.lang.String key)`

#### Parameters

- `key` (`java.lang.String`)

### `public abstract java.util.Set<java.lang.String> getKeys()`

### `public abstract java.util.List<T> getList(java.lang.String key, java.lang.Class<T> elementType)`

#### Parameters

- `key` (`java.lang.String`)
- `elementType` (`java.lang.Class<T>`)

### `public abstract java.lang.Long getLong(java.lang.String key)`

#### Parameters

- `key` (`java.lang.String`)

### `public abstract T getOrDefault(java.lang.String key, T defaultValue)`

#### Parameters

- `key` (`java.lang.String`)
- `defaultValue` (`T`)

### `public abstract java.lang.Object getPath(java.lang.String path)`

#### Parameters

- `path` (`java.lang.String`)

### `public abstract java.lang.String getPathAsString(java.lang.String path)`

#### Parameters

- `path` (`java.lang.String`)

### `public abstract java.lang.String getString(java.lang.String key)`

#### Parameters

- `key` (`java.lang.String`)

### `public abstract long getVersion()`

### `public abstract boolean isEmpty()`

### `public abstract io.casehub.api.context.ReadableLayer layer(java.lang.String name)`

#### Parameters

- `name` (`java.lang.String`)

### `public abstract io.casehub.api.context.CaseContext merge(io.casehub.api.context.CaseContext other)`

#### Parameters

- `other` (`io.casehub.api.context.CaseContext`)

### `public default io.casehub.api.context.Subscription onAnyChange(java.util.function.Consumer<io.casehub.api.context.ContextChangeEvent> listener)`

Registers a listener that fires whenever any key in the working layer is changed via the flat
API. Any-change listeners fire after per-key listeners, in registration order.

<p><b>Callers must call `Subscription.cancel()` when the listener is no longer
needed.</b> See Consumer) for lifecycle details.

#### Parameters

- `listener` (`java.util.function.Consumer<io.casehub.api.context.ContextChangeEvent>`) — consumer invoked with a `ContextChangeEvent` carrying old/new values

#### Returns

a `Subscription` whose `Subscription.cancel()` removes this listener

### `public default io.casehub.api.context.Subscription onChange(java.lang.String key, java.util.function.Consumer<io.casehub.api.context.ContextChangeEvent> listener)`

Registers a listener that fires whenever the specified key in the working layer is changed via
the flat API (`set()`, `setAll()`, `remove()`, etc.).

<p>Listeners execute on the calling thread, after the write lock is released. They must be
non-blocking. Exceptions thrown by a listener are logged (WARN) and never propagated.

<p>Engine-internal writes (`engineSet()`, `applyDiff()`) do NOT fire listeners.

<p><b>Callers must call `Subscription.cancel()` when the listener is no longer
needed.</b> Listeners are held by strong reference and accumulate for the lifetime of the
CaseContext if not cancelled. For long-running cases with many registrations this can cause
unbounded growth.

#### Parameters

- `key` (`java.lang.String`) — the context key to observe
- `listener` (`java.util.function.Consumer<io.casehub.api.context.ContextChangeEvent>`) — consumer invoked with a `ContextChangeEvent` carrying old/new values

#### Returns

a `Subscription` whose `Subscription.cancel()` removes this listener

### `public abstract java.lang.Object putIfAbsent(java.lang.String key, java.lang.Object value)`

#### Parameters

- `key` (`java.lang.String`)
- `value` (`java.lang.Object`)

### `public abstract io.casehub.api.context.CaseContext remove(java.lang.String key)`

#### Parameters

- `key` (`java.lang.String`)

### `public abstract io.casehub.api.context.CaseContext set(java.lang.String key, java.lang.Object value)`

#### Parameters

- `key` (`java.lang.String`)
- `value` (`java.lang.Object`)

### `public abstract io.casehub.api.context.CaseContext setAll(java.util.Map<java.lang.String,java.lang.Object> values)`

#### Parameters

- `values` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `public abstract io.casehub.api.context.CaseContext setPath(java.lang.String path, java.lang.Object value)`

#### Parameters

- `path` (`java.lang.String`)
- `value` (`java.lang.Object`)

### `public abstract int size()`

### `public abstract io.casehub.api.context.CaseContext snapshot()`

### `public abstract io.casehub.api.context.CaseContext update(java.lang.String key, java.util.function.Function<java.lang.Object,java.lang.Object> updateFunction)`

#### Parameters

- `key` (`java.lang.String`)
- `updateFunction` (`java.util.function.Function<java.lang.Object,java.lang.Object>`)
