# io.casehub.api.model.WorkRequest

**Package:** `io.casehub.api.model`

**Kind:** `record`

Input to `WorkOrchestrator.submit()`. Describes the capability required and the data to
pass to the selected worker.

## Fields

### `capability` (`java.lang.String`)

### `input` (`java.util.Map<java.lang.String,java.lang.Object>`)

## Record Components

### `capability` (`java.lang.String`)

### `input` (`java.util.Map<java.lang.String,java.lang.Object>`)

## Constructors

### `public WorkRequest(java.lang.String capability, java.util.Map<java.lang.String,java.lang.Object> input)`

#### Parameters

- `capability` (`java.lang.String`)
- `input` (`java.util.Map<java.lang.String,java.lang.Object>`)

## Methods

### `public java.lang.String capability()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public java.util.Map<java.lang.String,java.lang.Object> input()`

### `public static io.casehub.api.model.WorkRequest of(java.lang.String capability, java.util.Map<java.lang.String,java.lang.Object> input)`

#### Parameters

- `capability` (`java.lang.String`)
- `input` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `public final java.lang.String toString()`
