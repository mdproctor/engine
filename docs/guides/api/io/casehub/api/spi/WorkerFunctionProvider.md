# io.casehub.api.spi.WorkerFunctionProvider

**Package:** `io.casehub.api.spi`

**Kind:** `interface`

SPI for constructing `WorkerFunction` instances from raw YAML worker nodes.

<p>Implementations detect whether a YAML worker node contains their recognized structure (e.g.,
`agent:`, `flow:`) and construct the corresponding `WorkerFunction`.

<p>Modules register providers by implementing this interface and exposing them as CDI beans. The
YAML mapper delegates function construction to the `WorkerFunctionProviderRegistry`, which
iterates all providers until one handles the node.

## Methods

### `public abstract WorkerFunction<?,?> create(JsonNode rawWorkerNode)`

#### Parameters

- `rawWorkerNode` (`JsonNode`)

### `public abstract boolean handles(JsonNode rawWorkerNode)`

Returns `true` if this provider can construct a `WorkerFunction` from the given
YAML worker node.

#### Parameters

- `rawWorkerNode` (`JsonNode`) — the YAML worker node

#### Returns

`true` if this provider handles the node
