# io.casehub.api.context.MapBridge

**Package:** `io.casehub.api.context`

**Kind:** `class`

## Fields

### `MAPPER` (`ObjectMapper`)

### `MAP_TYPE` (`TypeReference<java.util.Map<java.lang.String,java.lang.Object>>`)

## Constructors

### `public MapBridge()`

## Methods

### `public java.lang.Class<java.util.Map<java.lang.String,java.lang.Object>> contextType()`

### `public java.util.Map<java.lang.String,java.lang.Object> deserialise(JsonNode payload)`

#### Parameters

- `payload` (`JsonNode`)

### `public java.util.Map<java.lang.String,java.lang.Object> initialise(io.casehub.api.context.CaseContext context, JsonNode narrowedInput)`

#### Parameters

- `context` (`io.casehub.api.context.CaseContext`)
- `narrowedInput` (`JsonNode`)

### `public JsonNode serialise(java.util.Map<java.lang.String,java.lang.Object> context)`

#### Parameters

- `context` (`java.util.Map<java.lang.String,java.lang.Object>`)
