# io.casehub.api.spi.routing.MatchedWorker

**Package:** `io.casehub.api.spi.routing`

**Kind:** `record`

A worker paired with the match degree that qualified it for a capability.

<p>Returned by `CandidateMatchingStrategy.match` so that match metadata flows through the
dispatch pipeline without re-deriving it.

## Fields

### `matchDegree` (`MatchDegree`)

### `worker` (`Worker`)

## Record Components

### `matchDegree` (`MatchDegree`)

### `worker` (`Worker`)

## Constructors

### `public MatchedWorker(Worker worker, MatchDegree matchDegree)`

#### Parameters

- `worker` (`Worker`)
- `matchDegree` (`MatchDegree`)

## Methods

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public static io.casehub.api.spi.routing.MatchedWorker exact(Worker worker)`

#### Parameters

- `worker` (`Worker`)

### `public final int hashCode()`

### `public MatchDegree matchDegree()`

### `public final java.lang.String toString()`

### `public Worker worker()`
