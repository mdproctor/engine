# io.casehub.api.spi.routing.RoutingSignal

**Package:** `io.casehub.api.spi.routing`

**Kind:** `record`

Structured per-candidate scoring data returned by a `RoutingSignalProvider`.

<p>Each candidate signal carries a score in [0.0, 1.0] and an optional reason. `RoutingSignalAssembler` enforces the score range by clamping out-of-range values.

<p>Signal maps may be sparse — only candidates the provider has data for need entries. Missing
entries contribute +0 to the final score (absence of data, not evaluated-as-zero).

## Fields

### `candidates` (`java.util.Map<java.lang.String,io.casehub.api.spi.routing.RoutingSignal.CandidateSignal>`)

## Record Components

### `candidates` (`java.util.Map<java.lang.String,io.casehub.api.spi.routing.RoutingSignal.CandidateSignal>`)

per-candidate scoring data, keyed by worker ID

## Constructors

### `public RoutingSignal(java.util.Map<java.lang.String,io.casehub.api.spi.routing.RoutingSignal.CandidateSignal> candidates)`

#### Parameters

- `candidates` (`java.util.Map<java.lang.String,io.casehub.api.spi.routing.RoutingSignal.CandidateSignal>`)

## Methods

### `public java.util.Map<java.lang.String,io.casehub.api.spi.routing.RoutingSignal.CandidateSignal> candidates()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public final java.lang.String toString()`
