# Design: Agent Worker — AI Model Integration

**Date:** 2026-05-25
**Status:** Implemented

---

## Problem

Case Hub workers can be backed by Java functions, Serverless Workflow definitions, or external
files. However, many real-world cases — sentiment analysis, document classification, content
summarization, code review — benefit from LLM-powered workers that can reason over unstructured
input and produce structured output. The engine needs a first-class abstraction for AI agent workers
that:

1. Supports multiple LLM providers without coupling the engine core to any single vendor.
2. Transforms case context into agent input and agent output back into context updates using JQ expressions.
3. Handles API key secrets securely via the `use.secrets` / `$secret` mechanism already established
   in the YAML DSL.
4. Is fully declarable in YAML alongside traditional workers.

---

## Design

### Architecture

The agent subsystem lives in `casehub-engine-api` (`io.casehub.api.model.ai`) and consists of
four layers:

```
┌─────────────────────────────────────────────────────────┐
│  YAML DSL (CaseDefinition.yaml)                        │
│    worker.agent → Agent / AgentModel / *Model schemas   │
├─────────────────────────────────────────────────────────┤
│  Schema Model (jsonschema2pojo)                         │
│    io.casehub.model.Agent, AgentModel, OpenAiModel ...  │
├─────────────────────────────────────────────────────────┤
│  Converter Layer                                        │
│    AgentConverter: schema model → API model              │
│    CaseDefinitionYamlMapper: YAML → CaseDefinition      │
├─────────────────────────────────────────────────────────┤
│  API Model + Runtime                                    │
│    Agent, AgentBuilder, ChatModelProvider, JqTransformer │
│    Provider impls: OpenAi, Anthropic, Ollama, etc.      │
└─────────────────────────────────────────────────────────┘
```

### 1. Agent (`io.casehub.api.model.ai.Agent`)

Immutable execution unit. Holds:

| Field | Type | Description |
|-------|------|-------------|
| `systemPrompt` | `String` | System message sent to the LLM |
| `userMessageTemplate` | `String` (optional) | Langchain4j `PromptTemplate` with `{{variable}}` placeholders |
| `inputTransformer` | `UnaryOperator<JsonNode>` | Transforms case context into agent input |
| `outputTransformer` | `UnaryOperator<JsonNode>` | Transforms LLM response into case context updates |
| `model` | `ChatModel` | LangChain4j chat model instance |
| `responseSchema` | `JsonSchema` (optional) | Structured output schema for JSON mode |

**Execution flow** (`Agent.execute(Map<String, Object> input)`):

```
input (Map) → JsonNode → inputTransformer → user message text
                                                  ↓
                              ChatRequest(systemPrompt, userMessage, responseFormat)
                                                  ↓
                              ChatModel.chat() → AI response text
                                                  ↓
                              parse JSON → outputTransformer → Map output
```

If `userMessageTemplate` is set, the transformed input is applied to the template via
`PromptTemplate.apply()`. Otherwise, the transformed JSON is sent as-is.

The response is always expected as JSON. `AgentException` is thrown if the LLM returns
invalid JSON.

### 2. AgentBuilder

Fluent builder with two modes for input/output transformation:

- **JQ string mode** (`inputSchema(String)`, `outputSchema(String)`) — constructs a `JqTransformer`
  internally and wraps it as a lambda. Convenient for YAML-declared agents.
- **Lambda mode** (`inputTransformer(UnaryOperator<JsonNode>)`, `outputTransformer(...)`) — for CDI
  callers who supply a `JQEvaluator`-backed function or custom logic.

Both modes are mutually exclusive per direction — setting both `inputProjection` and `inputTransformer`
throws `IllegalStateException`. When neither is set, the transformer defaults to
`UnaryOperator.identity()`.

**Model resolution order:**

1. Explicit `ChatModel` (package-private, for tests)
2. Explicit `ChatModelProvider` instance
3. `ServiceLoader<ChatModelProvider>` lookup by `ModelType`

### 3. ChatModelProvider SPI

```java
public interface ChatModelProvider {
    ModelType type();
    ChatModel get();
}
```

Implementations use reflection to build LangChain4j vendor models, avoiding compile-time
dependency on every LLM SDK. Each provider has:

