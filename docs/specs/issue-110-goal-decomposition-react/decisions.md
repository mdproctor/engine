## D1: Architectural placement — new handler with explicit tool-use loop

**Choice:** New `ReActWorkerFunctionHandler` manages the reason-act-observe loop. Agent stays single-shot. The handler calls `ChatModel.chat()` per cycle, manages the multi-turn message list, dispatches tool calls, and writes per-cycle EventLog entries. New module `casehub-engine-react`.
**Alternatives:**
- Agent gains tool-use (Tier 1 internal) — Agent becomes responsible for engine integration, violates its "pure LLM wrapper" contract, dependency violation (engine-api cannot depend on engine-common for EventLog)
- Blocks PatternType.REACT — impedance mismatch (blocks' 5-SPI model is stateless per iteration, ReAct needs persistent conversation context), over-decomposition for a single-agent-with-tools pattern
**Rationale:** The handler is the right layer for engine integration — it owns timeout, WorkerRuntime, EventLog, and cancellation. Agent stays pure. Follows the established pattern where each execution model gets its own handler + function type (A2A, flow, blocks pattern). Blocks' 5-SPI model doesn't fit ReAct's single-agent-with-tools shape.
**Trade-offs:** New module + new handler + new WorkerFunction type. LangChain4j tool-use API adopted for the first time. Agent doesn't gain tool-use capability directly (handler owns it).
**Sources:** Agent.java:37-153 (single-shot LLM call, no tool-use), WorkerRuntime.java:24-33 (Tier 1 execute), AbstractExecutionDriver.java:97-149 (blocks 5-phase loop), PatternWorkerFunctionHandler (existing handler pattern), A2AWorkerFunctionHandler (existing handler pattern)
**Exploration:** quick
**Status:** captured

## D2: Tool model — sealed ToolSource type

**Choice:** `sealed interface ToolSource permits WorkerTool, LocalTool`. `WorkerTool` carries capability metadata (name, description, inputSchema, outputSchema) — handler dispatches via `WorkerRuntime.execute()`. `LocalTool` carries a `Function<Map, Map>` + JSON schema — handler calls directly. Both generate `ToolSpecification` for the LLM. Type-safe dispatch via pattern matching in the handler.
**Alternatives:**
- Unified ToolProvider SPI — CDI-discovered beans per tool. More extensible but heavier; every tool type needs a CDI bean. Over-engineered for two concrete variants.
- LangChain4j native ToolProvider/ToolExecutor — thinnest abstraction but couples LangChain4j types into engine API surface, breaking the existing pattern of keeping LLM SDK types internal to the agent layer.
**Rationale:** Sealed type is the cleanest representation of a closed set of two variants. Pattern matching gives exhaustive dispatch. No CDI overhead. LangChain4j types stay internal to the handler (only `toToolSpec()` bridges to the SDK). Follows the platform's sealed-type conventions (WorkerOutcome, RoutingResult, SubCaseMapping).
**Trade-offs:** Not extensible to third-party tool types without opening the sealed interface. Acceptable because tool types map to execution paths, and the engine owns all execution paths.
**Sources:** Capability.java:5 (record fields map to ToolSpecification), WorkerScope.java:17 (execute method for WorkerTool dispatch)
**Exploration:** quick
**Status:** captured

## D3: Audit model — per-cycle EventLog entries

**Choice:** New `CaseHubEventType.REACT_CYCLE`. One EventLog entry per reason-act-observe cycle. Metadata carries `cycleIndex`, `reasoningText`, `toolName`, `toolArgs`, `toolResult`, `toolSource` (worker|local), `tokenUsage`, `durationMs`. Full trace queryable via `EventLogRepository.findByCaseAndTypes(caseId, List.of(REACT_CYCLE))`.
**Alternatives:**
- Batch in protocolMetadata — all cycles as a JSON array in final WORKER_EXECUTION_COMPLETED. Simpler (no new event type) but not queryable per-cycle. An auditor can't find "which cycle called the risk model" without deserializing the entire array.
- Both per-cycle and summary — redundant storage. The per-cycle entries ARE the summary when queried in order.
**Rationale:** Per-cycle entries are the atomic audit unit. Each entry is independently queryable, timestamped, and ordered. This is the core differentiator from LangChain4j — the reasoning step that was invisible becomes a first-class persisted event. The handler writes the EventLog entry after each tool execution completes, before the next LLM call.
**Trade-offs:** More EventLog entries per case (N cycles = N entries). Acceptable — EventLog is designed for high-volume append. The `reasoningText` field can be large (LLM outputs). Consider truncation or configurable capture depth in v2.
**Sources:** WorkflowExecutionCompletedHandler.java:690-702 (protocolMetadata merge pattern), CaseHubEventType (existing event types)
**Exploration:** quick
**Status:** captured

## D4: Loop termination — LLM-native with config guards

**Choice:** Loop ends when LLM returns text without tool calls (natural completion — the LLM IS the termination logic). Config guards: `maxCycles` (default 20, hard limit) and overall timeout from `ExecutionPolicy`. No separate `TerminationStrategy` SPI. When `maxCycles` is hit, the handler returns `WorkerResult.expired("ReAct cycle limit exceeded")`.
**Alternatives:**
- Explicit goal condition — LLM writes to `.finalAnswer` key, Goal condition evaluates. Separates termination from handler but adds indirection; the LLM already signals completion by not calling tools.
- Pluggable TerminationEvaluator SPI — extensible but over-engineered for v1. Custom termination (confidence thresholds, cost budgets) can be added later as the config guard set grows.
**Rationale:** ReAct's termination is defined by the protocol: the LLM stops calling tools when it has the answer. This is how every ReAct implementation works (LangChain, LangChain4j, CrewAI). Adding a separate evaluator fights the protocol. The `maxCycles` guard prevents runaway loops. Timeout prevents runaway cost.
**Trade-offs:** No extensible termination in v1. If custom termination is needed (e.g. "stop when confidence > 0.8"), it requires handler modification or a v2 SPI addition.
**Depends on:** D1 (handler owns the loop, so handler owns termination)
**Sources:** OrchestratedDriver.java:24-31 (while loop with cancellation pattern), SyncAgentWorkerFunctionHandler (timeout enforcement pattern)
**Exploration:** quick
**Status:** captured
