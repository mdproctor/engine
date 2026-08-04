# io.casehub.api.model.ExtensionTarget

**Package:** `io.casehub.api.model`

**Kind:** `interface`

Runtime plugin escape hatch for binding targets not yet in the sealed hierarchy.

<p>No dispatcher exists in the engine for `ExtensionTarget` — unknown extension targets are
logged as warnings. Implementations must be registered explicitly with the engine runtime.
