# A2A Outbound Worker Provisioning — Design Spec

**Issue:** engine#830
**Branch:** issue-830-a2a-mcp-integration
**Date:** 2026-08-01

## Summary

Invoke remote A2A agents as casehub workers. A new `casehub-engine-a2a` module provides a `WorkerFunction`/`WorkerFunctionHandler` implementation that sends tasks to remote A2A-compliant agents over HTTP, with support for both synchronous and streaming execution.

## Architecture Decision

**WorkerFunction/Handler, not WorkerProvisioner.** The `WorkerProvisioner` SPI is for async external agents that deliver results via Qhorus channels — provision registers the agent, work dispatches separately, results arrive asynchronously. A2A is synchronous request/response (with optional SSE streaming), which maps naturally to the `WorkerFunctionHandler` model: block on a virtual thread, call the remote agent, return `WorkerResult`. This gives us timeout enforcement, retry via `QuartzRetryService`, proper `WorkerResult` outcomes, and full EventLog provenance for free.

Reference: garden entry GE-20260618-fe7c8e documents the distinction between inline WorkerFunction execution and external WorkerProvisioner provisioning.

**Relationship to SWF `call: a2a`.** The Serverless Workflow SDK (`casehub-engine-flow`) already supports A2A invocation via `CallA2A` with `A2AArguments` (methods: `message/send`, `message/stream`). That path is for workflow-step-level A2A calls within SWF definitions. This spec introduces a complementary path at the worker/capability-binding level — A2A agents participate in case execution via the same `WorkerFunction`/`WorkerFunctionHandler` pipeline as local workers, gaining routing, retry, outcome policy, and EventLog integration. The two paths share no runtime infrastructure in v1; shared client infrastructure (connection pooling, auth) is a follow-on concern tracked in engine#835.

## Module Structure

**Module:** `casehub-engine-a2a` (artifact ID) — directory `a2a/` at engine root, sibling to `flow/`. Short directory name per maven-submodule-folder-naming protocol. Jandex plugin required (library-jars-require-jandex protocol) — module ships `@ApplicationScoped` CDI beans.

**Compile dependencies:**
- `casehub-engine-common` — `WorkerFunctionHandler` SPI, `HandlerResult`
- `casehub-engine-api` — `WorkerFunctionProvider`
- `casehub-worker-api` — `WorkerFunction`, `WorkerResult`
- `java.net.http.HttpClient` (JDK) — A2A JSON-RPC over HTTP, SSE streaming via `HttpResponse.BodyHandlers.ofLines()`
- `quarkus-arc` — CDI

**Test dependencies:**
- `casehub-engine` (runtime), `casehub-engine-scheduler-quartz`, `casehub-persistence-memory` — full integration test stack (same pattern as flow module)

**Not depending on:** `runtime` at compile scope (peer module), `casehub-eidos-api` (no eidos coupling in v1).

**Activation:** Consumer adds `casehub-engine-a2a` to their classpath. CDI discovers the handler and provider automatically.

## Core Types

### `A2AAuthConfig`

```java
package io.casehub.engine.a2a;

public record A2AAuthConfig(AuthType type, String tokenConfigKey) {

    public enum AuthType { NONE, BEARER, API_KEY }

    public static final A2AAuthConfig NONE = new A2AAuthConfig(AuthType.NONE, null);
}
```

Carries per-endpoint authentication configuration from YAML parsing through to client creation. `tokenConfigKey` references a Quarkus config property — secrets stay in environment variables or config sources, never in YAML.

### `A2AWorkerFunction`

```java
package io.casehub.engine.a2a;

@SuppressWarnings("unchecked")
public record A2AWorkerFunction(
    String endpoint,
    String skill,
    boolean streaming,
    A2AAuthConfig auth
) implements WorkerFunction<Map<String, Object>, Map<String, Object>> {

    @Override public Class<Map<String, Object>> inputType() { return (Class) Map.class; }
    @Override public Class<Map<String, Object>> outputType() { return (Class) Map.class; }
}
```

