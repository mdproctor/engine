# io.casehub.api.model.RetryState

**Package:** `io.casehub.api.model`

**Kind:** `record`

Explicit retry attempt history for a worker execution or plan item. Records every retry attempt
with timestamp, error message, duration, and success flag.

<p>Attached to `io.casehub.api.engine.PlanExecutionContext` for `PlanningStrategy`
reasoning and `DeadLetterEntry` for DLQ enrichment.

<p>Use `.empty()` to create an instance with no attempts. Use Instant,
Instant) to create an instance from a list of attempts.

## Fields

### `attemptCount` (`int`)

### `attempts` (`java.util.List<io.casehub.api.model.RetryState.RetryAttempt>`)

### `firstAttemptTime` (`java.time.Instant`)

### `lastAttemptTime` (`java.time.Instant`)

## Record Components

### `attemptCount` (`int`)

total number of retry attempts recorded

### `attempts` (`java.util.List<io.casehub.api.model.RetryState.RetryAttempt>`)

ordered list of retry attempts, oldest first

### `firstAttemptTime` (`java.time.Instant`)

timestamp of the first retry attempt, null if no attempts

### `lastAttemptTime` (`java.time.Instant`)

timestamp of the most recent retry attempt, null if no attempts

## Constructors

### `public RetryState(int attemptCount, java.util.List<io.casehub.api.model.RetryState.RetryAttempt> attempts, java.time.Instant firstAttemptTime, java.time.Instant lastAttemptTime)`

#### Parameters

- `attemptCount` (`int`)
- `attempts` (`java.util.List<io.casehub.api.model.RetryState.RetryAttempt>`)
- `firstAttemptTime` (`java.time.Instant`)
- `lastAttemptTime` (`java.time.Instant`)

## Methods

### `public int attemptCount()`

### `public java.util.List<io.casehub.api.model.RetryState.RetryAttempt> attempts()`

### `public static io.casehub.api.model.RetryState empty()`

Creates an empty retry state with no attempts.

#### Returns

a retry state with attemptCount=0 and empty attempts list

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public java.time.Instant firstAttemptTime()`

### `public final int hashCode()`

### `public java.time.Instant lastAttemptTime()`

### `public static io.casehub.api.model.RetryState of(java.util.List<io.casehub.api.model.RetryState.RetryAttempt> attempts, java.time.Instant firstAttemptTime, java.time.Instant lastAttemptTime)`

Creates a retry state from a list of attempts.

#### Parameters

- `attempts` (`java.util.List<io.casehub.api.model.RetryState.RetryAttempt>`) — the list of retry attempts
- `firstAttemptTime` (`java.time.Instant`) — timestamp of the first attempt
- `lastAttemptTime` (`java.time.Instant`) — timestamp of the last attempt

#### Returns

a retry state with the given attempts

### `public final java.lang.String toString()`
