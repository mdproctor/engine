# io.casehub.api.context.PropagationContext

**Package:** `io.casehub.api.context`

**Kind:** `class`

Immutable tracing and budget context that flows from parent to child across a case hierarchy.

<p>Carries a W3C-compatible trace ID (shared across the entire hierarchy), inherited attributes
(e.g. tenantId, userId), and an optional resource budget expressed as a deadline and remaining
duration. Child contexts inherit the parent's trace ID and reduce the remaining budget by the
elapsed time since the parent was created.

<p>Use `.createRoot()` or its overloads to start a new trace, and `.createChild()` /
`.createChild(Map)` to propagate context through nested work. Use Map, Instant, Duration) to restore a context from persistence.

## Fields

### `createdAt` (`java.time.Instant`)

### `deadline` (`java.time.Instant`)

### `inheritedAttributes` (`java.util.Map<java.lang.String,java.lang.String>`)

### `remainingBudget` (`java.time.Duration`)

### `traceId` (`java.lang.String`)

## Constructors

### `private PropagationContext(java.lang.String traceId, java.util.Map<java.lang.String,java.lang.String> inheritedAttributes, java.time.Instant deadline, java.time.Duration remainingBudget)`

#### Parameters

- `traceId` (`java.lang.String`)
- `inheritedAttributes` (`java.util.Map<java.lang.String,java.lang.String>`)
- `deadline` (`java.time.Instant`)
- `remainingBudget` (`java.time.Duration`)

## Methods

### `public io.casehub.api.context.PropagationContext createChild()`

Creates a child context that inherits this context's trace ID and attributes. If this context
has a remaining budget, the child's budget is reduced by the elapsed time since this context
was created. The child budget is clamped to `Duration.ZERO` and never goes negative.

### `public io.casehub.api.context.PropagationContext createChild(java.util.Map<java.lang.String,java.lang.String> additionalAttributes)`

Creates a child context that inherits this context's trace ID and merges additional attributes.
Child attributes override parent attributes on key collision. If this context has a remaining
budget, the child's budget is reduced by the elapsed time since this context was created.

#### Parameters

- `additionalAttributes` (`java.util.Map<java.lang.String,java.lang.String>`) — extra attributes to add or override in the child

### `public static io.casehub.api.context.PropagationContext createRoot()`

Creates a root context with a new random W3C-compatible trace ID, no inherited attributes, and
no budget.

### `public static io.casehub.api.context.PropagationContext createRoot(java.lang.String traceId)`

Creates a root context with a caller-supplied trace ID, no inherited attributes, and no budget.
Use when the caller can supply the active OTel trace ID so that case spans are correlatable in
distributed tracing systems.

#### Parameters

- `traceId` (`java.lang.String`) — the trace ID to use (must not be null)

### `public static io.casehub.api.context.PropagationContext createRoot(java.lang.String traceId, java.util.Map<java.lang.String,java.lang.String> attributes)`

Creates a root context with a caller-supplied trace ID and inherited attributes, but no budget.

#### Parameters

- `traceId` (`java.lang.String`) — the trace ID to use (must not be null)
- `attributes` (`java.util.Map<java.lang.String,java.lang.String>`) — key-value pairs to carry through the hierarchy (e.g. userId, roles)

### `public static io.casehub.api.context.PropagationContext createRoot(java.lang.String traceId, java.util.Map<java.lang.String,java.lang.String> attributes, java.time.Duration budget)`

Creates a root context with a caller-supplied trace ID, inherited attributes, and a time
budget. The deadline is computed as `now + budget`.

#### Parameters

- `traceId` (`java.lang.String`) — the trace ID to use (must not be null)
- `attributes` (`java.util.Map<java.lang.String,java.lang.String>`) — key-value pairs to carry through the hierarchy
- `budget` (`java.time.Duration`) — maximum duration allowed for work under this context

### `public static io.casehub.api.context.PropagationContext createRoot(java.util.Map<java.lang.String,java.lang.String> attributes)`

Creates a root context with the given inherited attributes and no budget.

#### Parameters

- `attributes` (`java.util.Map<java.lang.String,java.lang.String>`) — key-value pairs to carry through the hierarchy (e.g. tenantId, userId)

### `public static io.casehub.api.context.PropagationContext createRoot(java.util.Map<java.lang.String,java.lang.String> attributes, java.time.Duration budget)`

Creates a root context with inherited attributes and a time budget. The deadline is computed as
`now + budget`.

#### Parameters

- `attributes` (`java.util.Map<java.lang.String,java.lang.String>`) — key-value pairs to carry through the hierarchy
- `budget` (`java.time.Duration`) — maximum duration allowed for work under this context

### `public static io.casehub.api.context.PropagationContext fromStorage(java.lang.String traceId, java.util.Map<java.lang.String,java.lang.String> attributes, java.time.Instant deadline, java.time.Duration remainingBudget)`

Reconstructs a `PropagationContext` from persistence. Used by storage providers to
restore context from entity fields.

#### Parameters

- `traceId` (`java.lang.String`) — the trace ID to restore (must not be null)
- `attributes` (`java.util.Map<java.lang.String,java.lang.String>`) — the inherited attributes, or null to use an empty map
- `deadline` (`java.time.Instant`) — the deadline instant, or null if none was set
- `remainingBudget` (`java.time.Duration`) — the remaining budget duration, or null if none was set

### `public java.util.Optional<java.lang.String> getAttribute(java.lang.String key)`

Returns the value for the given attribute key, or `Optional.empty()` if not present.

#### Parameters

- `key` (`java.lang.String`) — the attribute key to look up

### `public java.util.Optional<java.time.Instant> getDeadline()`

Returns the deadline instant, or `Optional.empty()` if no deadline was set.

### `public java.util.Map<java.lang.String,java.lang.String> getInheritedAttributes()`

Returns an unmodifiable view of the inherited attributes map.

### `public java.util.Optional<java.time.Duration> getRemainingBudget()`

Returns the remaining budget duration, or `Optional.empty()` if no budget was set. Note:
this value reflects the budget at the time the context was created (or passed from parent), not
a live countdown.

### `public java.lang.String getTraceId()`

Returns the W3C-compatible trace ID shared across the entire parent-child hierarchy.

### `public boolean isBudgetExhausted()`

Returns `true` if the budget for this context is exhausted — either the deadline has
passed or the remaining budget duration is zero or negative. Returns `false` when no
deadline or budget was set.
