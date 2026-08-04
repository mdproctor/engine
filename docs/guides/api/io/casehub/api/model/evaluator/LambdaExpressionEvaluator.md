# io.casehub.api.model.evaluator.LambdaExpressionEvaluator

**Package:** `io.casehub.api.model.evaluator`

**Kind:** `class`

An `ExpressionEvaluator` backed by a Java lambda. Thin subclass of platform's `LambdaExpression` that preserves the `Predicate`-based constructor for Java DSL users.

<p>Not serialisable — use `JQExpressionEvaluator` for YAML-defined cases.

## Fields

### `TYPE` (`java.lang.String`)

## Constructors

### `public LambdaExpressionEvaluator(java.util.function.Predicate<io.casehub.api.context.CaseContext> predicate)`

#### Parameters

- `predicate` (`java.util.function.Predicate<io.casehub.api.context.CaseContext>`)

## Methods

### `public boolean test(io.casehub.api.context.CaseContext context)`

#### Parameters

- `context` (`io.casehub.api.context.CaseContext`)

### `public java.lang.String type()`
