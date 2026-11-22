# Brief Notes — Agentic Execution Bridge

> Source repo: openclaw (3 Maven modules + TypeScript plugin + Python SDK, 69 Java source files)
> Audit date: 2026-10-02

## Capability Areas (descriptive names)

### 1. Agent Execution Bridge
Bidirectional bridge between CaseHub case orchestration and OpenClaw agent execution. Two invocation modes: webhook delivery (OpenClaw autonomous → CaseHub) and direct call (CaseHub case step → OpenClaw). DirectCallBridge provides synchronous request-reply over async webhooks via CompletableFuture, enabling both AgentProvider SPI and langchain4j ChatModel interfaces.
- **Differentiation:** Not just "call an LLM" — full lifecycle bridge with commitment tracking, session management, and delivery guarantees. DirectCallBridge uniquely solves the synchronous-over-async problem without requiring persistent sessions. langchain4j ChatModel adapter means OpenClaw agents appear as standard chat models to any langchain4j consumer.
- **Extent:** 3 Maven modules (core, casehub, app). 8 CaseHub SPI implementations (WorkerProvisioner, CaseChannelProvider, WorkerStatusListener, ChannelBackend, 2× MessageObserver, AgentProvider, OversightGateService). 1:N agent registry with parallel COMMAND routing via target field. Virtual thread migration complete (reactive deleted).

### 2. Cross-Channel Context Intelligence
In-memory ring buffer feeding cross-channel message history into agent system prompts. ChannelContextWindowObserver captures every dispatched Qhorus message; TypeScript plugin injects relevant context via cursor-based incremental fetching in before_prompt_build.
- **Differentiation:** Agents gain situational awareness across all channels of a case without explicit handoff. Two-phase association (agent-to-case at provision, case-to-channel at open) means no cross-SPI coordination at write time. Fail-open design — context unavailability never blocks the agent turn.
- **Extent:** Ring buffer per channel with configurable capacity (default 100) and TTL (default 30 min). TTL eviction via quarkus-scheduler. REST and MCP access via @McpDomain API.

### 3. Commitment Lifecycle MCP Surface
18 operations exposed as MCP tools + REST + GraphQL via @McpDomain SPI. Agents use MCP tools to commit, checkpoint, escalate, delegate, block, reject, and complete work — with structural accountability via Qhorus commitment tracking and Watchdog TTL enforcement.
- **Differentiation:** Completion signaling is exclusively via explicit MCP tool calls (ADR-0004) — no text classification, no heuristics. This is unusual: most agent frameworks infer completion from output text. CaseHub makes agents structurally declare their state transitions. 10 core tools (7 commitment + 1 query + 2 workitem) plus 8 plugin/context/scenario operations.
- **Extent:** 8 SKILL.md files for OpenClaw agents. TypeScript plugin SDK (npm) with 4 hooks (before_prompt_build, before_tool_call, agent_end, session_start). Python client library (PyPI) for channel context access. Auto-commit mode for tool-call-driven commitment lifecycle.

### 4. Oversight Gate Lifecycle
Human-in-the-loop oversight for high-risk agent actions. ActionRiskClassifier CDI beans (most-restrictive-wins) determine whether an action requires human approval. Gate context serialized as Java Properties into Qhorus COMMAND messages for crash-safe persistence.
- **Differentiation:** Risk classification is pluggable via CDI — applications inject their own classifiers. Fail-open on infrastructure errors (agent continues), fail-safe on classifier errors (gate required). Gate context survives Quarkus restarts because it's persisted in Qhorus messages, not in-memory state.
- **Extent:** Full gate lifecycle: evaluate → openGate → fulfill. Cross-tenant delivery handling (webhooks have no casehub principal). OversightGateDispatcher for atomic approval/rejection dispatch.

### 5. Demo Scenario Framework
Multi-agent scenario execution for showcasing CaseHub + OpenClaw integration patterns. Three reference scenarios (dev-team, trading-oversight, incident-response) with real-time SSE monitoring via Lit Web Components dashboard.
- **Differentiation:** Not a toy demo — the scenarios exercise the full integration surface: provisioning, channel routing, commitment lifecycle, oversight gates, and multi-agent coordination. The demo UI uses casehub-pages and casehub-blocks-ui components, proving the frontend component story.
- **Extent:** ScenarioStateStore with typed CaseExecutionEvent sealed hierarchy (9 event types). SSE streaming via @PlatformStream. DemoGateClassifier for risk simulation. Docker Compose for local multi-agent development.

## Cross-Repo Patterns

- **Qhorus dependency is structural:** Every channel, message, commitment, and gate flows through Qhorus SPIs. OpenClaw doesn't own any messaging primitives — it orchestrates them.
- **Engine SPI consumer:** Implements WorkerProvisioner, CaseChannelProvider, WorkerStatusListener, OversightGateService, ActionRiskClassifier from casehub-engine-api.
- **Platform agent infrastructure:** Uses AgentProvider SPI from casehub-platform-agent-api. Bridges to langchain4j ChatModel.
- **Frontend composition:** Demo UI consumes casehub-pages tokens and casehub-blocks-ui components — validates the shared component library approach.
- **@McpDomain pioneer:** First repo to adopt the @McpDomain SPI for tri-channel API generation. Pattern now available for all CaseHub repos.

## Vision

- **#52:** Migrate plugin auth from bridge token to OIDC client-credentials — proper service account auth for plugins.
- **#18:** Upstream dependency on OpenClaw after_tool_call hook for embedded agent runs — currently not firing, which limits auto-commit reliability in embedded mode.
- **Multi-agent orchestration patterns:** 1:N registry and parallel COMMAND routing (#63, #70) are the foundation for complex multi-agent cases where different agents handle different steps concurrently.