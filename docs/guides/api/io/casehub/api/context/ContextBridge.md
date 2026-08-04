# io.casehub.api.context.ContextBridge

**Package:** `io.casehub.api.context`

**Kind:** `interface`

## Methods

### `public abstract java.lang.Class<T> contextType()`

### `public abstract T deserialise(JsonNode payload)`

#### Parameters

- `payload` (`JsonNode`)

### `public default java.util.Map<java.lang.String,java.lang.Object> extractOutput(T context)`

#### Parameters

- `context` (`T`)

### `public abstract T initialise(io.casehub.api.context.CaseContext context, JsonNode narrowedInput)`

#### Parameters

- `context` (`io.casehub.api.context.CaseContext`)
- `narrowedInput` (`JsonNode`)

### `public default boolean isLiveView()`

### `public default void onWrite(java.lang.String key, java.lang.Object value, io.casehub.api.context.CaseContext enclosing)`

#### Parameters

- `key` (`java.lang.String`)
- `value` (`java.lang.Object`)
- `enclosing` (`io.casehub.api.context.CaseContext`)

### `public abstract JsonNode serialise(T context)`

#### Parameters

- `context` (`T`)
