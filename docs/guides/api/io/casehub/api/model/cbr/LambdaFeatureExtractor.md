# io.casehub.api.model.cbr.LambdaFeatureExtractor

**Package:** `io.casehub.api.model.cbr`

**Kind:** `class`

Lambda-based feature extractor. Accepts a Java function that extracts features from a case
context. Used in Java DSL for programmatic CBR configurations.

<p>Not serializable — use `JqFeatureExtractor` for YAML-defined cases.

## Fields

### `TYPE` (`java.lang.String`)

### `extractionFunction` (`java.util.function.Function<io.casehub.api.context.CaseContext,java.util.Map<java.lang.String,java.lang.Object>>`)

## Constructors

### `public LambdaFeatureExtractor(java.util.function.Function<io.casehub.api.context.CaseContext,java.util.Map<java.lang.String,java.lang.Object>> extractionFunction)`

#### Parameters

- `extractionFunction` (`java.util.function.Function<io.casehub.api.context.CaseContext,java.util.Map<java.lang.String,java.lang.Object>>`)

## Methods

### `public java.util.Map<java.lang.String,java.lang.Object> extract(io.casehub.api.context.CaseContext context)`

Extracts features from the given case context.

#### Parameters

- `context` (`io.casehub.api.context.CaseContext`) — the case context

#### Returns

map of feature name → value

### `public java.lang.String type()`
