# Brief Notes — Shared Platform Services

> Source repo: platform (178 modules, 1821 Java source files)
> Audit date: 2026-10-02

## Capability Areas (descriptive names)

### 1. Identity & Tenancy
Three-level typed identity hierarchy (Principal → Actor → Participant) with strict tenancy isolation. Every SPI call is tenant-scoped. Actor types: human, agent, system. DID resolution (did:key, did:web, SCIM) bridges to verifiable credentials.
- **Differentiation:** Most platforms use raw string user IDs. CaseHub has a typed identity model that distinguishes ownership (who owns this?) from execution context (who did this?) from participation (who is in this conversation?). DID support adds verifiable identity to agent interactions.
- **Extent:** 3 identity modules, OIDC integration, SCIM 2.0 group membership, DID resolver composite (Ed25519 + P-256 + secp256k1), SSRF-protected web DID resolution.

### 2. Expression Engine
Three expression engines (JQ, MVEL3, JEXL3) with config/secret scope injection, auto-registered via CDI.
- **Differentiation:** Three engines in one registry, each optimised for its context (JQ for JSON transforms, MVEL for business rules, JEXL for template expressions). Config and secret injection via `$config` and `$secret` scopes — expressions can reference business configuration without hard-coding values.
- **Extent:** Framework-neutral core (expression-core) with Quarkus and Spring wiring. PropertySource abstraction enables dual-framework without duplicating logic. Lambda expressions as compiled expressions.

### 3. Notification Pipeline
Full subscription → match → dispatch → deliver → track → digest pipeline. Three-path delivery (immediate/suppress/digest). Quiet hours with buffer-for-digest.
- **Differentiation:** Not just "send notifications" — a complete event-driven subscription engine with alpha network matching (Rete algorithm), suppression rules, per-user digest batching, delivery tracking with retry, engagement events (opened/clicked/dismissed/replied/converted), and quiet hours. Modular target kinds (user vs agent vs system) with different routing paths.
- **Extent:** 14 notification-related modules. JPA production backends with SKIP LOCKED claims, keyset pagination, retention purge. In-memory alternatives for testing. SSE push delivery. REST API for presentation.

### 4. Access Control (ACL)
Hierarchical resource ACL with group-based grants, deny entries, parent-child inheritance via recursive CTE.
- **Differentiation:** Full deny-wins-at-specificity resolution with recursive CTE hierarchy traversal in a single SQL query. Wildcard grants (`type:*`), parent chain walking (depth 20), tenant-scoped audit logging with retention purge.
- **Extent:** 7 ACL modules. Admin REST API with `@RolesAllowed`, worker credential filter for outbound authentication.

### 5. Agent Infrastructure
Multi-backend agent routing with model registry, rate limiting, and manifest-driven configuration.
- **Differentiation:** Vendor-agnostic `AgentProvider` SPI with pluggable backends (Claude, OpenAI, Gemini, Gemini CLI, Codex, Ollama, LangChain4j). Three-step model resolution (alias → tier → registry → key). Manifest-driven configuration (`agent-config.yaml`) with credential reference resolution (env/file/Vault). Token bucket + concurrency gate rate limiting via CDI decorator.
- **Extent:** 24 agent modules. Native SDK integration (not wrappers) for OpenAI (v4.50), Gemini (GenAI v1.65), Claude (claude-code-sdk). Bidirectional LangChain4j interop. Local model support via Ollama.

### 6. Simulation Framework
Complete SPI testing infrastructure replacing Mockito — configurable simulation for any SPI with corpus-based strategies, verification API, and temporal event simulation.
- **Differentiation:** Nothing comparable exists in the Java ecosystem. Generated CDI decorators intercept SPI calls, resolve from corpus via strategy (sequential, key-lookup, random, recorded-replay, nearest-match), record to journal for verification. Multi-format corpus (YAML, JSON, CSV). LLM-generated corpus population. Temporal simulation with 3-level speed composition. Full Mockito-quality verification API.
- **Extent:** 14 simulation modules. APT code generator for decorator + QN constants + parameter metadata. Pre-built adapters for 11 platform SPIs + CaseMemoryStore + AgentProvider. Event simulation with tick-based emitter and temporal driver lifecycle (start/pause/resume/stop/setSpeed).

### 7. YAML Declaration Primitives
Pure Java YAML processing — variable resolution, for-each expansion, truthiness evaluation, CSV parsing, module system.
- **Differentiation:** Not a YAML parser — a YAML declaration layer. `VariableResolver` with pluggable sources and deferred prefixes. `ForEachExpander` with inline + named groups, `when` conditions, ID-keyed results. Module system (`YamlModule`, `ModuleExpander`, `TypedExpandedModule<T>`) for composable YAML sections with typed expansion. Zero deps, J2CL-transpilable.
- **Extent:** 5 YAML modules. Jackson integration with dynamic section capture. Step runtime for YAML-declared action pipelines. Plugin system with APT-based plugin processor.

