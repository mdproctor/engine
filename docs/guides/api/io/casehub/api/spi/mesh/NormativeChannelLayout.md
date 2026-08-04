# io.casehub.api.spi.mesh.NormativeChannelLayout

**Package:** `io.casehub.api.spi.mesh`

**Kind:** `class`

Canonical 4-channel agent mesh layout: work / observe / oversight / coordination.

<p>Type enforcement per protocols PP-20260604-a7ad99 and PP-20260508-a15390:

<ul>
  <li>`work` — all obligation-carrying types; unrestricted
  <li>`observe` — `MessageType.EVENT` only (telemetry; hard-blocked from obligation
      types)
  <li>`oversight` — `deniedTypes = {EVENT`} (advisory enforcement; all
      obligation-carrying types permitted)
  <li>`coordination` — engine coordination; unrestricted
</ul>

<p>`caseId` and `definition` are both ignored — the layout is
case-definition-agnostic.

## Constructors

### `public NormativeChannelLayout()`

## Methods

### `public java.util.List<io.casehub.api.spi.mesh.CaseChannelLayout.ChannelSpec> channelsFor(java.util.UUID caseId, io.casehub.api.model.CaseDefinition definition)`

#### Parameters

- `caseId` (`java.util.UUID`)
- `definition` (`io.casehub.api.model.CaseDefinition`)
