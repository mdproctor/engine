# io.casehub.api.spi.CaseCorrelationResolver

**Package:** `io.casehub.api.spi`

**Kind:** `interface`

Resolves a correlation value extracted from an inbound connector message to a case UUID.

<p>Follows the `NamedStrategy` convention — resolved via `EngineStrategyResolver`
from the `correlationResolver` field on `io.casehub.api.model.InboundSignalMapping`.

## Methods

### `public abstract Uni<java.util.UUID> resolve(java.lang.String correlationValue, java.lang.String tenancyId)`

#### Parameters

- `correlationValue` (`java.lang.String`)
- `tenancyId` (`java.lang.String`)
