# io.casehub.api.spi.ChainedActionRiskClassifier

**Package:** `io.casehub.api.spi`

**Kind:** `class`

Chains all @RiskClassifier-qualified `ActionRiskClassifier` beans
and returns the most restrictive `RiskDecision`.

<p>When no consumer has registered any `@RiskClassifier` classifier, the injection point is
unsatisfied and the method returns `Autonomous` immediately.

<p>If any classifier throws, the fail-safe `GateRequired` is returned — the action is gated
for manual review.

<p>"Most restrictive" = fewest `candidateGroups`; tie → shorter `expiresIn`; tie →
CDI iteration order (first wins).

## Fields

### `FAIL_SAFE` (`io.casehub.api.spi.RiskDecision.GateRequired`)

### `LOG` (`Logger`)

### `classifiers` (`Instance<io.casehub.api.spi.ActionRiskClassifier>`)

## Constructors

### `public ChainedActionRiskClassifier()`

## Methods

### `private int candidateSetSize(io.casehub.api.spi.routing.CandidateSetStrategy strategy)`

#### Parameters

- `strategy` (`io.casehub.api.spi.routing.CandidateSetStrategy`)

### `public io.casehub.api.spi.RiskDecision classify(PlannedAction action, io.casehub.api.spi.ClassificationContext context)`

#### Parameters

- `action` (`PlannedAction`)
- `context` (`io.casehub.api.spi.ClassificationContext`)

### `io.casehub.api.spi.RiskDecision mostRestrictive(io.casehub.api.spi.RiskDecision a, io.casehub.api.spi.RiskDecision b)`

#### Parameters

- `a` (`io.casehub.api.spi.RiskDecision`)
- `b` (`io.casehub.api.spi.RiskDecision`)

### `private io.casehub.api.spi.RiskDecision.GateRequired narrower(io.casehub.api.spi.RiskDecision.GateRequired a, io.casehub.api.spi.RiskDecision.GateRequired b)`

#### Parameters

- `a` (`io.casehub.api.spi.RiskDecision.GateRequired`)
- `b` (`io.casehub.api.spi.RiskDecision.GateRequired`)
