# io.casehub.api.context.DataRef

**Package:** `io.casehub.api.context`

**Kind:** `record`

Standard reference to externally-stored domain data.

<p>`$dataRef` is a reserved top-level JSON key in the ContextBridge protocol. Domain
objects used with ContextBridge must not contain `$dataRef` as a top-level key. The `$` prefix follows the JSON Schema convention for meta-properties (`$ref`, `$id`,
`$schema`).

<p>Stores the type name as a `String`, not a `Class<?>`. No `Class.forName()`
call occurs at deserialization time — type resolution is deferred to `DataRefRegistry.resolve()`, which validates the type name against registered resolvers before any
class loading.

## Fields

### `DISCRIMINATOR` (`java.lang.String`)

### `key` (`java.lang.String`)

### `source` (`java.lang.String`)

### `typeName` (`java.lang.String`)

## Record Components

### `key` (`java.lang.String`)

the reference key within that source — opaque to the engine

### `source` (`java.lang.String`)

resolver identifier (e.g., "document-store", "ledger", "s3") — matches the `id()` of a `io.casehub.api.spi.DataRefResolver`

### `typeName` (`java.lang.String`)

the fully qualified Java class name this resolves to

## Constructors

### `public DataRef(java.lang.String source, java.lang.String key, java.lang.String typeName)`

#### Parameters

- `source` (`java.lang.String`)
- `key` (`java.lang.String`)
- `typeName` (`java.lang.String`)

## Methods

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public static io.casehub.api.context.DataRef<?> fromJson(JsonNode node)`

#### Parameters

- `node` (`JsonNode`)

### `public final int hashCode()`

### `public static boolean isRef(JsonNode node)`

#### Parameters

- `node` (`JsonNode`)

### `public java.lang.String key()`

### `public static io.casehub.api.context.DataRef<T> of(java.lang.String source, java.lang.String key, java.lang.Class<T> type)`

#### Parameters

- `source` (`java.lang.String`)
- `key` (`java.lang.String`)
- `type` (`java.lang.Class<T>`)

### `public java.lang.String source()`

### `public JsonNode toJson(ObjectMapper mapper)`

#### Parameters

- `mapper` (`ObjectMapper`)

### `public final java.lang.String toString()`

### `public java.lang.String typeName()`
