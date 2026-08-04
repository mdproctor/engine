# io.casehub.api.spi.routing.JqCandidateSetStrategy

**Package:** `io.casehub.api.spi.routing`

**Kind:** `class`

Self-contained JQ-based `CandidateSetStrategy` value object for the fluent builder API.

<p>Compiles and evaluates the JQ expression using jackson-jq directly, without needing `ExpressionEngineRegistry`. Suitable for programmatic case definitions where no CDI context is
available. For YAML-loaded definitions, `ExpressionSetStrategy` (in the runtime module) is
preferred because it delegates to the pluggable expression engine SPI.

## Fields

### `ROOT_SCOPE` (`Scope`)

### `compiledQuery` (`JsonQuery`)

### `expression` (`java.lang.String`)

## Constructors

### `public JqCandidateSetStrategy(java.lang.String expression)`

#### Parameters

- `expression` (`java.lang.String`)

## Methods

### `public java.util.Set<java.lang.String> evaluate(io.casehub.api.spi.routing.CandidateSetContext context)`

#### Parameters

- `context` (`io.casehub.api.spi.routing.CandidateSetContext`)

### `public java.lang.String expression()`

### `public java.lang.String id()`
