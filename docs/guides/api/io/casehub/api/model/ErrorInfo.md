# io.casehub.api.model.ErrorInfo

**Package:** `io.casehub.api.model`

**Kind:** `record`

Structured error information for worker outcomes and system failures.

<p>Carries machine-readable error codes, human-readable messages, optional context data, and
recoverability hints.

## Fields

### `context` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `errorCode` (`java.lang.String`)

### `message` (`java.lang.String`)

### `recoverable` (`boolean`)

## Record Components

### `context` (`java.util.Map<java.lang.String,java.lang.Object>`)

optional diagnostic context (e.g., failed field names, threshold values)

### `errorCode` (`java.lang.String`)

machine-readable error identifier (e.g., "TIMEOUT", "VALIDATION_FAILED")

### `message` (`java.lang.String`)

human-readable error description

### `recoverable` (`boolean`)

whether the error is potentially recoverable via retry or alternate path

## Constructors

### `public ErrorInfo(java.lang.String errorCode, java.lang.String message, java.util.Map<java.lang.String,java.lang.Object> context, boolean recoverable)`

#### Parameters

- `errorCode` (`java.lang.String`)
- `message` (`java.lang.String`)
- `context` (`java.util.Map<java.lang.String,java.lang.Object>`)
- `recoverable` (`boolean`)

## Methods

### `public java.util.Map<java.lang.String,java.lang.Object> context()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public java.lang.String errorCode()`

### `public final int hashCode()`

### `public java.lang.String message()`

### `public static io.casehub.api.model.ErrorInfo of(java.lang.String errorCode, java.lang.String message, boolean recoverable)`

Creates an ErrorInfo with no context.

#### Parameters

- `errorCode` (`java.lang.String`) — machine-readable error identifier
- `message` (`java.lang.String`) — human-readable error description
- `recoverable` (`boolean`) — whether the error is potentially recoverable

#### Returns

new ErrorInfo instance

### `public static io.casehub.api.model.ErrorInfo of(java.lang.String errorCode, java.lang.String message, java.util.Map<java.lang.String,java.lang.Object> context, boolean recoverable)`

Creates an ErrorInfo with optional context.

#### Parameters

- `errorCode` (`java.lang.String`) — machine-readable error identifier
- `message` (`java.lang.String`) — human-readable error description
- `context` (`java.util.Map<java.lang.String,java.lang.Object>`) — diagnostic context (nullable, will be copied if non-null)
- `recoverable` (`boolean`) — whether the error is potentially recoverable

#### Returns

new ErrorInfo instance

### `public boolean recoverable()`

### `public final java.lang.String toString()`
