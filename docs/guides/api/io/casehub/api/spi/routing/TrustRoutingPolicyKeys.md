# io.casehub.api.spi.routing.TrustRoutingPolicyKeys

**Package:** `io.casehub.api.spi.routing`

**Kind:** `class`

## Fields

### `blendFactor` (`PreferenceKey<io.casehub.api.spi.routing.DoublePreference>`)

### `borderlineMargin` (`PreferenceKey<io.casehub.api.spi.routing.DoublePreference>`)

### `cbrWeight` (`PreferenceKey<io.casehub.api.spi.routing.DoublePreference>`)

### `floorKeys` (`java.util.Map<java.lang.String,PreferenceKey<io.casehub.api.spi.routing.DoublePreference>>`)

### `minimumObservations` (`PreferenceKey<io.casehub.api.spi.routing.IntPreference>`)

### `scopePrefix` (`java.lang.String`)

### `threshold` (`PreferenceKey<io.casehub.api.spi.routing.DoublePreference>`)

## Constructors

### `private TrustRoutingPolicyKeys(java.lang.String scopePrefix, java.util.Map<java.lang.String,PreferenceKey<io.casehub.api.spi.routing.DoublePreference>> floorKeys)`

#### Parameters

- `scopePrefix` (`java.lang.String`)
- `floorKeys` (`java.util.Map<java.lang.String,PreferenceKey<io.casehub.api.spi.routing.DoublePreference>>`)

## Methods

### `public java.util.Map<java.lang.String,PreferenceKey<io.casehub.api.spi.routing.DoublePreference>> allFloorKeys()`

### `public PreferenceKey<io.casehub.api.spi.routing.DoublePreference> blendFactor()`

### `public PreferenceKey<io.casehub.api.spi.routing.DoublePreference> borderlineMargin()`

### `public PreferenceKey<io.casehub.api.spi.routing.DoublePreference> cbrWeight()`

### `public static io.casehub.api.spi.routing.TrustRoutingPolicyKeys create(java.lang.String scopePrefix)`

#### Parameters

- `scopePrefix` (`java.lang.String`)

### `public PreferenceKey<io.casehub.api.spi.routing.IntPreference> minimumObservations()`

### `public PreferenceKey<io.casehub.api.spi.routing.DoublePreference> threshold()`

### `public io.casehub.api.spi.routing.TrustRoutingPolicyKeys withFloor(java.lang.String dimension, java.lang.String keySuffix)`

#### Parameters

- `dimension` (`java.lang.String`)
- `keySuffix` (`java.lang.String`)
