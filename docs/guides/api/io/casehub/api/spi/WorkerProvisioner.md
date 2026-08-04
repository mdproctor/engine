# io.casehub.api.spi.WorkerProvisioner

**Package:** `io.casehub.api.spi`

**Kind:** `interface`

Provisions and terminates workers for CaseEngine.

<p>Called when a PlanItem is eligible but no workers with the required capabilities are
available. Implementations spin up actual compute: a tmux session (Claudony), a Docker container,
a Nono sandbox, etc.

<p>Implementations are CDI beans (`@ApplicationScoped`). The default no-op throws `ProvisioningException` to signal misconfiguration.

## Methods

### `public abstract java.util.Set<java.lang.String> getCapabilities()`

Returns the capability tags this provisioner can supply. Used by CaseEngine to decide whether
to call this provisioner.

### `public abstract io.casehub.api.spi.ProvisionResult provision(java.util.Set<java.lang.String> capabilities, io.casehub.api.model.ProvisionContext context)`

Provision a new worker with the given capabilities.

#### Parameters

- `capabilities` (`java.util.Set<java.lang.String>`) — required capability set for the PlanItem
- `context` (`io.casehub.api.model.ProvisionContext`) — case context, pre-built worker context, and propagation

#### Returns

the provisioning outcome, including optional causal ledger entry linkage

#### Throws

- `ProvisioningException` — if the worker cannot be started

### `public abstract void terminate(java.lang.String workerId, java.lang.String tenancyId)`

Terminate a previously provisioned worker. No-op if the worker is unknown.

#### Parameters

- `workerId` (`java.lang.String`) — the worker name as returned by `Worker.getName()`
- `tenancyId` (`java.lang.String`) — the tenant that owns the case — avoids ambiguous lookups when the same
    workerId is used across tenants
