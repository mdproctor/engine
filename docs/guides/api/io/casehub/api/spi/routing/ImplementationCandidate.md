# io.casehub.api.spi.routing.ImplementationCandidate

**Package:** `io.casehub.api.spi.routing`

**Kind:** `record`

One competing implementation for a capability, passed to `ImplementationRoutingStrategy.select`.

## Fields

### `bindingName` (`java.lang.String`)

### `capabilityName` (`java.lang.String`)

### `workerName` (`java.lang.String`)

## Record Components

### `bindingName` (`java.lang.String`)

the binding that targets this capability

### `capabilityName` (`java.lang.String`)

the capability being targeted

### `workerName` (`java.lang.String`)

the resolved worker name for this binding

## Constructors

### `public ImplementationCandidate(java.lang.String bindingName, java.lang.String workerName, java.lang.String capabilityName)`

#### Parameters

- `bindingName` (`java.lang.String`)
- `workerName` (`java.lang.String`)
- `capabilityName` (`java.lang.String`)

## Methods

### `public java.lang.String bindingName()`

### `public java.lang.String capabilityName()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public final java.lang.String toString()`

### `public java.lang.String workerName()`
