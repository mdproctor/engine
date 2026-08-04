# io.casehub.api.model.RetryState.RetryAttempt

**Package:** `io.casehub.api.model`

**Kind:** `record`

A single retry attempt record.

## Fields

### `duration` (`java.time.Duration`)

### `errorMessage` (`java.lang.String`)

### `succeeded` (`boolean`)

### `timestamp` (`java.time.Instant`)

## Record Components

### `duration` (`java.time.Duration`)

how long the attempt took

### `errorMessage` (`java.lang.String`)

the error message from the failure, null if succeeded

### `succeeded` (`boolean`)

whether the attempt succeeded

### `timestamp` (`java.time.Instant`)

when the attempt occurred

## Constructors

### `public RetryAttempt(java.time.Instant timestamp, java.lang.String errorMessage, java.time.Duration duration, boolean succeeded)`

#### Parameters

- `timestamp` (`java.time.Instant`)
- `errorMessage` (`java.lang.String`)
- `duration` (`java.time.Duration`)
- `succeeded` (`boolean`)

## Methods

### `public java.time.Duration duration()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public java.lang.String errorMessage()`

### `public final int hashCode()`

### `public boolean succeeded()`

### `public java.time.Instant timestamp()`

### `public final java.lang.String toString()`
