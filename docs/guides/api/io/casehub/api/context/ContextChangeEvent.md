# io.casehub.api.context.ContextChangeEvent

**Package:** `io.casehub.api.context`

**Kind:** `record`

Event fired when a key in the working layer of a `CaseContext` is changed via the flat API
(`set()`, `setAll()`, `remove()`, etc.).

<p>`oldValue` is captured atomically with the write — no TOCTOU race. `oldValue` is
`null` when a key is created; `newValue` is `null` when a key is removed.

## Fields

### `key` (`java.lang.String`)

### `newValue` (`java.lang.Object`)

### `oldValue` (`java.lang.Object`)

## Record Components

### `key` (`java.lang.String`)

the context key that changed

### `newValue` (`java.lang.Object`)

the new value (may be `null`)

### `oldValue` (`java.lang.Object`)

the previous value (may be `null`)

## Constructors

### `public ContextChangeEvent(java.lang.String key, java.lang.Object oldValue, java.lang.Object newValue)`

#### Parameters

- `key` (`java.lang.String`)
- `oldValue` (`java.lang.Object`)
- `newValue` (`java.lang.Object`)

## Methods

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public java.lang.String key()`

### `public java.lang.Object newValue()`

### `public java.lang.Object oldValue()`

### `public final java.lang.String toString()`
