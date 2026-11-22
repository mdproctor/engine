# Brief Notes — Multi-Transport Worker Dispatch

> Source repo: workers (19 modules, 134 Java source files)
> Audit date: 2026-10-02

## Capability Areas (descriptive names)

### 1. Worker Dispatch Infrastructure
Pluggable multi-transport worker execution framework. Each transport (HTTP, Camel, MCP, GitHub Actions, Script, K8s, Scenario) implements the same `WorkerRuntime` SPI and `WorkerExecutionManager` contract. Auto-discovered via CDI — classpath presence activates each backend with zero configuration required.
- **Differentiation:** No competitor offers 7 transport backends behind a single dispatch SPI. LangChain/CrewAI have HTTP tool calling; CaseHub has HTTP + Camel (300+ connectors) + MCP (Streamable HTTP with session management) + Kubernetes Jobs + GitHub Actions + local scripts + scenario automation — all with the same fault handling, retry, and correlation model. Transport is a deployment choice, not a code change.
- **Extent:** 19 modules. Two-layer architecture per transport: framework-neutral `-core` module (zero CDI, JDK types) and framework-specific wiring (Quarkus CDI or Spring auto-configuration). Consistent three-class pattern: Runtime, Resolver, ExecutionManager. `workers-spring` provides consolidated Spring Boot auto-configuration for all transports.

### 2. Unified Fault Pipeline
Centralized fault handling across all 7 transports. Classification (permanent vs retryable), configurable retry with 3 backoff strategies (FIXED, EXPONENTIAL, EXPONENTIAL_WITH_JITTER), `Retry-After` header parsing, and exhaustion signalling.
- **Differentiation:** Transport-specific fault classification (HTTP 429 → RetryAfter, K8s OOMKilled → Permanent, MCP session expiry → retryable with invalidation, GitHub Actions 422 on workflow-dispatch → RetryAfter(60s) due to trigger caching) all converges into one pipeline. Most platforms retry blindly or not at all. CaseHub classifies faults by transport semantics and delegates retry ownership to its own pipeline, never to the transport (K8s backoffLimit=0, restartPolicy=Never).
- **Extent:** `WorkerFaultPublisher` → `WorkerFaultHandler` → `WorkerRetrySupport`. `PermanentFaultException` and `RetryAfterException` as fault signals. Virtual-thread execution via fire-and-forget `Consumer<WorkerFaultEvent>`.

### 3. Async Completion & Callback
In-memory `AsyncWorkerCompletionRegistry` with TTL-based expiry for long-running dispatch patterns. External systems call back via `WorkerCallbackResource` REST endpoint with constant-time token validation.
- **Differentiation:** Not just fire-and-forget — tracked pending completions with TTL expiry, callback token security (`MessageDigest.isEqual` for timing-attack resistance), re-registration on token mismatch (preserves completion for legitimate retry), and automatic fault escalation on expiry. K8s module adds restart recovery from Job labels — surviving application restarts without losing dispatch state.
- **Extent:** Used by HTTP (async mode), Camel (InOnly), K8s (all dispatches), and Scenario. `CompletionExpiredEvent` and `FaultCallbackEvent` CDI async events bridge to the fault pipeline.

### 4. Multi-Tier Capability Resolution
Three-tier endpoint resolution shared across HTTP, MCP, and Scenario: SPI beans (programmatic, highest priority) → config properties → `EndpointRegistry` (tenant-aware runtime resolution). Camel adds convention auto-discovery from `CamelContext` routes.
- **Differentiation:** Tenant-aware resolution throughout — every `resolve()` and `canResolve()` call carries `tenancyId`. EndpointRegistry handles tenant → platform-global fallback internally. This means the same capability tag can resolve to different endpoints per tenant. No other worker framework has built-in multi-tenancy in its dispatch routing.
- **Extent:** `WorkerCapabilityResolver<T>` SPI with transport-specific implementations. Path conventions per transport type. `capabilities()` stays static (SPI + config); EndpointRegistry provides runtime-resolved capabilities.

### 5. Kubernetes Job Lifecycle
Full lifecycle management for K8s Job-based dispatch: two construction modes (image-based, template-based), watch-based completion via `SharedIndexInformer`, restart recovery, input size validation (etcd limits), Pod log capture.
- **Differentiation:** Goes far beyond "create a Job and wait". Template overlay mode enables reusing existing Kubernetes Job templates while injecting CaseHub-managed fields. Restart recovery reconstructs dispatch state from Job labels after application restart — no external database needed. Informer-based completion avoids polling. Enforces `restartPolicy: Never` and `backoffLimit: 0` — CaseHub's fault pipeline owns retry logic, preventing K8s from fighting with the application over retry decisions.
- **Extent:** 9 classes. `K8sJobBuilder` (two modes), `K8sJobInformerManager` (per-namespace informers, terminal/delete handling, restart recovery), `K8sJobOutputCapture` (Pod log capture with bounded output). Fault classification enriched from Pod-level reasons (OOMKilled, ImagePullBackOff, Eviction).

### 6. MCP Protocol Integration
Model Context Protocol dispatch with Streamable HTTP transport, session management, `tools/list` discovery, and dual response parsing (JSON + SSE).
- **Differentiation:** Full MCP 2025-06-18 protocol compliance (no backwards compat with 2024-11-05). Session lifecycle with concurrent dedup, session invalidation on 404, and allowlist-filtered tool discovery. SSE multi-event parsing finds the correct JSON-RPC response by request ID. `structuredContent` extraction preferred over `content`. This is production-grade MCP, not a demo client.
- **Extent:** 8 classes. Session init ordering is critical (`onFailure` before `memoize` to avoid caching failed sessions). Parallel server initialization via `Uni.join().all()`.

### 7. Scenario Automation Dispatch
Bridge to casehub-pages' GraphQL-based scenario engine for browser automation (ARIA), GraphQL mutations, and REST interactions. Named script resolution from pages library or raw YAML.
- **Differentiation:** Unique to CaseHub — a worker transport that triggers multi-step browser and API automation scenarios. Named scripts are managed in the pages library; ad-hoc YAML enables dynamic automation. Completes via callback, integrating with the standard async completion registry.
- **Extent:** 2 core classes. 3-tier endpoint resolution (config + EndpointRegistry, no SPI tier). GraphQL mutation dispatch via Vert.x WebClient.

## Cross-Repo Patterns

- **worker SPI contract:** `workers` implements transport backends; `casehub-worker` defines worker identity and capability vocabulary; `casehub-engine` orchestrates dispatch timing and routing through `CompositeWorkerExecutionManager`
- **Dual-framework deployment:** `-core` modules are framework-neutral (JDK only); `workers-spring` provides Spring Boot auto-configuration; Quarkus modules provide CDI wiring. Same logic, two deployment targets
- **EndpointRegistry integration:** `casehub-platform-api` provides tenant-aware endpoint resolution as Tier 3 for HTTP, MCP, and Scenario backends
- **Callback completion:** Workers dispatch asynchronously; external systems call back to `WorkerCallbackResource`; engine receives `WorkflowExecutionCompleted` events through CDI
- **bindingName correlation:** YAML binding names flow from engine through dispatch, completion, and fault events — enabling tracing which YAML declaration triggered a worker execution

## Vision

- **Multi-cluster K8s (#16):** Dispatch Jobs to multiple Kubernetes clusters, not just one
- **Spring auto-config parity (#29, #30):** Script-core and camel-core missing Spring auto-configs; 288 lines hand-written without drift detection generators
- **CI integration (#13):** Full CI pipeline integration (partially addressed)