---
title: "A2A and MCP — Handler, Not Provisioner"
date: 2026-08-04
tags: [casehub, engine, a2a, mcp, architecture, worker-model]
projects: [casehub-engine]
entry_type: note
subtype: diary
status: draft
---

The langchain4j parity audit flagged two gaps: we couldn't invoke A2A agents or MCP tools as casehub workers. The obvious implementation path was `WorkerProvisioner` — it's the SPI that provisions external workers. That's what the original issue description assumed. It was wrong.

## Why Provisioner Is the Wrong Seam

`WorkerProvisioner` exists for a specific scenario: something external needs to be *started* and then *monitored*. It provisions infrastructure, returns a `ProvisionResult`, and the engine tracks the lifecycle through channels. A2A and MCP don't fit this model. An A2A `message/send` is a synchronous HTTP call. An MCP `callTool()` is a synchronous RPC. Neither needs provisioning, neither has an independent lifecycle. They're function calls with a network boundary.

`WorkerFunctionHandler` is the right seam. It gives us timeout enforcement via `Future.get(timeoutMs)`, retry through `QuartzRetryService`, outcome mapping to `WorkerResult`, and EventLog provenance — all for free. The handler pipeline already solves every cross-cutting concern. Forcing these into `WorkerProvisioner` would mean reimplementing timeout, retry, and outcome semantics outside the existing pipeline.

## One Function Per Tool

The early MCP design had a single `McpWorkerFunction` that routed to different tools via `ExecutionMetadata`. This creates an implicit dispatch layer — the handler has to inspect metadata to know which tool to call, and capability bindings can't target individual tools.

The fix was obvious once stated: one `McpWorkerFunction` per discovered tool. The tool name is on the function record at construction time. The handler calls `client.callTool(mcp.toolName(), input)` directly — no routing, no metadata inspection, no ambiguity about which binding targets which tool. A single YAML `mcp:` declaration expands into N workers via `discoverWorkers()`:

```java
// Provider connects at definition build time, discovers tools
List<Tool> tools = discoveryClient.listTools().tools();

// One worker per tool — binding-level targeting works naturally
return tools.stream().map(tool -> {
    Capability capability = Capability.builder()
        .name(tool.name())
        .inputSchema(".").outputSchema(".").build();
    McpWorkerFunction function = new McpWorkerFunction(transport, tool.name());
    return new DiscoveredWorker(workerName, capability, function);
}).toList();
```

A2A follows the same pattern: one `A2AWorkerFunction` per skill.

## The Cross-Cutting Change That Paid For Both

A2A needed protocol metadata — endpoint, task ID, message ID, streaming status transitions — threaded through to the EventLog for audit. The existing handler contract returned `WorkerResult<?>`, which has no room for metadata that isn't worker output.

`HandlerResult` is a two-field record wrapping `WorkerResult<?>` with a `Map<String, Object> protocolMetadata`. Every handler returns it; existing handlers wrap with empty metadata. It's a twelve-line type that changes the return signature of every handler in the system — the kind of cross-cutting change you want to make once, not twice.

Because A2A paid the cost of introducing `HandlerResult`, MCP got protocol metadata (server identity, tool name, transport type, call duration) for zero additional cross-cutting work. Same record, same threading path through `QuartzWorkerExecutionJob` and `WorkflowExecutionCompletedHandler`.

## AuthConfig Extraction

A2A originally defined `A2AAuthConfig` — a record with `AuthType` (NONE, BEARER, API_KEY) and a Quarkus config key for token resolution. MCP needs identical auth for HTTP transport. Rather than duplicate or depend on the A2A module, I extracted it to `io.casehub.engine.common.internal.auth.AuthConfig`. Both modules depend on engine-common. Six files changed in the A2A module — all mechanical renames with no behavioural change.

## What This Actually Enables

The engine can now invoke any A2A-compliant agent or MCP server as a casehub worker, with the same lifecycle guarantees as in-process workers: timeout, retry, outcome routing, EventLog provenance. YAML declarations wire them into capability bindings — no Java code required for the common case:

```yaml
workers:
  - name: remote-analyst
    capabilities: [analysis]
    a2a:
      endpoint: https://analyst-agent.example.com
      streaming: true

  - name: file-tools
    mcp:
      command: ["/path/to/mcp-server"]
```

The file-tools worker auto-discovers whatever tools the MCP server exposes. Each tool becomes a separate capability. The analyst worker talks A2A with optional SSE streaming, artifact accumulation, and deadline-aware cancellation.

Next step is AgentCard-to-AgentDescriptor bridging for A2A — grounding remote agents in the same vocabulary subsumption that local agents use for capability matching.