- `endpoint` — remote A2A server base URL
- `skill` — optional target skill on the remote agent (null = default). Mapped to the A2A message metadata as a skill selector — the remote agent uses this to route to the correct skill handler. If null, the remote agent uses its default skill.
- `streaming` — `true` uses `message/stream` (SSE), `false` uses `message/send`
- `auth` — per-endpoint auth configuration (default `A2AAuthConfig.NONE`)
- Input/output typed as `Map<String, Object>` — A2A is schema-less at the transport level. The engine's existing JQ input/output projection pipeline handles mapping. Generic signature matches `FlowWorkerFunction` and `AgentWorkerFunction` conventions.
- No A2A transport types leak into the record — just endpoint config.

### `A2AClientRegistry`

```java
package io.casehub.engine.a2a;

@ApplicationScoped
public class A2AClientRegistry {
    private final ConcurrentHashMap<String, A2AClient> clients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, A2AAuthConfig> authConfigs = new ConcurrentHashMap<>();

    public A2AClient getOrCreate(String endpoint, A2AAuthConfig auth) { ... }

    void shutdown(@Observes ShutdownEvent event) { ... }
}
```

- One A2A client per endpoint URL, lazily created, thread-safe
- Multiple workers targeting the same remote server share the client (connection reuse)
- **Auth conflict detection:** if `getOrCreate` is called with a different `A2AAuthConfig` for an endpoint that already has a client, throws `IllegalArgumentException`. Same endpoint must use the same auth — this is a configuration error, not a runtime ambiguity. Detected on first handler execution, not at definition build time, because client creation is lazy.
- **Shutdown coordination:** The registry maintains an `AtomicBoolean shutdownRequested` flag. On `@Observes ShutdownEvent`: (1) sets the flag to `true`, (2) interrupts all virtual threads with active A2A calls (tracked via a `Set<Thread>` that handlers register/deregister on entry/exit), (3) waits up to 5 seconds for active handlers to return, (4) closes all HTTP clients. Handlers check `shutdownRequested` in their event loop and on timeout, returning `WorkerResult.expired("Application shutting down")` — this routes through `OutcomePolicy.onExpired()`, which is appropriate because the Quartz trigger should not be rescheduled during shutdown. The `QuartzRetryService` will not schedule retries because the scheduler is also shutting down.
- **Per-request token resolution:** Auth tokens are resolved from Quarkus config properties (`ConfigProvider.getConfig().getValue(key, String.class)`) on **every request**, not at client creation time. The `A2AClient` stores the `A2AAuthConfig` (which contains the config key name, not the token value) and resolves the token fresh before each HTTP call. This handles token rotation, OAuth expiry, and external secret rotation without requiring client eviction or restart.
- **401 eviction:** On HTTP 401 responses, the handler evicts the cached client from the registry and propagates the error as an exception (see §Error Handling). The next retry creates a fresh client, which re-resolves credentials.

### `A2AWorkerFunctionHandler`

```java
package io.casehub.engine.a2a;

@ApplicationScoped
public class A2AWorkerFunctionHandler implements WorkerFunctionHandler {

    private final A2AClientRegistry clientRegistry;
    private final ExecutorService virtualThreads;

    @Inject
    public A2AWorkerFunctionHandler(
            A2AClientRegistry clientRegistry,
            @VirtualThreads ExecutorService virtualThreads) {
        this.clientRegistry = clientRegistry;
        this.virtualThreads = virtualThreads;
    }

    @Override
    public boolean supports(WorkerFunction<?, ?> function) {
        return function instanceof A2AWorkerFunction;
    }

    @Override
    public HandlerResult execute(
            WorkerFunction<?, ?> function, Object inputData,
            WorkerContext context, int timeoutMs,
            ExecutionMetadata metadata) { ... }
}
```

- Runs on `@VirtualThreads ExecutorService` (same as `SyncAgentWorkerFunctionHandler`)
- Timeout enforcement via `Future.get(timeoutMs, MILLISECONDS)` using the `timeoutMs` parameter
- Delegates to sync or streaming path based on `A2AWorkerFunction.streaming()`
- Returns `HandlerResult` with A2A protocol metadata for EventLog enrichment (see §Protocol Metadata Pipeline)

### `A2AWorkerFunctionProvider`

```java
package io.casehub.engine.a2a;

@ApplicationScoped
public class A2AWorkerFunctionProvider implements WorkerFunctionProvider {

    @Override
    public boolean handles(JsonNode rawWorkerNode) {
        return rawWorkerNode.has("a2a");
    }

    @Override
    public WorkerFunction<?, ?> create(JsonNode rawWorkerNode) { ... }
}
```

