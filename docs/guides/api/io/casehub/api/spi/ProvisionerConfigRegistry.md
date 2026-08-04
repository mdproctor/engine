# io.casehub.api.spi.ProvisionerConfigRegistry

**Package:** `io.casehub.api.spi`

**Kind:** `interface`

Service provider interface for provisioner configuration lookup. Enables per-agent, per-provider
configuration resolution — consumed by worker provisioners across the platform (Claudony, ops,
OpenClaw).

## Methods

### `public abstract java.util.Map<java.lang.String,java.lang.Object> configFor(java.lang.String providerName, java.lang.String agentId)`

Retrieve configuration for a specific agent under a provider.

#### Parameters

- `providerName` (`java.lang.String`) — provider name (e.g., "claudony-casehub", "ops-provisioner")
- `agentId` (`java.lang.String`) — agent identifier (e.g., "code-reviewer", "code-security-auditor")

#### Returns

configuration map; empty if agent not found or provider has no registry

### `public abstract java.util.Set<java.lang.String> declaredAgentIds(java.lang.String providerName)`

List all agent IDs known to this provider.

#### Parameters

- `providerName` (`java.lang.String`) — provider name

#### Returns

set of agent IDs; empty if provider has no registry
