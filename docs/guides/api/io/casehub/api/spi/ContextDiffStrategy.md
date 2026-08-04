# io.casehub.api.spi.ContextDiffStrategy

**Package:** `io.casehub.api.spi`

**Kind:** `interface`

SPI: computes the diff between CaseContext state before and after a worker execution.

<p>The result is stored as `contextChanges` in the `WORKER_EXECUTION_COMPLETED`
EventLog metadata. Return `null` to omit `contextChanges` entirely.

<p>Select a built-in implementation via:

<pre>casehub.engine.diff-strategy=none|top-level|json-patch</pre>

<ul>
  <li>`none` (default) — returns null; no diff overhead
  <li>`top-level` — per-key before/after object
  <li>`json-patch` — RFC 6902 patch array
</ul>

<p>A consumer `@ApplicationScoped` implementation of this interface takes priority over the
config-selected built-in.

## Methods

### `public abstract JsonNode compute(JsonNode before, JsonNode after)`

Computes the diff between the CaseContext before and after a worker execution.

#### Parameters

- `before` (`JsonNode`) — CaseContext state snapshotted before `setAll(output)` was called
- `after` (`JsonNode`) — CaseContext state after `setAll(output)` was called

#### Returns

diff node to store as `contextChanges`, or `null` to omit the field
