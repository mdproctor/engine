# Brief Notes — Composable Dashboard & Automation Platform

> Source repo: pages (28 TS packages + 17 Java backend modules, 661 TS source files + 137 Java source files)
> Audit date: 2026-10-02

## Capability Areas (descriptive names)

### 1. YAML-Declarable Data Dashboards
Full pipeline from YAML declaration through data resolution to interactive rendering. `loadSite()` orchestrator parses YAML, builds DataSetScope, creates CSS grid layouts, wires data pipelines to components. 30+ component types with full type guards. JSONata data transformation. 14 DataSource implementations including mutable REST for CRUD.
- **Differentiation:** No competitor offers YAML-declared dashboards with a typed data pipeline, JSONata transformation, and full interactivity. Grafana uses JSON configs and requires a server. Retool/Appsmith are code-heavy. CaseHub Pages is the only framework where domain experts author interactive dashboards entirely in YAML — no code, no server config.
- **Extent:** 28 TS packages. 15 chart types (bar, line, area, pie, scatter, bubble, timeseries, timeline, heatmap, density heatmap, treemap, meter, metric, map, graph). Data table with virtual scroll, cell spanning, tree data. Grouped views with 3 presets. Schema-driven forms with nested objects, arrays, `$ref` resolution. Expression evaluation engine. Cross-filter state management. Layout serialization (localStorage and REST). Pluggable OKLCH theme system.

### 2. YAML Composition Engine (yaml-core)
Format-agnostic preprocessing layer: variables, modules (parameterized templates), forEach (iteration), conditionals, CSV data sources. Any YAML format gains composition via Zod schema intersection. Step action system with `Definition`, `PluginRegistry`, and cross-runtime `Portability` model.
- **Differentiation:** No equivalent exists. Helm charts handle templating for Kubernetes YAML but are Go-template-based and domain-specific. yaml-core is format-agnostic — any CaseHub YAML format (pages, scenarios, case definitions) automatically gains composition, modules, and iteration. The portability model (`universal | java | ts | both`) enables cross-runtime step validation — actions declare which runtimes they support, and the system validates compatibility before execution.
- **Extent:** 6 entry points (barrel, expand, schema, orchestration, condition, step). Orchestration primitives: select/match, barrier, signal, quorum, deadline, correlation. DecoratorChain 13-layer execution pipeline. StepRunner with DeadlineContext and QuorumTracker. CompositeCatalog for multi-source action discovery.

### 3. Scenario Automation Framework
Full-stack scenario orchestration: YAML scenarios → ARIA-based UI automation → MCP domain exposure → CDI action handlers. Virtual clock for time-controlled execution. Hierarchical scenario composition with call graph validation. Tutorial system for interactive learning.
- **Differentiation:** Playwright and Cypress test UIs but don't compose with business domain logic. CaseHub's scenario system bridges UI automation (ARIA) with domain operations (GraphQL/MCP) in a single YAML scenario, with virtual time control and narrative content. The `@ScenarioAction` CDI pattern means any backend service can register as a scenario step handler — scenarios orchestrate both frontend and backend.
- **Extent:** `pages-aria` (40 TS files — walker, executor, scheduler, tutorial host), `pages-scenario` (25 Java files — model, compiler, call graph validator), `pages-scenario-client` (CDI executor), `pages-scenario-runtime` (ARIA dispatcher), `pages-mcp` (MCP/GraphQL domain resolver). Cross-parser tests validate YAML consistency between TS and Java parsers.

### 4. Visual Diagram Editor
Domain-agnostic graph editing with stencil grammar system for domain-specific editors. React Flow 12 bridge via Lit web components. ELK layout engine with multiple layout algorithms. Persistence SPI with optimistic concurrency. Runtime overlays for badges, heatmaps, highlights.
- **Differentiation:** BPMN.io and GoJS are the main competitors. CaseHub's approach is unique: domain-agnostic `graph-core` with stencil grammar (containment/connection rules with cardinality), `DomainAdapter<T>` for model translation, and `EditPolicy` for domain-controlled interactions. The Lit-React Flow bridge allows embedding in the web component ecosystem. Radial and stack-column layouts are unusual in graph editors.
- **Extent:** 6 packages. Stencil grammar with validation (containment, connection, cardinality). EditPolicy with 7 hooks. Immutable model mutations (add/remove/split/splice). Hold-to-move and drag-to-splice gestures. HTML5 DnD palette integration. SVG/PNG export. GitHubBackend for persistence.

