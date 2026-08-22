---
title: "When Reasoning Becomes a First-Class Record"
date: 2026-08-22
entry_type: note
subtype: diary
tags: [react, auditability, langchain4j, tool-use, eventlog]
---

# When Reasoning Becomes a First-Class Record

LangChain4j's `AiServices` runs a perfectly serviceable ReAct loop. The LLM reasons about what to do, calls a tool, observes the result, reasons again. The problem is where the reasoning goes: into the LLM's context window, and nowhere else. When the loop ends, the chain of thought that produced the answer evaporates. For a research prototype, that's fine. For a financial services agent evaluating a loan application, it's a gap you can drive a compliance audit through.

I wanted to solve this at the engine level — not as an afterthought bolted onto an existing loop, but as a first-class execution model where the reasoning trace is the point.

## The architectural fork

The obvious approach was to extend `Agent` — casehub's existing LLM wrapper — with tool-use capabilities. Agent already handles system prompts, input/output transformation, and structured responses. Adding a tool-use loop feels natural.

It's also wrong. Agent lives in `engine-api`, which cannot depend on `engine-common` where EventLog lives. The per-cycle audit trail needs to happen at the handler layer, not inside the LLM wrapper. And once you accept that the loop belongs in the handler, the design simplifies: Agent stays a pure single-shot call. The handler owns the conversation, the tool dispatch, the cycle tracking, and the audit writes.

Three options went through the design review. A blocks `PatternType.REACT` was tempting — blocks already has an orchestrated driver with a five-SPI composition model (routing, activation, dispatch, aggregation, termination). But ReAct is fundamentally a single agent calling tools, not multiple agents being coordinated. The five-SPI decomposition adds ceremony without composability benefit, and the stateless-per-iteration model fights ReAct's need for persistent conversation context across cycles.

The winner: a new `ReActWorkerFunctionHandler` in its own optional module, following the same pattern as the A2A, MCP, and flow modules. Each execution model gets its own handler. The engine already knows how to discover and compose them.

## Two kinds of tools

A ReAct agent needs tools. In casehub, tools come in two flavours. A `WorkerTool` wraps an engine `Capability` and dispatches through `WorkerRuntime.execute()` — full retry, full audit, distributed execution. A `LocalTool` is a plain Java function, called directly, no engine overhead. Both implement `ToolSource`, a sealed interface with exhaustive pattern matching in the handler's dispatch loop.

The sealed type matters. When the LLM calls a tool, the handler switches on the variant:

```java
switch (tool) {
    case ToolSource.WorkerTool wt -> {
        var result = runtime.execute(wt.workerName(), args);
        output = extractOutput(result);
    }
    case ToolSource.LocalTool lt -> {
        output = lt.fn().apply(args);
    }
}
```

No `instanceof` chains, no dynamic dispatch, no tool registry to query. The compiler enforces exhaustiveness. If a third variant appears later, every switch breaks until it's handled.

## The audit contract

Each cycle through the loop — reason, act, observe — produces a `REACT_CYCLE` EventLog entry. Not at the end of the loop. Not as a batch summary. Per cycle, as it happens.

```json
{
  "cycleIndex": 2,
  "reasoningText": "The credit score is 720. I should now retrieve the risk model...",
  "toolCalls": [{
    "toolName": "risk-model-retrieval",
    "toolArgs": {"modelId": "standard-v2"},
    "toolResult": {"risk": "low", "score": 0.12},
    "toolSource": "worker",
    "durationMs": 1450
  }],
  "tokenUsage": {"inputTokens": 1240, "outputTokens": 89}
}
```

An auditor reconstructs the full decision chain with a single query: `findByCaseAndTypes(caseId, List.of(REACT_CYCLE))`. Ordered by timestamp, each entry shows what the LLM was thinking, what it decided to do, and what came back. The reasoning that LangChain4j discards is the thing we're keeping.

## What the review caught

The design review surfaced a real runtime bug that unit tests couldn't find. The handler was publishing `ReActCycleEvent` — a Java record — directly onto the Vert.x event bus. Mockito's mock `EventBus` happily accepts anything. A real Vert.x bus requires a `MessageCodec` for custom types, and without one, the first cycle would throw `No message codec for type`. The fix: serialize to `JsonObject` before publishing. The event handler on the receiving end deserializes from the JSON. Straightforward once you see it — invisible until you do.

A second subtlety was `Duration` serialization. Jackson's default `ObjectMapper` doesn't handle `java.time.Duration` without the JSR-310 module. The `ToolCallRecord` carries a `Duration` field for per-tool timing. Serialization fails silently in places where the Duration is nested inside a list inside a record — the stack trace points at `valueToTree` with no obvious connection to the Duration. Registering `JavaTimeModule` on the mapper fixed both the handler's event publishing and the event handler's EventLog write.

## What this opens up

The handler runs on a virtual thread with `Future.get()` for hard timeout enforcement. The LLM can take as long as it needs per cycle, but the total execution is bounded. `Thread.interrupted()` is checked before each LLM call for cancellation awareness.

The next step is the deferred `@QuarkusTest` integration test — full case flow from case start through the tool-use loop to case completion. That needs a mock `ChatModelProvider` that returns canned tool-use responses, which is the same pattern every other handler module uses. The infrastructure is there; we just ran out of session before wiring it.

Longer term, this is the foundation for compliance-grade agent tracing. A loan officer, a clinical triage agent, an AML investigator — any domain where "show your working" isn't optional. The reasoning trace was always the valuable part. Now it's a database record.
