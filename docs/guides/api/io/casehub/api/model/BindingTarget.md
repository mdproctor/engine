# io.casehub.api.model.BindingTarget

**Package:** `io.casehub.api.model`

**Kind:** `interface`

Sealed discriminator for what a `Binding` targets.

<p>Permits: `CapabilityTarget`, `SubCaseTarget`, `HumanTaskTarget`, `ExtensionTarget`.

<p>All dispatch sites use exhaustive switch pattern matching (Java 21), which provides
compile-time guarantee that all sealed permits are handled.
