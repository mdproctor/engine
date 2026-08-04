# io.casehub.api.spi.routing.ExperiencePlanStep

**Package:** `io.casehub.api.spi.routing`

**Kind:** `record`

## Fields

### `adaptationAction` (`java.lang.String`)

### `adaptationReason` (`java.lang.String`)

### `bindingName` (`java.lang.String`)

### `capabilityName` (`java.lang.String`)

### `parameters` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `priority` (`int`)

### `stepOutcome` (`io.casehub.api.spi.routing.RoutingOutcome`)

### `workerName` (`java.lang.String`)

## Record Components

### `adaptationAction` (`java.lang.String`)

### `adaptationReason` (`java.lang.String`)

### `bindingName` (`java.lang.String`)

### `capabilityName` (`java.lang.String`)

### `parameters` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `priority` (`int`)

### `stepOutcome` (`io.casehub.api.spi.routing.RoutingOutcome`)

### `workerName` (`java.lang.String`)

## Constructors

### `public ExperiencePlanStep(java.lang.String bindingName, java.lang.String capabilityName, java.lang.String workerName, io.casehub.api.spi.routing.RoutingOutcome stepOutcome, int priority, java.util.Map<java.lang.String,java.lang.Object> parameters)`

#### Parameters

- `bindingName` (`java.lang.String`)
- `capabilityName` (`java.lang.String`)
- `workerName` (`java.lang.String`)
- `stepOutcome` (`io.casehub.api.spi.routing.RoutingOutcome`)
- `priority` (`int`)
- `parameters` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `public ExperiencePlanStep(java.lang.String bindingName, java.lang.String capabilityName, java.lang.String workerName, io.casehub.api.spi.routing.RoutingOutcome stepOutcome, int priority, java.util.Map<java.lang.String,java.lang.Object> parameters, java.lang.String adaptationAction, java.lang.String adaptationReason)`

#### Parameters

- `bindingName` (`java.lang.String`)
- `capabilityName` (`java.lang.String`)
- `workerName` (`java.lang.String`)
- `stepOutcome` (`io.casehub.api.spi.routing.RoutingOutcome`)
- `priority` (`int`)
- `parameters` (`java.util.Map<java.lang.String,java.lang.Object>`)
- `adaptationAction` (`java.lang.String`)
- `adaptationReason` (`java.lang.String`)

## Methods

### `public java.lang.String adaptationAction()`

### `public java.lang.String adaptationReason()`

### `public java.lang.String bindingName()`

### `public java.lang.String capabilityName()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public java.util.Map<java.lang.String,java.lang.Object> parameters()`

### `public int priority()`

### `public io.casehub.api.spi.routing.RoutingOutcome stepOutcome()`

### `public final java.lang.String toString()`

### `public java.lang.String workerName()`
