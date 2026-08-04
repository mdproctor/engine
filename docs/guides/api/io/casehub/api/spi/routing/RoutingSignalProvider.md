# io.casehub.api.spi.routing.RoutingSignalProvider

**Package:** `io.casehub.api.spi.routing`

**Kind:** `interface`

## Methods

### `public abstract io.casehub.api.spi.routing.RoutingSignal evaluate(io.casehub.api.spi.routing.AgentRoutingContext context, java.util.List<io.casehub.api.spi.routing.AgentCandidate> eligible)`

Compute per-candidate scoring signals for the given routing context and eligible candidates.

<p>All scores must be in [0.0, 1.0] — `RoutingSignalAssembler` clamps out-of-range values
and logs a warning.

#### Parameters

- `context` (`io.casehub.api.spi.routing.AgentRoutingContext`) — the routing context carrying caseId, capabilityName, experiences, etc.
- `eligible` (`java.util.List<io.casehub.api.spi.routing.AgentCandidate>`) — the pre-filtered, health-probed candidate list

#### Returns

a signal with per-candidate scores, or `null` if this provider has nothing to
    contribute
