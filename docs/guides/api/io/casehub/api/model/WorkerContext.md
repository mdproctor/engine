# io.casehub.api.model.WorkerContext

**Package:** `io.casehub.api.model`

**Kind:** `record`

Context handed to a new worker at startup.

<p>Built by `WorkerContextProvider` from CaseLedgerEntry history. Contains the task
description, the case identifier, the channels open for the case, ordered summaries of prior
workers, the propagation context for tracing, arbitrary backend-specific properties, and
retrieved CBR experiences from similar past cases.

<p>`channels`, `priorWorkers`, `properties`, and `experiences` default to
empty collections when `null` is supplied and are always immutable.

## Fields

### `caseId` (`java.util.UUID`)

### `channels` (`java.util.List<io.casehub.api.model.CaseChannel>`)

### `experiences` (`java.util.List<io.casehub.api.spi.routing.RetrievedExperience>`)

### `priorWorkers` (`java.util.List<io.casehub.api.model.WorkerSummary>`)

### `propagationContext` (`io.casehub.api.context.PropagationContext`)

### `properties` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `taskDescription` (`java.lang.String`)

## Record Components

### `caseId` (`java.util.UUID`)

### `channels` (`java.util.List<io.casehub.api.model.CaseChannel>`)

### `experiences` (`java.util.List<io.casehub.api.spi.routing.RetrievedExperience>`)

### `priorWorkers` (`java.util.List<io.casehub.api.model.WorkerSummary>`)

### `propagationContext` (`io.casehub.api.context.PropagationContext`)

### `properties` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `taskDescription` (`java.lang.String`)

## Constructors

### `public WorkerContext(java.lang.String taskDescription, java.util.UUID caseId, java.util.List<io.casehub.api.model.CaseChannel> channels, java.util.List<io.casehub.api.model.WorkerSummary> priorWorkers, io.casehub.api.context.PropagationContext propagationContext, java.util.Map<java.lang.String,java.lang.Object> properties)`

#### Parameters

- `taskDescription` (`java.lang.String`)
- `caseId` (`java.util.UUID`)
- `channels` (`java.util.List<io.casehub.api.model.CaseChannel>`)
- `priorWorkers` (`java.util.List<io.casehub.api.model.WorkerSummary>`)
- `propagationContext` (`io.casehub.api.context.PropagationContext`)
- `properties` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `public WorkerContext(java.lang.String taskDescription, java.util.UUID caseId, java.util.List<io.casehub.api.model.CaseChannel> channels, java.util.List<io.casehub.api.model.WorkerSummary> priorWorkers, io.casehub.api.context.PropagationContext propagationContext, java.util.Map<java.lang.String,java.lang.Object> properties, java.util.List<io.casehub.api.spi.routing.RetrievedExperience> experiences)`

#### Parameters

- `taskDescription` (`java.lang.String`)
- `caseId` (`java.util.UUID`)
- `channels` (`java.util.List<io.casehub.api.model.CaseChannel>`)
- `priorWorkers` (`java.util.List<io.casehub.api.model.WorkerSummary>`)
- `propagationContext` (`io.casehub.api.context.PropagationContext`)
- `properties` (`java.util.Map<java.lang.String,java.lang.Object>`)
- `experiences` (`java.util.List<io.casehub.api.spi.routing.RetrievedExperience>`)

## Methods

### `public java.util.UUID caseId()`

### `public java.util.List<io.casehub.api.model.CaseChannel> channels()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public java.util.List<io.casehub.api.spi.routing.RetrievedExperience> experiences()`

### `public final int hashCode()`

### `public java.util.List<io.casehub.api.model.WorkerSummary> priorWorkers()`

### `public io.casehub.api.context.PropagationContext propagationContext()`

### `public java.util.Map<java.lang.String,java.lang.Object> properties()`

### `public java.lang.String taskDescription()`

### `public final java.lang.String toString()`
