# io.casehub.api.spi.CaseOutcomeEvent

**Package:** `io.casehub.api.spi`

**Kind:** `record`

Outcome event fired by the engine when a case reaches a terminal state (COMPLETED, FAULTED, or
CANCELLED). Delivered to all `CaseOutcomeObserver` beans discovered via CDI.

<p>`outcomeLabel` reflects the terminal status name ("COMPLETED", "FAULTED", "CANCELLED").
Applications that need domain-specific labels (e.g. "WIN", "LOSS") can derive them from `caseFileSnapshot` in their observer implementation.

<p>`caseFileSnapshot` is the working layer context at the time of terminal transition — the
last committed view of the case state, including all worker outputs. Treat it as read-only.

<p>`tenancyId` identifies the tenant owning the case — required for tenant-scoped
persistence operations in observer implementations.

<p>Refs casehubio/engine#477 (CBR Retain step).

## Fields

### `caseFileSnapshot` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `caseId` (`java.util.UUID`)

### `caseType` (`java.lang.String`)

### `closedAt` (`java.time.Instant`)

### `metadata` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `outcomeLabel` (`java.lang.String`)

### `tenancyId` (`java.lang.String`)

## Record Components

### `caseFileSnapshot` (`java.util.Map<java.lang.String,java.lang.Object>`)

working-layer context at case close; non-null, may be empty

### `caseId` (`java.util.UUID`)

case instance UUID

### `caseType` (`java.lang.String`)

case definition name (e.g. "aml-investigation", "starcraft-game")

### `closedAt` (`java.time.Instant`)

timestamp of the terminal transition

### `metadata` (`java.util.Map<java.lang.String,java.lang.Object>`)

additional context provided by the engine; currently empty, reserved for future
    use

### `outcomeLabel` (`java.lang.String`)

terminal status name: "COMPLETED", "FAULTED", or "CANCELLED"

### `tenancyId` (`java.lang.String`)

tenant identifier owning the case

## Constructors

### `public CaseOutcomeEvent(java.lang.String caseType, java.lang.String tenancyId, java.util.UUID caseId, java.util.Map<java.lang.String,java.lang.Object> caseFileSnapshot, java.lang.String outcomeLabel, java.time.Instant closedAt, java.util.Map<java.lang.String,java.lang.Object> metadata)`

#### Parameters

- `caseType` (`java.lang.String`)
- `tenancyId` (`java.lang.String`)
- `caseId` (`java.util.UUID`)
- `caseFileSnapshot` (`java.util.Map<java.lang.String,java.lang.Object>`)
- `outcomeLabel` (`java.lang.String`)
- `closedAt` (`java.time.Instant`)
- `metadata` (`java.util.Map<java.lang.String,java.lang.Object>`)

## Methods

### `public java.util.Map<java.lang.String,java.lang.Object> caseFileSnapshot()`

### `public java.util.UUID caseId()`

### `public java.lang.String caseType()`

### `public java.time.Instant closedAt()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public java.util.Map<java.lang.String,java.lang.Object> metadata()`

### `public java.lang.String outcomeLabel()`

### `public java.lang.String tenancyId()`

### `public final java.lang.String toString()`
