# io.casehub.api.engine.ExpressionEngine

**Package:** `io.casehub.api.engine`

**Kind:** `interface`

SPI for pluggable expression evaluation engines.

<p>Each engine declares the `ExpressionEvaluator.type()` it handles and evaluates
expressions of that type against a `CaseContext`. Register additional engines as CDI beans
to support new expression languages (e.g. Drools, SpEL) without modifying the runtime.

## Methods

### `public default CompiledExpression<C,R> compile(java.lang.String expression, java.lang.Class<C> contextType, java.lang.Class<R> resultType)`

#### Parameters

- `expression` (`java.lang.String`)
- `contextType` (`java.lang.Class<C>`)
- `resultType` (`java.lang.Class<R>`)

### `public default CompiledExpression<C,R> compile(java.lang.String expression, java.lang.Class<C> contextType, java.lang.Class<R> resultType, java.util.Map<java.lang.String,java.lang.Object> variables)`

#### Parameters

- `expression` (`java.lang.String`)
- `contextType` (`java.lang.Class<C>`)
- `resultType` (`java.lang.Class<R>`)
- `variables` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `public default ExpressionEvaluator create(java.lang.String expression)`

Creates an `ExpressionEvaluator` from a raw expression string.

<p>Called by `io.casehub.engine.common.spi.ExpressionEngineRegistry.create` during YAML
case definition loading. Only engines that override this method can be used in YAML definitions
via `expressionLang: <type>`. Lambda-type evaluators are Java-DSL-only and intentionally
do not override this method.

<p>Contract: the returned evaluator's `type()` MUST equal this engine's `type()`.

#### Parameters

- `expression` (`java.lang.String`) — the raw expression string

#### Returns

a new evaluator for the given expression

#### Throws

- `UnsupportedOperationException` — if this engine does not support string-based creation

### `public abstract boolean evaluate(ExpressionEvaluator evaluator, io.casehub.api.context.CaseContext context)`

Evaluates the expression against the given context.

#### Parameters

- `evaluator` (`ExpressionEvaluator`) — the expression to evaluate — guaranteed to match `.type()`
- `context` (`io.casehub.api.context.CaseContext`) — the current case state

#### Returns

`true` if the expression matches, `false` otherwise

### `public default java.util.Optional<java.lang.String> extractString(ExpressionEvaluator evaluator, io.casehub.api.context.CaseContext context)`

Extracts a string value from the given context using this evaluator.

<p>Default implementation throws `UnsupportedOperationException`. Expression engines that
support value extraction (not just boolean evaluation) must override this method.

<p>The `io.casehub.engine.common.spi.ExpressionEngineRegistry` catches `UnsupportedOperationException` from this method and returns `Optional.empty()` + WARN —
so callers never see the exception propagate unless they invoke this method directly on an
engine that doesn't support it.

#### Parameters

- `evaluator` (`ExpressionEvaluator`) — the expression to evaluate — guaranteed to match `.type()`
- `context` (`io.casehub.api.context.CaseContext`) — the current case state; implementations evaluate against the WORKING layer

#### Returns

the string value extracted from context, or empty if absent or evaluation fails

### `public default boolean supportsStringCreation()`

Returns `true` if this engine overrides `.create(String)` and supports creation of
evaluators from string expressions.

<p>Used by `io.casehub.engine.common.spi.ExpressionEngineRegistry.assertLanguageSupported` to distinguish
"no engine registered" from "engine registered but Java-DSL-only".

### `public default java.util.List<JsonNode> transform(ExpressionEvaluator evaluator, JsonNode input)`

Transforms the input JSON by applying the expression and returning the result(s).

<p>Unlike `.evaluate`, which returns a boolean condition result, this method returns the
actual transformed output — used for output/input schema evaluation where the expression
reshapes data rather than testing a condition.

#### Parameters

- `evaluator` (`ExpressionEvaluator`) — the expression to apply — guaranteed to match `.type()`
- `input` (`JsonNode`) — the JSON to transform

#### Returns

the transformation result(s); never `null`

#### Throws

- `UnsupportedOperationException` — if this engine does not support transformation
- `IllegalArgumentException` — if evaluation fails

### `public abstract java.lang.String type()`

Returns the evaluator type this engine handles, matching `ExpressionEvaluator.type()`.

### `public abstract void validate(ExpressionEvaluator evaluator)`

Validates the expression syntax without evaluating it against any context.

#### Parameters

- `evaluator` (`ExpressionEvaluator`) — the expression to validate — guaranteed to match `.type()`

#### Throws

- `IllegalArgumentException` — if the expression is syntactically invalid
