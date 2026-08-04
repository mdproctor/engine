# io.casehub.api.model.routing.ContextConstraint

**Package:** `io.casehub.api.model.routing`

**Kind:** `record`

## Fields

### `condition` (`ExpressionEvaluator`)

### `effect` (`io.casehub.api.model.routing.ContextConstraint.Effect`)

### `weight` (`double`)

## Record Components

### `condition` (`ExpressionEvaluator`)

### `effect` (`io.casehub.api.model.routing.ContextConstraint.Effect`)

### `weight` (`double`)

## Constructors

### `public ContextConstraint(ExpressionEvaluator condition, io.casehub.api.model.routing.ContextConstraint.Effect effect, double weight)`

#### Parameters

- `condition` (`ExpressionEvaluator`)
- `effect` (`io.casehub.api.model.routing.ContextConstraint.Effect`)
- `weight` (`double`)

## Methods

### `public static io.casehub.api.model.routing.ContextConstraint.Builder builder()`

### `public ExpressionEvaluator condition()`

### `public io.casehub.api.model.routing.ContextConstraint.Effect effect()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public final java.lang.String toString()`

### `public double weight()`
