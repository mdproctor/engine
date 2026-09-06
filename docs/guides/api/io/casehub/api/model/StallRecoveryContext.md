# io.casehub.api.model.StallRecoveryContext

**Package:** `io.casehub.api.model`

**Kind:** `record`

## Fields

### `affectedAgentIds` (`java.util.List<java.lang.String>`)

### `alertContext` (`AlertContext`)

### `alertSummary` (`java.lang.String`)

### `caseId` (`java.util.UUID`)

### `conditionType` (`WatchdogConditionType`)

### `firedAt` (`java.time.Instant`)

### `resolvedBindingName` (`java.lang.String`)

### `resolvedPlanItemId` (`java.lang.String`)

### `tenancyId` (`java.lang.String`)

## Record Components

### `affectedAgentIds` (`java.util.List<java.lang.String>`)

### `alertContext` (`AlertContext`)

### `alertSummary` (`java.lang.String`)

### `caseId` (`java.util.UUID`)

### `conditionType` (`WatchdogConditionType`)

### `firedAt` (`java.time.Instant`)

### `resolvedBindingName` (`java.lang.String`)

### `resolvedPlanItemId` (`java.lang.String`)

### `tenancyId` (`java.lang.String`)

## Constructors

### `public StallRecoveryContext(java.util.UUID caseId, java.lang.String tenancyId, WatchdogConditionType conditionType, java.util.List<java.lang.String> affectedAgentIds, java.lang.String alertSummary, AlertContext alertContext, java.time.Instant firedAt, java.lang.String resolvedBindingName, java.lang.String resolvedPlanItemId)`

#### Parameters

- `caseId` (`java.util.UUID`)
- `tenancyId` (`java.lang.String`)
- `conditionType` (`WatchdogConditionType`)
- `affectedAgentIds` (`java.util.List<java.lang.String>`)
- `alertSummary` (`java.lang.String`)
- `alertContext` (`AlertContext`)
- `firedAt` (`java.time.Instant`)
- `resolvedBindingName` (`java.lang.String`)
- `resolvedPlanItemId` (`java.lang.String`)

## Methods

### `public java.util.List<java.lang.String> affectedAgentIds()`

### `public AlertContext alertContext()`

### `public java.lang.String alertSummary()`

### `public java.util.UUID caseId()`

### `public WatchdogConditionType conditionType()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public java.time.Instant firedAt()`

### `public final int hashCode()`

### `public java.lang.String resolvedBindingName()`

### `public java.lang.String resolvedPlanItemId()`

### `public java.lang.String tenancyId()`

### `public final java.lang.String toString()`

### `public io.casehub.api.model.StallRecoveryContext withBinding(java.lang.String bindingName, java.lang.String planItemId)`

#### Parameters

- `bindingName` (`java.lang.String`)
- `planItemId` (`java.lang.String`)