### 5. Visual Builder Workbench (pre-alpha)
Visual dashboard authoring: component catalog with context-aware filtering, tree view with DnD, clipboard with YAML fragment serialization, selection overlay, property palette with schema-driven editors, document model with insertion constraints.
- **Differentiation:** Moves CaseHub Pages from "YAML-only authoring" to "visual + YAML hybrid authoring." The document model (`PageDocument` with typed nodes and insertion constraints) provides structural editing, while YAML remains the source of truth. Builder shell shows pre-expansion YAML in editor and post-expansion results in visual preview — the yaml-core composition layer is visible in the builder.
- **Extent:** 4 packages (pre-alpha, not yet in build chain). Builder shell with diff/patch, scope collector, gutter indicators. Component catalog with palette context and context relevance scoring. Preview data registry with per-component strategies.

### 6. Real-Time Push Infrastructure
Bidirectional typed wire protocol: WebSocket + SSE push with topic-based routing via segment-trie `TopicRegistry`. EventStore SPI with InMemory, PostgreSQL (JDBC), and Redis Streams implementations. EventBroadcaster with store-and-forward replay. CDI producers for drop-in Quarkus integration.
- **Differentiation:** Most push frameworks (Socket.IO, Pusher) are generic message brokers. CaseHub's push protocol is typed (PushMessage builders, PushRequest sealed parser), wildcard-aware (segment-trie with `*` and `#`), and event-sourced (new subscriptions replay last N events). ARIA command correlation lets server-side code execute and observe UI operations — unique.
- **Extent:** 4 Java modules. Wildcard topic patterns (literal, single-segment, multi-segment). Configurable replay buffer. JDBC and Redis durable stores. CDI producers for TopicRegistry, EventStore, JsonWriter, EventBroadcaster.

### 7. Server-Side Data Providers
Backend data provider adapters: SQL with frontend push-down (filter/sort/group operations translated to SQL), IoTDB for time-series device data, Prometheus for metrics queries.
- **Differentiation:** The push-down model is distinctive — frontend filter/sort/group operations are serialized and translated to native query languages on the backend, so large datasets are filtered server-side. Most dashboard tools either pull all data to the client or require separate query definitions. CaseHub's approach lets the same YAML dashboard declaration work with both client-side and server-side data processing.
- **Extent:** SQL query builder with TIME_FRAME filter, IoTDB query translator, Prometheus client with PromQL builder and response mapper.

### 8. Design Token & Theme System
OKLCH 12-step colour scales with perceptually uniform wide-gamut colours. Token categories: colour scales, spacing, typography, elevation, motion, radius, density. Pluggable theme pipeline with theme picker web component. Dark theme with blue-dominant neutrals.
- **Differentiation:** OKLCH is the most modern colour space for design systems (perceptually uniform, wide-gamut). Most design token systems still use HSL or hex. CaseHub's 12-step scale generation ensures consistent contrast ratios across all hue families. Semantic role tokens map from scale steps, so themes only need to set a few base values.
- **Extent:** Token generation CLI. Build-time and runtime token generation. Theme pipeline with transforms and presets. 22 downstream components styled exclusively via tokens.

### 9. LSP & IDE Integration
Full LSP server for CaseHub YAML formats with schema registry, completion, diagnostics, hover, refactoring (symbol table, rename, workspace index), and jq expression intelligence. IntelliJ plugin via LSP4IJ with bundled Node.js server.
- **Differentiation:** No competitor offers IDE intelligence for YAML-declared dashboards. The LSP leverages Zod schemas (from pages-schema) for type-aware completions and diagnostics. jq expression intelligence is rare — most jq users work without IDE support.
- **Extent:** Node.js stdio + web worker transports. esbuild single-file CJS bundle. IntelliJ plugin (Kotlin/Gradle, LSP4IJ, JDK 21). pages-schema provides 45+ component property schemas.

## Cross-Repo Patterns

- **Consumed by all CaseHub web applications** (10+ apps) via Quinoa + Maven SNAPSHOT distribution (ADR-0001). Each app: Maven POM includes `casehub-pages-*` Java modules, `src/main/webui/` workspace with TypeScript sources, `.casehub-packages/` for local package resolution.
- **blocks-ui depends on pages-primitives** (a11y mixins), pages-table (data table), pages-ui-components (form inputs).
- **yaml-core step system bridges to engine** — the Portability model validates that step actions can execute on the target runtime (Java engine vs TypeScript browser).
- **Scenario system bridges to platform MCP** — `@McpDomain("aria")` resolver exposes UI automation as MCP tools, enabling LLM agents to interact with dashboards.
- **Push wire protocol used by engine** — `casehub-pages-push` (Java SDK) can be used by any Quarkus app for typed real-time data delivery.

## Vision

- **Builder workbench GA** — visual + YAML hybrid dashboard authoring (pages-builder, pages-document currently pre-alpha)
- **Graph editor completion** — full stencil rendering pipeline, custom node rendering, interaction layer, toolbar
- **Step system expansion** — more orchestration primitives, cross-runtime step execution, step marketplace
- **Scenario marketplace** — shareable scenario libraries with step catalog discovery
- **Additional data providers** — expanding server-side push-down to more data sources