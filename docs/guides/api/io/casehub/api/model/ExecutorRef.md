# io.casehub.api.model.ExecutorRef

**Package:** `io.casehub.api.model`

**Kind:** `interface`

Shared executor identity across all coordination models.

<p>Implemented by engine's `Worker` (via adapter), blocks' `AgentRef` variants, and
any future executor types.

## Methods

### `public abstract java.lang.String description()`

### `public static io.casehub.api.model.ExecutorRef fromWorker(io.casehub.worker.api.Worker worker)`

#### Parameters

- `worker` (`io.casehub.worker.api.Worker`)

### `public abstract java.lang.String name()`

### `public static io.casehub.api.model.ExecutorRef of(java.lang.String name)`

#### Parameters

- `name` (`java.lang.String`)

### `public static io.casehub.api.model.ExecutorRef of(java.lang.String name, java.lang.String description)`

#### Parameters

- `name` (`java.lang.String`)
- `description` (`java.lang.String`)
