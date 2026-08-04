# io.casehub.api.spi.routing.ImplementationRoutingContext

**Package:** `io.casehub.api.spi.routing`

**Kind:** `record`

Routing context passed to `ImplementationRoutingStrategy.select`.

## Fields

### `capabilityName` (`java.lang.String`)

### `caseContext` (`JsonNode`)

### `caseId` (`java.util.UUID`)

### `experiences` (`java.util.List<io.casehub.api.spi.routing.RetrievedExperience>`)

### `tenancyId` (`java.lang.String`)

## Record Components

### `capabilityName` (`java.lang.String`)

the capability being routed

### `caseContext` (`JsonNode`)

the current case context as a JSON node (working layer)

### `caseId` (`java.util.UUID`)

the case instance UUID

### `experiences` (`java.util.List<io.casehub.api.spi.routing.RetrievedExperience>`)

retrieved similar cases from CBR (empty list if CBR is not configured or no
    matches found)

### `tenancyId` (`java.lang.String`)

the tenant ID owning the case; used for tenant-scoped CBR routing

## Constructors

### `public ImplementationRoutingContext(java.util.UUID caseId, java.lang.String capabilityName, JsonNode caseContext, java.lang.String tenancyId, java.util.List<io.casehub.api.spi.routing.RetrievedExperience> experiences)`

#### Parameters

- `caseId` (`java.util.UUID`)
- `capabilityName` (`java.lang.String`)
- `caseContext` (`JsonNode`)
- `tenancyId` (`java.lang.String`)
- `experiences` (`java.util.List<io.casehub.api.spi.routing.RetrievedExperience>`)

## Methods

### `public java.lang.String capabilityName()`

### `public JsonNode caseContext()`

### `public java.util.UUID caseId()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public java.util.List<io.casehub.api.spi.routing.RetrievedExperience> experiences()`

### `public final int hashCode()`

### `public java.lang.String tenancyId()`

### `public final java.lang.String toString()`
