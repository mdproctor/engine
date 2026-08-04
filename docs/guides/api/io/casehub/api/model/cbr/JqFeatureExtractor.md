# io.casehub.api.model.cbr.JqFeatureExtractor

**Package:** `io.casehub.api.model.cbr`

**Kind:** `record`

JQ-based feature extractor. Maps feature names to JQ expressions that extract values from case
contexts. Used in YAML-defined CBR configurations.

## Fields

### `TYPE` (`java.lang.String`)

### `featureExpressions` (`java.util.Map<java.lang.String,java.lang.String>`)

## Record Components

### `featureExpressions` (`java.util.Map<java.lang.String,java.lang.String>`)

Map of feature name → JQ expression (e.g., "amount" →
    ".transaction.amount")

## Constructors

### `public JqFeatureExtractor(java.util.Map<java.lang.String,java.lang.String> featureExpressions)`

#### Parameters

- `featureExpressions` (`java.util.Map<java.lang.String,java.lang.String>`)

## Methods

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public java.util.Map<java.lang.String,java.lang.String> featureExpressions()`

### `public final int hashCode()`

### `public final java.lang.String toString()`

### `public java.lang.String type()`
