# io.casehub.api.engine.PlanExecutionContext

**Package:** `io.casehub.api.engine`

**Kind:** `record`

Context passed to `LoopControl.select` — carries case identity, definition, context, and
current `CaseStatus`, enabling LoopControl implementations to decide both which bindings to
fire and whether to evaluate at all for the given case state.

## Fields

### `caseContext` (`io.casehub.api.context.CaseContext`)

### `caseId` (`java.util.UUID`)

### `caseStatus` (`io.casehub.api.model.CaseStatus`)

### `definition` (`io.casehub.api.model.CaseDefinition`)

### `experiences` (`java.util.List<io.casehub.api.spi.routing.RetrievedExperience>`)

### `origin` (`io.casehub.api.model.event.ExecutionOrigin`)

### `retryState` (`io.casehub.api.model.RetryState`)

### `tenancyId` (`java.lang.String`)

## Record Components

### `caseContext` (`io.casehub.api.context.CaseContext`)

the case runtime context

### `caseId` (`java.util.UUID`)

the case instance UUID

### `caseStatus` (`io.casehub.api.model.CaseStatus`)

the case status

### `definition` (`io.casehub.api.model.CaseDefinition`)

the case type definition

### `experiences` (`java.util.List<io.casehub.api.spi.routing.RetrievedExperience>`)

retrieved similar cases from CBR (empty list if CBR is not configured or no
    matches found)

### `origin` (`io.casehub.api.model.event.ExecutionOrigin`)

provenance metadata — the execution path that triggered this plan evaluation
    (nullable)

### `retryState` (`io.casehub.api.model.RetryState`)

retry attempt history for the current execution (nullable — present only when
    the current execution is a retry)

### `tenancyId` (`java.lang.String`)

the tenant that owns this case

## Constructors

### `public PlanExecutionContext(java.util.UUID caseId, io.casehub.api.model.CaseDefinition definition, io.casehub.api.context.CaseContext caseContext, io.casehub.api.model.CaseStatus caseStatus, java.lang.String tenancyId, java.util.List<io.casehub.api.spi.routing.RetrievedExperience> experiences, io.casehub.api.model.event.ExecutionOrigin origin, io.casehub.api.model.RetryState retryState)`

#### Parameters

- `caseId` (`java.util.UUID`)
- `definition` (`io.casehub.api.model.CaseDefinition`)
- `caseContext` (`io.casehub.api.context.CaseContext`)
- `caseStatus` (`io.casehub.api.model.CaseStatus`)
- `tenancyId` (`java.lang.String`)
- `experiences` (`java.util.List<io.casehub.api.spi.routing.RetrievedExperience>`)
- `origin` (`io.casehub.api.model.event.ExecutionOrigin`)
- `retryState` (`io.casehub.api.model.RetryState`)

## Methods

### `public io.casehub.api.context.CaseContext caseContext()`

### `public java.util.UUID caseId()`

### `public io.casehub.api.model.CaseStatus caseStatus()`

### `public io.casehub.api.model.CaseDefinition definition()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public java.util.List<io.casehub.api.spi.routing.RetrievedExperience> experiences()`

### `public final int hashCode()`

### `public io.casehub.api.model.event.ExecutionOrigin origin()`

### `public io.casehub.api.model.RetryState retryState()`

### `public java.lang.String tenancyId()`

### `public final java.lang.String toString()`