- Detects `a2a:` block in raw worker JSON node
- Parses `endpoint`, `skill`, `streaming`, `auth` fields
- Returns `A2AWorkerFunction`

## YAML Schema

```yaml
capabilities:
  - name: data-analysis
    description: "Analyse financial data for anomalies"
    inputSchema: ".transaction"

workers:
  - name: remote-analyst
    capabilities: [data-analysis]
    a2a:
      endpoint: https://analyst-agent.example.com
      skill: anomaly-detection       # optional, targets specific A2A skill
      streaming: true                # optional, default false
      auth:                          # optional, default none
        type: bearer                 # bearer | api-key | none
        tokenConfigKey: analyst.token  # Quarkus config key
```

Multiple workers can target the same endpoint with different skills — they share the same `A2AClient` via the registry (keyed by endpoint URL).

## Input/Output Mapping

No special handling. The engine's existing pipeline covers it:

1. `inputSchema` (JQ on working layer) → produces input `Map`
2. `A2AWorkerFunctionHandler` wraps the `Map` as A2A task content (JSON message part)
3. A2A response artifacts are unwrapped back to a `Map`
4. `outputProjection` (if declared on the capability) applies on the way back
5. `ConflictResolver` merges into the case context

The A2A module does not touch JQ evaluation or context projection — those are cross-cutting concerns owned by `DefaultWorkerExecutor` and the handler pipeline.

## Execution Model

### Synchronous (`streaming: false`, default)

1. Handler builds A2A message from input data (JSON text part)
2. Calls `message/send` via the A2A client — blocks virtual thread until complete
3. Extracts task ID from response for protocol metadata
4. Returns `HandlerResult` with `WorkerResult` and protocol metadata

### Streaming (`streaming: true`)

