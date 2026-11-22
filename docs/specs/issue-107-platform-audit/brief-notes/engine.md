# Brief Notes — Case Orchestration Engine

## Capabilities (descriptive names)

### Case Orchestration Engine (core)
Hybrid choreography + orchestration coordination engine implementing the Blackboard Architecture (Hayes-Roth, 1985) with CMMN terminology. Coordinates workers (AI agents, humans, workflows) via case definitions, binding rules, and optional synchronous orchestration. All handlers run on virtual threads.

### Goal-Directed Planning
CMMN-inspired planning with compound hierarchy (Stage fully retired → Compound), completion semantics (All, M-of-N, FirstWins), dispatch modes (orchestrated/choreographed). Sub-case orchestration with grouped M-of-N threshold logic.

### Worker Dispatch Pipeline
Full lifecycle: schedule → provision → execute → apply output → re-evaluate bindings. Concurrency budgets (case-level + external DispatchBudget SPI). Watchdog integration for hung workers. Scoped worker sessions (Persistent, Reinvoked). Multiple handler types: sync agent, flow, A2A, MCP, ReAct, pattern-based (blocks adapter).

### Routing Architecture
Composable signal-based routing: independent RoutingSignalProviders score candidates, compositor computes weighted sum. Built-in signals: workload, trust, experience (CBR), personality (JPAF), semantic (embedding). Strategy SPIs for agent routing, implementation routing, human task routing, candidate matching, decomposition.

### DAG Parallel Execution
Dependency-graph-aware execution driver using virtual threads. Validated immutable DAG plans with streaming or barrier dispatch. Node state tracking (Pending → Dispatched → Completed/Failed/Skipped/Cancelled). Contingency support.

### HTN Decomposition
Hierarchical Task Network SPIs for task decomposition. DecompositionStrategy selects methods, methods produce DagPlans of sub-tasks.

### Case-Based Reasoning Integration
Experience-driven routing and planning. Mixed retrieval (plan traces + documents). Human-in-the-loop resolution selection via judgment bindings. Automatic outcome weighting and retrieval feedback (3 layers). Document ingestion via CorpusSourceAdapter.

### Oversight Gate Architecture
Multi-approver oversight for consequential worker actions. Risk classification via composable ActionRiskClassifier chain ("most restrictive wins"). M-of-N quorum approval. Gate lifecycle events. Configurable per binding.

### Expression Engine
Unified with platform expression hierarchy. Three languages: JQ (default, evaluates against working layer), MVEL (typed POJO context), Lambda (Java DSL). Per-expression language override via `{lang: expr}` syntax.

### Resilience
Dead Letter Queue with auto-replay, PoisonPill detection with sliding-window circuit breaker, backoff strategies, case timeout enforcement, persistent DLQ storage.

### YAML Application Framework
Three pathways for case definition: YAML DSL, Java annotations (@Case), TypeScript (planned). Progressive complexity — flat YAML for simple cases, directory conventions for complex apps.

### Agent Mesh Primitives
Channel topology SPIs (NormativeChannelLayout: 4-channel, SimpleLayout: 2-channel). Mesh participation strategies (Active, Reactive, Silent).

### Worker Rights and Scoped Credentials
Case-scoped credential minting at dispatch time with auto-managed ACL grants. Differential revocation for shared service accounts.

## Differentiation

- **Not a workflow engine** — this is a full Blackboard Architecture with reactive choreography AND orchestration, not a DAG-of-steps workflow. Bindings fire on context change, not on graph edges.
- **Composable routing is unique** — no other case/workflow engine blends trust scores, personality profiles, workload, CBR experience, and semantic embeddings into a single routing decision via pluggable signal providers.
- **Oversight gates are built-in** — not bolted on. Every worker action can be risk-classified and gated with M-of-N multi-approver quorum. This is native accountability.
- **Multi-framework** — same engine logic runs on Quarkus AND Spring Boot via core/framework module split. 44 non-example modules, 24 example modules.
- **Virtual threads throughout** — no reactive Mutiny pipelines, no CompletableFuture chains. Clean synchronous code on virtual threads.

## Extent

- 44 non-example modules + 24 example modules = 68 total
- 6 multi-framework layers (core + Quarkus + Spring each)
- 5+ persistence backends (in-memory, Hibernate/Panache, Spring Data JPA, H2, PostgreSQL with RLS)
- 2 scheduler backends (Quartz, db-scheduler)
- 6 worker function handlers (sync agent, flow, A2A, MCP, ReAct, pattern)
- 5 routing signal providers (workload, trust, experience, personality, semantic)
- REST API with 20+ endpoints + GraphQL surface
- 15+ SPIs for extension

## Consistency Benefit

- CDI `@DefaultBean` convention shared with all foundation repos — consumer just provides an `@ApplicationScoped` implementation
- `NamedStrategy` convention shared with platform — all strategy types resolve the same way
- `CaseLifecycleEvent` CDI events integrate with ledger, qhorus, work without coupling
- Expression engine delegates to platform's `ExpressionEngineRegistry` — same JQ/MVEL/JEXL everywhere
- Tenancy enforcement via shared `CurrentPrincipal` + RLS pattern
- Worker model (`WorkerResult`, `WorkerScope`) shared with worker repo

## Vision (from open issues)

- **Spring completeness** (#1206 epic) — 11 CDI modules without -core extraction, 18 leaked Quarkus imports, no rest-spring module
- **Auto-bootstrap** (#1203) — auto-discover YAML case definitions from classpath
- **Evolution conductor UI** (#1180) — surface evolution in devtown as first consumer
- **CBR plan-to-step converter** (#1194) — future control flow support for CBR-to-playbook mapping
- **Concurrent registry** (#1205) — lazy re-registration race condition fix