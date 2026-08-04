# io.casehub.api.model.CaseStatus

**Package:** `io.casehub.api.model`

**Kind:** `enum`

Lifecycle states for a `CaseInstance`.

<p>Aligned with `io.serverlessworkflow.impl.WorkflowStatus` as used by Quarkus Flow and the
CNCF Serverless Workflow specification.

<p>`PENDING` is intentionally absent: the async event cycle transitions a case directly to
`RUNNING` on creation. PENDING semantics for plan items and stages are managed by the
`casehub-blackboard` module, not by `CaseInstance`.

## Enum Constants

### `CANCELLED` (`io.casehub.api.model.CaseStatus`)

Case was stopped before completion.

### `COMPLETED` (`io.casehub.api.model.CaseStatus`)

Case completed successfully.

### `FAULTED` (`io.casehub.api.model.CaseStatus`)

Case terminated due to an error.

### `RUNNING` (`io.casehub.api.model.CaseStatus`)

Case is actively executing.

### `STARTING` (`io.casehub.api.model.CaseStatus`)

Case is initializing — cached but event handlers have not yet completed.

### `SUSPENDED` (`io.casehub.api.model.CaseStatus`)

Case has been paused by an administrative action.

### `WAITING` (`io.casehub.api.model.CaseStatus`)

Case is blocked waiting for an external event or signal.

## Constructors

### `private CaseStatus()`

## Methods

### `public static io.casehub.api.model.CaseStatus valueOf(java.lang.String name)`

#### Parameters

- `name` (`java.lang.String`)

### `public static io.casehub.api.model.CaseStatus[] values()`
