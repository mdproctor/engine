# io.casehub.api.model.SubCase

**Package:** `io.casehub.api.model`

**Kind:** `class`

Identifies a child case definition to launch as part of a Stage's work. SubCase binding wiring:
casehubio/engine#195. M-of-N coordination: casehubio/engine#112.

## Fields

### `completionStrategy` (`io.casehub.api.model.SubCaseCompletionStrategy`)

### `groupId` (`java.lang.String`)

### `inputMapping` (`io.casehub.api.model.SubCaseMapping`)

### `maxRecursionDepth` (`int`)

### `name` (`java.lang.String`)

### `namespace` (`java.lang.String`)

### `onThresholdReached` (`io.casehub.api.model.OnThresholdReached`)

### `outputMapping` (`io.casehub.api.model.SubCaseMapping`)

### `requiredCount` (`int`)

### `totalInGroup` (`int`)

### `version` (`java.lang.String`)

### `waitForCompletion` (`boolean`)

## Constructors

### `private SubCase(io.casehub.api.model.SubCase.Builder b)`

#### Parameters

- `b` (`io.casehub.api.model.SubCase.Builder`)

## Methods

### `public static io.casehub.api.model.SubCase.Builder builder()`

### `public io.casehub.api.model.SubCaseCompletionStrategy completionStrategy()`

### `public java.lang.String groupId()`

### `public io.casehub.api.model.SubCaseMapping inputMapping()`

### `public int maxRecursionDepth()`

### `public java.lang.String name()`

### `public java.lang.String namespace()`

### `public io.casehub.api.model.OnThresholdReached onThresholdReached()`

### `public io.casehub.api.model.SubCaseMapping outputMapping()`

### `public int requiredCount()`

### `public int totalInGroup()`

### `public java.lang.String version()`

### `public boolean waitForCompletion()`
