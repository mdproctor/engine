# io.casehub.api.model.ConflictResolver

**Package:** `io.casehub.api.model`

**Kind:** `class`

## Fields

### `DEEP_MERGE` (`java.lang.String`)

### `FAIL` (`java.lang.String`)

### `FIRST_WRITER_WINS` (`java.lang.String`)

### `LAST_WRITER_WINS` (`java.lang.String`)

## Constructors

### `private ConflictResolver()`

## Methods

### `private static java.lang.Object deepMerge(java.lang.Object existing, java.lang.Object incoming)`

#### Parameters

- `existing` (`java.lang.Object`)
- `incoming` (`java.lang.Object`)

### `public static java.lang.Object resolve(java.lang.String strategy, java.lang.String key, java.lang.Object existing, java.lang.Object incoming)`

#### Parameters

- `strategy` (`java.lang.String`)
- `key` (`java.lang.String`)
- `existing` (`java.lang.Object`)
- `incoming` (`java.lang.Object`)
