# io.casehub.api.model.ScheduleTrigger

**Package:** `io.casehub.api.model`

**Kind:** `class`

Time-based trigger for scheduling worker execution. Supports cron-based periodic scheduling and
delay-based one-shot scheduling. Use `Binding.getWhen()` to add conditions for conditional
execution.

## Fields

### `cron` (`java.lang.String`)

### `delay` (`java.time.Duration`)

## Constructors

### `private ScheduleTrigger(java.lang.String cron, java.time.Duration delay)`

#### Parameters

- `cron` (`java.lang.String`)
- `delay` (`java.time.Duration`)

## Methods

### `public static io.casehub.api.model.ScheduleTrigger cron(java.lang.String cronExpression)`

Create a cron-based periodic trigger.

#### Parameters

- `cronExpression` (`java.lang.String`) — Quartz cron expression

#### Returns

ScheduleTrigger configured for periodic execution

### `public static io.casehub.api.model.ScheduleTrigger delay(java.time.Duration delay)`

Create a delay-based one-shot trigger.

#### Parameters

- `delay` (`java.time.Duration`) — Duration to wait before execution

#### Returns

ScheduleTrigger configured for delayed one-shot execution

### `public java.lang.String getCron()`

Returns the cron expression if this is a cron-based trigger.

#### Returns

cron expression, or null if this is a delay-based trigger

### `public java.time.Duration getDelay()`

Returns the delay duration if this is a delay-based trigger.

#### Returns

delay duration, or null if this is a cron-based trigger

### `public boolean isCron()`

Returns true if this is a cron-based periodic trigger.

#### Returns

true if cron expression is set

### `public boolean isDelay()`

Returns true if this is a delay-based one-shot trigger.

#### Returns

true if delay duration is set

### `public java.lang.String toString()`
