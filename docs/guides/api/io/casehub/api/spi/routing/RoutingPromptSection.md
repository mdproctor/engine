# io.casehub.api.spi.routing.RoutingPromptSection

**Package:** `io.casehub.api.spi.routing`

**Kind:** `interface`

Pluggable SPI for composable LLM prompt enrichment in agent routing strategies.

<p>Implementations contribute a prompt section — contextual information that helps an LLM-based
routing strategy make better selection decisions. All discovered implementations are composed by
`RoutingPromptAssembler` and injected into the routing prompt.

<p>Implement as `@ApplicationScoped` with optional `@Priority(N)` to control
rendering order (lower values render first). Return `null` when the section has nothing to
contribute for the current context.

<p>Implementations must be thread-safe — `render()` may be called concurrently.

<p>Known implementations:

<ul>
  <li>`CbrRoutingPromptSection` (casehub-blocks) — renders historical CBR outcomes per
      eligible agent
</ul>

## Methods

### `public abstract java.lang.String render(io.casehub.api.spi.routing.AgentRoutingContext context, java.util.List<io.casehub.api.spi.routing.AgentCandidate> eligible)`

Render a prompt section for the given routing context and eligible candidates.

#### Parameters

- `context` (`io.casehub.api.spi.routing.AgentRoutingContext`) — the routing context carrying caseId, capabilityName, caseContext, and tenancyId
- `eligible` (`java.util.List<io.casehub.api.spi.routing.AgentCandidate>`) — the pre-filtered, health-probed candidate list

#### Returns

a prompt section string, or `null` if this section has nothing to contribute
