# io.casehub.api.spi.RiskClassifier

**Package:** `io.casehub.api.spi`

**Kind:** `annotation`

CDI qualifier for `ActionRiskClassifier` implementations.

<p>Consumer implementations must be annotated `@RiskClassifier @ApplicationScoped` so the
engine's `ChainedActionRiskClassifier` can discover and chain them without circular
dependency. The chain implements `ActionRiskClassifier`, not `ActionRiskClassifier`,
which prevents self-injection.

<p>Multiple classifiers from different repos (casehub-aml, casehub-clinical) are automatically
chained — the most restrictive `RiskDecision.GateRequired` wins.
