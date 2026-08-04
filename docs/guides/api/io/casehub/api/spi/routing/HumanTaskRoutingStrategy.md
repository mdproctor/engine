# io.casehub.api.spi.routing.HumanTaskRoutingStrategy

**Package:** `io.casehub.api.spi.routing`

**Kind:** `interface`

Pluggable strategy for enriching humanTask candidate sets with historical data. Symmetric with
`AgentRoutingStrategy` — follows the routing strategy convention (engine#634): `select()` method, context/candidates separation, sealed result type.

<p>The strategy receives pre-resolved candidate groups/users and retrieved CBR experiences, and
returns an enriched or unchanged result. The default implementation (`NoOpHumanTaskRoutingStrategy`) returns `HumanTaskRoutingResult.Unchanged`.

<p>Resolved via `StrategyResolver` from `CaseDefinition.getHumanTaskRouting()`.

## Methods

### `public abstract io.casehub.api.spi.routing.HumanTaskRoutingResult select(io.casehub.api.spi.routing.HumanTaskRoutingContext context, io.casehub.api.spi.routing.HumanTaskCandidates candidates)`

#### Parameters

- `context` (`io.casehub.api.spi.routing.HumanTaskRoutingContext`)
- `candidates` (`io.casehub.api.spi.routing.HumanTaskCandidates`)
