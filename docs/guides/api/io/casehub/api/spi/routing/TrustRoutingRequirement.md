# io.casehub.api.spi.routing.TrustRoutingRequirement

**Package:** `io.casehub.api.spi.routing`

**Kind:** `record`

Compliance evidence wrapper for trust routing decisions.

## Fields

### `citation` (`java.lang.String`)

### `decisions` (`java.util.List<io.casehub.api.spi.routing.RoutingDecisionRecord>`)

### `mechanism` (`java.lang.String`)

### `requirementId` (`java.lang.String`)

### `status` (`io.casehub.api.spi.routing.RequirementStatus`)

## Record Components

### `citation` (`java.lang.String`)

human-readable regulatory citation

### `decisions` (`java.util.List<io.casehub.api.spi.routing.RoutingDecisionRecord>`)

routing decision records supporting this requirement

### `mechanism` (`java.lang.String`)

description of how the requirement is met

### `requirementId` (`java.lang.String`)

regulatory requirement identifier (e.g. "FATF-R20-TRUST-ROUTING")

### `status` (`io.casehub.api.spi.routing.RequirementStatus`)

current compliance status

## Constructors

### `public TrustRoutingRequirement(java.lang.String requirementId, java.lang.String citation, java.lang.String mechanism, io.casehub.api.spi.routing.RequirementStatus status, java.util.List<io.casehub.api.spi.routing.RoutingDecisionRecord> decisions)`

#### Parameters

- `requirementId` (`java.lang.String`)
- `citation` (`java.lang.String`)
- `mechanism` (`java.lang.String`)
- `status` (`io.casehub.api.spi.routing.RequirementStatus`)
- `decisions` (`java.util.List<io.casehub.api.spi.routing.RoutingDecisionRecord>`)

## Methods

### `public java.lang.String citation()`

### `public java.util.List<io.casehub.api.spi.routing.RoutingDecisionRecord> decisions()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public java.lang.String mechanism()`

### `public java.lang.String requirementId()`

### `public io.casehub.api.spi.routing.RequirementStatus status()`

### `public final java.lang.String toString()`
