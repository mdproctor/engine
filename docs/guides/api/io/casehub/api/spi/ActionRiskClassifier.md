# io.casehub.api.spi.ActionRiskClassifier

**Package:** `io.casehub.api.spi`

**Kind:** `interface`

Classifies a worker's planned action to determine whether it may proceed autonomously or must be
gated for human approval.

<p>Implementations must be annotated `RiskClassifier` and `@ApplicationScoped`. The
engine chains all registered implementations — the most restrictive result wins. Multiple
consumer repos can provide classifiers simultaneously without conflict.

<p>The engine chains all registered implementations via `ChainedActionRiskClassifier`,
which applies most-restrictive-wins semantics.

<p>If `classify` throws, the engine applies a fail-safe `RiskDecision.GateRequired`
requiring manual review. Do not throw to bypass the gate — the fail-safe will catch it.

## Methods

### `public abstract io.casehub.api.spi.RiskDecision classify(PlannedAction action, io.casehub.api.spi.ClassificationContext context)`

#### Parameters

- `action` (`PlannedAction`)
- `context` (`io.casehub.api.spi.ClassificationContext`)
