# io.casehub.api.model.WorkResult

**Package:** `io.casehub.api.model`

**Kind:** `record`

Result of orchestrated work returned by `WorkOrchestrator.submit()`. The `correlationKey` is the idempotency hash used to match this result to its submission.

<p>`caseId` is set when the result is produced by the case engine and identifies the case
that owned the worker. Listeners can use it for precise per-case lookups. Null when the result is
produced outside the engine context (e.g. direct WorkOrchestrator calls).

## Fields

### `caseId` (`java.util.UUID`)

### `correlationKey` (`java.lang.String`)

### `output` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `status` (`io.casehub.api.model.WorkStatus`)

### `workerId` (`java.lang.String`)

## Record Components

### `caseId` (`java.util.UUID`)

### `correlationKey` (`java.lang.String`)

### `output` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `status` (`io.casehub.api.model.WorkStatus`)

### `workerId` (`java.lang.String`)

## Constructors

### `public WorkResult(java.lang.String correlationKey, io.casehub.api.model.WorkStatus status, java.util.Map<java.lang.String,java.lang.Object> output, java.lang.String workerId, java.util.UUID caseId)`

#### Parameters

- `correlationKey` (`java.lang.String`)
- `status` (`io.casehub.api.model.WorkStatus`)
- `output` (`java.util.Map<java.lang.String,java.lang.Object>`)
- `workerId` (`java.lang.String`)
- `caseId` (`java.util.UUID`)

## Methods

### `public java.util.UUID caseId()`

### `public static io.casehub.api.model.WorkResult completed(java.lang.String correlationKey, java.util.Map<java.lang.String,java.lang.Object> output, java.lang.String workerId)`

#### Parameters

- `correlationKey` (`java.lang.String`)
- `output` (`java.util.Map<java.lang.String,java.lang.Object>`)
- `workerId` (`java.lang.String`)

### `public static io.casehub.api.model.WorkResult completed(java.lang.String correlationKey, java.util.Map<java.lang.String,java.lang.Object> output, java.lang.String workerId, java.util.UUID caseId)`

#### Parameters

- `correlationKey` (`java.lang.String`)
- `output` (`java.util.Map<java.lang.String,java.lang.Object>`)
- `workerId` (`java.lang.String`)
- `caseId` (`java.util.UUID`)

### `public java.lang.String correlationKey()`

### `public static io.casehub.api.model.WorkResult declined(java.lang.String correlationKey, java.lang.String workerId, java.util.UUID caseId)`

#### Parameters

- `correlationKey` (`java.lang.String`)
- `workerId` (`java.lang.String`)
- `caseId` (`java.util.UUID`)

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public static io.casehub.api.model.WorkResult expired(java.lang.String correlationKey, java.lang.String workerId, java.util.UUID caseId)`

#### Parameters

- `correlationKey` (`java.lang.String`)
- `workerId` (`java.lang.String`)
- `caseId` (`java.util.UUID`)

### `public static io.casehub.api.model.WorkResult failed(java.lang.String correlationKey, java.lang.String workerId, java.util.UUID caseId)`

#### Parameters

- `correlationKey` (`java.lang.String`)
- `workerId` (`java.lang.String`)
- `caseId` (`java.util.UUID`)

### `public static io.casehub.api.model.WorkResult faulted(java.lang.String correlationKey, java.lang.String workerId)`

#### Parameters

- `correlationKey` (`java.lang.String`)
- `workerId` (`java.lang.String`)

### `public static io.casehub.api.model.WorkResult faulted(java.lang.String correlationKey, java.lang.String workerId, java.util.UUID caseId)`

#### Parameters

- `correlationKey` (`java.lang.String`)
- `workerId` (`java.lang.String`)
- `caseId` (`java.util.UUID`)

### `public final int hashCode()`

### `public java.util.Map<java.lang.String,java.lang.Object> output()`

### `public io.casehub.api.model.WorkStatus status()`

### `public final java.lang.String toString()`

### `public java.lang.String workerId()`
