# io.casehub.api.spi.RiskDecision.GateRequired

**Package:** `io.casehub.api.spi`

**Kind:** `record`

## Fields

### `candidateGroups` (`io.casehub.api.spi.routing.CandidateSetStrategy`)

### `expiresIn` (`java.time.Duration`)

### `quorum` (`io.casehub.api.spi.QuorumConfig`)

### `reason` (`java.lang.String`)

### `resolutionType` (`java.lang.Class<?>`)

### `reversible` (`boolean`)

### `scope` (`java.lang.String`)

## Record Components

### `candidateGroups` (`io.casehub.api.spi.routing.CandidateSetStrategy`)

### `expiresIn` (`java.time.Duration`)

### `quorum` (`io.casehub.api.spi.QuorumConfig`)

### `reason` (`java.lang.String`)

### `resolutionType` (`java.lang.Class<?>`)

### `reversible` (`boolean`)

### `scope` (`java.lang.String`)

## Constructors

### `public GateRequired(java.lang.String reason, boolean reversible, io.casehub.api.spi.routing.CandidateSetStrategy candidateGroups, java.time.Duration expiresIn, java.lang.String scope, java.lang.Class<?> resolutionType, io.casehub.api.spi.QuorumConfig quorum)`

#### Parameters

- `reason` (`java.lang.String`)
- `reversible` (`boolean`)
- `candidateGroups` (`io.casehub.api.spi.routing.CandidateSetStrategy`)
- `expiresIn` (`java.time.Duration`)
- `scope` (`java.lang.String`)
- `resolutionType` (`java.lang.Class<?>`)
- `quorum` (`io.casehub.api.spi.QuorumConfig`)

## Methods

### `public io.casehub.api.spi.routing.CandidateSetStrategy candidateGroups()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public java.time.Duration expiresIn()`

### `public final int hashCode()`

### `public io.casehub.api.spi.QuorumConfig quorum()`

### `public java.lang.String reason()`

### `public java.lang.Class<?> resolutionType()`

### `public boolean reversible()`

### `public java.lang.String scope()`

### `public final java.lang.String toString()`
