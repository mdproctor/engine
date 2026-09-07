# io.casehub.api.spi.routing.RoutingSelection

**Package:** `io.casehub.api.spi.routing`

**Kind:** `record`

Routing rationale captured at selection time.

<p>Carried on `RoutingResult.Selected` so the handler that schedules the worker can bridge
it into the event-layer `SelectionContext` for ledger auditing.

## Fields

### `alternatives` (`java.util.List<io.casehub.api.spi.routing.RoutingSelection.Candidate>`)

### `selected` (`io.casehub.api.spi.routing.RoutingSelection.Candidate`)

### `strategyId` (`java.lang.String`)

## Record Components

### `alternatives` (`java.util.List<io.casehub.api.spi.routing.RoutingSelection.Candidate>`)

other candidates considered (may be empty)

### `selected` (`io.casehub.api.spi.routing.RoutingSelection.Candidate`)

the chosen candidate

### `strategyId` (`java.lang.String`)

which strategy made the selection

## Constructors

### `public RoutingSelection(java.lang.String strategyId, io.casehub.api.spi.routing.RoutingSelection.Candidate selected, java.util.List<io.casehub.api.spi.routing.RoutingSelection.Candidate> alternatives)`

#### Parameters

- `strategyId` (`java.lang.String`)
- `selected` (`io.casehub.api.spi.routing.RoutingSelection.Candidate`)
- `alternatives` (`java.util.List<io.casehub.api.spi.routing.RoutingSelection.Candidate>`)

## Methods

### `public java.util.List<io.casehub.api.spi.routing.RoutingSelection.Candidate> alternatives()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public io.casehub.api.spi.routing.RoutingSelection.Candidate selected()`

### `public java.lang.String strategyId()`

### `public final java.lang.String toString()`
