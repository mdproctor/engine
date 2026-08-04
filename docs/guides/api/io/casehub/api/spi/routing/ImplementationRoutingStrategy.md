# io.casehub.api.spi.routing.ImplementationRoutingStrategy

**Package:** `io.casehub.api.spi.routing`

**Kind:** `interface`

Selects which implementation(s) handle a capability when multiple bindings target the same
capability. Symmetric to `AgentRoutingStrategy` which selects which worker instance handles
a task.

<p>Returns `Uni` per protocol PP-20260529-9f9627 — implementations may perform blocking I/O
(trust lookups, external classification).

<p>Refs casehubio/engine#476.

## Methods

### `public abstract io.casehub.api.spi.routing.ImplementationSelection select(io.casehub.api.spi.routing.ImplementationRoutingContext context, java.util.List<io.casehub.api.spi.routing.ImplementationCandidate> candidates)`

#### Parameters

- `context` (`io.casehub.api.spi.routing.ImplementationRoutingContext`)
- `candidates` (`java.util.List<io.casehub.api.spi.routing.ImplementationCandidate>`)
