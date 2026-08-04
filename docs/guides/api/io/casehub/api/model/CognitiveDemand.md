# io.casehub.api.model.CognitiveDemand

**Package:** `io.casehub.api.model`

**Kind:** `record`

Weighted cognitive function demand profile for a capability. Keys are Jungian function name
strings (Ti, Te, Fi, Fe, Si, Se, Ni, Ne). Weights are 0.0–1.0 and must sum to 1.0.

<p>Used by `PersonalitySignalProvider` to score candidates by alignment between the task's
cognitive demands and the agent's personality profile.

## Fields

### `functionWeights` (`java.util.Map<java.lang.String,java.lang.Double>`)

## Record Components

### `functionWeights` (`java.util.Map<java.lang.String,java.lang.Double>`)

function name → demand weight, summing to 1.0

## Constructors

### `public CognitiveDemand(java.util.Map<java.lang.String,java.lang.Double> functionWeights)`

#### Parameters

- `functionWeights` (`java.util.Map<java.lang.String,java.lang.Double>`)

## Methods

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public java.util.Map<java.lang.String,java.lang.Double> functionWeights()`

### `public final int hashCode()`

### `public final java.lang.String toString()`
