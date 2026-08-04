# io.casehub.engine.plan.DagNode

**Package:** `io.casehub.engine.plan`

**Kind:** `record`

## Fields

### `dependsOn` (`java.util.Set<java.lang.String>`)

### `id` (`java.lang.String`)

### `joinType` (`io.casehub.engine.plan.JoinType`)

### `task` (`T`)

## Record Components

### `dependsOn` (`java.util.Set<java.lang.String>`)

### `id` (`java.lang.String`)

### `joinType` (`io.casehub.engine.plan.JoinType`)

### `task` (`T`)

## Constructors

### `public DagNode(java.lang.String id, T task, java.util.Set<java.lang.String> dependsOn, io.casehub.engine.plan.JoinType joinType)`

#### Parameters

- `id` (`java.lang.String`)
- `task` (`T`)
- `dependsOn` (`java.util.Set<java.lang.String>`)
- `joinType` (`io.casehub.engine.plan.JoinType`)

## Methods

### `public java.util.Set<java.lang.String> dependsOn()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public java.lang.String id()`

### `public io.casehub.engine.plan.JoinType joinType()`

### `public T task()`

### `public final java.lang.String toString()`
