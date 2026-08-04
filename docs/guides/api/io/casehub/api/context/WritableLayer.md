# io.casehub.api.context.WritableLayer

**Package:** `io.casehub.api.context`

**Kind:** `interface`

## Methods

### `public abstract java.util.Optional<JsonNode> applyAndDiff(java.lang.String path, java.lang.Object value)`

#### Parameters

- `path` (`java.lang.String`)
- `value` (`java.lang.Object`)

### `public abstract void applyDiff(JsonNode diff)`

#### Parameters

- `diff` (`JsonNode`)

### `public abstract io.casehub.api.context.WritableLayer clear()`

### `public abstract boolean compareAndSet(java.lang.String key, java.lang.Object expected, java.lang.Object newValue)`

#### Parameters

- `key` (`java.lang.String`)
- `expected` (`java.lang.Object`)
- `newValue` (`java.lang.Object`)

### `public abstract java.lang.Object computeIfAbsent(java.lang.String key, java.util.function.Function<java.lang.String,java.lang.Object> mappingFunction)`

#### Parameters

- `key` (`java.lang.String`)
- `mappingFunction` (`java.util.function.Function<java.lang.String,java.lang.Object>`)

### `public abstract JsonNode diff(io.casehub.api.context.ReadableLayer other)`

#### Parameters

- `other` (`io.casehub.api.context.ReadableLayer`)

### `public abstract io.casehub.api.context.WritableLayer merge(io.casehub.api.context.ReadableLayer other)`

#### Parameters

- `other` (`io.casehub.api.context.ReadableLayer`)

### `public abstract java.lang.Object putIfAbsent(java.lang.String key, java.lang.Object value)`

#### Parameters

- `key` (`java.lang.String`)
- `value` (`java.lang.Object`)

### `public abstract io.casehub.api.context.WritableLayer remove(java.lang.String key)`

#### Parameters

- `key` (`java.lang.String`)

### `public abstract io.casehub.api.context.WritableLayer set(java.lang.String key, java.lang.Object value)`

#### Parameters

- `key` (`java.lang.String`)
- `value` (`java.lang.Object`)

### `public abstract io.casehub.api.context.WritableLayer setAll(java.util.Map<java.lang.String,java.lang.Object> values)`

#### Parameters

- `values` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `public abstract io.casehub.api.context.WritableLayer setPath(java.lang.String path, java.lang.Object value)`

#### Parameters

- `path` (`java.lang.String`)
- `value` (`java.lang.Object`)

### `public abstract io.casehub.api.context.WritableLayer update(java.lang.String key, java.util.function.Function<java.lang.Object,java.lang.Object> updateFunction)`

#### Parameters

- `key` (`java.lang.String`)
- `updateFunction` (`java.util.function.Function<java.lang.Object,java.lang.Object>`)
