# io.casehub.api.engine.YamlCaseHub

**Package:** `io.casehub.api.engine`

**Kind:** `class`

Base class for YAML-backed CaseHub definitions.

<p>In CDI contexts, `ExpressionEngineRegistry` and `ObjectMapper` are injected
automatically; all registered expression languages are supported. Outside CDI (tests, tooling),
the no-arg constructor path falls back to JQ-only parsing.

<p>Supports YAML overlay composition: a base YAML is loaded first, then an optional overlay YAML
is deep-merged on top via `YamlMerger`. The overlay can be specified explicitly via the
two-arg constructor, or discovered by convention (`-overrides` suffix in the same
directory). After merging, `.augment(CaseDefinition)` runs for programmatic modifications.

<p>Resolution order: base YAML → overlay YAML (explicit or convention) → augment().

## Fields

### `agentProviderInstance` (`jakarta.enterprise.inject.Instance<io.casehub.platform.agent.AgentProvider>`)

### `definition` (`io.casehub.api.model.CaseDefinition`)

### `expressionEngineRegistry` (`io.casehub.api.engine.ExpressionEngineRegistry`)

### `objectMapper` (`ObjectMapper`)

### `overlayPath` (`java.lang.String`)

### `path` (`java.lang.String`)

### `workerFunctionProviderRegistry` (`io.casehub.api.spi.WorkerFunctionProviderRegistry`)

## Constructors

### `public YamlCaseHub(java.lang.String path)`

#### Parameters

- `path` (`java.lang.String`)

### `public YamlCaseHub(java.lang.String path, java.lang.String overlayPath)`

#### Parameters

- `path` (`java.lang.String`)
- `overlayPath` (`java.lang.String`)

## Methods

### `protected void augment(io.casehub.api.model.CaseDefinition definition)`

Hook for subclasses to augment the YAML-loaded definition with programmatic workers, agent
descriptors, or other modifications.

<p>Called once, inside the double-checked lock, between YAML loading and caching. CDI-injected
fields are available. The default implementation is a no-op.

#### Parameters

- `definition` (`io.casehub.api.model.CaseDefinition`) — the loaded definition to augment

### `public static java.lang.String deriveConventionPath(java.lang.String basePath)`

#### Parameters

- `basePath` (`java.lang.String`)

### `public final io.casehub.api.model.CaseDefinition getDefinition()`

### `private JsonNode loadYamlAsJsonNode(java.lang.String resourcePath)`

#### Parameters

- `resourcePath` (`java.lang.String`)

### `private JsonNode resolveOverlay()`
