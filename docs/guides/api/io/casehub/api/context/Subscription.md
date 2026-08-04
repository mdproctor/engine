# io.casehub.api.context.Subscription

**Package:** `io.casehub.api.context`

**Kind:** `interface`

Handle returned by `CaseContext.onChange` and `CaseContext.onAnyChange` to allow
callers to unsubscribe from change notifications.

<p><b>Callers must call `.cancel()` when the listener is no longer needed.</b> Listeners
are held by strong reference in the CaseContext and will accumulate if not cancelled, causing
unbounded memory growth in long-running cases.

## Fields

### `NOOP` (`io.casehub.api.context.Subscription`)

A no-op subscription whose `.cancel()` method does nothing.

## Methods

### `public abstract void cancel()`

Removes this listener from the context. Subsequent changes will no longer be notified.
