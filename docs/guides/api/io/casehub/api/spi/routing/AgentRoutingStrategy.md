# io.casehub.api.spi.routing.AgentRoutingStrategy

**Package:** `io.casehub.api.spi.routing`

**Kind:** `interface`

Engine-owned SPI for agent worker selection. Replaces the borrowed `WorkerSelectionStrategy` from casehub-work.

<p>Implement as `@ApplicationScoped @Alternative @Priority(N)` where N > 0 to override
`io.casehub.engine.internal.routing.LeastLoadedAgentStrategy`. Higher priority wins.

<p>Implementations that do only in-memory work (e.g. trust scoring against a local cache) should
return `Uni.createFrom().item(result)`. Implementations that make blocking calls (e.g. an
embedding service) must return a reactive chain that executes on a worker thread — never blocking
the Vert.x IO thread.

<p>Implementations MUST be thread-safe — `select()` may be called concurrently.

<p>Known implementations:

<ul>
  <li>`LeastLoadedAgentStrategy` — `@DefaultBean`, prefers fewest running Quartz jobs
  <li>`TrustWeightedAgentStrategy` — `@Alternative @Priority(1)`, trust maturity
      model
  <li>`SemanticAgentRoutingStrategy` — `@Alternative @Priority(2)`, optional module
      `casehub-engine-ai`, embedding-based semantic re-ranking over trust-qualified
      candidates
</ul>

## Methods

### `public abstract io.casehub.api.spi.routing.RoutingResult select(io.casehub.api.spi.routing.AgentRoutingContext context, java.util.List<io.casehub.api.spi.routing.AgentCandidate> candidates)`

Select a worker from the pre-filtered candidate list.

<p>Candidates are pre-filtered: `Unavailable` workers are never passed here. `EPISTEMICALLY_WEAK` and `DEGRADED` workers are included — implementations may apply
preference demotion via `AgentCandidate.health()`.

#### Parameters

- `context` (`io.casehub.api.spi.routing.AgentRoutingContext`) — routing context carrying caseId, capabilityName, and caseContext
- `candidates` (`java.util.List<io.casehub.api.spi.routing.AgentCandidate>`) — non-empty list of eligible candidates (filtered, health-probed)

#### Returns

one of: `RoutingResult.Selected`, `RoutingResult.Unresolvable`, or `RoutingResult.Escalated`
