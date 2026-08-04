# io.casehub.api.model.converter.AgentConverter

**Package:** `io.casehub.api.model.converter`

**Kind:** `class`

## Constructors

### `public AgentConverter()`

## Methods

### `private static io.casehub.api.model.ai.ChatModelProvider toAnthropicProvider(AnthropicModel model)`

#### Parameters

- `model` (`AnthropicModel`)

### `public static io.casehub.api.model.ai.Agent toApiAgent(Agent schemaAgent)`

#### Parameters

- `schemaAgent` (`Agent`)

### `private static io.casehub.api.model.ai.ChatModelProvider toChatModelProvider(AgentModel model)`

#### Parameters

- `model` (`AgentModel`)

### `private static io.casehub.api.model.ai.ChatModelProvider toGoogleAiProvider(GoogleAiGeminiModel model)`

#### Parameters

- `model` (`GoogleAiGeminiModel`)

### `private static io.casehub.api.model.ai.ChatModelProvider toMistralProvider(MistralAiModel model)`

#### Parameters

- `model` (`MistralAiModel`)

### `private static io.casehub.api.model.ai.ChatModelProvider toOllamaProvider(OllamaModel model)`

#### Parameters

- `model` (`OllamaModel`)

### `private static io.casehub.api.model.ai.ChatModelProvider toOpenAiProvider(OpenAiModel model)`

#### Parameters

- `model` (`OpenAiModel`)
