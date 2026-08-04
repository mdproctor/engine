# io.casehub.api.spi.mesh.MeshParticipationStrategy.MeshParticipation

**Package:** `io.casehub.api.spi.mesh`

**Kind:** `enum`

Participation level for an agent in the CaseHub mesh.

## Enum Constants

### `ACTIVE` (`io.casehub.api.spi.mesh.MeshParticipationStrategy.MeshParticipation`)

Register on startup, post STATUS, check messages periodically.

### `REACTIVE` (`io.casehub.api.spi.mesh.MeshParticipationStrategy.MeshParticipation`)

Do not register; only engage when directly addressed.

### `SILENT` (`io.casehub.api.spi.mesh.MeshParticipationStrategy.MeshParticipation`)

No mesh participation.

## Constructors

### `private MeshParticipation()`

## Methods

### `public static io.casehub.api.spi.mesh.MeshParticipationStrategy.MeshParticipation valueOf(java.lang.String name)`

#### Parameters

- `name` (`java.lang.String`)

### `public static io.casehub.api.spi.mesh.MeshParticipationStrategy.MeshParticipation[] values()`
