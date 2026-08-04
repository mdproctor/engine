# io.casehub.api.spi.WorkerFunctionProviderRegistry

**Package:** `io.casehub.api.spi`

**Kind:** `interface`

Registry for `WorkerFunctionProvider` instances.

<p>Dispatches YAML worker node construction to the appropriate `WorkerFunctionProvider` by
iterating all registered providers until one handles the node. All CDI beans implementing `WorkerFunctionProvider` are discovered automatically.

## Methods

### `public abstract WorkerFunction<?,?> createFunction(JsonNode rawWorkerNode)`

#### Parameters

- `rawWorkerNode` (`JsonNode`)
