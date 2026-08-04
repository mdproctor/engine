# io.casehub.api.model.cbr.FeatureExtractor

**Package:** `io.casehub.api.model.cbr`

**Kind:** `interface`

Feature extractor for CBR case retrieval. Sealed hierarchy enforces exhaustiveness checks in
pattern matching when selecting retrieval features from case contexts.

<p>Two modes: `JqFeatureExtractor` for YAML-defined feature expressions, and `LambdaFeatureExtractor` for Java DSL programmatic extraction.

## Methods

### `public abstract java.lang.String type()`

Discriminator for the feature extractor type.

#### Returns

"jq" for `JqFeatureExtractor`, "lambda" for `LambdaFeatureExtractor`
