# io.casehub.api.engine.YamlCaseHub

**Package:** `io.casehub.api.engine`

**Kind:** `class`

Base class for YAML-backed CaseHub definitions.

<p>In CDI contexts, `ExpressionEngineRegistry` and `ObjectMapper` are injected
automatically; all registered expression languages are supported. Outside CDI (tests, tooling),
the no-arg constructor path falls back to JQ-only parsing.

<p>Subclasses that need to add programmatic workers (backed by CDI-injected services) override
`.augment(CaseDefinition)` instead of `getDefinition()`. The hook is called once,
inside the double-checked lock, between YAML loading and caching.

## Fields

### `definition` (`io.casehub.api.model.CaseDefinition`)

### `expressionEngineRegistry` (`io.casehub.api.engine.ExpressionEngineRegistry`)

### `objectMapper` (`ObjectMapper`)

### `path` (`java.lang.String`)

### `workerFunctionProviderRegistry` (`io.casehub.api.spi.WorkerFunctionProviderRegistry`)

## Constructors

### `public YamlCaseHub(java.lang.String path)`

#### Parameters

- `path` (`java.lang.String`)

## Methods

### `protected void augment(io.casehub.api.model.CaseDefinition definition)`

Hook for subclasses to augment the YAML-loaded definition with programmatic workers, agent
descriptors, or other modifications.

<p>Called once, inside the double-checked lock, between YAML loading and caching. CDI-injected
fields are available. The default implementation is a no-op.

#### Parameters

- `definition` (`io.casehub.api.model.CaseDefinition`) — the loaded definition to augment

### `public final io.casehub.api.model.CaseDefinition getDefinition()`
