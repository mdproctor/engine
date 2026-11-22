# Brief Notes — Agentic Work Platform

> Source repo: claudony (4 modules, 164 Java source files, 12 Lit components)
> Audit date: 2026-10-02

## Capability Areas (descriptive names)

### 1. LLM Fleet Manager
Capacity-bounded agent session pools with suspend/resume semantics (tmux as persistence layer). Declarative definitions via three peer representations: fluent Java DSL, YAML, and `@PoolDefinition` annotations with APT code generation. Runtime registry for dynamic reconfiguration. Auto-scaling via pluggable `ScalingPolicy` SPI (step thresholds, target-tracking). Eviction via pluggable `EvictionPolicy` SPI. Micrometer metrics export and IoTDB time-series bridge. Real-time SSE event push for dashboard. 43 classes in the fleet package alone.
- **Differentiation:** No competing platform offers declarative LLM fleet management with three-representation parity (Java/YAML/annotation), auto-scaling policies, and suspend/resume semantics that preserve conversation context across work assignments. LangChain/CrewAI create agents on demand; Claudony manages persistent pools where sessions survive across tasks.
- **Extent:** `AgentPoolDefinition` model, `AgentSessionManager` lifecycle, `ScalingScheduler` with 3 policies, `EvictionPolicy` SPI, `PoolMetricsRegistrar` + `IoTDBFlatLabelAdapter`, `PoolDefinitionProcessor` APT, `PoolDefinitionSource` classpath scan, `AgentPoolConfigBridge` ops integration, `claudony-testing` module with in-memory pool utilities.

### 2. Remote Terminal Orchestration
tmux-based session lifecycle with WebSocket streaming via named FIFO pipes (no PTY). Fleet management across multiple nodes with symmetric peer mesh, circuit breaker health, and session federation. Multi-tenant session registry with tenant-filtered and unscoped query paths. Three pluggable session expiry policies.
- **Differentiation:** Purpose-built terminal orchestration for LLM CLI sessions — not a generic terminal emulator. Stateful sessions with crash recovery via tmux session options. Fleet federation across nodes without a central coordinator. No other platform manages Claude Code (or similar CLI) sessions as first-class fleet resources.
- **Extent:** 15 classes in `claudony-core`. `TmuxService` wraps all tmux operations. `SessionRegistry` (ConcurrentHashMap) with tenant isolation. Fleet: `PeerRegistry`, circuit breaker (3 failures → OPEN, exponential backoff), `ProxyWebSocket` for NAT traversal, 3 discovery mechanisms (static, manual, mDNS scaffold).

### 3. CaseHub Worker Runtime
Full `WorkerProvisioner` + `WorkerExecutionManager` SPI implementation. Virtual thread watcher per worker session. Signal drain pattern for correct ordering of ledger writes and case completion signals. Crash recovery via tmux session options (`@casehub_case_id`, `@casehub_role`). Session-level dispatch budget for concurrency throttling.
- **Differentiation:** Not just "spawn an LLM" — full lifecycle integration with the CaseHub case engine. Worker sessions get enriched context (ledger lineage, mesh channels, prior workers), produce audit trail entries with W3C PROV-DM causal links, and participate in case goal evaluation. Dispatch budget prevents runaway session creation.
- **Extent:** 8 SPI implementations in `claudony-casehub`. `ClaudonyLedgerEventCapture` replaces the standard ledger event capture with direct entity writes. `QhorusCausalLinkResolver` establishes provenance chains. `CompositeProviderConfigSource` aggregates runtime and static config.

### 4. Agent Communication Mesh
Reference implementation of the CaseHub agent mesh. Configurable channel layout (normative 3-channel vs simple 2-channel) and participation level (active/reactive/silent). Structured system prompts built from case lineage and channel context. Universal channel namespace with prefix ownership conventions. 40+ MCP tools for agent-to-agent communication.
- **Differentiation:** Normative accountability applied to agent communication. Channels enforce allowed speech acts at the Qhorus layer. Three participation levels give precise control over agent engagement. System prompts are structurally assembled from case context — not ad-hoc strings. No other platform combines structured agent communication with accountability enforcement.
- **Extent:** `MeshSystemPromptTemplate` (3 participation levels), `CaseChannelLayout` SPI (2 layouts), `WorkerSessionMapping` (role↔session bridge), `ClaudonyInstanceActorIdProvider` (audit identity), dual MCP endpoints (`/mcp` session tools + `/qhorus` mesh tools).

### 5. Browser Workspace
Two-page Lit 3 dashboard: fleet home (sessions, peers, mesh, pools) and terminal workbench (xterm.js, channel feed, task inbox, case browser). 12 Lit components. Responsive design for tablet and phone. WebAuthn passkey authentication with rate limiting.
- **Differentiation:** Purpose-built workspace for managing LLM agent fleets — not a generic admin panel. Case-bound sessions get the full workbench with channel navigation, action inbox, and correlation panels. The pool dashboard tab shows real-time scaling status and demand metrics.
- **Extent:** 12 Lit components, Quinoa + esbuild build, blocks-ui and pages-ui component consumption via Maven SNAPSHOT, SSE for real-time updates, responsive layouts.

## Cross-Repo Patterns

- **Integration terminus** — depends on engine, qhorus, ledger, platform, worker, work; nothing depends on it
- **Agent mesh reference implementation** — defines how agents participate in normative channels; other backends could implement the same SPIs
- **Fleet-as-a-service** — pool definitions from ops provisioner config bridge; scaling policies composable with desiredstate reconciliation
- **@McpDomain migration** (#204) — unified API generation using platform SPI; 5 API domain classes, 26 operations

## Vision

- **Declarative LLM fleet deployment** (#246) — desiredstate reconciliation + ops provisioning for fleet definitions
- **Standalone fleet script runner** (#247) — one-shot YAML fleet deployment with e2e tests
- **Cross-machine pool distribution** (#210) — distributed agent pools across fleet nodes
- **Eidos agent identity integration** (#209) — structured agent identity for pool members
- **Cost budgeting and limits** (#211) — per-pool cost tracking and enforcement
- **Model fallback chains** (#212) — cascading model selection for agent routing
- **Debate channel integration** (#158) — session-to-channel binding for autonomous Claude dialogue
- **Terminal UI convergence** (#244) — push pages terminal component into claudony