# io.casehub.api.model.StallRecoveryPolicy

**Package:** `io.casehub.api.model`

**Kind:** `record`

## Fields

### `DEFAULT` (`io.casehub.api.model.StallRecoveryPolicy`)

### `classifierId` (`java.lang.String`)

### `conditionActions` (`java.util.Map<WatchdogConditionType,io.casehub.api.model.StallRecoveryAction>`)

### `defaultAction` (`io.casehub.api.model.StallRecoveryAction`)

### `enabled` (`boolean`)

## Record Components

### `classifierId` (`java.lang.String`)

### `conditionActions` (`java.util.Map<WatchdogConditionType,io.casehub.api.model.StallRecoveryAction>`)

### `defaultAction` (`io.casehub.api.model.StallRecoveryAction`)

### `enabled` (`boolean`)

## Constructors

### `public StallRecoveryPolicy(boolean enabled, java.lang.String classifierId, java.util.Map<WatchdogConditionType,io.casehub.api.model.StallRecoveryAction> conditionActions, io.casehub.api.model.StallRecoveryAction defaultAction)`

#### Parameters

- `enabled` (`boolean`)
- `classifierId` (`java.lang.String`)
- `conditionActions` (`java.util.Map<WatchdogConditionType,io.casehub.api.model.StallRecoveryAction>`)
- `defaultAction` (`io.casehub.api.model.StallRecoveryAction`)

## Methods

### `public java.lang.String classifierId()`

### `public java.util.Map<WatchdogConditionType,io.casehub.api.model.StallRecoveryAction> conditionActions()`

### `public io.casehub.api.model.StallRecoveryAction defaultAction()`

### `public boolean enabled()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public final java.lang.String toString()`