1. Handler builds A2A message from input data (JSON text part)
2. Calls `message/stream` via the A2A client — returns SSE event stream
3. Virtual thread blocks, processing events as they arrive:
   - `TaskStatusUpdateEvent` → logged to EventLog as metadata entries (audit trail of remote agent's status transitions: WORKING → COMPLETED)
   - `TaskArtifactUpdateEvent` → accumulated into the result using the artifact's `index` field as a key. If the event's `append` flag is true, content is appended to the existing artifact at that index; otherwise the artifact is replaced. This mirrors the A2A protocol's artifact update semantics.
   - Events arriving after a terminal status event are ignored.
4. On terminal state (COMPLETED/FAILED/CANCELED/INPUT_REQUIRED) → returns `HandlerResult` with accumulated result and protocol metadata
5. Final output map is built from all accumulated artifacts at the terminal state.

**Artifact bounds:** Accumulated artifacts are bounded to prevent unbounded memory growth during long-running SSE streams:
- Maximum artifact count: configurable via `casehub.a2a.max-artifacts` (default: 100)
- Maximum total artifact size: configurable via `casehub.a2a.max-artifact-bytes` (default: 10MB)
- On overflow: handler closes the SSE connection and returns `WorkerResult.failed("Artifact limit exceeded: ...")` with any partial output accumulated before the limit was reached. This routes through `OutcomePolicy` as a semantic failure, allowing reroute to a different agent if configured.

### Outcome Mapping

| A2A state | WorkerResult |
|-----------|-------------|
| COMPLETED | `WorkerResult.completed(outputMap)` |
| FAILED | `WorkerResult.failed(failureMessage)` |
| CANCELED | `WorkerResult.failed("Remote agent cancelled task")` |
| INPUT_REQUIRED | `WorkerResult.failed("Remote agent requires additional input — not supported")` |
| Timeout | `WorkerResult.expired("Remote A2A task timed out after " + timeoutMs + "ms")` |

**INPUT_REQUIRED rationale:** The engine's worker execution model is non-interactive — send input, receive result. Multi-turn interactive A2A tasks require fundamentally different plumbing (closer to the qhorus channel model). V1 treats INPUT_REQUIRED as a terminal failure. Interactive A2A task support is tracked as future work (engine#845).

## Timeout and Cancellation

Both sync and streaming paths run inside `Future.get(timeoutMs, MILLISECONDS)`. On `TimeoutException`, the handler must clean up the in-flight operation before returning `WorkerResult.expired()`:

1. **Cancel the Future:** `future.cancel(true)` sets the interrupt flag on the virtual thread running the HTTP call or SSE event loop.
2. **Close the HTTP connection:** The A2A client's HTTP request is made with `HttpClient`, which respects thread interruption — `HttpClient.send()` throws `InterruptedException` when the virtual thread is interrupted, which closes the underlying connection. For SSE streaming, the `Stream<String>` from `BodyHandlers.ofLines()` is closed explicitly via a `try-with-resources` or `finally` block inside the streaming loop.
3. **Return cleanly:** After cancellation, the handler returns `WorkerResult.expired()` with a descriptive message including the `timeoutMs` value.

**Streaming-specific deadline enforcement:** The streaming event loop checks `Instant.now().isAfter(deadline)` after processing each SSE event, in addition to the outer `Future.get()` timeout. This provides two layers of protection:
- The outer `Future.get()` catches the case where the event loop is blocked waiting for the next SSE event
- The inner deadline check catches the case where events arrive continuously (e.g., periodic WORKING updates) but never reach a terminal state

This mirrors the qhorus inbound A2A SSE implementation (`A2AResource.streamTask()`), which uses `maxDurationSeconds` as a deadline with `queue.poll(Math.min(heartbeatMs, remaining))`.

**Leak prevention:** Each retry by `QuartzRetryService` creates a new `Future` and HTTP connection. Without cancellation, N retries against a slow endpoint would accumulate N leaked virtual threads and N open HTTP connections. The cancellation ensures that at most one connection per worker execution is active at any time.

**Best-effort remote cancellation (streaming only):** When a streaming call times out and the handler has received a task ID from a prior SSE event, the handler sends a best-effort `tasks/cancel` JSON-RPC request to the remote agent before returning `WorkerResult.expired()`. This is fire-and-forget — the handler does not wait for the cancellation response. If the request fails (network error, agent doesn't support `tasks/cancel`), the failure is logged at DEBUG level but does not affect the timeout result. This prevents zombie tasks on remote agents that would otherwise continue executing indefinitely after CaseHub abandons the request.

For synchronous calls (`message/send`), remote cancellation is not possible — the task ID is only available in the response, which the handler is waiting for when the timeout fires. Remote agents handling synchronous requests should implement their own server-side timeouts.

## Protocol Metadata Pipeline

The A2A handler produces protocol-specific metadata alongside its `WorkerResult`. This metadata enriches the EventLog entry for observability and cross-system correlation, but the current handler contract (`WorkerFunctionHandler.execute()` returns `WorkerResult<?>`) has no metadata channel. This spec introduces `HandlerResult` to bridge the gap.

### `HandlerResult` (new type in engine-common)

```java
package io.casehub.engine.common.internal.executor;

public record HandlerResult(WorkerResult<?> result, Map<String, Object> protocolMetadata) {
    public HandlerResult(WorkerResult<?> result) {
        this(result, Map.of());
    }
}
```

### Metadata threading chain

1. **Handler** → `A2AWorkerFunctionHandler.execute()` returns `HandlerResult(workerResult, a2aMetadata)`
2. **Executor** → `DefaultWorkerExecutor.execute()` applies output schema to `result`, preserves `protocolMetadata`
3. **Scheduler** → `QuartzWorkerExecutionJob` extracts both result and metadata, threads metadata into `WorkflowExecutionCompleted`
4. **Completion handler** → `WorkflowExecutionCompletedHandler.buildMetadata()` merges protocol metadata into the EventLog `metadata` JsonNode

### Required changes

| Type | Module | Change |
|------|--------|--------|
| `HandlerResult` | engine-common | New record in `io.casehub.engine.common.internal.executor` |
| `WorkerFunctionHandler.execute()` | engine-common | Return type `WorkerResult<?>` → `HandlerResult` |
| `WorkerExecutor.execute()` | engine-common | Return type `WorkerResult<?>` → `HandlerResult` |
| `WorkflowExecutionCompleted` | engine-common | Add `Map<String, Object> protocolMetadata` field |
| `DefaultWorkerExecutor` | runtime | Extract `result` for output schema, pass `protocolMetadata` through |
| `WorkflowExecutionCompletedHandler` | runtime | Merge protocol metadata into EventLog metadata |
| `SyncAgentWorkerFunctionHandler` | runtime | Return `new HandlerResult(workerResult)` (empty metadata) |
| `QuartzWorkerExecutionJob` | scheduler-quartz | Accept `HandlerResult` from `workerExecutor.execute()`, extract `WorkerResult` for bridge post-processing, thread `protocolMetadata` into `WorkflowExecutionCompleted` |
| `FlowWorkerFunctionHandler` | flow | Return `new HandlerResult(workerResult)` (empty metadata) |

**Design rationale:** `WorkerResult` is a public API type in `casehub-worker-api` — user-written worker functions return it. Adding metadata here would pollute the API with engine internals (the same principle that motivated `ExecutionMetadata` being separate from `WorkerContext`). `HandlerResult` keeps protocol metadata in the engine-internal layer where it belongs.

## EventLog Provenance

The handler populates `HandlerResult.protocolMetadata` with A2A-specific fields:

| Key | Value |
|-----|-------|
| `a2aEndpoint` | Remote server URL |
| `a2aSkill` | Targeted skill (nullable) |
| `a2aTaskId` | Remote task ID (cross-system correlation). Assigned by the remote A2A server and returned in the task response (sync) or first SSE event (streaming). |
| `a2aMessageId` | Deterministic message ID sent with the A2A request (see §Idempotency). |
| `a2aStreaming` | Whether streaming was used |
| `a2aStatusTransitions` | List of status changes observed (streaming only) |

### Idempotency

The handler generates a deterministic `messageId` for each A2A request, derived from the `inputDataHash` (the idempotency key stable across retries for the same logical work unit). Format: `casehub:{caseId}:{bindingName}:{inputDataHash}`.

This messageId is sent as the A2A message's `messageId` field. A well-behaved remote agent can use it to deduplicate requests — if the same messageId arrives twice, the agent returns the result of the first execution rather than re-executing.

**Limitation:** Idempotency depends on the remote agent honouring the messageId contract. CaseHub cannot enforce this. The messageId provides a best-effort deduplication signal. A complementary `tasks/get` pre-check before retrying (to detect if the prior task already completed successfully) is deferred to engine#846.

## Authentication

Per-endpoint auth configured in the `a2a:` YAML block and carried by `A2AAuthConfig`:

| Type | Behaviour |
|------|-----------|
| `none` (default) | No auth header |
| `bearer` | `Authorization: Bearer <token>` — token resolved from Quarkus config key at runtime |
| `api-key` | `X-API-Key: <key>` — key resolved from Quarkus config key at runtime |

**Auth flow:** `A2AWorkerFunctionProvider` parses the `auth:` block and stores it in `A2AAuthConfig` → `A2AWorkerFunction` carries the config as a record field → `A2AWorkerFunctionHandler` passes the config to `A2AClientRegistry.getOrCreate(endpoint, auth)` → the registry creates the HTTP client (connection pool, TLS) and stores the `A2AAuthConfig` alongside it.

**Per-request token resolution:** The `A2AClient` resolves the token from the Quarkus config key (`ConfigProvider.getConfig().getValue(key, String.class)`) on **every HTTP request**, not at client creation time. The `A2AAuthConfig` stores the config key name (e.g., `analyst.token`), not the token value. This means:
- Token rotation is picked up immediately on the next request
- OAuth token refresh in Quarkus config sources (e.g., via `quarkus-oidc-client`) propagates without restart
- External secret rotation (Vault, AWS Secrets Manager) is reflected as soon as the config source refreshes

**401 recovery:** On HTTP 401, the handler evicts the cached client and propagates the error as an exception → `QuartzRetryService` retries → fresh client created with re-resolved credentials. See §Error Handling.

## Error Handling

The engine has two distinct failure paths, and routing to the wrong one produces incorrect recovery behaviour:

- **Exception path (QuartzRetryService):** When the handler throws an exception, `QuartzWorkerExecutionJob.execute()` catches it in the outer try-catch → `onFailure()` → `QuartzRetryService.handleFailure()` → evaluates `RetryPolicy` → reschedules the **same worker** with exponential backoff. This is the correct path for transient failures.

- **Semantic failure path (OutcomePolicy):** When the handler returns `WorkerResult.failed()` / `.declined()` / `.expired()`, the Quartz job calls `onSuccess()` → publishes `WORKER_EXECUTION_FINISHED` → `WorkflowExecutionCompletedHandler.handleSemanticFailure()` → evaluates `OutcomePolicy` → REROUTE (try a **different agent**) or FAULT (case fails). This is the correct path for semantic failures where the agent tried and couldn't do the job.

Three error categories with correct routing:

1. **Transient failures** (connection refused, DNS timeout, HTTP 5xx, HTTP 401/403, HTTP 429, `IOException`, `ConnectException`): the handler **lets these exceptions propagate uncaught**. The outer try-catch in `QuartzWorkerExecutionJob` routes them to `QuartzRetryService` for retry with backoff against the **same endpoint**. These are conditions where the same endpoint may succeed on a subsequent attempt.

2. **A2A protocol errors** (invalid agent card, malformed JSON-RPC response, unknown skill, HTTP 4xx other than 401/403/429): caught and wrapped as `WorkerResult.failed(message)` with descriptive reason. Routes through `OutcomePolicy` — these are **not retryable** because they indicate configuration errors or permanent incompatibility. Retrying the same endpoint would produce the same error.

3. **Remote agent failures** (A2A task reaches FAILED or CANCELED state): mapped to `WorkerResult.failed(task.status.message)`. Routes through `OutcomePolicy` — the remote agent explicitly reported inability to complete the task, so rerouting to a different agent (if configured) is the correct strategy.

**HTTP 401/403 handling:** Authentication failures are treated as transient (propagated as exceptions). On HTTP 401, the `A2AClientRegistry` evicts the cached client for the endpoint, forcing recreation with fresh credentials on the next retry. HTTP 403 is treated identically — authorization failures may resolve after token refresh or permission propagation. See §Authentication.

**HTTP 429 handling:** Rate-limiting responses are treated as transient (propagated as exceptions). `QuartzRetryService` retries with backoff against the same endpoint — rerouting to a different agent via OutcomePolicy would be counterproductive, as the rate limit applies to the endpoint regardless of which worker binding targets it. **Limitation:** The `Retry-After` header is not honoured in v1; retry timing follows the worker's `RetryPolicy` schedule. If field experience shows this produces excessive rejected requests against rate-limited endpoints, honouring `Retry-After` can be added to `QuartzRetryService` as a cross-cutting enhancement.

## Testing Strategy

**Unit tests:**
- `A2AWorkerFunctionProviderTest` — `handles()` detects `a2a:` blocks, `create()` produces correct records
- `A2AClientRegistryTest` — lazy creation, same-endpoint deduplication, shutdown cleanup
- `A2AWorkerFunctionHandlerTest` — mock A2A client, verify sync/streaming paths, outcome mapping, protocol metadata population, artifact bounds enforcement

**Integration tests** (`@QuarkusTest`):
- Full engine stack with `casehub-persistence-memory`
- `CaseHub` subclass with `a2a:` worker binding
- Mock A2A server (embedded HTTP server) — no external network dependency
- End-to-end: case start → binding fires → A2A handler executes → output in case context → goal evaluates
- Retry: mock returns HTTP 503, confirm `QuartzRetryService` reschedules
- Protocol metadata: verify EventLog entries contain A2A-specific metadata after `HandlerResult` threading

## Eidos Integration (v1 scope)

**No eidos dependency in v1.** A2A workers participate via exact-match routing, consistent with any worker without an `AgentDescriptor`. This is the same behaviour as workers declared in YAML without an `agent:` block.

## Future Work (engine#835)

Follow-on integration tracked in epic engine#835:

### Near-term
- **#836 AgentCard→AgentDescriptor bridge** — map AgentCards to eidos AgentDescriptor for subsumption matching and personality routing
- **#837 A2A health probing** — `CapabilityHealth` implementation calling remote health endpoints
- **#839 A2A vocabulary grounding** — ground A2A capability strings to vocabulary terms for subsumption matching
- **#840 AgentCard validation at build time** — fetch and validate AgentCard during CaseDefinition build
- **#845 Interactive A2A tasks (INPUT_REQUIRED)** — support multi-turn interactive A2A tasks where the remote agent requests additional input. Requires fundamentally different plumbing than the current fire-and-forget worker model — likely bridges to the qhorus channel model for conversation management.

### Semantic runtime worker discovery
- **#841 WorkerDiscoveryProvider SPI** — dynamic worker contribution at routing time (goal in, matching workers out)
- **#842 Semantic worker index** — embedding-based index over thousands of registered workers
- **#843 A2A discovery federation** — discover and index AgentCards from remote registries
- **#844 MCP discovery federation** — discover and index tools from configured MCP servers
