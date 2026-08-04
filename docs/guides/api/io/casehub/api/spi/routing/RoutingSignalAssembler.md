# io.casehub.api.spi.routing.RoutingSignalAssembler

**Package:** `io.casehub.api.spi.routing`

**Kind:** `class`

Discovers all `RoutingSignalProvider` implementations via CDI, sorts them by `jakarta.annotation.Priority` (lower values first), and assembles their signals into a map keyed
by provider `RoutingSignalProvider.id()`.

<p>Providers returning `null` are skipped. Providers that throw are logged and skipped — a
failing provider never prevents other providers from contributing.

<p>Out-of-range scores (outside [0.0, 1.0]) are clamped and logged.

## Fields

### `LOG` (`Logger`)

### `providers` (`java.util.List<io.casehub.api.spi.routing.RoutingSignalProvider>`)

## Constructors

### `public RoutingSignalAssembler(Instance<io.casehub.api.spi.routing.RoutingSignalProvider> providers)`

#### Parameters

- `providers` (`Instance<io.casehub.api.spi.routing.RoutingSignalProvider>`)

## Methods

### `public java.util.Map<java.lang.String,io.casehub.api.spi.routing.RoutingSignal> assemble(io.casehub.api.spi.routing.AgentRoutingContext context, java.util.List<io.casehub.api.spi.routing.AgentCandidate> eligible)`

#### Parameters

- `context` (`io.casehub.api.spi.routing.AgentRoutingContext`)
- `eligible` (`java.util.List<io.casehub.api.spi.routing.AgentCandidate>`)

### `private static io.casehub.api.spi.routing.RoutingSignal clampScores(io.casehub.api.spi.routing.RoutingSignal signal, java.lang.String providerId)`

#### Parameters

- `signal` (`io.casehub.api.spi.routing.RoutingSignal`)
- `providerId` (`java.lang.String`)

### `private static int priority(io.casehub.api.spi.routing.RoutingSignalProvider provider)`

#### Parameters

- `provider` (`io.casehub.api.spi.routing.RoutingSignalProvider`)
