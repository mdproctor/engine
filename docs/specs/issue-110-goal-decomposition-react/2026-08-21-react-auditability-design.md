# ReAct Cycles with Full Auditability

**Issue:** engine#114
**Branch:** issue-110-goal-decomposition-react
**Depends on:** engine#110 (goal decomposition — closed), engine#881 (agentic planning — closed)

## Problem

casehub workers call LLMs via `Agent.execute()` — a single-shot system+user → JSON response with no tool-use, no multi-turn, and no reasoning trace capture. When an LLM needs to iteratively call tools (search, retrieve, compute) before producing a final answer, there is no mechanism to:

1. Expose Worker capabilities as LLM-callable tools
2. Run the reason-act-observe loop with per-cycle auditability
3. Persist the LLM's reasoning text alongside each tool call

LangChain4j's `AiServices` + `@Tool` runs the loop but reasoning traces exist only in the LLM's context window — no persistence, no stage gating, no distributed tool execution.

## Architecture

### Core Principle

The `ReActWorkerFunctionHandler` manages the reason-act-observe loop. `Agent` stays single-shot (pure LLM call wrapper). The handler calls `ChatModel.chat()` per cycle, manages the multi-turn message list, dispatches tool calls via two paths (engine Workers or local functions), and writes per-cycle `REACT_CYCLE` EventLog entries via the event bus.

New module: `casehub-engine-react` (optional, classpath-activated — same pattern as `casehub-engine-flow`, `casehub-engine-a2a`, `casehub-engine-mcp`). Directory: `react/`.

```
Worker input arrives
  → ReActWorkerFunctionHandler.execute()
      → Runs on virtual thread (Future.get with timeout)
      → Builds ToolSpecifications from ToolSource list
      → Constructs initial messages: [SystemMessage, UserMessage(input)]
      → Loop:
          1. Check cancellation (CaseCompletionTracker)
          2. ChatModel.chat(messages, toolSpecs) → ChatResponse
          3. AiMessage = response.aiMessage()
          4. If AiMessage.hasToolExecutionRequests():
               For each ToolExecutionRequest:
                 Match toolName → ToolSource (guard: unknown name → error message to LLM)
                 WorkerTool → resolve worker name → WorkerRuntime.execute(workerName, args)
                 LocalTool  → fn.apply(args)
               Append ToolExecutionResultMessages to messages
               Publish REACT_CYCLE event via event bus
               Check maxCycles guard
               Goto 1
          5. If AiMessage.text() only → final answer
               Parse JSON → Map via Jackson
               → return HandlerResult(WorkerResult.of(output), reactMetadata)
```

### Why This Architecture

Four options were evaluated:

1. **Agent gains tool-use** — violates Agent's "pure LLM wrapper" contract. Agent is in `engine-api` which cannot depend on `engine-common` (EventLog). Per-cycle EventLog writes require the handler layer.

2. **Blocks PatternType.REACT** — impedance mismatch. Blocks' 5-SPI model (routing, activation, dispatch, aggregation, termination) is stateless per iteration. ReAct needs persistent LLM conversation context across iterations. Single-agent-with-tools doesn't benefit from multi-agent coordination abstractions.

3. **Engine binding loop with LlmPlanningStrategy** — the issue's original proposal. `LlmPlanningStrategy` was never built (#110 delivered `GoalDecomposer` instead). Even if it existed, the engine's binding loop doesn't maintain LLM conversation context across evaluations.

4. **New handler (chosen)** — the handler owns timeout, WorkerRuntime, EventLog, and cancellation. Agent stays pure. Follows the established pattern where each execution model gets its own handler + function type.

## Components

All types live in `casehub-engine-react` (not engine-api). This follows the A2A, MCP, and flow module pattern — each execution model's types are self-contained in their module. The `WorkerFunction` marker interface and `WorkerFunctionHandler` SPI are in engine-common; concrete implementations are in the module.

### ToolSource (casehub-engine-react, `io.casehub.engine.react`)

Sealed interface representing a tool available to the ReAct loop. Two variants: engine-dispatched Workers and local in-process functions.

```java
public sealed interface ToolSource {
    String name();
    String description();

    record WorkerTool(
        Capability capability,
        String workerName
    ) implements ToolSource {
        @Override public String name() { return capability.name(); }
        @Override public String description() { return capability.description(); }
    }

    record LocalTool(
        String name,
        String description,
        Function<Map<String, Object>, Map<String, Object>> fn,
        Map<String, Object> parameterSchema
    ) implements ToolSource {}
}
```

