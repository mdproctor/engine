# io.casehub.api.spi.mesh.CaseChannelLayout

**Package:** `io.casehub.api.spi.mesh`

**Kind:** `interface`

SPI: declares the Qhorus channel topology for an agent mesh case.

<p>Implementations return one `ChannelSpec` per channel to create. The purpose field
becomes the channel name suffix; semantic, allowedTypes and deniedTypes are enforced at the
Qhorus layer.

<p>`CaseDefinition definition` is passed as `null` at all current call sites — the
parameter exists for future strategies that may vary topology per case definition (e.g. a
definition with `requires_oversight: false` selecting `SimpleLayout`). Do not remove
it; it is an intentional extensibility point from the original SPI design (claudony#87).

<p>Standard implementations: `NormativeChannelLayout` (3-channel: work/observe/oversight),
`SimpleLayout` (2-channel: work/observe). Select via `.named(String)` for
config-driven layout choice.

## Methods

### `public abstract java.util.List<io.casehub.api.spi.mesh.CaseChannelLayout.ChannelSpec> channelsFor(java.util.UUID caseId, io.casehub.api.model.CaseDefinition definition)`

Returns the channel specs for a case.

#### Parameters

- `caseId` (`java.util.UUID`) — the case identifier
- `definition` (`io.casehub.api.model.CaseDefinition`) — the case definition; may be `null` if not yet available

### `public static io.casehub.api.spi.mesh.CaseChannelLayout named(java.lang.String configValue)`

Factory for standard layouts by config string.

<p>Valid values: `"normative"` → `NormativeChannelLayout`, `"simple"` →
`SimpleLayout`.

#### Parameters

- `configValue` (`java.lang.String`)

#### Throws

- `IllegalArgumentException` — for unknown config values
