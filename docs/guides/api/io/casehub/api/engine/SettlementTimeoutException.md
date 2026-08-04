# io.casehub.api.engine.SettlementTimeoutException

**Package:** `io.casehub.api.engine`

**Kind:** `class`

Thrown when awaiting a child case or worker outcome times out before settlement.

## Fields

### `targetId` (`java.util.UUID`)

### `timeout` (`java.time.Duration`)

## Constructors

### `public SettlementTimeoutException(java.util.UUID targetId, java.time.Duration timeout)`

#### Parameters

- `targetId` (`java.util.UUID`)
- `timeout` (`java.time.Duration`)

## Methods

### `public java.util.UUID getTargetId()`

### `public java.time.Duration getTimeout()`
