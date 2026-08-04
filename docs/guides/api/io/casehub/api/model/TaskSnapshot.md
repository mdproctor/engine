# io.casehub.api.model.TaskSnapshot

**Package:** `io.casehub.api.model`

**Kind:** `record`

Immutable read model projected from any `TaskDescriptor`. Flat, serializable — uses String
executor identity instead of `ExecutorRef` for transport across serialization boundaries.

## Fields

### `createdAt` (`java.time.Instant`)

### `description` (`java.lang.String`)

### `executorDescription` (`java.lang.String`)

### `executorName` (`java.lang.String`)

### `id` (`java.lang.String`)

### `status` (`io.casehub.api.model.TaskStatus`)

## Record Components

### `createdAt` (`java.time.Instant`)

### `description` (`java.lang.String`)

### `executorDescription` (`java.lang.String`)

### `executorName` (`java.lang.String`)

### `id` (`java.lang.String`)

### `status` (`io.casehub.api.model.TaskStatus`)

## Constructors

### `public TaskSnapshot(java.lang.String id, java.lang.String description, java.lang.String executorName, java.lang.String executorDescription, io.casehub.api.model.TaskStatus status, java.time.Instant createdAt)`

#### Parameters

- `id` (`java.lang.String`)
- `description` (`java.lang.String`)
- `executorName` (`java.lang.String`)
- `executorDescription` (`java.lang.String`)
- `status` (`io.casehub.api.model.TaskStatus`)
- `createdAt` (`java.time.Instant`)

## Methods

### `public java.time.Instant createdAt()`

### `public java.lang.String description()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public java.lang.String executorDescription()`

### `public java.lang.String executorName()`

### `public final int hashCode()`

### `public java.lang.String id()`

### `public io.casehub.api.model.TaskStatus status()`

### `public final java.lang.String toString()`
