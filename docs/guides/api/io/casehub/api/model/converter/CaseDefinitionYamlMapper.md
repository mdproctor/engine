# io.casehub.api.model.converter.CaseDefinitionYamlMapper

**Package:** `io.casehub.api.model.converter`

**Kind:** `class`

Centralized YAML marshaller for CaseDefinition.

<p>Reads YAML CaseDefinition files, deserializes to generated schema models (io.casehub.model.*),
and converts to API models (io.casehub.api.model.*).

<p>Use ObjectMapper, ExpressionEngineRegistry) in CDI contexts. Use
`.load(InputStream)` for non-CDI contexts (tests, tooling) — JQ only.

## Fields

### `EMPTY_PROVIDERS` (`io.casehub.api.spi.WorkerFunctionProviderRegistry`)

Empty WorkerFunctionProviderRegistry for non-CDI contexts. Returns null for all worker nodes,
causing mapper to use API-local construction (agent, sync).

### `JQ_ONLY` (`io.casehub.api.engine.ExpressionEngineRegistry`)

JQ-only registry for non-CDI contexts. Does not support custom expression languages.

### `LOG` (`Logger`)

### `MAPPER` (`ObjectMapper`)

## Constructors

### `private CaseDefinitionYamlMapper()`

## Methods

### `private static java.util.List<java.lang.String> castStringList(java.lang.String fieldName, java.util.List<?> raw)`

#### Parameters

- `fieldName` (`java.lang.String`)
- `raw` (`java.util.List<?>`)

### `private static CompiledExpression<java.util.Map<java.lang.String,java.lang.Object>,java.lang.Boolean> compileJqBoolean(java.lang.String expression)`

#### Parameters

- `expression` (`java.lang.String`)

### `private static io.casehub.api.model.Binding convertBinding(io.casehub.model.Binding schemaBinding, java.util.Map<java.lang.String,Capability> capabilityMap, io.casehub.api.engine.ExpressionEngineRegistry registry, java.lang.String expressionLang)`

#### Parameters

- `schemaBinding` (`io.casehub.model.Binding`)
- `capabilityMap` (`java.util.Map<java.lang.String,Capability>`)
- `registry` (`io.casehub.api.engine.ExpressionEngineRegistry`)
- `expressionLang` (`java.lang.String`)

### `private static io.casehub.api.model.SubCaseCompletionStrategy convertCompletionStrategy(io.casehub.model.SubCase.CompletionStrategy schemaStrategy)`

#### Parameters

- `schemaStrategy` (`io.casehub.model.SubCase.CompletionStrategy`)

### `private static ExecutionPolicy convertExecutionPolicy(io.casehub.model.ExecutionPolicy schema)`

#### Parameters

- `schema` (`io.casehub.model.ExecutionPolicy`)

### `private static io.casehub.api.model.GoalExpression convertGoalExpression(io.casehub.model.GoalExpression expr, java.util.Map<java.lang.String,io.casehub.api.model.Goal> goalMap)`

#### Parameters

- `expr` (`io.casehub.model.GoalExpression`)
- `goalMap` (`java.util.Map<java.lang.String,io.casehub.api.model.Goal>`)

### `private static io.casehub.api.model.HumanTaskTarget convertHumanTask(io.casehub.model.HumanTask schema)`

#### Parameters

- `schema` (`io.casehub.model.HumanTask`)

### `private static io.casehub.api.model.SubCase convertSubCase(io.casehub.model.SubCase schemaModel)`

#### Parameters

- `schemaModel` (`io.casehub.model.SubCase`)

### `private static io.casehub.api.model.CaseDefinition convertToApiModel(io.casehub.model.CaseDefinition schema, JsonNode rawNode, ObjectMapper objectMapper, io.casehub.api.engine.ExpressionEngineRegistry registry, io.casehub.api.spi.WorkerFunctionProviderRegistry providerRegistry)`

Converts generated schema model to API model.

#### Parameters