- A no-arg constructor for `ServiceLoader` (reads API keys from environment variables)
- A `Builder` for programmatic construction (used by `AgentConverter` from YAML)

### 4. Supported Providers

| Provider | `ModelType` | Default model | API key source | Key env var |
|----------|-------------|---------------|----------------|-------------|
| OpenAI | `OPENAI` | `gpt-4o-mini` | `apiKey` (required) | `OPENAI_API_KEY` |
| Anthropic | `ANTHROPIC` | `claude-3-5-sonnet-20241022` | `apiKey` (required) | `ANTHROPIC_API_KEY` |
| Mistral AI | `MISTRAL` | `mistral-small-latest` | `apiKey` (required) | `MISTRAL_API_KEY` |
| Google AI Gemini | `GOOGLE_AI_GEMINI` | `gemini-2.0-flash` | `apiKey` (required) | `GOOGLE_API_KEY` |
| Ollama | `OLLAMA` | (env: `OLLAMA_MODEL`) | N/A (local) | `OLLAMA_BASE_URL` |

**Provider-specific parameters:**

| Parameter | OpenAI | Anthropic | Mistral | Gemini | Ollama |
|-----------|--------|-----------|---------|--------|--------|
| `temperature` | 0.0–2.0 | 0.0–1.0 | 0.0–1.0 | 0.0–1.0 | 0.0–2.0 |
| `topP` | 0.0–1.0 | 0.0–1.0 | 0.0–1.0 | 0.0–1.0 | 0.0–1.0 |
| `topK` | — | yes | — | yes | yes |
| `maxTokens` | yes | yes | yes | yes (→ maxOutputTokens) | yes (→ numPredict) |
| `baseUrl` | optional | optional | optional | — | required |
| `frequencyPenalty` | -2.0–2.0 | — | — | — | — |
| `presencePenalty` | -2.0–2.0 | — | — | — | — |
| `organizationId` | optional | — | — | — | — |

### 5. JqTransformer

Standalone JQ evaluator using `jackson-jq` (JQ 1.6). Thread-safe after construction —
`Scope` is fully populated in the constructor and never mutated. Used internally by
`AgentBuilder` when `inputProjection`/`outputProjection` are JQ expression strings.

---

## YAML Representation

### Schema (`CaseDefinition.yaml`)

`Agent` is a worker function type alongside Serverless Workflow and file references:

```yaml
Worker:
  oneOf:
    - # Serverless Workflow ($ref)
    - # File reference (type: string)
    - type: object
      required: [agent]
      properties:
        agent:
          $ref: "#/$defs/Agent"

Agent:
  required: [systemPrompt, inputSchema, outputSchema, model]
  properties:
    systemPrompt:    # string — system prompt for the LLM
    inputSchema:     # string — JQ expression (context → agent input)
    outputSchema:    # string — JQ expression (agent output → context)
    userMessageTemplate:  # string (optional) — PromptTemplate
    model:           # $ref AgentModel

AgentModel:
  oneOf: [openai, ollama, anthropic, mistralAi, googleAiGemini]
```

### Minimal Example

```yaml
dsl: "0.1"
name: sentiment-analysis
namespace: example
version: "1.0.0"

spec:
  capabilities:
    - name: analyzeSentiment
      description: "Analyze text sentiment"
      inputSchema: "{ text: .text }"
      outputSchema: "{ sentiment: .sentiment }"

  workers:
    - name: sentiment-analyzer
      capabilities:
        - analyzeSentiment
      agent:
        systemPrompt: |
          Analyze the input text and classify sentiment as POSITIVE, NEGATIVE, or NEUTRAL.
          Return only JSON with a single field "sentiment".
        inputSchema: "{ text: .text }"
        outputSchema: "{ sentiment: .sentiment }"
        model:
          openai:
            apiKey: "sk-..."
            modelName: "gpt-4"
            temperature: 0.3
            maxTokens: 100
      executionPolicy:
        timeoutMs: 30000
        retries:
          maxAttempts: 3
          delayMs: 1000

  bindings:
    - name: trigger-analysis
      capability: analyzeSentiment
      on:
        contextChange:
          filter: ".status == \"pending\""
```

