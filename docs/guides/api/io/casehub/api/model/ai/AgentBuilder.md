# io.casehub.api.model.ai.AgentBuilder

**Package:** `io.casehub.api.model.ai`

**Kind:** `class`

## Fields

### `chatModelProvider` (`io.casehub.api.model.ai.ChatModelProvider`)

### `inputProjection` (`java.lang.String`)

### `inputTransformerFn` (`java.util.function.UnaryOperator<JsonNode>`)

### `model` (`ChatModel`)

### `modelType` (`io.casehub.api.model.ai.ModelType`)

### `outputProjection` (`java.lang.String`)

### `outputTransformerFn` (`java.util.function.UnaryOperator<JsonNode>`)

### `plannedActionExtractor` (`java.util.function.Function<java.util.Map<java.lang.String,java.lang.Object>,io.casehub.worker.api.PlannedAction>`)

### `responseSchema` (`JsonSchema`)

### `systemPrompt` (`java.lang.String`)

### `userMessageTemplate` (`java.lang.String`)

## Constructors

### `AgentBuilder()`

## Methods

### `public io.casehub.api.model.ai.Agent build()`

### `public io.casehub.api.model.ai.AgentBuilder inputProjection(java.lang.String jqExpression)`

#### Parameters

- `jqExpression` (`java.lang.String`)

### `public io.casehub.api.model.ai.AgentBuilder inputTransformer(java.util.function.UnaryOperator<JsonNode> fn)`

Supply a custom input transformer instead of a jq expression string.

#### Parameters

- `fn` (`java.util.function.UnaryOperator<JsonNode>`)

### `public io.casehub.api.model.ai.AgentBuilder model(ChatModel model)`

#### Parameters

- `model` (`ChatModel`)

### `public io.casehub.api.model.ai.AgentBuilder model(io.casehub.api.model.ai.ChatModelProvider provider)`

#### Parameters

- `provider` (`io.casehub.api.model.ai.ChatModelProvider`)

### `public io.casehub.api.model.ai.AgentBuilder model(io.casehub.api.model.ai.ModelType modelType)`

#### Parameters

- `modelType` (`io.casehub.api.model.ai.ModelType`)

### `public io.casehub.api.model.ai.AgentBuilder outputProjection(java.lang.String jqExpression)`

#### Parameters

- `jqExpression` (`java.lang.String`)

### `public io.casehub.api.model.ai.AgentBuilder outputTransformer(java.util.function.UnaryOperator<JsonNode> fn)`

Supply a custom output transformer instead of a jq expression string.

#### Parameters

- `fn` (`java.util.function.UnaryOperator<JsonNode>`)

### `public io.casehub.api.model.ai.AgentBuilder plannedActionExtractor(java.util.function.Function<java.util.Map<java.lang.String,java.lang.Object>,io.casehub.worker.api.PlannedAction> extractor)`

#### Parameters

- `extractor` (`java.util.function.Function<java.util.Map<java.lang.String,java.lang.Object>,io.casehub.worker.api.PlannedAction>`)

### `public io.casehub.api.model.ai.AgentBuilder responseSchema(JsonSchema responseSchema)`

#### Parameters

- `responseSchema` (`JsonSchema`)

### `public io.casehub.api.model.ai.AgentBuilder systemPrompt(java.lang.String systemPrompt)`

#### Parameters

- `systemPrompt` (`java.lang.String`)

### `public io.casehub.api.model.ai.AgentBuilder userMessage(java.lang.String template)`

#### Parameters

- `template` (`java.lang.String`)
