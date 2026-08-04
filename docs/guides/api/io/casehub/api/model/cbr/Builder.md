# io.casehub.api.model.cbr.CbrConfig.Builder

**Package:** `io.casehub.api.model.cbr`

**Kind:** `class`

## Fields

### `caseType` (`java.lang.String`)

### `cbrType` (`java.lang.String`)

### `domain` (`java.lang.String`)

### `jqFeatures` (`java.util.Map<java.lang.String,java.lang.String>`)

### `lambdaExtractor` (`java.util.function.Function<io.casehub.api.context.CaseContext,java.util.Map<java.lang.String,java.lang.Object>>`)

### `minSimilarity` (`double`)

### `temporalDecayHalfLifeDays` (`java.lang.Integer`)

### `timing` (`io.casehub.api.model.cbr.CbrConfig.CbrRetrievalTiming`)

### `topK` (`int`)

### `vectorWeight` (`double`)

### `weights` (`java.util.Map<java.lang.String,java.lang.Double>`)

## Constructors

### `public Builder()`

## Methods

### `public io.casehub.api.model.cbr.CbrConfig build()`

### `public io.casehub.api.model.cbr.CbrConfig.Builder caseType(java.lang.String caseType)`

#### Parameters

- `caseType` (`java.lang.String`)

### `public io.casehub.api.model.cbr.CbrConfig.Builder cbrType(java.lang.String cbrType)`

#### Parameters

- `cbrType` (`java.lang.String`)

### `public io.casehub.api.model.cbr.CbrConfig.Builder domain(java.lang.String domain)`

#### Parameters

- `domain` (`java.lang.String`)

### `public io.casehub.api.model.cbr.CbrConfig.Builder feature(java.lang.String name, java.lang.String jqExpression)`

#### Parameters

- `name` (`java.lang.String`)
- `jqExpression` (`java.lang.String`)

### `public io.casehub.api.model.cbr.CbrConfig.Builder featureExtractor(java.util.function.Function<io.casehub.api.context.CaseContext,java.util.Map<java.lang.String,java.lang.Object>> extractor)`

#### Parameters

- `extractor` (`java.util.function.Function<io.casehub.api.context.CaseContext,java.util.Map<java.lang.String,java.lang.Object>>`)

### `public io.casehub.api.model.cbr.CbrConfig.Builder minSimilarity(double minSimilarity)`

#### Parameters

- `minSimilarity` (`double`)

### `public io.casehub.api.model.cbr.CbrConfig.Builder temporalDecayHalfLifeDays(java.lang.Integer temporalDecayHalfLifeDays)`

#### Parameters

- `temporalDecayHalfLifeDays` (`java.lang.Integer`)

### `public io.casehub.api.model.cbr.CbrConfig.Builder timing(io.casehub.api.model.cbr.CbrConfig.CbrRetrievalTiming timing)`

#### Parameters

- `timing` (`io.casehub.api.model.cbr.CbrConfig.CbrRetrievalTiming`)

### `public io.casehub.api.model.cbr.CbrConfig.Builder topK(int topK)`

#### Parameters

- `topK` (`int`)

### `public io.casehub.api.model.cbr.CbrConfig.Builder vectorWeight(double vectorWeight)`

#### Parameters

- `vectorWeight` (`double`)

### `public io.casehub.api.model.cbr.CbrConfig.Builder weight(java.lang.String featureName, double weight)`

#### Parameters

- `featureName` (`java.lang.String`)
- `weight` (`double`)