### 8. Data Source Alpha Network
Rete-style event routing with tenant-scoped registries, self-pruning lifecycle, and convergent re-registration.
- **Differentiation:** Applies the Rete algorithm's alpha network to domain event routing — type nodes, filter nodes, fan-out processing with self-pruning deregistration. Not a generic pub-sub — a pattern-matched, tenant-scoped event routing engine.
- **Extent:** 3 datasource modules + CloudEvent routing (DataSourceRouter, CloudEventTypeDispatcher). JPA production backend with startup reconciliation.

### 9. Document Signing & PDF Generation
EU DSS 6.2 PAdES/CAdES document signing with per-tenant keystores, certificate lifecycle, and PDF/A-2b generation.
- **Differentiation:** Production-grade compliance document signing — not toy crypto. EU Trusted List validation, per-tenant PKCS#12 keystores, atomic keystore rotation without restart, certificate expiry monitoring. PDF/A-2b conformance for archival.
- **Extent:** Document signing (PAdES embedded + CAdES detached) + verification. Trusted list loading. Per-tenant keystore resolution. PDF generation with bundled fonts for reproducible rendering.

### 10. Preference Management
Scope-hierarchical business configuration with runtime changes, schema discovery, and validation.
- **Differentiation:** Business configuration that varies per case type and installation — not deployment config. Full scope hierarchy (org → app → case-type). Schema registry with runtime validation, ETag-based versioning. MongoDB and JPA backends.
- **Extent:** 4 preference modules. REST editor with schema discovery. Typed preference keys (boolean, int, double, duration, multi-value, map).

### 11. Callback System
Remote procedure call over HTTP with lease management, retry, and heartbeat renewal.
- **Differentiation:** SPI-level remote callbacks — generated decorators route local SPI calls to remote HTTP endpoints. Lease-based registration with heartbeat renewal and reaper. Retry via PolicyEnforcer.
- **Extent:** 10 callback modules. APT code generator. Client-side auto-registration. MCP tools for callback management.

### 12. MCP Hierarchical Model
Auto-discovered MCP tool registration from GraphQL/REST APIs with domain report aggregation.
- **Differentiation:** MCP tools are not hand-written — they're generated from `@McpDomain`-annotated interfaces and classes. `GraphQLModelScanner` auto-discovers domains. `DynamicToolRegistrar` handles capability exceptions gracefully. `LandscapeReportService` aggregates all domain reports in parallel.
- **Extent:** 4 MCP modules. GraphQL generator (APT), REST generator (Maven plugin), Spring variants. Platform landscape aggregation.

### 13. Streams Integration
Five event stream connectors with CloudEvent construction.
- **Differentiation:** Full CloudEvent-based event streaming with 5 connector types. Camel connector supports runtime-dynamic route addition — the only connector that doesn't require restart.
- **Extent:** Kafka, AMQP, webhook, poll, Camel. Shared CloudEvent factory. Per-endpoint failure isolation in poll connector.

### 14. Spring Boot Support (dual-framework)
Full Spring Boot auto-configuration — every Quarkus capability has a Spring equivalent.
- **Differentiation:** This is not "also works with Spring." Every module follows a `-core` (framework-neutral POJO) + Quarkus wiring + Spring auto-configuration pattern. Spring generators produce `@Controller`, `@RestController`, Spring AI `@Tool` equivalents. Integration tested with Spring starters.
- **Extent:** ~30 Spring-specific modules (starters, generators, auto-configurations). Framework-neutral `-core` extraction means zero business logic duplication between Quarkus and Spring.

## Consistency Benefit
Every capability above shares:
- CDI `@DefaultBean` displacement pattern (Quarkus) / `@ConditionalOnMissingBean` (Spring)
- Tenant-scoped repositories and queries
- CloudEvent-based event model
- `CurrentPrincipal` for identity context
- Alpha network for event routing
- Simulation framework coverage (any SPI can be simulated)
- JPA + in-memory dual backend pattern with CDI priority ladder
- MCP tool auto-discovery and generation

This consistency means: add a new capability and it immediately plugs into the notification pipeline, the ACL system, the audit trail, the simulation framework, and the MCP tool surface. No integration glue.

## Vision (from open issues)
- Spring completeness (#503) — closing remaining gaps for full framework parity
- YAML cross-repo parity (#502) — aligning yaml-core across platform + pages
- mTLS support (#498) — mutual TLS for remote manifest authentication
- Durable cache with temporal diffs (#488) — external platform state caching
- Distributed command dispatch (#486) — scaling scenario concurrency
- Agent Ollama Spring extraction (#508) — completing Spring agent parity

## Stale Claims in Parent Indexes
- contributor-index.md says "~50 modules" — actual is **178 modules**
- Neither index mentions Spring Boot support as a first-class capability
- capabilities.md is missing entries for: callback system, simulation framework, document signing, MCP model, capacity signal/redistribution