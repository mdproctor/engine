# io.casehub.api.spi.routing.ImplementationSelection

**Package:** `io.casehub.api.spi.routing`

**Kind:** `interface`

Result of `ImplementationRoutingStrategy.select`. A sealed type with three outcomes:

<ul>
  <li>`Selected` — one or more specific bindings were chosen
  <li>`RunAll` — all implementations run (current default behaviour)
  <li>`RunNone` — all candidates are inappropriate; skip this capability
</ul>

<p>Callers must switch exhaustively on the sealed type. Refs casehubio/engine#476.
