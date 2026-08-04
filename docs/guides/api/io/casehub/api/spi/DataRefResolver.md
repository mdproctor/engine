# io.casehub.api.spi.DataRefResolver

**Package:** `io.casehub.api.spi`

**Kind:** `interface`

Resolves `DataRef` references to domain objects.

<p>CDI-discovered. Each resolver declares `.id()` matching the `source` field on
DataRef values it handles. No `@DefaultBean` — if no resolver exists for a source,
resolution fails fast.

## Methods

### `public abstract T resolve(io.casehub.api.context.DataRef<T> ref)`

#### Parameters

- `ref` (`io.casehub.api.context.DataRef<T>`)
