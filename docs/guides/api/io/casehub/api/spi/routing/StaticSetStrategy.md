# io.casehub.api.spi.routing.StaticSetStrategy

**Package:** `io.casehub.api.spi.routing`

**Kind:** `class`

## Fields

### `values` (`java.util.Set<java.lang.String>`)

## Constructors

### `private StaticSetStrategy(java.util.Set<java.lang.String> values)`

#### Parameters

- `values` (`java.util.Set<java.lang.String>`)

## Methods

### `public java.util.Set<java.lang.String> evaluate(io.casehub.api.spi.routing.CandidateSetContext context)`

#### Parameters

- `context` (`io.casehub.api.spi.routing.CandidateSetContext`)

### `public java.lang.String id()`

### `public static io.casehub.api.spi.routing.StaticSetStrategy of(java.lang.String[] values)`

#### Parameters

- `values` (`java.lang.String[]`)

### `public static io.casehub.api.spi.routing.StaticSetStrategy of(java.util.Set<java.lang.String> values)`

#### Parameters

- `values` (`java.util.Set<java.lang.String>`)

### `public java.util.Set<java.lang.String> values()`
