# io.casehub.api.spi.routing.RoutingResult

**Package:** `io.casehub.api.spi.routing`

**Kind:** `interface`

## Methods

### `public static io.casehub.api.spi.routing.RoutingResult assigned(io.casehub.api.spi.routing.Assignment assignment)`

#### Parameters

- `assignment` (`io.casehub.api.spi.routing.Assignment`)

### `public static io.casehub.api.spi.routing.RoutingResult assigned(java.lang.String executorId, java.lang.String reason)`

#### Parameters

- `executorId` (`java.lang.String`)
- `reason` (`java.lang.String`)

### `public static io.casehub.api.spi.routing.RoutingResult assigned(java.util.List<io.casehub.api.spi.routing.Assignment> assignments)`

#### Parameters

- `assignments` (`java.util.List<io.casehub.api.spi.routing.Assignment>`)

### `public static io.casehub.api.spi.routing.RoutingResult escalate(java.lang.String capabilityName, io.casehub.api.spi.routing.EscalationReason reason, java.lang.String rationale)`

#### Parameters

- `capabilityName` (`java.lang.String`)
- `reason` (`io.casehub.api.spi.routing.EscalationReason`)
- `rationale` (`java.lang.String`)

### `public static io.casehub.api.spi.routing.RoutingResult unresolvable(java.lang.String reason)`

#### Parameters

- `reason` (`java.lang.String`)
