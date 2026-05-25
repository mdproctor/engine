# Design: Agent Subsystem — Documentation & Test Completeness

**Date:** 2026-05-25
**Status:** Approved

---

## Problem

The AI agent subsystem (`io.casehub.api.model.ai`) was implemented incrementally across
multiple issues but documentation and test coverage did not keep pace. A systematic audit
reveals:

1. **DESIGN.md** — missing AgentConverter layer, AgentDescriptor/CapabilityHealth integration,
   `userMessageTemplate`, `responseSchema`, Agent branch in Worker Execution Lifecycle, and
   YAML representation.
2. **worker-timeout.md** — no mention of Agent worker timeout path.
3. **config-secrets-management.md** — only covers OpenAI/Anthropic secrets; missing Mistral,
   Gemini, Ollama; no per-provider convention table.
4. **secret-manager-spi.md** — stale Phase 1 status checkboxes; no cross-reference to agent
   model spec.
5. **CLAUDE.md** — no agent subsystem conventions or test patterns.
6. **Tests** — `AgentConverter` has zero dedicated tests (170 lines, 5 provider dispatch paths);
   `responseSchema` is completely untested; `JqTransformerTest` has only 3 tests.

---

## Epic

**Title:** Agent Worker AI Model — Documentation & Test Completeness

### Child Issues

| # | Title | Type | Scope |
|---|-------|------|-------|
| 1 | Update DESIGN.md with agent subsystem details | docs | AgentConverter, AgentDescriptor, userMessageTemplate, responseSchema, Worker Execution Lifecycle, YAML representation |
| 2 | Update worker-timeout.md for agent workers | docs | Agent timeout via `CompletableFuture.supplyAsync()` |
| 3 | Update config-secrets-management.md for all agent providers | docs | Per-provider secret table, Mistral/Gemini/Ollama coverage |
| 4 | Fix stale status in secret-manager-spi.md | docs | Phase 1 checkboxes, cross-ref to agent model spec |
| 5 | Update CLAUDE.md with agent subsystem conventions | docs | Module location, test patterns, dependency rules |
| 6 | Write AgentConverterTest | test | All 5 providers, null handling, userMessageTemplate |
| 7 | Add responseSchema tests | test | JsonSchema, Class<?>, integration with ChatRequest |
| 8 | Expand JqTransformerTest coverage | test | Empty result, null input, runtime error, multiple results |
| 9 | Add Agent.execute() error path tests | test | Input/output transformer failures, empty LLM response |
| 10 | Verify and fix spec cross-references | docs | All agent-related specs have valid cross-links |

---

## Documentation Changes

### Issue 1: DESIGN.md

**Worker Execution Lifecycle — Agent branch:**

Add to the worker execution lifecycle section:

> Agent workers: When `WorkerFunctionHolder` wraps an `Agent`, the Quartz job calls
> `agent.execute(caseContext)` inside `CompletableFuture.supplyAsync()` with timeout
> enforcement. The execution flow is:
> 1. Case context → `inputTransformer` (JQ or lambda) → transformed input
> 2. Optionally apply `userMessageTemplate` via LangChain4j `PromptTemplate`
> 3. Build `ChatRequest` with system prompt, user message, and optional `responseSchema`
> 4. Call `ChatModel.chat()` → parse JSON response
> 5. Response → `outputTransformer` → merged into case context

**Agent subsection additions:**

- `AgentConverter` — schema model → API model bridge. Located in
  `api/.../converter/AgentConverter.java`. Dispatches to 5 provider-specific builders
  based on which `AgentModel` field is non-null.
- `AgentDescriptor` (optional, from `casehub-eidos-api`) — carries agent identity for
  `CapabilityHealth` probing. See `2026-05-23-capability-health-design.md`.
- `userMessageTemplate` — LangChain4j `PromptTemplate` with `{{variable}}` syntax.
  Applied after input transformation. When absent, transformed JSON is sent as-is.
- `responseSchema` — structured output. Builder accepts `JsonSchema` or `Class<?>`.
  Collections auto-wrap in `JsonArraySchema`; non-objects wrap in `JsonObjectSchema`
  with a `"value"` property.
- `AgentException` — unchecked exception for agent failures (invalid JSON from LLM,
  JQ transformation failures, template application errors).

### Issue 2: worker-timeout.md

Add third bullet to the worker type timeout list:

> **Agent workers:** Timeout applies to the entire LLM round-trip, including network
> latency and model inference time. The agent function runs inside
> `CompletableFuture.supplyAsync()` with `orTimeout(timeoutMs, MILLISECONDS)`.
> `TimeoutException` triggers the standard worker retry/stall mechanism.

### Issue 3: config-secrets-management.md

Add per-provider secret convention table:

| Provider | Secret name | Fields | Notes |
|----------|-------------|--------|-------|
| OpenAI | `openai` | `apiKey`, `organizationId` | |
| Anthropic | `anthropic` | `apiKey` | |
| Mistral AI | `mistralai` | `apiKey` | |
| Google AI | `googleai` | `apiKey` | |
| Ollama | — | N/A | Local deployment, no auth required |

