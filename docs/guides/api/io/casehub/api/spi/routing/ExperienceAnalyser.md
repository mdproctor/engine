# io.casehub.api.spi.routing.ExperienceAnalyser

**Package:** `io.casehub.api.spi.routing`

**Kind:** `class`

Shared utility for computing per-worker success rates from CBR plan trace data. Used by both
`io.casehub.ledger.routing.TrustWeightedAgentStrategy` (engine-ledger, trust-blended
scoring) and `CbrAgentRoutingStrategy` (blocks, CBR-first routing).

<p>Stateless — all methods are static. Co-located with `RetrievedExperience` and `ExperiencePlanStep` which it operates on.

## Fields

### `DEFAULT_OUTCOME_WEIGHTS` (`java.util.Map<io.casehub.api.spi.routing.RoutingOutcome,java.lang.Double>`)

## Constructors

### `private ExperienceAnalyser()`

## Methods

### `public static java.util.Map<java.lang.String,java.lang.Double> workerSuccessRates(java.util.List<io.casehub.api.spi.routing.RetrievedExperience> experiences, java.util.Set<java.lang.String> eligibleWorkerIds, java.lang.String capabilityName, java.util.Map<io.casehub.api.spi.routing.RoutingOutcome,java.lang.Double> outcomeWeights)`

Computes per-worker success rates, filtering plan trace steps by capability name.

<p>Delegates to the predicate overload. Steps with null `capabilityName` (e.g. humanTask
traces) are naturally excluded since `"x".equals(null)` is false.

#### Parameters

- `experiences` (`java.util.List<io.casehub.api.spi.routing.RetrievedExperience>`)
- `eligibleWorkerIds` (`java.util.Set<java.lang.String>`)
- `capabilityName` (`java.lang.String`)
- `outcomeWeights` (`java.util.Map<io.casehub.api.spi.routing.RoutingOutcome,java.lang.Double>`)

### `public static java.util.Map<java.lang.String,java.lang.Double> workerSuccessRates(java.util.List<io.casehub.api.spi.routing.RetrievedExperience> experiences, java.util.Set<java.lang.String> eligibleWorkerIds, java.util.function.Predicate<io.casehub.api.spi.routing.ExperiencePlanStep> stepFilter, java.util.Map<io.casehub.api.spi.routing.RoutingOutcome,java.lang.Double> outcomeWeights)`

Computes per-worker success rates using a caller-supplied step filter predicate.

<p>Generalisation of Set, String, Map) — the predicate
replaces the hardcoded `capabilityName.equals(step.capabilityName())` check, enabling
callers to match on any step field (e.g. `step.bindingName()` for humanTask traces).

#### Parameters

- `experiences` (`java.util.List<io.casehub.api.spi.routing.RetrievedExperience>`) — retrieved similar cases from the CBR store
- `eligibleWorkerIds` (`java.util.Set<java.lang.String>`) — worker IDs to score
- `stepFilter` (`java.util.function.Predicate<io.casehub.api.spi.routing.ExperiencePlanStep>`) — predicate selecting which plan trace steps to include in scoring
- `outcomeWeights` (`java.util.Map<io.casehub.api.spi.routing.RoutingOutcome,java.lang.Double>`) — per-outcome scoring weights

#### Returns

per-worker scores in [-1.0, 1.0]; empty map when no matching data
