# io.casehub.api.spi.routing.TrustRoutingPolicyResolver

**Package:** `io.casehub.api.spi.routing`

**Kind:** `class`

Resolves a `TrustRoutingPolicy` from `Preferences` using `io.casehub.api.spi.routing.TrustRoutingPolicyKeys`. Eliminates the duplicated
preference-to-policy parsing logic across domain repos.

## Constructors

### `private TrustRoutingPolicyResolver()`

## Methods

### `public static java.util.Map<java.lang.String,java.lang.Double> collectFloors(Preferences prefs, java.util.Map<java.lang.String,PreferenceKey<io.casehub.api.spi.routing.DoublePreference>> floorKeys)`

Collects quality floors from preferences, skipping absent or zero-valued floors. Useful for
hybrid providers that read some fields from a domain registry and only the floors from
preferences.

#### Parameters

- `prefs` (`Preferences`)
- `floorKeys` (`java.util.Map<java.lang.String,PreferenceKey<io.casehub.api.spi.routing.DoublePreference>>`)

### `public static io.casehub.api.spi.routing.TrustRoutingPolicy resolve(Preferences prefs, io.casehub.api.spi.routing.TrustRoutingPolicyKeys keys)`

#### Parameters

- `prefs` (`Preferences`)
- `keys` (`io.casehub.api.spi.routing.TrustRoutingPolicyKeys`)

### `public static io.casehub.api.spi.routing.TrustRoutingPolicy resolve(Preferences prefs, io.casehub.api.spi.routing.TrustRoutingPolicyKeys keys, boolean bootstrapEscalationRequired)`

#### Parameters

- `prefs` (`Preferences`)
- `keys` (`io.casehub.api.spi.routing.TrustRoutingPolicyKeys`)
- `bootstrapEscalationRequired` (`boolean`)
