# io.casehub.api.model.TaskStatus

**Package:** `io.casehub.api.model`

**Kind:** `enum`

Shared lifecycle states for any coordination model's unit of work.

<p><b>Active states:</b>

<ul>
  <li>PENDING — work defined, not yet started
  <li>RUNNING — actively executing
  <li>DELEGATED — control passed to external actor; waiting for completion signal
  <li>SUSPENDED — execution paused; slot occupied, resumes without re-dispatch
</ul>

<p><b>Terminal states:</b>

<ul>
  <li>COMPLETED — finished successfully
  <li>FAULTED — failed (system failure, deadline breach, or gate rejection)
  <li>REJECTED — actor deliberately refused the work
  <li>OBSOLETE — context changed, work became irrelevant
  <li>CANCELLED — deliberate stop by human or system
</ul>

<p>Stored as STRING in JPA — ordinal safety is not a concern.

## Enum Constants

### `CANCELLED` (`io.casehub.api.model.TaskStatus`)

### `COMPLETED` (`io.casehub.api.model.TaskStatus`)

### `DELEGATED` (`io.casehub.api.model.TaskStatus`)

### `FAULTED` (`io.casehub.api.model.TaskStatus`)

### `OBSOLETE` (`io.casehub.api.model.TaskStatus`)

### `PENDING` (`io.casehub.api.model.TaskStatus`)

### `REJECTED` (`io.casehub.api.model.TaskStatus`)

### `RUNNING` (`io.casehub.api.model.TaskStatus`)

### `SUSPENDED` (`io.casehub.api.model.TaskStatus`)

## Constructors

### `private TaskStatus()`

## Methods

### `public boolean isActive()`

### `public boolean isTerminal()`

### `public static io.casehub.api.model.TaskStatus valueOf(java.lang.String name)`

#### Parameters

- `name` (`java.lang.String`)

### `public static io.casehub.api.model.TaskStatus[] values()`
