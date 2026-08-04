# io.casehub.api.context.JacksonPojoBridge

**Package:** `io.casehub.api.context`

**Kind:** `class`

## Fields

### `MAPPER` (`ObjectMapper`)

### `targetClass` (`java.lang.Class<T>`)

## Constructors

### `public JacksonPojoBridge(java.lang.Class<T> targetClass)`

#### Parameters

- `targetClass` (`java.lang.Class<T>`)

## Methods

### `public java.lang.Class<T> contextType()`

### `public T deserialise(JsonNode payload)`

#### Parameters

- `payload` (`JsonNode`)

### `public T initialise(io.casehub.api.context.CaseContext context, JsonNode narrowedInput)`

#### Parameters

- `context` (`io.casehub.api.context.CaseContext`)
- `narrowedInput` (`JsonNode`)

### `public JsonNode serialise(T context)`

#### Parameters

- `context` (`T`)