Add guidance: "Numeric fields sourced from configMaps require `| tonumber` coercion
in the JQ expression (e.g., `${$config.\"model-params\".temperature | tonumber}`)."

### Issue 4: secret-manager-spi.md

- Update Phase 1 checkboxes to reflect completed status.
- Add cross-reference:
  > See also: `docs/specs/2026-05-25-agent-worker-ai-model-design.md` for the complete
  > agent model architecture including per-provider secret conventions.

### Issue 5: CLAUDE.md

Add section after "Worker Provisioner SPIs":

```markdown
## Agent Worker AI Model

AI agent workers live in `api/src/main/java/io/casehub/api/model/ai/`:

- `Agent` — immutable execution unit; holds systemPrompt, transformers, ChatModel
- `AgentBuilder` — fluent builder; JQ string mode or lambda mode for transformers
- `ChatModelProvider` — SPI interface; implementations use reflection to avoid
  compile-time LLM SDK dependencies
- `ModelType` — enum: OPENAI, OLLAMA, ANTHROPIC, MISTRAL, GOOGLE_AI_GEMINI
- `JqTransformer` — standalone JQ evaluator (jackson-jq 1.6); thread-safe after
  construction
- `AgentException` — unchecked exception for agent failures

Provider implementations in sub-packages (`openai/`, `anthropic/`, `mistral/`,
`gemini/`, `ollama/`) use `ServiceLoader` for discovery and reflection-based
builder construction via `Class.forName()`.

`AgentConverter` (`api/.../converter/`) bridges jsonschema2pojo schema models to
API `Agent` instances. Called by `CaseDefinitionYamlMapper` when a worker has an
`agent` YAML block.

**Test pattern:** Mock `ChatModel` via package-private `AgentBuilder.model(ChatModel)`
for unit tests. `@QuarkusTest` integration tests use inner `CaseHub` subclasses with
mock `ChatModelProvider` returning canned JSON.
```

---

## Test Changes

### Issue 6: AgentConverterTest

New file: `api/src/test/java/io/casehub/api/model/converter/AgentConverterTest.java`

Tests:
- `toApiAgent_null_returnsNull()`
- `toChatModelProvider_null_throwsIllegalArgument()`
- `toChatModelProvider_noProviderConfigured_throwsIllegalArgument()`
- `toApiAgent_withOpenAi_allFields()` — all OpenAI fields set
- `toApiAgent_withOpenAi_minimalFields()` — only required fields
- `toApiAgent_withAnthropic_allFields()` / `_minimalFields()`
- `toApiAgent_withMistral_allFields()` / `_minimalFields()`
- `toApiAgent_withGemini_allFields()` / `_minimalFields()`
- `toApiAgent_withOllama_allFields()` / `_minimalFields()`
- `toApiAgent_withUserMessageTemplate()`
- `toApiAgent_withoutUserMessageTemplate()`

Each provider test verifies: builder returns non-null `Agent`, agent can `execute()`
with a mock `ChatModel` returning valid JSON, and provider-specific fields are applied
correctly.

### Issue 7: responseSchema tests

Added to existing `AgentBuilderTest.java`:
- `build_responseSchema_jsonSchema_passedToRequest()` — verify `ChatRequest.responseFormat()`
  contains the schema
- `build_responseSchema_class_objectType_generatesObjectSchema()`
- `build_responseSchema_class_collectionType_generatesArraySchema()`

### Issue 8: JqTransformerTest expansions

- `apply_expressionProducesNoOutput_throwsAgentException()`
- `apply_runtimeJqError_throwsAgentException()`
- `apply_multipleResults_returnsFirst()`
- `constructor_nullExpression_throws()`

### Issue 9: Agent.execute() error paths

Added to existing `AgentTest.java`:
- `execute_inputTransformerFails_throwsAgentException()`
- `execute_outputTransformerFails_throwsAgentException()`
- `execute_llmReturnsEmptyString_throwsAgentException()`

---

## Files Touched

| File | Change |
|------|--------|
| `docs/DESIGN.md` | Add agent lifecycle, converter, AgentDescriptor, userMessageTemplate, responseSchema |
| `docs/worker-timeout.md` | Add agent worker timeout documentation |
| `docs/config-secrets-management.md` | Add per-provider secret table, Mistral/Gemini/Ollama |
| `docs/secret-manager-spi.md` | Fix Phase 1 checkboxes, add cross-reference |
| `CLAUDE.md` | Add Agent Worker AI Model section |
| `api/src/test/.../converter/AgentConverterTest.java` | **New file** — all converter tests |
| `api/src/test/.../ai/AgentBuilderTest.java` | Add responseSchema tests |
| `api/src/test/.../ai/AgentTest.java` | Add error path tests |
| `api/src/test/.../ai/JqTransformerTest.java` | Add error/edge case tests |

---

## Related Specs

- `2026-05-25-agent-worker-ai-model-design.md` — agent subsystem architecture (primary spec)
- `2026-05-22-agent-command-content-design.md` — Agent/JqTransformer decoupling (engine#316)
- `2026-05-23-capability-health-design.md` — AgentDescriptor/CapabilityHealth (engine#341)
- `2026-05-14-config-secrets-design.md` — secrets/configMaps resolution
