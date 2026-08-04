# io.casehub.api.model.OutcomePolicy

**Package:** `io.casehub.api.model`

**Kind:** `record`

Policy for handling semantic worker outcomes (DECLINED, FAILED, EXPIRED) on a per-binding basis.

<p>Each outcome type maps to an `OutcomeAction`: `REROUTE` writes failure state and
re-dispatches to a different agent; `FAULT` marks the case FAULTED immediately.

## Fields

### `maxRerouteAttempts` (`int`)

### `onDecline` (`io.casehub.api.model.OutcomeAction`)

### `onExpired` (`io.casehub.api.model.OutcomeAction`)

### `onFailure` (`io.casehub.api.model.OutcomeAction`)

## Record Components

### `maxRerouteAttempts` (`int`)

maximum dispatch+outcome cycles before writing REROUTES_EXHAUSTED

### `onDecline` (`io.casehub.api.model.OutcomeAction`)

action when a worker returns `WorkerOutcome.Declined`

### `onExpired` (`io.casehub.api.model.OutcomeAction`)

action when a worker times out or its commitment expires

### `onFailure` (`io.casehub.api.model.OutcomeAction`)

action when a worker returns `WorkerOutcome.Failed`

## Constructors

### `public OutcomePolicy()`

### `public OutcomePolicy(io.casehub.api.model.OutcomeAction onDecline, io.casehub.api.model.OutcomeAction onFailure, io.casehub.api.model.OutcomeAction onExpired, int maxRerouteAttempts)`

#### Parameters

- `onDecline` (`io.casehub.api.model.OutcomeAction`)
- `onFailure` (`io.casehub.api.model.OutcomeAction`)
- `onExpired` (`io.casehub.api.model.OutcomeAction`)
- `maxRerouteAttempts` (`int`)

## Methods

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public int maxRerouteAttempts()`

### `public io.casehub.api.model.OutcomeAction onDecline()`

### `public io.casehub.api.model.OutcomeAction onExpired()`

### `public io.casehub.api.model.OutcomeAction onFailure()`

### `public final java.lang.String toString()`
