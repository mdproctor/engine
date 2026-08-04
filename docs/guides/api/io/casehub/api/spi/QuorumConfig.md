# io.casehub.api.spi.QuorumConfig

**Package:** `io.casehub.api.spi`

**Kind:** `record`

## Fields

### `allowSameAssignee` (`boolean`)

### `instances` (`int`)

### `onThresholdReached` (`io.casehub.api.model.OnThresholdReached`)

### `required` (`int`)

## Record Components

### `allowSameAssignee` (`boolean`)

### `instances` (`int`)

### `onThresholdReached` (`io.casehub.api.model.OnThresholdReached`)

### `required` (`int`)

## Constructors

### `public QuorumConfig(int instances, int required, io.casehub.api.model.OnThresholdReached onThresholdReached, boolean allowSameAssignee)`

#### Parameters

- `instances` (`int`)
- `required` (`int`)
- `onThresholdReached` (`io.casehub.api.model.OnThresholdReached`)
- `allowSameAssignee` (`boolean`)

## Methods

### `public boolean allowSameAssignee()`

### `public static io.casehub.api.spi.QuorumConfig atLeast(int candidateCount, int required)`

#### Parameters

- `candidateCount` (`int`)
- `required` (`int`)

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public int instances()`

### `public static io.casehub.api.spi.QuorumConfig majority(int candidateCount)`

#### Parameters

- `candidateCount` (`int`)

### `public io.casehub.api.model.OnThresholdReached onThresholdReached()`

### `public int required()`

### `public final java.lang.String toString()`

### `public static io.casehub.api.spi.QuorumConfig unanimous(int candidateCount)`

#### Parameters

- `candidateCount` (`int`)
