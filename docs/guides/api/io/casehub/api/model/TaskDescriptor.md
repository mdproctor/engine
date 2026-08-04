# io.casehub.api.model.TaskDescriptor

**Package:** `io.casehub.api.model`

**Kind:** `interface`

Shared behavioral interface for any coordination model's unit of work. Implemented by engine's
`PlanItem` and (deferred) blocks' `PlannedTask`.

## Methods

### `public abstract java.time.Instant createdAt()`

### `public abstract java.lang.String description()`

### `public abstract io.casehub.api.model.ExecutorRef executor()`

### `public abstract java.lang.String id()`

### `public default io.casehub.api.model.TaskSnapshot snapshot()`

### `public abstract io.casehub.api.model.TaskStatus status()`
