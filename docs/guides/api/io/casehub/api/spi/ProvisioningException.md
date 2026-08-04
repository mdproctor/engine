# io.casehub.api.spi.ProvisioningException

**Package:** `io.casehub.api.spi`

**Kind:** `class`

Thrown by `WorkerProvisioner.provision()` when a worker cannot be started.

<p>Unchecked — callers decide whether to catch and retry or propagate. Common causes include
resource exhaustion (no available tmux sessions, no Docker capacity), network failures when
reaching a remote provisioner, or configuration errors.

## Constructors

### `public ProvisioningException(java.lang.String message)`

#### Parameters

- `message` (`java.lang.String`)

### `public ProvisioningException(java.lang.String message, java.lang.Throwable cause)`

#### Parameters

- `message` (`java.lang.String`)
- `cause` (`java.lang.Throwable`)