`WorkerTool` holds a `Capability` reference directly (not duplicated fields) and carries `workerName` for dispatch. The handler calls `runtime.execute(workerName, args)` — `WorkerScope.execute(String, Map)` takes a worker name, not a capability name.

`LocalTool` carries a `Function<Map, Map>` (direct invocation) and a `Map<String, Object>` parameter schema (JSON Schema as a map — avoids importing LangChain4j's `JsonSchemaElement` into the sealed interface). The handler converts to `JsonSchemaElement` internally. Local tools are registered programmatically via the builder; YAML support deferred (functions are not serializable).

### ToolSpecificationBuilder (casehub-engine-react, internal)

Converts `ToolSource` instances to LangChain4j `ToolSpecification`. All LangChain4j tool types (`ToolSpecification`, `JsonSchemaElement`) are imported only within this module — never in engine-api.

```java
class ToolSpecificationBuilder {
    static ToolSpecification fromWorkerTool(ToolSource.WorkerTool wt) {
        return ToolSpecification.builder()
            .name(wt.capability().name())
            .description(wt.capability().description())
            .parameters(deriveParametersFromCapability(wt.capability()))
            .build();
    }

    static ToolSpecification fromLocalTool(ToolSource.LocalTool lt) {
        return ToolSpecification.builder()
            .name(lt.name())
            .description(lt.description())
            .parameters(toJsonSchemaElement(lt.parameterSchema()))
            .build();
    }
}
```

For `WorkerTool`, parameters are derived from `Capability.inputSchema()`. Since `inputSchema` is a JQ expression (context projection), the builder extracts top-level field names from the JQ output shape. Complex nested schemas require `LocalTool` with explicit parameter schema.

### ReActWorkerFunction (casehub-engine-react, `io.casehub.engine.react`)

Record implementing `WorkerFunction<Map, Map>`. Holds the ChatModel, system prompt, tool list, and cycle limit.

```java
public record ReActWorkerFunction(
    ChatModel model,
    String systemPrompt,
    List<ToolSource> tools,
    int maxCycles
) implements WorkerFunction<Map, Map> {

    public ReActWorkerFunction {
        Objects.requireNonNull(tools);
        if (tools.isEmpty()) throw new IllegalArgumentException("ReAct requires at least one tool");
        if (maxCycles < 1) throw new IllegalArgumentException("maxCycles must be >= 1");
    }

    public ReActWorkerFunction(ChatModel model, String systemPrompt, List<ToolSource> tools) {
        this(model, systemPrompt, tools, 20);
    }

    @Override public Class<Map> inputType() { return Map.class; }
    @Override public Class<Map> outputType() { return Map.class; }
}
```

### ReActWorkerFunctionProvider (casehub-engine-react)

`@ApplicationScoped`, implements `WorkerFunctionProvider`. Detects `react:` YAML blocks on worker definitions, constructs `ReActWorkerFunction`.

```yaml
workers:
  - name: research-analyst
    capabilities: [web-search, document-retrieval, summarise]
    react:
      maxCycles: 15
      tools:                          # optional — defaults to all capabilities
        - web-search
        - document-retrieval
        - summarise
    agent:
      model: anthropic
      modelName: claude-sonnet-4-20250514
      systemPrompt: |
        You are a research analyst investigating {{goal}}.
        Use the available tools to gather information.
        Produce a summarised analysis with confidence >= 0.8.
```

`react:` declares the loop configuration and optional tool filtering. `agent:` provides the `ChatModel` and `systemPrompt` configuration (via `ChatModelProvider`, same resolution as `AgentConverter`). The `systemPrompt` lives on the `agent:` block (not duplicated on `react:`) — a worker has one system prompt regardless of execution mode.

When `tools:` is omitted, all capabilities declared on the worker are exposed as `WorkerTool`. When `tools:` is specified, only named capabilities are included. The provider resolves `Capability` records and worker names from the `CaseDefinition` at build time.

When a worker has both `react:` and `agent:` blocks, `ReActWorkerFunctionProvider` takes precedence over the default agent provider — the worker is a ReAct worker, not a single-shot agent.

`LocalTool` registration is builder-only (programmatic API):
```java
Worker.builder()
    .name("analyst")
    .function(new ReActWorkerFunction(
        chatModel,
        "You are an analyst...",
        List.of(
            new ToolSource.WorkerTool(webSearchCap, "web-search-worker"),
            new ToolSource.LocalTool("calculate", "Run a calculation",
                args -> Map.of("result", compute(args)),
                Map.of("expression", Map.of("type", "string")))
        )))
    .build();
```

### ReActWorkerFunctionHandler (casehub-engine-react)

`@ApplicationScoped`, implements `WorkerFunctionHandler`. The core execution loop. Runs on a virtual thread with `Future.get(timeout)` for hard timeout enforcement.

```java
@ApplicationScoped
public class ReActWorkerFunctionHandler implements WorkerFunctionHandler {

    private final WorkerRuntimeFactory runtimeFactory;
    private final EventBus eventBus;
    @VirtualThreads ExecutorService executor;

    @Override
    public boolean supports(WorkerFunction<?, ?> function) {
        return function instanceof ReActWorkerFunction;
    }

    @Override
    public HandlerResult execute(
            WorkerFunction<?, ?> function, Object inputData,
            WorkerContext context, int timeoutMs,
            ExecutionMetadata metadata) {

        var reactFn = (ReActWorkerFunction) function;
        var future = executor.submit(() ->
            executeLoop(reactFn, inputData, context, metadata));

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return toExpiredResult("timeout", reactFn, 0);
        }
    }

    private HandlerResult executeLoop(
            ReActWorkerFunction reactFn, Object inputData,
            WorkerContext context, ExecutionMetadata metadata) {

        var runtime = runtimeFactory.create(
            context.caseId(), metadata.workerName(), context);

        var toolSpecs = ToolSpecificationBuilder.buildAll(reactFn.tools());
        var toolMap = buildToolMap(reactFn.tools());

        var messages = new ArrayList<ChatMessage>();
        messages.add(SystemMessage.from(reactFn.systemPrompt()));
        messages.add(UserMessage.from(formatInput(inputData)));

        int cycleCount = 0;
        int totalInputTokens = 0;
        int totalOutputTokens = 0;
        var toolsUsed = new LinkedHashSet<String>();

        while (cycleCount < reactFn.maxCycles()) {
            if (Thread.interrupted()) {
                return toExpiredResult("cancelled", reactFn, cycleCount);
            }

            var request = ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(toolSpecs)
                .build();

            var response = reactFn.model().chat(request);
            var aiMessage = response.aiMessage();
            messages.add(aiMessage);

            if (response.tokenUsage() != null) {
                totalInputTokens += response.tokenUsage().inputTokenCount();
                totalOutputTokens += response.tokenUsage().outputTokenCount();
            }

            if (!aiMessage.hasToolExecutionRequests()) {
                return toCompletedResult(
                    aiMessage.text(), reactFn, cycleCount,
                    totalInputTokens, totalOutputTokens, toolsUsed);
            }

            var toolResults = executeToolCalls(
                aiMessage.toolExecutionRequests(), toolMap, runtime);
            messages.addAll(toolResults.messages());
            toolResults.results().forEach(r -> toolsUsed.add(r.name()));

            publishCycleEvent(context.caseId(), metadata, cycleCount,
                aiMessage, toolResults, response.tokenUsage());

            cycleCount++;
        }

        return toExpiredResult("maxCycles", reactFn, cycleCount);
    }
}
```

**Tool dispatch (pattern matching with hallucination guard):**
```java
private ToolCallResults executeToolCalls(
        List<ToolExecutionRequest> requests,
        Map<String, ToolSource> toolMap,
        WorkerRuntime runtime) {

    var resultMessages = new ArrayList<ToolExecutionResultMessage>();
    var results = new ArrayList<ToolCallResult>();

    for (var request : requests) {
        var tool = toolMap.get(request.name());

        if (tool == null) {
            var errorMsg = "Unknown tool: " + request.name()
                + ". Available tools: " + toolMap.keySet();
            resultMessages.add(ToolExecutionResultMessage.from(request, errorMsg));
            results.add(new ToolCallResult(
                request.name(), Map.of(), Map.of("error", errorMsg),
                "unknown", Duration.ZERO));
            continue;
        }

        var args = parseArgs(request.arguments());
        var start = Instant.now();

        Map<String, Object> output;
        String sourceType;

        try {
            switch (tool) {
                case ToolSource.WorkerTool wt -> {
                    var result = runtime.execute(wt.workerName(), args);
                    output = extractOutput(result);
                    sourceType = "worker";
                }
                case ToolSource.LocalTool lt -> {
                    output = lt.fn().apply(args);
                    sourceType = "local";
                }
            }
        } catch (Exception e) {
            output = Map.of("error", e.getMessage());
            sourceType = tool instanceof ToolSource.WorkerTool ? "worker" : "local";
        }

        var duration = Duration.between(start, Instant.now());
        resultMessages.add(ToolExecutionResultMessage.from(
            request, MAPPER.writeValueAsString(output)));
        results.add(new ToolCallResult(
            request.name(), args, output, sourceType, duration));
    }
    return new ToolCallResults(resultMessages, results);
}
```

**Per-cycle event publishing (via event bus, not direct EventLog save):**

The handler publishes a `ReActCycleEvent` on the Vert.x event bus. A `ReActCycleEventHandler` (`@ConsumeEvent @RunOnVirtualThread`) receives it and writes the EventLog entry. This follows the engine's event infrastructure pattern — handlers publish events, dedicated consumers persist them.

```java
private void publishCycleEvent(
        UUID caseId, ExecutionMetadata metadata,
        int cycleIndex, AiMessage aiMessage,
        ToolCallResults toolResults,
        dev.langchain4j.model.output.TokenUsage tokenUsage) {

    var event = new ReActCycleEvent(
        caseId,
        metadata.workerName(),
        metadata.tenancyId(),
        cycleIndex,
        extractReasoningText(aiMessage),
        toolResults.results().stream().map(r -> new ToolCallRecord(
            r.name(), r.args(), r.output(), r.sourceType(), r.duration()
        )).toList(),
        tokenUsage != null
            ? new TokenUsage(tokenUsage.inputTokenCount(), tokenUsage.outputTokenCount())
            : null
    );

    eventBus.publish(EventBusAddresses.REACT_CYCLE, event);
}
```

`ReActCycleEventHandler` writes the EventLog:
```java
@ApplicationScoped
public class ReActCycleEventHandler {

    @Inject EventLogRepository eventLogRepository;

    @ConsumeEvent(EventBusAddresses.REACT_CYCLE)
    @RunOnVirtualThread
    public void onReactCycle(ReActCycleEvent event) {
        var eventLog = new EventLog();
        eventLog.setCaseId(event.caseId());
        eventLog.setEventType(CaseHubEventType.REACT_CYCLE);
        eventLog.setWorkerId(event.workerName());
        eventLog.setTenancyId(event.tenancyId());
        eventLog.setMetadata(buildMetadata(event));
        eventLogRepository.save(eventLog, event.tenancyId());
    }
}
```

### CaseHubEventType.REACT_CYCLE (engine-api)

New event type in the `CaseHubEventType` enum. `CaseHubEventType` lives in `engine-api` (`io.casehub.api.model`), not engine-common.

Metadata schema — uniform for single and parallel tool calls:
```json
{
  "cycleIndex": 0,
  "reasoningText": "I should search for recent papers on quantum computing...",
  "toolCalls": [
    {
      "toolName": "web-search",
      "toolArgs": {"query": "quantum computing financial cryptography 2026"},
      "toolResult": {"searchResults": [...]},
      "toolSource": "worker",
      "durationMs": 1450
    }
  ],
  "tokenUsage": {"inputTokens": 1240, "outputTokens": 89}
}
```

`toolCalls` is always an array (uniform schema regardless of single or parallel tool use). This eliminates the asymmetric `toolName` + `allToolCalls` design from the original spec.

Queryable via:
```java
eventLogRepository.findByCaseAndTypes(
    caseId,
    List.of(CaseHubEventType.REACT_CYCLE, CaseHubEventType.WORKER_EXECUTION_COMPLETED)
);
```

### Final Answer Parsing

When the LLM returns text without tool calls, the handler parses the text as JSON into `Map<String, Object>` via Jackson:

```java
private HandlerResult toCompletedResult(String text, ...) {
    Map<String, Object> output;
    try {
        output = MAPPER.readValue(text, MAP_TYPE);
    } catch (JsonProcessingException e) {
        output = Map.of("answer", text);
    }
    return new HandlerResult(
        WorkerResult.of(output),
        buildProtocolMetadata(cycleCount, totalInputTokens, totalOutputTokens, toolsUsed));
}
```

If the text is valid JSON, it becomes the output map directly. If not (plain text answer), it's wrapped as `{"answer": "..."}`. This is consistent with how `Agent.executeDetailed()` handles responses.

### Reasoning Text Extraction

LLMs that support tool-use often include reasoning text before the tool call in the same response. The handler extracts this from `AiMessage.text()` (present alongside `toolExecutionRequests()` on models that support it — Claude, GPT-4). When `text()` is null (some models only return tool calls), `reasoningText` in the EventLog is empty string.

### HandlerResult Integration

The handler returns `HandlerResult` with `protocolMetadata`:
```java
Map.of(
    "reactCycleCount", cycleCount,
    "reactMaxCycles", reactFn.maxCycles(),
    "reactToolsUsed", List.copyOf(toolsUsed),
    "reactTotalDurationMs", totalDuration,
    "reactTotalInputTokens", totalInputTokens,
    "reactTotalOutputTokens", totalOutputTokens
)
```

This is merged into the `WORKER_EXECUTION_COMPLETED` EventLog by `WorkflowExecutionCompletedHandler` — same path as A2A and pattern handlers. Total token counts are aggregated across all cycles.

### Event Bus Address

New constant in `EventBusAddresses`: `REACT_CYCLE`. Published by the handler, consumed by `ReActCycleEventHandler`.

### Error Handling

**Tool execution failure:**
- `WorkerTool` failure → `WorkerResult.failed()` from `runtime.execute()`. The handler converts the failure to a `ToolExecutionResultMessage` with the error text, allowing the LLM to reason about the failure and try a different tool.
- `LocalTool` exception → caught, converted to error result message. Same pattern.
- The LLM decides whether to retry, try another tool, or give up.

**Hallucinated tool name:**
- LLM requests a tool name not in the tool map → handler returns an error message listing available tools. No NPE, no exception. The LLM sees the error and can correct itself.

**LLM failure:**
- `ChatModel.chat()` throws → handler catches, returns `WorkerResult.failed()` with the exception message. The engine's retry infrastructure handles retry.

**Max cycles exceeded:**
- Handler returns `WorkerResult.expired("ReAct cycle limit exceeded after N cycles")`. Routes through `OutcomePolicy.onExpired`. Distinct from timeout — metadata carries `"expiredReason": "maxCycles"`.

**Overall timeout:**
- Hard enforcement via `Future.get(timeoutMs, TimeUnit.MILLISECONDS)`. If the LLM call or tool dispatch exceeds the budget, `TimeoutException` fires, the virtual thread is interrupted, and the handler returns `WorkerResult.expired()` with `"expiredReason": "timeout"`. This prevents hanging LLM calls from blocking indefinitely.

**Case cancellation:**
- The loop checks `Thread.interrupted()` before each LLM call. When the engine cancels the case, the virtual thread is interrupted, and the loop exits with an expired result.

**Partial audit trail on mid-loop failure:**
- REACT_CYCLE events are published per cycle via the event bus. If the LLM call fails on cycle 5, cycles 0-4 are already persisted. The final WORKER_EXECUTION_COMPLETED event carries the failure reason and the cycle count, so auditors know how far the loop progressed.

## YAML Schema

```yaml
workers:
  - name: research-analyst
    capabilities: [web-search, document-retrieval, summarise]
    react:
      maxCycles: 15
      tools:                          # optional — defaults to all capabilities
        - web-search
        - document-retrieval
        - summarise
    agent:
      model: anthropic
      modelName: claude-sonnet-4-20250514
      systemPrompt: |
        You are a research analyst investigating {{goal}}.
        Use the available tools to gather information.
        Produce a summarised analysis with confidence >= 0.8.
```

`react:` and `agent:` blocks coexist. `react:` declares the loop configuration and optional tool filtering. `agent:` provides `ChatModel` and `systemPrompt`. When both are present, `ReActWorkerFunctionProvider` takes precedence — the worker is a ReAct worker, not a single-shot agent.

## Compile Dependencies

- `casehub-engine-common` (WorkerFunctionHandler SPI, WorkerExecutor, EventBus)
- `casehub-engine-api` (WorkerFunction, CaseHubEventType, EventBusAddresses)
- `casehub-worker-api` (WorkerResult, Capability)
- `casehub-engine` (runtime) — for `WorkerRuntimeFactory` (same pattern as `PatternWorkerFunctionHandler`)
- `dev.langchain4j:langchain4j-core` (ChatModel, ToolSpecification, ToolExecutionRequest)
- `quarkus-arc`, `quarkus-virtual-threads`

No dependency on `casehub-eidos-api` or `casehub-blocks`.

## Testing

### Unit tests (casehub-engine-react)

1. **`ReActWorkerFunctionHandlerTest`** — handler supports ReActWorkerFunction, runs tool-use loop with mock ChatModel returning tool calls then final answer. Verifies HandlerResult.
2. **`ToolSourceTest`** — WorkerTool from Capability, LocalTool invocation, sealed exhaustiveness.
3. **`ToolSpecificationBuilderTest`** — WorkerTool → ToolSpecification conversion, LocalTool schema mapping.
4. **`ReActWorkerFunctionProviderTest`** — detects `react:` YAML, constructs ReActWorkerFunction with correct config. Capability filtering when `tools:` specified.
5. **`ReActWorkerFunctionTest`** — record validation, maxCycles guard, empty tools rejection.
6. **`ReActErrorHandlingTest`** — tool failure → error message to LLM, hallucinated tool name → error message, max cycles → expired, timeout → expired, cancellation.
7. **`ReActCycleEventHandlerTest`** — event → EventLog write with correct metadata schema.

### Integration tests (@QuarkusTest, casehub-engine-react)

8. **`ReActExecutionIntegrationTest`** — full flow: case with react worker → start → handler runs → tool calls dispatch via engine pipeline → final answer → case completes. Mock ChatModelProvider with canned tool-use responses.
9. **`ReActAuditTrailTest`** — verify REACT_CYCLE EventLog entries are queryable, ordered, and contain complete reasoning traces. Verify WORKER_EXECUTION_COMPLETED carries react protocol metadata with aggregated token counts.

## Scope Boundaries

**In scope:**
- `casehub-engine-react` module with all types (ReActWorkerFunction, ToolSource, handler, provider, event handler)
- `CaseHubEventType.REACT_CYCLE` in engine-api
- `EventBusAddresses.REACT_CYCLE` in engine-api
- Per-cycle EventLog entries via event bus
- YAML `react:` block on worker definitions
- LangChain4j `ToolSpecification` / `ToolExecutionRequest` adoption (module-internal only)
- Error handling: tool failure → error message to LLM, hallucinated tool → error message, max cycles → expired, timeout → expired, cancellation
- Hard timeout via `Future.get()` on virtual thread
- Uniform `toolCalls` array metadata schema

**v1 limitations (deliberate):**
- Parallel tool calls executed sequentially (simplicity)
- `LocalTool` registration is builder-only (no YAML — functions not serializable)
- No custom termination SPI (LLM-native + config guards only)
- No conversation context persistence across JVM restarts (loop runs within a single worker execution; engine retry restarts from scratch)
- JQ `inputSchema`/`outputSchema` on Capability describe context projection, not JSON Schema. The builder extracts top-level field names for `ToolSpecification` parameters. Complex nested schemas require `LocalTool` with explicit parameter schema.
- No context window management for message list growth. maxCycles (default 20) bounds total messages. Long loops with large tool results may hit LLM context limits — the LLM's error is surfaced as a tool-use failure.

**Out of scope (future work):**
- Parallel tool call dispatch (CompletableFuture)
- Checkpointing for long-running ReAct loops (reuse PatternExecutionCheckpoint pattern)
- Custom TerminationEvaluator SPI
- Tool availability gating by compound scope
- Streaming tool results to the LLM
- LocalTool YAML support (requires function serialization or CDI discovery)
- Conversation context persistence for crash recovery
- Message list truncation / summarization for context window management

## References

- engine#114 — issue (requirements, structural comparison with LangChain4j)
- Agent.java:37-153 — current single-shot LLM call (no tool-use)
- WorkerRuntime.java:24-33 — Tier 1 execute interface
- WorkerScope.java:17 — `execute(String workerName, Map)` — takes worker name, not capability name
- Capability.java:5-33 — name/inputSchema/outputSchema/description → maps to ToolSpecification
- AbstractExecutionDriver.java:97-149 — blocks 5-phase iteration loop (considered, rejected for ReAct)
- A2AWorkerFunctionHandler — handler pattern precedent (module-local types, WorkerRuntimeFactory injection)
- McpWorkerFunctionHandler — handler pattern precedent (module-local types)
- PatternWorkerFunctionHandler — handler pattern precedent (virtual thread + Future.get timeout)
- WorkflowExecutionCompletedHandler.java:690-702 — protocolMetadata merge into EventLog
- SyncAgentWorkerFunctionHandler — timeout enforcement pattern
- PP-20260723-c4c1cf — virtual thread handler convention (@RunOnVirtualThread + void)
- PP-20260727-5267d2 — plan-type module boundary
- ReAct paper (Yao et al., 2022): https://arxiv.org/abs/2210.03629
