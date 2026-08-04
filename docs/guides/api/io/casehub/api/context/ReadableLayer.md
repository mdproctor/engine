# io.casehub.api.context.ReadableLayer

**Package:** `io.casehub.api.context`

**Kind:** `interface`

## Methods

### `public abstract JsonNode asJsonNode()`

### `public abstract boolean contains(java.lang.String key)`

#### Parameters

- `key` (`java.lang.String`)

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

### `public abstract boolean isReadOnly()`

### `public abstract java.lang.String layerName()`

### `public abstract int size()`

### `public abstract io.casehub.api.context.ReadableLayer snapshot()`
