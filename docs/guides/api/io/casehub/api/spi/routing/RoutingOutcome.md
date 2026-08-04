# io.casehub.api.spi.routing.RoutingOutcome

**Package:** `io.casehub.api.spi.routing`

**Kind:** `enum`

Outcome of a routing decision — recorded by `RoutingOutcomeRecorder` to feed back into
routing strategies (e.g. CBR-enriched routing).

<p>`.SUCCESS` and `.FAILURE` are recorded from the worker completion path in `WorkflowExecutionCompletedHandler`. `.GATE_REJECTED` and `.GATE_EXPIRED` are recorded
directly from the gate resolution handlers. `.DECLINED`, `.CANCELLED`, and `.OBSOLETE` are recorded from the task lifecycle terminal states.

## Enum Constants

### `CANCELLED` (`io.casehub.api.spi.routing.RoutingOutcome`)

Task was cancelled externally (not the worker's fault).

### `DECLINED` (`io.casehub.api.spi.routing.RoutingOutcome`)

Worker declined the assigned task.

### `FAILURE` (`io.casehub.api.spi.routing.RoutingOutcome`)

Worker returned a non-success outcome (Failed or Expired).

### `GATE_EXPIRED` (`io.casehub.api.spi.routing.RoutingOutcome`)

Worker's planned action gate expired without review.

### `GATE_REJECTED` (`io.casehub.api.spi.routing.RoutingOutcome`)

Worker's planned action was rejected by a human via the oversight gate.

### `OBSOLETE` (`io.casehub.api.spi.routing.RoutingOutcome`)

Task became irrelevant before completion (not the worker's fault).

### `SUCCESS` (`io.casehub.api.spi.routing.RoutingOutcome`)

Worker completed successfully (including gate-approved re-dispatch).

## Constructors

### `private RoutingOutcome()`

## Methods

### `public static io.casehub.api.spi.routing.RoutingOutcome valueOf(java.lang.String name)`

#### Parameters

- `name` (`java.lang.String`)

### `public static io.casehub.api.spi.routing.RoutingOutcome[] values()`
