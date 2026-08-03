# MCP Tool Integration — Design Spec

**Issue:** engine#831
**Branch:** issue-830-a2a-mcp-integration
**Date:** 2026-08-03 (revised after light design review)

## Summary

Invoke MCP server tools as casehub workers. A new `casehub-engine-mcp` module provides a `WorkerFunction`/`WorkerFunctionHandler` implementation that connects to MCP-compliant servers, discovers tools via `tools/list`, and executes them via `callTool()`. Supports stdio (local subprocess) and HTTP (remote server) transports via the official MCP Java SDK.

**One function per tool** — each discovered MCP tool becomes a separate `Worker` with its own `McpWorkerFunction(transport, toolName)`. This matches the A2A pattern (one function per skill) and eliminates the need for tool-selection routing through `ExecutionMetadata`. A single YAML `mcp:` declaration expands into multiple workers via `WorkerFunctionProvider.discoverWorkers()`.

## Architecture Decisions

**WorkerFunction/Handler, not WorkerProvisioner.** Same rationale as A2A (engine#830): MCP tool calls are synchronous request/response, mapping naturally to the handler pipeline with timeout enforcement, retry via `QuartzRetryService`, `WorkerResult` outcomes, and EventLog provenance.

**Official MCP SDK, not langchain4j.** `io.modelcontextprotocol.sdk:mcp` v2.0.0 (MIT, maintained by Anthropic + Spring AI) provides `McpSyncClient` with transport abstraction, session lifecycle, and tool operations. `quarkus-langchain4j-mcp` was rejected — its primary value is LLM tool-calling pipeline integration (which casehub doesn't use), and it introduces a langchain4j dependency.

**One function per tool, not multi-tool routing.** The A2A module uses one `A2AWorkerFunction` per skill — the tool name is on the function record, not resolved at runtime via metadata. MCP follows this: each discovered tool gets its own `McpWorkerFunction(transport, toolName)`. No `ExecutionMetadata` cross-cutting change needed.

**Discovery in v1.** MCP's `tools/list` is a core protocol feature. Discovery happens at definition build time — the provider connects, lists tools, and creates workers. Differs from A2A (which deferred AgentCardDiscovery) because MCP tool enumeration is the standard interaction model.

## Module Structure

**Module:** `casehub-engine-mcp` (artifact ID) — directory `mcp/` at engine root. Short directory name per maven-submodule-folder-naming protocol. Jandex plugin required.

**Compile dependencies:**
- `casehub-engine-common` — `WorkerFunctionHandler`, `HandlerResult`, `AuthConfig`
- `casehub-engine-api` — `WorkerFunctionProvider`
- `casehub-worker-api` — `WorkerFunction`, `WorkerResult`, `Capability`
- `io.modelcontextprotocol.sdk:mcp` — MCP sync client, transports, protocol types
- `quarkus-arc`, `quarkus-virtual-threads`

**Not depending on:** `casehub-engine` (runtime), `casehub-eidos-api`, `casehub-engine-a2a`.

## Cross-Cutting Changes

### 1. AuthConfig extraction (engine-common)

Move `A2AAuthConfig` to `io.casehub.engine.common.internal.auth.AuthConfig`. Same record, same `AuthType` enum (NONE, BEARER, API_KEY), same `NONE` constant. Both `casehub-engine-a2a` and `casehub-engine-mcp` depend on engine-common.

`A2AWorkerFunction` and `A2AClient` switch from `A2AAuthConfig` to `AuthConfig`. Breaking change inside a2a — no external callers.

### 2. WorkerFunctionProvider.discoverWorkers() (engine-api)

New `default` method on `WorkerFunctionProvider`:

```java
public record DiscoveredWorker(
    String workerName,
    Capability capability,
    WorkerFunction<?, ?> function
) {}

default List<DiscoveredWorker> discoverWorkers(JsonNode rawWorkerNode) {
    return List.of();
}
```

When `discoverWorkers()` returns non-empty, `CaseDefinitionYamlMapper` creates one `Worker` per entry and adds each `Capability` to the definition (unless a capability with that name is already declared — explicit YAML declarations take precedence over discovered defaults).

Existing providers (A2A, flow) return empty (default method) — zero behavioral change.

### 3. CaseDefinitionYamlMapper integration (engine runtime)

Worker processing loop gains a discovery branch:

1. Find provider via `handles()`
2. Call `provider.discoverWorkers(node)` — if non-empty:
   - For each `DiscoveredWorker`: add capability (if not already declared), create Worker
   - Skip the `create()` path
3. If empty: call `provider.create(node)` — existing single-function path

**Impact scope:**
- `AuthConfig` rename: a2a module only (6-8 files)
- `discoverWorkers()`: engine-api (default method on existing interface) + engine runtime (mapper branch)
- No `ExecutionMetadata` change — eliminated by one-function-per-tool design
- No consumer repo impact

## Core Types

### McpTransport (sealed)

```java
package io.casehub.engine.mcp;

public sealed interface McpTransport {
    record Stdio(List<String> command, Map<String, String> env) implements McpTransport {
        public Stdio {
            Objects.requireNonNull(command);
            if (command.isEmpty()) throw new IllegalArgumentException("command must not be empty");
            env = env != null ? Map.copyOf(env) : Map.of();
        }
    }
    record Http(String url, AuthConfig auth) implements McpTransport {
        public Http {
            Objects.requireNonNull(url);
            auth = auth != null ? auth : AuthConfig.NONE;
        }
    }
}
```

Sealed type eliminates nullable mutual exclusion. Transport type is encoded in the type system — no runtime `transportType()` dispatch needed.

### McpWorkerFunction

```java
package io.casehub.engine.mcp;

@SuppressWarnings("unchecked")
public record McpWorkerFunction(
    McpTransport transport,
    String toolName
) implements WorkerFunction<Map<String, Object>, Map<String, Object>> {

    public McpWorkerFunction {
        Objects.requireNonNull(transport);
        Objects.requireNonNull(toolName);
    }

    @Override public Class<Map<String, Object>> inputType() { return (Class) Map.class; }
    @Override public Class<Map<String, Object>> outputType() { return (Class) Map.class; }
}
```

One function per tool. `toolName` is known at construction time (from discovery) — the handler calls `client.callTool(toolName, input)` directly. No runtime tool selection needed.

### McpClientRegistry

```java
package io.casehub.engine.mcp;

@ApplicationScoped
public class McpClientRegistry {
    private final ConcurrentHashMap<String, McpSyncClient> clients = new ConcurrentHashMap<>();

    public McpSyncClient getOrCreate(McpTransport transport) { ... }
    public void evict(McpTransport transport) { ... }
    void shutdown(@Observes ShutdownEvent event) { ... }
}
```

- One `McpSyncClient` per server, keyed by transport identity:
  - Stdio: `"stdio:" + String.join(" ", command)` — same command = same process
  - HTTP: `"http:" + url` — same URL = same connection pool
- **Thread-safe initialization:** `computeIfAbsent` with a factory that creates the SDK client, selects transport, and runs the MCP initialize/initialized handshake. SDK `McpSyncClient` construction is itself thread-safe.
- **Auth conflict detection (HTTP):** same URL with different auth → `IllegalArgumentException` (same pattern as `A2AClientRegistry`)
- **Env conflict detection (stdio):** same command with different env → `IllegalArgumentException`
- Shutdown: closes all clients (stdio subprocesses terminated, HTTP connections closed)
- Eviction: on transient errors, evict and recreate on next call

### McpWorkerFunctionProvider

```java
package io.casehub.engine.mcp;

@ApplicationScoped
public class McpWorkerFunctionProvider implements WorkerFunctionProvider {

    @Override
    public boolean handles(JsonNode rawWorkerNode) {
        return rawWorkerNode.has("mcp");
    }

    @Override
    public WorkerFunction<?, ?> create(JsonNode rawWorkerNode) {
        throw new UnsupportedOperationException("Use discoverWorkers()");
    }

    @Override
    public List<DiscoveredWorker> discoverWorkers(JsonNode rawWorkerNode) {
        McpTransport transport = parseTransport(rawWorkerNode.get("mcp"));
        List<String> explicitCapabilities = parseCapabilities(rawWorkerNode);
        String baseName = rawWorkerNode.get("name").asText();

        McpSyncClient discoveryClient = createDiscoveryClient(transport);
        try {
            List<Tool> tools = discoveryClient.listTools().tools();
            List<String> toolNames = filterTools(tools, explicitCapabilities);

            return toolNames.stream().map(toolName -> {
                Tool tool = findTool(tools, toolName);
                Capability capability = Capability.builder()
                    .name(toolName)
                    .description(tool.description())
                    .inputSchema(".")
                    .outputSchema(".")
                    .build();
                McpWorkerFunction function = new McpWorkerFunction(transport, toolName);
                String workerName = toolNames.size() == 1
                    ? baseName : baseName + "--" + toolName;
                return new DiscoveredWorker(workerName, capability, function);
            }).toList();
        } finally {
            discoveryClient.close();
        }
    }
}
```

- Discovery uses a **temporary** `McpSyncClient` — separate from the runtime registry. Closed after `listTools()`. Runtime connections are managed by `McpClientRegistry` with proper lifecycle.
- If explicit `capabilities:` in YAML: validates each against discovered tools (fail-fast on mismatch), returns only matching tools
- If no explicit capabilities: returns all discovered tools
- Worker naming: single tool → uses base name; multiple tools → `baseName--toolName`
- Default `Capability` has `inputSchema: "."` / `outputSchema: "."` (passthrough). Explicit YAML capability declarations take precedence in the mapper.

**Cold start resilience:** If the MCP server is unavailable at definition build time, `discoverWorkers()` throws. The definition fails to load with a clear error. This is fail-fast by design — a case definition that can't reach its MCP server cannot execute. Retry is the consumer's responsibility (restart the application after the server is available).

### McpWorkerFunctionHandler

```java
package io.casehub.engine.mcp;

@ApplicationScoped
public class McpWorkerFunctionHandler implements WorkerFunctionHandler {

    private final McpClientRegistry clientRegistry;
    private final ExecutorService virtualThreads;

    @Override
    public boolean supports(WorkerFunction<?, ?> function) {
        return function instanceof McpWorkerFunction;
    }

    @Override
    public HandlerResult execute(
            WorkerFunction<?, ?> function, Object inputData,
            WorkerContext context, int timeoutMs,
            ExecutionMetadata metadata) {
        McpWorkerFunction mcp = (McpWorkerFunction) function;
        McpSyncClient client = clientRegistry.getOrCreate(mcp.transport());

        Future<HandlerResult> future = virtualThreads.submit(
            () -> callTool(client, mcp, inputData));

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            trySendCancellation(client, mcp.toolName());
            return new HandlerResult(
                WorkerResult.expired("MCP tool timed out after " + timeoutMs + "ms"),
                buildMetadata(mcp, timeoutMs));
        } catch (ExecutionException e) { ... }
    }
}
```

- **Tool name from function:** `mcp.toolName()` — no metadata routing needed
- **Timeout:** `Future.get(timeoutMs)` — session initialization time is included in the timeout budget (first call to a new server pays the handshake cost)
- **Cancellation on timeout:** sends MCP `notifications/cancelled` to the server (fire-and-forget, same pattern as A2A's `tasks/cancel`). Allows the server to clean up in-progress work.
- **Subprocess crash (stdio):** `IOException` from dead process → evict client → exception propagates → `QuartzRetryService` retries → `getOrCreate()` starts fresh subprocess

## YAML Schema

```yaml
# Auto-discover all tools — one Worker per tool created
workers:
  - name: file-tools
    mcp:
      command: ["/path/to/mcp-server"]

# Explicit capability filter — only these tools from the server
workers:
  - name: file-tools
    capabilities: [read-file, write-file]
    mcp:
      command: ["/path/to/mcp-server"]

# HTTP transport with auth
workers:
  - name: remote-tools
    mcp:
      url: https://mcp-server.example.com/mcp
      timeout: 30000                      # optional, per-call timeout ms
      auth:
        type: bearer
        tokenConfigKey: mcp.server.token

# Stdio with environment variables
workers:
  - name: db-tools
    mcp:
      command: ["/usr/local/bin/db-mcp-server"]
      env:
        DATABASE_URL: ${database.url}

# Override discovered capability with explicit inputSchema
capabilities:
  - name: read-file
    description: "Read a file with path projection"
    inputSchema: "{ path: .filePath }"
    outputSchema: "."
```

**Transport detection:** `command` field → Stdio. `url` field → Http. Both present → parse error. Neither → parse error.

**Capability precedence:** Explicit YAML `capabilities:` section declarations override discovered defaults. The discovered `Capability` (with `.` passthrough schemas) is only used when no explicit declaration exists for that tool name.

## Execution Model

1. Definition loads: provider connects to MCP server, discovers tools `[read-file, write-file, list-files]`
2. Provider creates 3 workers: `file-tools--read-file`, `file-tools--write-file`, `file-tools--list-files`
3. Each worker has one `McpWorkerFunction(Stdio(["/path/to/server"], {}), "read-file")` etc.
4. Binding fires for capability `read-file` → worker `file-tools--read-file` matches
5. Handler calls `client.callTool("read-file", inputData)` — tool name from function record
6. Converts `CallToolResult` → `Map<String, Object>` → `HandlerResult`

## Output Mapping

MCP `CallToolResult` contains content parts and an `isError` flag.

**`isError = true`:** All text content concatenated as the failure message → `WorkerResult.failed(message)`.

**`isError = false`:** Content parts processed in order:

| Content type | Mapping |
|-------------|---------|
| Text (single) | Attempt JSON parse → use as output map. Non-JSON → `{"text": "..."}` |
| Text (multiple) | Concatenate all text, then JSON parse. Non-JSON → `{"text": concatenated}` |
| Image | Added as `{"image_N": {"data": base64, "mimeType": "..."}}` (N = 0-based index among image parts) |
| Resource | Added as `{"resource_N": {"uri": "...", "mimeType": "..."}}` (N = 0-based index among resource parts) |
| Mixed text + binary | Text parsed as JSON map, binary parts merged with numbered keys |

## Error Handling

Three categories with correct routing (same model as A2A):

| Category | Examples | Routing |
|----------|---------|---------|
| Transient | Connection refused, subprocess crash, IOException, session init failure | Exception → `QuartzRetryService` retry with backoff |
| Protocol | Invalid JSON-RPC response, MCP protocol error | `WorkerResult.failed()` → `OutcomePolicy` |
| Tool error | MCP `isError=true` response | `WorkerResult.failed()` → `OutcomePolicy` |

**Unknown tool at runtime:** If a tool discovered at build time no longer exists at runtime (server restarted with different tools), `callTool()` returns an MCP error → protocol error → `WorkerResult.failed()` → `OutcomePolicy`. This is a known limitation of build-time discovery. The alternative (runtime re-discovery before each call) adds latency without proportional benefit — tool sets rarely change between server restarts.

**Subprocess crash recovery (stdio):** IOException from dead subprocess → handler evicts client from registry → exception propagates → `QuartzRetryService` retries → `getOrCreate()` starts fresh subprocess with new session.

## Protocol Metadata

Populated in `HandlerResult.protocolMetadata()`, merged into EventLog:

| Key | Value |
|-----|-------|
| `mcpServer` | Command string or URL |
| `mcpTool` | Tool name invoked |
| `mcpTransport` | `stdio` or `http` |
| `mcpDuration` | Call duration in ms |

## Session Lifecycle

MCP requires an initialize/initialized handshake before tool calls. Managed by `McpClientRegistry`:

- **Lazy initialization:** First `getOrCreate()` creates the SDK client, selects transport, runs the handshake. Session is cached for the client's lifetime.
- **Timeout budget:** Session initialization time counts toward the handler's `timeoutMs`. First call to a new server is slower (handshake + tool call). Subsequent calls reuse the cached session.
- **Capability negotiation:** Initialize request declares `tools` capability only (no resources, prompts, sampling in v1).
- **Session persistence:** Stdio sessions persist as long as the subprocess lives. HTTP sessions persist per server's session management.

## Testing Strategy

**Unit tests:**
- `McpWorkerFunctionProviderTest` — `handles()` detection, transport parsing, discovery creates workers per tool, explicit capabilities filter and validate, cold start failure
- `McpClientRegistryTest` — lazy creation, same-server deduplication, auth/env conflict detection, eviction, shutdown, thread-safe initialization
- `McpWorkerFunctionHandlerTest` — tool call via function's `toolName`, output mapping (text, JSON, multi-part, image, isError), timeout with cancellation, transient error propagation, protocol metadata

**Integration test (`@QuarkusTest`):**
- Full engine stack with `casehub-persistence-memory`
- HTTP transport with MockWebServer (MCP over HTTP is JSON-RPC)
- End-to-end: case start → binding fires → handler calls MCP tool → output in context → goal evaluates

**Stdio transport test:**
- Unit test with a test MCP server script (simple Python or Java subprocess)
- Verifies subprocess lifecycle: start, tool call, crash recovery, shutdown

**Cross-cutting tests:**
- `AuthConfig` rename — all A2A tests pass with extracted type
- `discoverWorkers()` — mapper integrates discovered workers and capabilities

## Future Work

- **#841 WorkerDiscoveryProvider SPI** — dynamic worker contribution at routing time
- **#844 MCP discovery federation** — discover and index tools from configured MCP server registries
- MCP resources and prompts (not tools) — v1 is tools-only
- MCP sampling support — server-initiated LLM calls
- Runtime re-discovery — periodic `tools/list` to detect tool set changes
