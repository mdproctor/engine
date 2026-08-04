# io.casehub.api.model.ai.Agent

**Package:** `io.casehub.api.model.ai`

**Kind:** `class`

## Fields

### `MAPPER` (`ObjectMapper`)

### `MAP_TYPE` (`TypeReference<java.util.Map<java.lang.String,java.lang.Object>>`)

### `inputTransformer` (`java.util.function.UnaryOperator<JsonNode>`)

### `model` (`ChatModel`)

### `outputTransformer` (`java.util.function.UnaryOperator<JsonNode>`)

### `plannedActionExtractor` (`java.util.function.Function<java.util.Map<java.lang.String,java.lang.Object>,PlannedAction>`)

### `responseSchema` (`JsonSchema`)

### `systemPrompt` (`java.lang.String`)

### `userMessageTemplate` (`java.lang.String`)

## Constructors

### `Agent(java.lang.String systemPrompt, java.lang.String userMessageTemplate, java.util.function.UnaryOperator<JsonNode> inputTransformer, java.util.function.UnaryOperator<JsonNode> outputTransformer, ChatModel model, JsonSchema responseSchema, java.util.function.Function<java.util.Map<java.lang.String,java.lang.Object>,PlannedAction> plannedActionExtractor)`

#### Parameters

- `systemPrompt` (`java.lang.String`)
- `userMessageTemplate` (`java.lang.String`)
- `inputTransformer` (`java.util.function.UnaryOperator<JsonNode>`)
- `outputTransformer` (`java.util.function.UnaryOperator<JsonNode>`)
- `model` (`ChatModel`)
- `responseSchema` (`JsonSchema`)
- `plannedActionExtractor` (`java.util.function.Function<java.util.Map<java.lang.String,java.lang.Object>,PlannedAction>`)

## Methods

### `private ResponseFormat buildResponseFormat()`

### `public static io.casehub.api.model.ai.AgentBuilder builder()`

### `public WorkerResult<java.util.Map<java.lang.String,java.lang.Object>> execute(java.util.Map<java.lang.String,java.lang.Object> input)`

Executes this agent with the given input and returns a `WorkerResult`.

<p>The output map is the LLM response after applying the output transformer. When a `plannedActionExtractor` is configured and returns a non-null `PlannedAction`, the result
carries the action for downstream risk classification via PlannedAction).

#### Parameters

- `input` (`java.util.Map<java.lang.String,java.lang.Object>`)
