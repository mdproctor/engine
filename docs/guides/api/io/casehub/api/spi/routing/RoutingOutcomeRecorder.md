# io.casehub.api.spi.routing.RoutingOutcomeRecorder

**Package:** `io.casehub.api.spi.routing`

**Kind:** `interface`

Optional SPI for recording routing outcomes to a persistent store, enabling feedback-loop routing
strategies (e.g. CBR-enriched LLM routing).

<p>The engine calls `record()` after a worker completes execution (`RoutingOutcome.SUCCESS`, `RoutingOutcome.FAILURE`) and from gate resolution handlers when a
gate is rejected (`RoutingOutcome.GATE_REJECTED`) or expires (`RoutingOutcome.GATE_EXPIRED`). Gate-approved outcomes record on the re-dispatch completion path
as `RoutingOutcome.SUCCESS`.

<p>Implementations are discovered via `Instance<RoutingOutcomeRecorder>`. When no
implementation is present, the engine silently skips recording. Implementations must be
thread-safe — `record()` may be called concurrently.

<p>The engine subscribes fire-and-forget — recording failure never blocks execution.

## Methods

### `public abstract Uni<java.lang.Void> record(io.casehub.api.spi.routing.AgentRoutingContext context, java.lang.String workerId, java.lang.String bindingName, io.casehub.api.spi.routing.RoutingOutcome outcome, java.time.Duration executionDuration)`

Record a routing outcome.

<p>Called by the engine after a worker completes execution (`RoutingOutcome.SUCCESS`,
`RoutingOutcome.FAILURE`) and by gate resolution handlers when a gate is rejected (`RoutingOutcome.GATE_REJECTED`) or expires (`RoutingOutcome.GATE_EXPIRED`). Gate-approved
outcomes record on the re-dispatch completion path as `SUCCESS`.

#### Parameters

- `context` (`io.casehub.api.spi.routing.AgentRoutingContext`) — the routing context at the time of the decision
- `workerId` (`java.lang.String`) — the worker that was selected and executed
- `bindingName` (`java.lang.String`) — the case definition binding that dispatched the worker
- `outcome` (`io.casehub.api.spi.routing.RoutingOutcome`) — the routing outcome
- `executionDuration` (`java.time.Duration`) — wall-clock duration of the worker execution; nullable when the engine
    does not track dispatch timestamps or for gate outcomes

#### Returns

a Uni completing when the record is persisted
