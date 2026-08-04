# io.casehub.api.spi.routing.RequirementStatus

**Package:** `io.casehub.api.spi.routing`

**Kind:** `enum`

## Enum Constants

### `BREACHED` (`io.casehub.api.spi.routing.RequirementStatus`)

Mechanism present but obligation not met (e.g. SLA deadline passed).

### `CLOSED` (`io.casehub.api.spi.routing.RequirementStatus`)

Requirement demonstrably met with evidence.

### `GAP` (`io.casehub.api.spi.routing.RequirementStatus`)

Architectural gap; requirement not addressed.

### `PARTIAL` (`io.casehub.api.spi.routing.RequirementStatus`)

Mechanism present but evidence incomplete.

## Constructors

### `private RequirementStatus()`

## Methods

### `public static io.casehub.api.spi.routing.RequirementStatus valueOf(java.lang.String name)`

#### Parameters

- `name` (`java.lang.String`)

### `public static io.casehub.api.spi.routing.RequirementStatus[] values()`
