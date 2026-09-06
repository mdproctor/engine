# io.casehub.api.spi.recovery.StallClassificationContext

**Package:** `io.casehub.api.spi.recovery`

**Kind:** `record`

## Fields

### `definition` (`io.casehub.api.model.CaseDefinition`)

### `policy` (`io.casehub.api.model.StallRecoveryPolicy`)

### `recoveryContext` (`io.casehub.api.model.StallRecoveryContext`)

## Record Components

### `definition` (`io.casehub.api.model.CaseDefinition`)

### `policy` (`io.casehub.api.model.StallRecoveryPolicy`)

### `recoveryContext` (`io.casehub.api.model.StallRecoveryContext`)

## Constructors

### `public StallClassificationContext(io.casehub.api.model.StallRecoveryContext recoveryContext, io.casehub.api.model.CaseDefinition definition, io.casehub.api.model.StallRecoveryPolicy policy)`

#### Parameters

- `recoveryContext` (`io.casehub.api.model.StallRecoveryContext`)
- `definition` (`io.casehub.api.model.CaseDefinition`)
- `policy` (`io.casehub.api.model.StallRecoveryPolicy`)

## Methods

### `public io.casehub.api.model.CaseDefinition definition()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public io.casehub.api.model.StallRecoveryPolicy policy()`

### `public io.casehub.api.model.StallRecoveryContext recoveryContext()`

### `public final java.lang.String toString()`
