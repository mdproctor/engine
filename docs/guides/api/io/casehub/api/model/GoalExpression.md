# io.casehub.api.model.GoalExpression

**Package:** `io.casehub.api.model`

**Kind:** `interface`

## Methods

### `public static io.casehub.api.model.GoalExpression allOf(io.casehub.api.model.GoalExpression[] children)`

#### Parameters

- `children` (`io.casehub.api.model.GoalExpression[]`)

### `public static io.casehub.api.model.GoalExpression allOf(io.casehub.api.model.Goal[] goals)`

#### Parameters

- `goals` (`io.casehub.api.model.Goal[]`)

### `public static io.casehub.api.model.GoalExpression allOf(java.util.Collection<io.casehub.api.model.Goal> goals)`

#### Parameters

- `goals` (`java.util.Collection<io.casehub.api.model.Goal>`)

### `public static io.casehub.api.model.GoalExpression anyOf(io.casehub.api.model.GoalExpression[] children)`

#### Parameters

- `children` (`io.casehub.api.model.GoalExpression[]`)

### `public static io.casehub.api.model.GoalExpression anyOf(io.casehub.api.model.Goal[] goals)`

#### Parameters

- `goals` (`io.casehub.api.model.Goal[]`)

### `public static io.casehub.api.model.GoalExpression goal(java.lang.String name)`

#### Parameters

- `name` (`java.lang.String`)

### `public abstract java.util.Set<java.lang.String> goalNames()`

### `public abstract boolean isSatisfiedBy(java.util.Set<java.lang.String> reachedGoalNames)`

#### Parameters

- `reachedGoalNames` (`java.util.Set<java.lang.String>`)

### `public abstract java.lang.String satisfiedGoalName(java.util.Set<java.lang.String> reachedGoalNames)`

#### Parameters

- `reachedGoalNames` (`java.util.Set<java.lang.String>`)
