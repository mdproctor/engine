# io.casehub.api.spi.routing.AgentHealth

**Package:** `io.casehub.api.spi.routing`

**Kind:** `enum`

Pre-probed agent health status, mapped from `casehub-eidos-api` `CapabilityStatus` at
candidate construction time.

<p>`UNAVAILABLE` workers are filtered before the candidate list is built — they never reach
`AgentRoutingStrategy.select`. This enum exists so `casehub-engine-api` does not take
a compile-time dependency on `casehub-eidos-api`.

## Enum Constants

### `DEGRADED` (`io.casehub.api.spi.routing.AgentHealth`)

Agent is available but operating in a degraded state — keep, consider demoting.

### `EPISTEMICALLY_WEAK` (`io.casehub.api.spi.routing.AgentHealth`)

Agent's epistemic coverage for this capability is uncertain — keep, consider demoting.

### `READY` (`io.casehub.api.spi.routing.AgentHealth`)

Agent is available and operating normally.

## Constructors

### `private AgentHealth()`

## Methods

### `public static io.casehub.api.spi.routing.AgentHealth valueOf(java.lang.String name)`

#### Parameters

- `name` (`java.lang.String`)

### `public static io.casehub.api.spi.routing.AgentHealth[] values()`
