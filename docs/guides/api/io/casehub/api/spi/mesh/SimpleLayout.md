# io.casehub.api.spi.mesh.SimpleLayout

**Package:** `io.casehub.api.spi.mesh`

**Kind:** `class`

2-channel agent mesh layout for cases that do not require a human governance gate.

<p>Channels: `work` (unrestricted) and `observe` (EVENT-only telemetry). No `oversight` channel.

<p>`caseId` and `definition` are both ignored — the layout is
case-definition-agnostic.

## Constructors

### `public SimpleLayout()`

## Methods

### `public java.util.List<io.casehub.api.spi.mesh.CaseChannelLayout.ChannelSpec> channelsFor(java.util.UUID caseId, io.casehub.api.model.CaseDefinition definition)`

#### Parameters

- `caseId` (`java.util.UUID`)
- `definition` (`io.casehub.api.model.CaseDefinition`)
