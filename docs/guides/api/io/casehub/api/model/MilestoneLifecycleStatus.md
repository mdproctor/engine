# io.casehub.api.model.MilestoneLifecycleStatus

**Package:** `io.casehub.api.model`

**Kind:** `enum`

Lifecycle status of a milestone.

<p>A milestone progresses: PENDING → ACTIVE → COMPLETED.

<ul>
  <li><b>PENDING</b> — waiting for entryCriteria to become true
  <li><b>ACTIVE</b> — entryCriteria met, working toward completionCriteria
  <li><b>COMPLETED</b> — completionCriteria met successfully
</ul>

## Enum Constants

### `ACTIVE` (`io.casehub.api.model.MilestoneLifecycleStatus`)

### `COMPLETED` (`io.casehub.api.model.MilestoneLifecycleStatus`)

### `PENDING` (`io.casehub.api.model.MilestoneLifecycleStatus`)

## Constructors

### `private MilestoneLifecycleStatus()`

## Methods

### `public static io.casehub.api.model.MilestoneLifecycleStatus valueOf(java.lang.String name)`

#### Parameters

- `name` (`java.lang.String`)

### `public static io.casehub.api.model.MilestoneLifecycleStatus[] values()`
