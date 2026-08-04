# io.casehub.api.model.ProvisionContext

**Package:** `io.casehub.api.model`

**Kind:** `record`

Input to `WorkerProvisioner.provision()`.

<p>Contains all information needed to spin up a new worker for a case: the case identifier, the
declared task type (maps to a capability string), the fully-built `WorkerContext` that will
be injected into the worker's startup prompt, and the propagation context for distributed
tracing. `workerContext` is nullable — callers that have not yet built one may pass `null`.

<p>`tenancyId` identifies the tenant that owns the case being provisioned. Provisioner
implementations use this to resolve tenant-specific endpoints via `EndpointRegistry`.

<p>`triggerChannelId` and `triggerCorrelationId` carry the Qhorus channel ID and
`correlationId` of the COMMAND message that triggered this provisioning, when known. Both
are nullable: engine-internal call sites currently pass `null` because the engine does not
yet receive Qhorus trigger context at the point of provisioning (see engine#231 for the follow-on
work to thread this through the CaseFile-update API). Provisioner implementations that received a
Qhorus COMMAND may use these fields to establish causal linkage in the ledger (see claudony#94).

## Fields

### `caseId` (`java.util.UUID`)

### `propagationContext` (`io.casehub.api.context.PropagationContext`)

### `taskType` (`java.lang.String`)

### `tenancyId` (`java.lang.String`)

### `triggerChannelId` (`java.lang.String`)

### `triggerCorrelationId` (`java.lang.String`)

### `workerContext` (`io.casehub.api.model.WorkerContext`)

### `workerCredentialToken` (`java.lang.String`)

## Record Components

### `caseId` (`java.util.UUID`)

### `propagationContext` (`io.casehub.api.context.PropagationContext`)

### `taskType` (`java.lang.String`)

### `tenancyId` (`java.lang.String`)

### `triggerChannelId` (`java.lang.String`)

### `triggerCorrelationId` (`java.lang.String`)

### `workerContext` (`io.casehub.api.model.WorkerContext`)

### `workerCredentialToken` (`java.lang.String`)

## Constructors

### `public ProvisionContext(java.util.UUID caseId, java.lang.String tenancyId, java.lang.String taskType, io.casehub.api.model.WorkerContext workerContext, io.casehub.api.context.PropagationContext propagationContext, java.lang.String triggerChannelId, java.lang.String triggerCorrelationId, java.lang.String workerCredentialToken)`

#### Parameters

- `caseId` (`java.util.UUID`)
- `tenancyId` (`java.lang.String`)
- `taskType` (`java.lang.String`)
- `workerContext` (`io.casehub.api.model.WorkerContext`)
- `propagationContext` (`io.casehub.api.context.PropagationContext`)
- `triggerChannelId` (`java.lang.String`)
- `triggerCorrelationId` (`java.lang.String`)
- `workerCredentialToken` (`java.lang.String`)

## Methods

### `public java.util.UUID caseId()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public io.casehub.api.context.PropagationContext propagationContext()`

### `public java.lang.String taskType()`

### `public java.lang.String tenancyId()`

### `public final java.lang.String toString()`

### `public java.lang.String triggerChannelId()`

### `public java.lang.String triggerCorrelationId()`

### `public io.casehub.api.model.WorkerContext workerContext()`

### `public java.lang.String workerCredentialToken()`
