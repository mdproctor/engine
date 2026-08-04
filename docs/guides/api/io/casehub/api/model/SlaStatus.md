# io.casehub.api.model.SlaStatus

**Package:** `io.casehub.api.model`

**Kind:** `enum`

SLA (Service Level Agreement) status of a milestone.

<p>Tracks whether a milestone with `slaDuration` is within or past its deadline.

<ul>
  <li><b>NOT_STARTED</b> — milestone not yet activated (PENDING state)
  <li><b>ON_TRACK</b> — milestone activated, within SLA deadline
  <li><b>BREACHED</b> — SLA deadline passed
</ul>

<p>SLA status is orthogonal to lifecycle status: a milestone can be COMPLETED + BREACHED (late
completion).

## Enum Constants

### `BREACHED` (`io.casehub.api.model.SlaStatus`)

### `NOT_STARTED` (`io.casehub.api.model.SlaStatus`)

### `ON_TRACK` (`io.casehub.api.model.SlaStatus`)

## Constructors

### `private SlaStatus()`

## Methods

### `public static io.casehub.api.model.SlaStatus valueOf(java.lang.String name)`

#### Parameters

- `name` (`java.lang.String`)

### `public static io.casehub.api.model.SlaStatus[] values()`
