# io.casehub.api.spi.ActionGatePolicy

**Package:** `io.casehub.api.spi`

**Kind:** `enum`

Gate policy for `ActionRiskClassifier` implementations. Determines when a worker's `PlannedAction` requires human approval before the engine applies the output.

<p>Domain classifiers (AML, clinical, devtown, life) reference this enum instead of defining
their own equivalent — see casehubio/engine#472.

<ul>
  <li>`.ALWAYS` — every action of this type requires a gate, regardless of score or
      context. Use for irreversible or high-stakes actions (e.g. filing a SAR, administering
      medication).
  <li>`.THRESHOLD` — gate when the action's risk score exceeds a configured threshold. The
      threshold value is owned by the domain classifier, not by this enum.
  <li>`.CONDITIONAL` — gate based on contextual evaluation (e.g. JQ expression against the
      case context, NLI classification of the action description). The evaluation logic is owned
      by the domain classifier.
</ul>

## Enum Constants

### `ALWAYS` (`io.casehub.api.spi.ActionGatePolicy`)

### `CONDITIONAL` (`io.casehub.api.spi.ActionGatePolicy`)

### `THRESHOLD` (`io.casehub.api.spi.ActionGatePolicy`)

## Constructors

### `private ActionGatePolicy()`

## Methods

### `public static io.casehub.api.spi.ActionGatePolicy valueOf(java.lang.String name)`

#### Parameters

- `name` (`java.lang.String`)

### `public static io.casehub.api.spi.ActionGatePolicy[] values()`
