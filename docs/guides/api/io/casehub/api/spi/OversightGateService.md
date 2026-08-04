# io.casehub.api.spi.OversightGateService

**Package:** `io.casehub.api.spi`

**Kind:** `interface`

## Methods

### `public abstract void fulfill(java.util.UUID gateId, java.lang.String rawOutput)`

#### Parameters

- `gateId` (`java.util.UUID`)
- `rawOutput` (`java.lang.String`)

### `public abstract io.casehub.api.spi.GateOutcome openGate(java.lang.String agentId, java.lang.String commitmentId, java.lang.String outcome, java.lang.String tenancyId)`

#### Parameters

- `agentId` (`java.lang.String`)
- `commitmentId` (`java.lang.String`)
- `outcome` (`java.lang.String`)
- `tenancyId` (`java.lang.String`)
