# io.casehub.api.model.GoalKind

**Package:** `io.casehub.api.model`

**Kind:** `interface`

Classifies a goal kind and maps it to a terminal `CaseStatus`.

<p>Implementations must provide value-based equals/hashCode — GoalKind instances serve as map
keys in `GoalBasedCompletion`.

## Fields

### `FAILURE` (`io.casehub.api.model.GoalKind`)

### `SUCCESS` (`io.casehub.api.model.GoalKind`)

## Methods

### `public static io.casehub.api.model.GoalKind fromValue(java.lang.String value)`

#### Parameters

- `value` (`java.lang.String`)

### `public static io.casehub.api.model.GoalKind of(java.lang.String value, io.casehub.api.model.CaseStatus terminalStatus)`

#### Parameters

- `value` (`java.lang.String`)
- `terminalStatus` (`io.casehub.api.model.CaseStatus`)

### `public abstract io.casehub.api.model.CaseStatus terminalStatus()`

### `public abstract java.lang.String value()`
