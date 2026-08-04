# io.casehub.api.context.MutableCaseContext

**Package:** `io.casehub.api.context`

**Kind:** `interface`

Engine-internal extension of `CaseContext` that exposes writable layer access and layer
lifecycle management.

<p>Consumer code works with `CaseContext` (read/write via the flat API). Engine handlers,
the reactor, and episodic layer management work with `MutableCaseContext` to access named
writable layers and freeze layers after setup.

## Methods

### `public default void close()`

Releases resources held by this context's stores. Default no-op.

### `public abstract void freezeLayer(java.lang.String name)`

Freezes the named layer, making it read-only. Subsequent writes throw.

#### Parameters

- `name` (`java.lang.String`)

### `public abstract io.casehub.api.context.WritableLayer writableLayer(java.lang.String name)`

Returns the writable layer with the given name. Creates the layer on demand if it does not
exist.

#### Parameters

- `name` (`java.lang.String`)
