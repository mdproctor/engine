# io.casehub.api.spi.ProvisionResult

**Package:** `io.casehub.api.spi`

**Kind:** `record`

Outcome of a successful `WorkerProvisioner.provision` or `WorkerProvisioner.provision` call.

<p>`causedByEntryId` is the ledger entry ID of the COMMAND that triggered provisioning.
Provisioner implementations that can resolve this ID (e.g. by correlating against a Qhorus
message ledger) set it here; implementations that cannot leave it `null`. The engine passes
it through to the audit event so that ledger observers can establish causal linkage without
round-tripping through the engine's internal state.

<p>Will be non-null only after engine#231 threads Qhorus trigger context (channelId +
correlationId) through `io.casehub.api.model.ProvisionContext`.

<p>`resolvedWorkerId` is the identifier of the provisioned worker instance. This field
eliminates the workerName==agentId convention. Provisioner implementations that can resolve the
worker instance ID set it here; implementations that cannot leave it `null`. See
engine#760.

## Fields

### `causedByEntryId` (`java.util.UUID`)

### `resolvedWorkerId` (`java.lang.String`)

## Record Components

### `causedByEntryId` (`java.util.UUID`)

### `resolvedWorkerId` (`java.lang.String`)

## Constructors

### `public ProvisionResult(java.util.UUID causedByEntryId, java.lang.String resolvedWorkerId)`

#### Parameters

- `causedByEntryId` (`java.util.UUID`)
- `resolvedWorkerId` (`java.lang.String`)

## Methods

### `public java.util.UUID causedByEntryId()`

### `public static io.casehub.api.spi.ProvisionResult empty()`

Convenience factory for provisioners that do not resolve a causal ledger entry.

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public java.lang.String resolvedWorkerId()`

### `public final java.lang.String toString()`
