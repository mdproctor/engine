# io.casehub.api.spi.routing.EscalationReason

**Package:** `io.casehub.api.spi.routing`

**Kind:** `enum`

Why agent routing escalated to human oversight.

## Enum Constants

### `BORDERLINE_STALEMATE` (`io.casehub.api.spi.routing.EscalationReason`)

All candidates scored 0.0 and at least one was BORDERLINE (score within `borderlineMargin` of `threshold`). The pool has agents but none are clearly qualified.

### `NO_QUALIFIED_AGENT` (`io.casehub.api.spi.routing.EscalationReason`)

No QUALIFIED agent is available; only BOOTSTRAP-phase agents could be assigned. Pre-screen
fires before scoring — no scoring has occurred. Requires human routing.

## Constructors

### `private EscalationReason()`

## Methods

### `public static io.casehub.api.spi.routing.EscalationReason valueOf(java.lang.String name)`

#### Parameters

- `name` (`java.lang.String`)

### `public static io.casehub.api.spi.routing.EscalationReason[] values()`
