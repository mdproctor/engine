# io.casehub.api.spi.mesh.MeshParticipationStrategy

**Package:** `io.casehub.api.spi.mesh`

**Kind:** `interface`

SPI: declares how actively an agent participates in the CaseHub mesh.

<p>The participation level is consulted by `WorkerContextProvider` implementations to
determine which channels to surface in the agent's system prompt.

<p>Note: `strategyFor` receives `caseId` directly. Null is valid — the strategy may
be consulted before a case identifier is available.

<p>Standard implementations: `ActiveParticipationStrategy`, `ReactiveParticipationStrategy`, `SilentParticipationStrategy`. Select via `.named(String)` for config-driven choice.

## Methods

### `public static io.casehub.api.spi.mesh.MeshParticipationStrategy named(java.lang.String configValue)`

Factory for standard participation strategies by config string.

<p>Valid values: `"active"` → `ActiveParticipationStrategy`, `"reactive"` →
`ReactiveParticipationStrategy`, `"silent"` → `SilentParticipationStrategy`.

#### Parameters

- `configValue` (`java.lang.String`)

#### Throws

- `IllegalArgumentException` — for unknown config values

### `public abstract io.casehub.api.spi.mesh.MeshParticipationStrategy.MeshParticipation strategyFor(java.lang.String workerId, java.util.UUID caseId)`

Returns the participation level for the given worker.

#### Parameters

- `workerId` (`java.lang.String`) — the worker identifier; may be `null` or empty
- `caseId` (`java.util.UUID`) — the case identifier; may be `null` if not yet available
