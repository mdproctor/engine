# io.casehub.api.model.event.ExecutionOrigin

**Package:** `io.casehub.api.model.event`

**Kind:** `enum`

Provenance metadata for worker executions. Tags EventLog entries with the origination path that
triggered the worker execution.

## Enum Constants

### `BINDING_DISPATCH` (`io.casehub.api.model.event.ExecutionOrigin`)

Worker execution triggered by a capability binding dispatch (standard case-driven path).

### `RECOVERY` (`io.casehub.api.model.event.ExecutionOrigin`)

Worker execution triggered by recovery coordinator (restart/resume operations).

### `SCHEDULE_TRIGGER` (`io.casehub.api.model.event.ExecutionOrigin`)

Worker execution triggered by a scheduled timer or cron trigger.

### `SIGNAL` (`io.casehub.api.model.event.ExecutionOrigin`)

Worker execution triggered by an explicit signal to the case.

### `SUBCASE_COMPLETION` (`io.casehub.api.model.event.ExecutionOrigin`)

Worker execution triggered by sub-case completion.

## Constructors

### `private ExecutionOrigin()`

## Methods

### `public static io.casehub.api.model.event.ExecutionOrigin valueOf(java.lang.String name)`

#### Parameters

- `name` (`java.lang.String`)

### `public static io.casehub.api.model.event.ExecutionOrigin[] values()`
