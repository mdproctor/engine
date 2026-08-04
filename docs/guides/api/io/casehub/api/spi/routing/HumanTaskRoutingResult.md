# io.casehub.api.spi.routing.HumanTaskRoutingResult

**Package:** `io.casehub.api.spi.routing`

**Kind:** `interface`

Sealed result type from `HumanTaskRoutingStrategy.select`. Follows the convention of `RoutingResult` (Selected | Unresolvable | Escalated) and `ImplementationSelection`
(Selected | RunAll | RunNone).

<p>`candidateScores` keys are individual actor IDs (direct or group-expanded), never group
names. Invariant: `candidateScores.keySet() \u2286 candidateUsers`.
