# io.casehub.api.spi.StepOutcomeEvent

**Package:** `io.casehub.api.spi`

**Kind:** `record`

Outcome event fired by the engine after each worker execution step completes — on both success
and failure paths. Delivered to all `StepOutcomeObserver` beans discovered via CDI.

<p>`contextSnapshot` is the working layer at step execution time — on the success path,
captured <em>before</em> output application (the conditions under which the decision was made,
not the world after execution). On the failure path, captured at failure handling time (no output
was applied).

<p>Refs casehubio/engine#1050.

## Fields

### `bindingName` (`java.lang.String`)

### `capabilityName` (`java.lang.String`)

### `caseId` (`java.util.UUID`)

### `caseType` (`java.lang.String`)

### `contextSnapshot` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `executionDuration` (`java.time.Duration`)

### `outcome` (`io.casehub.api.spi.routing.RoutingOutcome`)

### `tenancyId` (`java.lang.String`)

### `workerName` (`java.lang.String`)

## Record Components

### `bindingName` (`java.lang.String`)

the case definition binding that dispatched the worker

### `capabilityName` (`java.lang.String`)

the capability targeted by this binding; nullable for JudgmentTarget traces

### `caseId` (`java.util.UUID`)

case instance UUID

### `caseType` (`java.lang.String`)

case definition name — consumer uses this to find their CaseDefinition/CbrConfig

### `contextSnapshot` (`java.util.Map<java.lang.String,java.lang.Object>`)

working-layer context at step execution time; non-null, may be empty

### `executionDuration` (`java.time.Duration`)

wall-clock duration of the worker execution; nullable

### `outcome` (`io.casehub.api.spi.routing.RoutingOutcome`)

the routing outcome (SUCCESS or FAILURE)

### `tenancyId` (`java.lang.String`)

tenant identifier owning the case

### `workerName` (`java.lang.String`)

the worker that executed

## Constructors

### `public StepOutcomeEvent(java.util.UUID caseId, java.lang.String tenancyId, java.lang.String caseType, java.lang.String bindingName, java.lang.String capabilityName, java.lang.String workerName, io.casehub.api.spi.routing.RoutingOutcome outcome, java.util.Map<java.lang.String,java.lang.Object> contextSnapshot, java.time.Duration executionDuration)`

#### Parameters

- `caseId` (`java.util.UUID`)
- `tenancyId` (`java.lang.String`)
- `caseType` (`java.lang.String`)
- `bindingName` (`java.lang.String`)
- `capabilityName` (`java.lang.String`)
- `workerName` (`java.lang.String`)
- `outcome` (`io.casehub.api.spi.routing.RoutingOutcome`)
- `contextSnapshot` (`java.util.Map<java.lang.String,java.lang.Object>`)
- `executionDuration` (`java.time.Duration`)

## Methods

### `public java.lang.String bindingName()`

### `public java.lang.String capabilityName()`

### `public java.util.UUID caseId()`

### `public java.lang.String caseType()`

### `public java.util.Map<java.lang.String,java.lang.Object> contextSnapshot()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public java.time.Duration executionDuration()`

### `public final int hashCode()`

### `public io.casehub.api.spi.routing.RoutingOutcome outcome()`

### `public java.lang.String tenancyId()`

### `public final java.lang.String toString()`

### `public java.lang.String workerName()`
