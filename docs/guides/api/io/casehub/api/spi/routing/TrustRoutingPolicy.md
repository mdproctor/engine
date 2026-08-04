# io.casehub.api.spi.routing.TrustRoutingPolicy

**Package:** `io.casehub.api.spi.routing`

**Kind:** `record`

Per-capability trust routing policy parameters.

## Fields

### `DEFAULT` (`io.casehub.api.spi.routing.TrustRoutingPolicy`)

Conservative defaults: 0.7 threshold, 10 observations, 0.1 margin, 60% trust blend, no CBR.

### `blendFactor` (`double`)

### `bootstrapEscalationRequired` (`boolean`)

### `borderlineMargin` (`double`)

### `cbrWeight` (`double`)

### `evidentialCheckPhases` (`java.util.Set<io.casehub.api.spi.routing.TrustPhase>`)

### `fallbackBinding` (`java.lang.String`)

### `minimumObservations` (`int`)

### `qualityFloors` (`java.util.Map<java.lang.String,java.lang.Double>`)

### `threshold` (`double`)

## Record Components

### `blendFactor` (`double`)

weight of trust score vs workload efficiency (0.0 = pure workload, 1.0 = pure
    trust)

### `bootstrapEscalationRequired` (`boolean`)

when true, BOOTSTRAP candidates are stripped from the scoring
    pool; if no QUALIFIED agent exists, escalates to `io.casehub.api.spi.routing.EscalationReason.NO_QUALIFIED_AGENT` instead of assigning an
    unproven agent. Set to true for high-stakes, irreversible capabilities. Default: false.

### `borderlineMargin` (`double`)

candidates whose score is within this margin of the threshold are
    excluded (score 0.0); tracked for escalation in engine#377

### `cbrWeight` (`double`)

weight of CBR similarity bonus vs trust blend for QUALIFIED candidates (0.0 =
    pure trust, 1.0 = pure CBR). Only applied when `AgentRoutingContext.experiences()` is
    non-empty and the candidate has matching plan trace data. Default: 0.0. Refs devtown#133.

### `evidentialCheckPhases` (`java.util.Set<io.casehub.api.spi.routing.TrustPhase>`)

trust phases for which evidential verification runs at attestation
    time. Empty set means no evidential checks. Refs engine#711, devtown#141.

### `fallbackBinding` (`java.lang.String`)

binding name exempt from BORDERLINE exclusion; used as the backstop when
    all candidates are excluded. Nullable — null means use first candidate (declaration order).
    Refs engine#625.

### `minimumObservations` (`int`)

decision count below which routing falls to Phase 0/1 (availability)

### `qualityFloors` (`java.util.Map<java.lang.String,java.lang.Double>`)

Phase 3: dimension name → minimum acceptable quality score; candidates
    failing any floor are excluded; no penalty if dimension data is absent

### `threshold` (`double`)

minimum CAPABILITY trust score for selection (Phase 2 entry)

## Constructors

### `public TrustRoutingPolicy(double threshold, int minimumObservations, double borderlineMargin, double blendFactor, java.util.Map<java.lang.String,java.lang.Double> qualityFloors, boolean bootstrapEscalationRequired, java.lang.String fallbackBinding, java.util.Set<io.casehub.api.spi.routing.TrustPhase> evidentialCheckPhases, double cbrWeight)`

#### Parameters

- `threshold` (`double`)
- `minimumObservations` (`int`)
- `borderlineMargin` (`double`)
- `blendFactor` (`double`)
- `qualityFloors` (`java.util.Map<java.lang.String,java.lang.Double>`)
- `bootstrapEscalationRequired` (`boolean`)
- `fallbackBinding` (`java.lang.String`)
- `evidentialCheckPhases` (`java.util.Set<io.casehub.api.spi.routing.TrustPhase>`)
- `cbrWeight` (`double`)

## Methods

### `public double blendFactor()`

### `public boolean bootstrapEscalationRequired()`

### `public double borderlineMargin()`

### `public double cbrWeight()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public java.util.Set<io.casehub.api.spi.routing.TrustPhase> evidentialCheckPhases()`

### `public java.lang.String fallbackBinding()`

### `public final int hashCode()`

### `public boolean isBootstrap(int decisionCount)`

True when an agent lacks sufficient decision history for trust-based routing (Phase 0/1).

#### Parameters

- `decisionCount` (`int`)

### `public boolean isBorderline(double score)`

True when the trust score is within `borderlineMargin` of `threshold`.

<p>A borderline candidate is NOT qualified for assignment. Borderline is a distinct Phase 2a
state that triggers human oversight when all candidates are in this state.

#### Parameters

- `score` (`double`)

### `public int minimumObservations()`

### `public boolean passesThresholdCheck(double score)`

True when the score exceeds the threshold and is not borderline.

<p>This is a Phase 2 first-pass check only — Phase 3 quality floors may still exclude a
candidate that passes this check. Do not interpret as "ready to assign".

#### Parameters

- `score` (`double`)

### `public java.util.Map<java.lang.String,java.lang.Double> qualityFloors()`

### `public double threshold()`

### `public final java.lang.String toString()`
