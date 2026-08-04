# io.casehub.api.spi.routing.TrustPhase

**Package:** `io.casehub.api.spi.routing`

**Kind:** `enum`

Trust maturity phases for routing policy configuration. Determines which phases trigger
evidential verification at attestation time.

<p>Distinct from `TrustCandidateClassifier.Phase` which is a routing-time classification.
This enum is policy-level vocabulary — it configures <em>when</em> evidential checking runs, not
<em>how</em> candidates are classified.

<p>Refs casehubio/engine#711, devtown#141.

## Enum Constants

### `BELOW_THRESHOLD` (`io.casehub.api.spi.routing.TrustPhase`)

### `BOOTSTRAP` (`io.casehub.api.spi.routing.TrustPhase`)

### `BORDERLINE` (`io.casehub.api.spi.routing.TrustPhase`)

### `QUALIFIED` (`io.casehub.api.spi.routing.TrustPhase`)

### `QUALITY_FAILED` (`io.casehub.api.spi.routing.TrustPhase`)

## Constructors

### `private TrustPhase()`

## Methods

### `public static io.casehub.api.spi.routing.TrustPhase valueOf(java.lang.String name)`

#### Parameters

- `name` (`java.lang.String`)

### `public static io.casehub.api.spi.routing.TrustPhase[] values()`