### Secrets for API Keys

API keys should not be hardcoded in YAML. The `use.secrets` / `$secret` mechanism provides
runtime resolution via JQ expressions:

```yaml
use:
  secrets:
    - openai
    - anthropic

spec:
  workers:
    - name: sentiment-analyzer
      capabilities:
        - analyzeSentiment
      agent:
        systemPrompt: "Analyze sentiment..."
        inputSchema: "${.context.text}"
        outputSchema: "${.sentiment}"
        model:
          openai:
            apiKey: "${$secret.openai.apiKey}"
            modelName: "${$config.\"model-params\".primary}"
            temperature: "${$config.\"model-params\".temperature | tonumber}"

    - name: fallback-analyzer
      capabilities:
        - analyzeSentiment
      agent:
        systemPrompt: "Analyze sentiment (fallback)..."
        inputSchema: "${.context.text}"
        outputSchema: "${.sentiment}"
        model:
          anthropic:
            apiKey: "${$secret.anthropic.apiKey}"
            modelName: "claude-3-sonnet-20240229"
            temperature: 0.5
```

**Secret resolution:**

- `${$secret.<name>.<field>}` — resolved at runtime via JQ expression evaluation
- `${$config."<configmap>".<field>}` — resolved from `use.configMaps` entries
- Secrets declared in `use.secrets` are validated at case definition load time (fail-fast)
- `| tonumber` coercion is required when the target field expects a numeric type but the
  config/secret source provides a string

**Per-provider secret convention:**

| Provider | Secret name | Fields |
|----------|-------------|--------|
| OpenAI | `openai` | `apiKey`, `organizationId` |
| Anthropic | `anthropic` | `apiKey` |
| Mistral AI | `mistralai` | `apiKey` |
| Google AI | `googleai` | `apiKey` |
| Ollama | — | N/A (local, no auth) |

### ConfigMap Usage

Model parameters can be externalized via `use.configMaps`:

```yaml
use:
  configMaps:
    - model-params

spec:
  workers:
    - name: analyzer
      agent:
        model:
          openai:
            apiKey: "${$secret.openai.apiKey}"
            modelName: "${$config.\"model-params\".primary}"
            temperature: "${$config.\"model-params\".temperature | tonumber}"
            maxTokens: "${$config.\"model-params\".maxTokens | tonumber}"
```

---

## Converter Layer

### AgentConverter

`AgentConverter.toApiAgent(io.casehub.model.Agent)` bridges the jsonschema2pojo-generated schema
model to the API `Agent`:

1. Reads `AgentModel` and dispatches to the matching provider builder (`openai`, `anthropic`, etc.)
2. Configures provider-specific parameters (apiKey, modelName, temperature, topP, topK, maxTokens)
3. Constructs `AgentBuilder` with `systemPrompt`, `inputProjection`, `outputProjection`, `userMessageTemplate`
4. Returns the built `Agent`

### CaseDefinitionYamlMapper

When parsing YAML workers, the mapper detects `worker.agent != null` and delegates to
`AgentConverter.toApiAgent()`:

```java
if (sw.getAgent() != null) {
    Agent apiAgent = AgentConverter.toApiAgent(sw.getAgent());
    worker = new Worker(sw.getName(), workerCaps, apiAgent);
} else {
    worker = new Worker(sw.getName(), workerCaps, sw.getWorkflowAsEmbedded());
}
```

---

## Worker Integration

`Worker` accepts an `Agent` as a function type via constructor overload:

```java
public Worker(String name, List<Capability> capabilities, Agent agent)
```

This wraps the `Agent` in a `WorkerFunctionHolder<Agent>`. At execution time
(`QuartzWorkerExecutionJob`), the holder detects the agent type and calls
`agent.execute(input)` with the case context as input.

Optional `AgentDescriptor` (from `casehub-eidos-api`) can be set on the `Worker` for
health probing via `CapabilityHealth` (see `2026-05-23-capability-health-design.md`).

---

## Module Dependencies

