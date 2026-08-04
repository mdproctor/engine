# io.casehub.api.spi.routing.AgentRoutingContext

**Package:** `io.casehub.api.spi.routing`

**Kind:** `record`

## Fields

### `capabilityName` (`java.lang.String`)

### `caseContext` (`JsonNode`)

### `caseId` (`java.util.UUID`)

### `cognitiveDemand` (`io.casehub.api.model.CognitiveDemand`)

### `experiences` (`java.util.List<io.casehub.api.spi.routing.RetrievedExperience>`)

### `routingSignalWeights` (`java.util.Map<java.lang.String,java.lang.Double>`)

### `tenancyId` (`java.lang.String`)

## Record Components

### `capabilityName` (`java.lang.String`)

### `caseContext` (`JsonNode`)

### `caseId` (`java.util.UUID`)

### `cognitiveDemand` (`io.casehub.api.model.CognitiveDemand`)

### `experiences` (`java.util.List<io.casehub.api.spi.routing.RetrievedExperience>`)

### `routingSignalWeights` (`java.util.Map<java.lang.String,java.lang.Double>`)

### `tenancyId` (`java.lang.String`)

## Constructors

### `public AgentRoutingContext(java.util.UUID caseId, java.lang.String capabilityName, JsonNode caseContext, java.lang.String tenancyId, java.util.List<io.casehub.api.spi.routing.RetrievedExperience> experiences, io.casehub.api.model.CognitiveDemand cognitiveDemand, java.util.Map<java.lang.String,java.lang.Double> routingSignalWeights)`

#### Parameters

- `caseId` (`java.util.UUID`)
- `capabilityName` (`java.lang.String`)
- `caseContext` (`JsonNode`)
- `tenancyId` (`java.lang.String`)
- `experiences` (`java.util.List<io.casehub.api.spi.routing.RetrievedExperience>`)
- `cognitiveDemand` (`io.casehub.api.model.CognitiveDemand`)
- `routingSignalWeights` (`java.util.Map<java.lang.String,java.lang.Double>`)

## Methods

### `public java.lang.String capabilityName()`

### `public JsonNode caseContext()`

### `public java.util.UUID caseId()`

### `public io.casehub.api.model.CognitiveDemand cognitiveDemand()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public java.util.List<io.casehub.api.spi.routing.RetrievedExperience> experiences()`

### `public final int hashCode()`

### `public java.util.Map<java.lang.String,java.lang.Double> routingSignalWeights()`

### `public java.lang.String tenancyId()`

### `public final java.lang.String toString()`
