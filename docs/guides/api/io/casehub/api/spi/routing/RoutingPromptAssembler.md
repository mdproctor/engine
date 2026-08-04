# io.casehub.api.spi.routing.RoutingPromptAssembler

**Package:** `io.casehub.api.spi.routing`

**Kind:** `class`

Discovers all `RoutingPromptSection` implementations via CDI, sorts them by `jakarta.annotation.Priority` (lower values first), and assembles their rendered output into a
single prompt string.

<p>Sections returning `null` or blank strings are skipped. Sections that throw are logged
and skipped — a failing section never prevents other sections from rendering.

<p>Non-null results are joined with double newlines (`\n\n`).

## Fields

### `LOG` (`Logger`)

### `sections` (`java.util.List<io.casehub.api.spi.routing.RoutingPromptSection>`)

## Constructors

### `public RoutingPromptAssembler(Instance<io.casehub.api.spi.routing.RoutingPromptSection> sections)`

#### Parameters

- `sections` (`Instance<io.casehub.api.spi.routing.RoutingPromptSection>`)

## Methods

### `public java.lang.String assemble(io.casehub.api.spi.routing.AgentRoutingContext context, java.util.List<io.casehub.api.spi.routing.AgentCandidate> eligible)`

Assemble all prompt sections for the given routing context.

#### Parameters

- `context` (`io.casehub.api.spi.routing.AgentRoutingContext`) — the routing context
- `eligible` (`java.util.List<io.casehub.api.spi.routing.AgentCandidate>`) — the pre-filtered candidate list

#### Returns

the assembled prompt string, or `null` if no section contributed content

### `private static int priority(io.casehub.api.spi.routing.RoutingPromptSection section)`

#### Parameters

- `section` (`io.casehub.api.spi.routing.RoutingPromptSection`)
