# io.casehub.api.engine.ExpressionEngineRegistry

**Package:** `io.casehub.api.engine`

**Kind:** `interface`

Registry for expression engines.

<p>Dispatches expression evaluation to the appropriate `io.casehub.api.engine.ExpressionEngine` by evaluator type. All CDI beans implementing `io.casehub.api.engine.ExpressionEngine` are discovered automatically.

## Methods

### `public abstract void assertLanguageSupported(java.lang.String expressionLang)`

Asserts that a registered `io.casehub.api.engine.ExpressionEngine` exists for `expressionLang` and that it supports creation from string expressions.

<p>Does NOT call `io.casehub.api.engine.ExpressionEngine.create` — no domain objects are
constructed as a side effect. Use this for fail-fast validation before parsing expressions.

#### Parameters

- `expressionLang` (`java.lang.String`) — the language identifier to check

#### Throws

- `IllegalArgumentException` — if no engine is registered for `expressionLang`
- `UnsupportedOperationException` — if the engine is registered but Java-DSL-only (does not
    override `create()`)

### `public abstract ExpressionEvaluator create(java.lang.String expression, java.lang.String expressionLang)`

Creates an `ExpressionEvaluator` for the given expression language by dispatching to the
`io.casehub.api.engine.ExpressionEngine` whose `type()` equals `expressionLang`.

<p>The returned evaluator's `type()` is asserted to equal `expressionLang` — a
contract violation by the engine's `create()` is caught immediately.

#### Parameters

- `expression` (`java.lang.String`) — the raw expression string
- `expressionLang` (`java.lang.String`) — the language identifier (e.g. `"jq"`)

#### Returns

a new evaluator whose `type()` equals `expressionLang`

#### Throws

- `IllegalArgumentException` — if no engine is registered for `expressionLang`
- `UnsupportedOperationException` — if the matching engine does not override `create()`

### `public abstract boolean evaluate(ExpressionEvaluator evaluator, JsonNode asNode)`

Evaluates the expression against a JSON node.

#### Parameters

- `evaluator` (`ExpressionEvaluator`) — the expression to evaluate
- `asNode` (`JsonNode`) — the JSON node to evaluate against

#### Returns

`true` if the expression matches

#### Throws

- `IllegalArgumentException` — if no engine is registered for the evaluator type

### `public abstract boolean evaluate(ExpressionEvaluator evaluator, io.casehub.api.context.CaseContext context)`

Evaluates the expression against the given context.

#### Parameters

- `evaluator` (`ExpressionEvaluator`) — the expression to evaluate; returns `true` if `null`
- `context` (`io.casehub.api.context.CaseContext`) — the current case state

#### Returns

`true` if the expression matches or is absent

#### Throws

- `IllegalArgumentException` — if no engine is registered for the evaluator type

### `public abstract java.util.Optional<java.lang.String> extractString(ExpressionEvaluator evaluator, io.casehub.api.context.CaseContext context)`

Extracts a string value from the given context using the expression in `evaluator`.

<p>Dispatches to the registered `ExpressionEngine` whose `ExpressionEngine.type()`
matches `ExpressionEvaluator.type()`. If the matched engine does not override CaseContext), this method returns `Optional.empty()` and logs a WARN — it does NOT propagate `UnsupportedOperationException`.

#### Parameters

- `evaluator` (`ExpressionEvaluator`) — the expression; returns empty if null
- `context` (`io.casehub.api.context.CaseContext`) — the current case state

#### Returns

the extracted string, or empty if unavailable or unsupported

#### Throws

- `IllegalArgumentException` — if no engine is registered for the evaluator type

### `public abstract java.util.List<JsonNode> transform(ExpressionEvaluator evaluator, JsonNode input)`

Transforms the input JSON by applying the expression and returning the result(s).

<p>Unlike `.evaluate`, which returns a boolean condition result, this method returns the
actual transformed output — used for output/input schema evaluation where the expression
reshapes data rather than testing a condition.

#### Parameters

- `evaluator` (`ExpressionEvaluator`) — the expression to apply; returns `List.of(input)` if `null`
- `input` (`JsonNode`) — the JSON to transform

#### Returns

the transformation result(s); never `null`

#### Throws

- `IllegalArgumentException` — if no engine is registered for the evaluator type, or if
    evaluation fails

### `public abstract void validate(ExpressionEvaluator evaluator)`

Validates the expression syntax without evaluating it against any context.

<p>Blocks case definition registration if the expression is invalid.

#### Parameters

- `evaluator` (`ExpressionEvaluator`) — the expression to validate; no-op if `null`

#### Throws

- `IllegalArgumentException` — if the expression is syntactically invalid or no engine is
    registered for the evaluator type
