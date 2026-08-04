# io.casehub.api.model.OutcomeKind

**Package:** `io.casehub.api.model`

**Kind:** `enum`

Shared outcome taxonomy across all coordination models. Maps to `WorkerOutcome` variants
(engine) and replaces `AgentResultStatus` (blocks).

## Enum Constants

### `COMPLETED` (`io.casehub.api.model.OutcomeKind`)

### `DECLINED` (`io.casehub.api.model.OutcomeKind`)

### `ESCALATED` (`io.casehub.api.model.OutcomeKind`)

### `EXPIRED` (`io.casehub.api.model.OutcomeKind`)

### `FAILED` (`io.casehub.api.model.OutcomeKind`)

### `SUCCESS` (`io.casehub.api.model.OutcomeKind`)

## Constructors

### `private OutcomeKind()`

## Methods

### `public static io.casehub.api.model.OutcomeKind fromWorkerOutcome(WorkerOutcome outcome)`

#### Parameters

- `outcome` (`WorkerOutcome`)

### `public boolean isTerminal()`

### `public static io.casehub.api.model.OutcomeKind valueOf(java.lang.String name)`

#### Parameters

- `name` (`java.lang.String`)

### `public static io.casehub.api.model.OutcomeKind[] values()`