- `schema` (`io.casehub.model.CaseDefinition`) — generated CaseDefinition from YAML
- `rawNode` (`JsonNode`) — raw YAML parsed as JsonNode (for free-form fields)
- `objectMapper` (`ObjectMapper`) — ObjectMapper for converting JsonNode to Map
- `registry` (`io.casehub.api.engine.ExpressionEngineRegistry`) — registry for creating ExpressionEvaluator instances from string expressions
- `providerRegistry` (`io.casehub.api.spi.WorkerFunctionProviderRegistry`) — registry for SDK-dependent worker construction (flow, etc.)

#### Returns

API model CaseDefinition

### `private static io.casehub.api.model.Trigger convertTrigger(io.casehub.model.Trigger schemaTrigger, io.casehub.api.engine.ExpressionEngineRegistry registry, java.lang.String expressionLang)`

#### Parameters

- `schemaTrigger` (`io.casehub.model.Trigger`)
- `registry` (`io.casehub.api.engine.ExpressionEngineRegistry`)
- `expressionLang` (`java.lang.String`)

### `public static io.casehub.api.model.CaseDefinition load(java.io.InputStream yamlStream)`

Loads a CaseDefinition from a YAML InputStream using a plain ObjectMapper and JQ-only
expression support.

<p>For non-CDI contexts (tests, tooling). Does not support custom expression languages — use
ObjectMapper, ExpressionEngineRegistry,
WorkerFunctionProviderRegistry) in CDI deployments.

#### Parameters

- `yamlStream` (`java.io.InputStream`) — InputStream containing YAML CaseDefinition

#### Returns

API model CaseDefinition

#### Throws

- `IOException` — if reading or parsing fails

### `public static io.casehub.api.model.CaseDefinition load(java.io.InputStream yamlStream, ObjectMapper objectMapper, io.casehub.api.engine.ExpressionEngineRegistry registry, io.casehub.api.spi.WorkerFunctionProviderRegistry providerRegistry)`

Loads a CaseDefinition from a YAML InputStream using the CDI-managed ObjectMapper and
ExpressionEngineRegistry. Supports all registered expression languages.

#### Parameters

- `yamlStream` (`java.io.InputStream`) — InputStream containing YAML CaseDefinition
- `objectMapper` (`ObjectMapper`) — ObjectMapper configured for YAML (with config/secret placeholder support)
- `registry` (`io.casehub.api.engine.ExpressionEngineRegistry`) — ExpressionEngineRegistry for creating evaluators from YAML expression strings
- `providerRegistry` (`io.casehub.api.spi.WorkerFunctionProviderRegistry`) — WorkerFunctionProviderRegistry for SDK-dependent worker construction

#### Returns

API model CaseDefinition

#### Throws

- `IOException` — if reading or parsing fails

### `private static io.casehub.api.spi.routing.CandidateSetSpec parseCandidateSet(java.lang.Object raw, java.lang.String fieldName)`

#### Parameters

- `raw` (`java.lang.Object`)
- `fieldName` (`java.lang.String`)

### `private static io.casehub.api.model.GoalExpression parseGoalElement(JsonNode element, java.util.Map<java.lang.String,io.casehub.api.model.Goal> goalMap)`

#### Parameters

- `element` (`JsonNode`)
- `goalMap` (`java.util.Map<java.lang.String,io.casehub.api.model.Goal>`)

### `private static io.casehub.api.model.GoalExpression parseGoalExpressionFromNode(JsonNode node, java.util.Map<java.lang.String,io.casehub.api.model.Goal> goalMap)`

#### Parameters

- `node` (`JsonNode`)
- `goalMap` (`java.util.Map<java.lang.String,io.casehub.api.model.Goal>`)

### `private static io.casehub.api.model.GoalKind resolveGoalKind(java.lang.String kindValue, JsonNode exprNode)`

#### Parameters

- `kindValue` (`java.lang.String`)
- `exprNode` (`JsonNode`)

### `private static void validateJqSyntax(java.lang.String expression, java.lang.String fieldName)`

#### Parameters

- `expression` (`java.lang.String`)
- `fieldName` (`java.lang.String`)
