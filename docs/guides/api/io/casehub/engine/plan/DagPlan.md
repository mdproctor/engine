# io.casehub.engine.plan.DagPlan

**Package:** `io.casehub.engine.plan`

**Kind:** `record`

## Fields

### `nodes` (`java.util.Map<java.lang.String,io.casehub.engine.plan.DagNode<T>>`)

## Record Components

### `nodes` (`java.util.Map<java.lang.String,io.casehub.engine.plan.DagNode<T>>`)

## Constructors

### `public DagPlan(java.util.Map<java.lang.String,io.casehub.engine.plan.DagNode<T>> nodes)`

#### Parameters

- `nodes` (`java.util.Map<java.lang.String,io.casehub.engine.plan.DagNode<T>>`)

## Methods

### `private static java.util.Set<java.lang.String> computeEntryNodes(java.util.Map<java.lang.String,io.casehub.engine.plan.DagNode<T>> nodes)`

#### Parameters

- `nodes` (`java.util.Map<java.lang.String,io.casehub.engine.plan.DagNode<T>>`)

### `public java.util.Set<java.lang.String> entryNodeIds()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public java.util.Set<java.lang.String> exitNodeIds()`

### `public static io.casehub.engine.plan.DagPlan<T> fromNodes(java.util.List<io.casehub.engine.plan.DagNode<T>> nodes)`

#### Parameters

- `nodes` (`java.util.List<io.casehub.engine.plan.DagNode<T>>`)

### `public final int hashCode()`

### `public java.util.Map<java.lang.String,io.casehub.engine.plan.DagNode<T>> nodes()`

### `public static io.casehub.engine.plan.DagPlan<T> parallel(java.util.List<? extends T> tasks)`

#### Parameters

- `tasks` (`java.util.List<? extends T>`)

### `public static io.casehub.engine.plan.DagPlan<T> sequence(java.util.List<? extends T> tasks)`

#### Parameters

- `tasks` (`java.util.List<? extends T>`)

### `public static io.casehub.engine.plan.DagPlan<T> sequentialMerge(java.util.List<io.casehub.engine.plan.DagPlan<T>> subPlans)`

#### Parameters

- `subPlans` (`java.util.List<io.casehub.engine.plan.DagPlan<T>>`)

### `public static io.casehub.engine.plan.DagPlan<T> singleton(T task)`

#### Parameters

- `task` (`T`)

### `public static io.casehub.engine.plan.DagPlan<T> singleton(java.lang.String id, T task)`

#### Parameters

- `id` (`java.lang.String`)
- `task` (`T`)

### `public final java.lang.String toString()`

### `public java.util.List<io.casehub.engine.plan.DagNode<T>> topologicalSort()`

### `private static void validateNoCycles(java.util.Map<java.lang.String,io.casehub.engine.plan.DagNode<T>> nodes)`

#### Parameters

- `nodes` (`java.util.Map<java.lang.String,io.casehub.engine.plan.DagNode<T>>`)

### `private static void validateReferences(java.util.Map<java.lang.String,io.casehub.engine.plan.DagNode<T>> nodes)`

#### Parameters

- `nodes` (`java.util.Map<java.lang.String,io.casehub.engine.plan.DagNode<T>>`)
