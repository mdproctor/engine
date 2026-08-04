# io.casehub.api.model.AnyOfGoalExpression

**Package:** `io.casehub.api.model`

**Kind:** `record`

## Fields

### `children` (`java.util.List<io.casehub.api.model.GoalExpression>`)

## Record Components

### `children` (`java.util.List<io.casehub.api.model.GoalExpression>`)

## Constructors

### `public AnyOfGoalExpression(java.util.List<io.casehub.api.model.GoalExpression> children)`

#### Parameters

- `children` (`java.util.List<io.casehub.api.model.GoalExpression>`)

## Methods

### `public java.util.List<io.casehub.api.model.GoalExpression> children()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public java.util.Set<java.lang.String> goalNames()`

### `public final int hashCode()`

### `public boolean isSatisfiedBy(java.util.Set<java.lang.String> reachedGoalNames)`

#### Parameters

- `reachedGoalNames` (`java.util.Set<java.lang.String>`)

### `public java.lang.String satisfiedGoalName(java.util.Set<java.lang.String> reachedGoalNames)`

#### Parameters

- `reachedGoalNames` (`java.util.Set<java.lang.String>`)

### `public final java.lang.String toString()`