```
casehub-engine-api
  ├── dev.langchain4j:langchain4j (core types: ChatModel, ChatRequest, PromptTemplate)
  ├── net.thisptr:jackson-jq (JQ evaluation in JqTransformer)
  └── io.casehub:casehub-eidos-api (optional — AgentDescriptor for health probing)
```

LLM vendor SDKs (`langchain4j-open-ai`, `langchain4j-anthropic`, etc.) are **not** compile
dependencies of `casehub-engine-api`. Providers use reflection (`Class.forName`) to avoid
hard coupling. The consumer's classpath determines which providers are available at runtime.

---

## Files

| File | Module | Purpose |
|------|--------|---------|
| `api/.../ai/Agent.java` | api | Immutable agent execution unit |
| `api/.../ai/AgentBuilder.java` | api | Fluent builder with JQ / lambda transformer modes |
| `api/.../ai/AgentException.java` | api | Runtime exception for agent failures |
| `api/.../ai/ChatModelProvider.java` | api | SPI for LLM provider resolution |
| `api/.../ai/JqTransformer.java` | api | JQ expression evaluator (jackson-jq) |
| `api/.../ai/ModelType.java` | api | Enum: OPENAI, OLLAMA, ANTHROPIC, MISTRAL, GOOGLE_AI_GEMINI |
| `api/.../ai/openai/OpenAiChatModelProvider.java` | api | OpenAI provider (reflection-based) |
| `api/.../ai/anthropic/AnthropicChatModelProvider.java` | api | Anthropic provider |
| `api/.../ai/mistral/MistralAiChatModelProvider.java` | api | Mistral AI provider |
| `api/.../ai/gemini/GoogleAiGeminiChatModelProvider.java` | api | Google AI Gemini provider |
| `api/.../ai/ollama/OllamaChatModelProvider.java` | api | Ollama provider (local, no auth) |
| `api/.../converter/AgentConverter.java` | api | Schema model → API model bridge |
| `api/.../converter/CaseDefinitionYamlMapper.java` | api | YAML → CaseDefinition (agent branch) |
| `schema/.../CaseDefinition.yaml` | schema | JSON Schema defining Agent, AgentModel, *Model |
| `schema/.../examples/agent-worker-example.yaml` | schema | OpenAI agent example |
| `schema/.../examples/agent-ollama-example.yaml` | schema | Ollama agent example |
| `runtime/test/.../use-secrets-example.yaml` | runtime (test) | Secrets + configMaps example |

---

## Tests

| Test class | Module | Coverage |
|------------|--------|----------|
| `AgentTest` | api | `execute()`, output filtering, systemPrompt, userMessageTemplate, invalid JSON |
| `AgentBuilderTest` | api | Custom transformers, conflict guards, identity defaults, schema string compat |
| `AgentExceptionTest` | api | Constructor variants |
| `JqTransformerTest` | api | JQ compilation, apply, error cases |
| `OpenAiChatModelProviderTest` | api | Builder, env var fallback, reflection model build |
| `AnthropicChatModelProviderTest` | api | Builder, env var fallback |
| `MistralAiChatModelProviderTest` | api | Builder, env var fallback |
| `GoogleAiGeminiChatModelProviderTest` | api | Builder, env var fallback |
| `OllamaChatModelProviderTest` | api | Builder, baseUrl default, env var fallback |
| `CaseDefinitionYamlMapperTest` | api | `load_workerWithAgent_convertsAgentToApiModel()` |
| `AgentModelDeserializationTest` | schema | Jackson deserialization for all five providers |
| `AgentWorkerTest` | api | Worker + Agent function integration |
| `AgentWorkerExecutionTest` | runtime | `@QuarkusTest` end-to-end: case → agent → COMPLETED |
| `AgentPipelineBeanTest` | runtime | Multi-step agent pipeline with mocked LLM services |
| `AgentExecutionTest` | scheduler-quartz | `QuartzWorkerExecutionJob.agent()` via reflection |

---

## Related Specs

- `2026-05-22-agent-command-content-design.md` — decoupling Agent from JqTransformer (engine#316)
- `2026-05-23-capability-health-design.md` — CapabilityHealth integration for agent-backed workers (engine#341)
- `2026-05-14-config-secrets-design.md` — secrets management for API keys in agent models (engine#247)
