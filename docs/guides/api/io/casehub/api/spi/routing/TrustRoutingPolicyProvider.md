# io.casehub.api.spi.routing.TrustRoutingPolicyProvider

**Package:** `io.casehub.api.spi.routing`

**Kind:** `interface`

SPI for resolving the routing policy to apply for a given capability.

<p>Deployments override with `@ApplicationScoped @Alternative @Priority(1)` to provide
per-capability policies. For example, devtown's `DevtownCapabilityRegistry` can implement
this interface to expose its per-capability routing configuration.

<p>The default implementation returns `io.casehub.api.spi.routing.TrustRoutingPolicy.DEFAULT` for all capabilities.

## Methods

### `public abstract io.casehub.api.spi.routing.TrustRoutingPolicy forCapability(java.lang.String capabilityName)`

Return the routing policy for the given capability name. Never returns null — use `io.casehub.api.spi.routing.TrustRoutingPolicy.DEFAULT` as the fallback.

#### Parameters

- `capabilityName` (`java.lang.String`)
