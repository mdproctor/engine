# io.casehub.api.model.OutcomeAction

**Package:** `io.casehub.api.model`

**Kind:** `enum`

Action the engine takes when a worker returns a non-success `WorkerOutcome`.

<ul>
  <li>`REROUTE` — write failure state, exclude the agent, re-dispatch to a different agent
  <li>`FAULT` — mark the case FAULTED immediately
</ul>

## Enum Constants

### `FAULT` (`io.casehub.api.model.OutcomeAction`)

### `REROUTE` (`io.casehub.api.model.OutcomeAction`)

## Constructors

### `private OutcomeAction()`

## Methods

### `public static io.casehub.api.model.OutcomeAction valueOf(java.lang.String name)`

#### Parameters

- `name` (`java.lang.String`)

### `public static io.casehub.api.model.OutcomeAction[] values()`
