# Decisions — issue-909-engine-annotations

## D1: LC4j annotation relationship

**Choice:** Own all annotations — CaseHub defines `@Case`, `@Worker`, `@SystemPrompt`, `@Goal`, `@Milestone`, `@Bind`, `@Completion` etc. No LC4j annotation dependency. LC4j remains the runtime LLM client library (ChatModel, ChatRequest, etc.) used by engine-ai, but the annotations module has zero LC4j dependency.
**Alternatives:**
- Compose with LC4j annotations (reuse `@SystemMessage`, `@Agent`) — semantic confusion (same annotation, different execution model), coupling to LC4j's annotation evolution for two annotations
- Fork LC4j's annotation types into CaseHub — impractical, `ChatModel` pulls 50+ types in its type graph
**Rationale:** CaseHub's orchestration model (reactive, event-driven dispatch via bindings/triggers) is fundamentally different from LC4j's (imperative sequence/parallel/loop). Annotations should reflect CaseHub's model, not LC4j's. LC4j's value is as an LLM client library, not an orchestration framework. CaseHub already abstracts LC4j at the API level (`Agent`, `ChatModelProvider`, `AgentConverter`).
**Trade-offs:** Developers who know LC4j won't see familiar annotations — they'll need to learn CaseHub's annotation vocabulary. But the semantics are different enough that reusing LC4j annotations would be more confusing than helpful.
**Exploration:** deep-analysis
**Status:** captured

## D2: Target audience and annotation surface

**Choice:** Progressive disclosure — simple annotations for app developers (80% case), power annotations and `@Customize` escape hatch for platform developers. The annotation surface covers `@Case`, `@Worker`, `@Capability`, `@Bind`, `@Goal`, `@Milestone`, `@Completion`, `@Customize`, `@SystemPrompt`, `@Effect`, plus GOAP mode.
**Alternatives:**
- App-developer-only (hide all advanced fields) — insufficient for platform work
- Platform-developer-only (1:1 mirror of builder) — 30+ attribute annotations, worse than builders
**Rationale:** `@Customize` eliminates the "abandon annotations entirely" cliff — annotation-defined cases get the full builder API for the long tail via static customizer methods, without losing annotation readability for the common fields.
**Trade-offs:** Two mechanisms for the same fields (annotation attributes + `@Customize` builder access). Clear convention needed: common fields on annotations, rare fields via `@Customize`.
**Exploration:** quick
**Status:** captured

## D3: Scope — holistic design, layered implementation

**Choice:** Design all annotation layers holistically in one spec (engine, eidos, work, blocks, desiredstate, ledger). Create a GitHub epic with child issues for each layer. Implementation batched into wrap windows. Engine-annotations (Layer 1) is first, complete before moving to next module.
**Alternatives:**
- Design each layer independently — risks composition gaps between layers
- Implement all layers in one issue — scope explosion, unmanageable
**Rationale:** Annotations compose across modules (`@OversightGate` on `@Worker`, `@Identity` on `@Case`). Design must account for these composition points. But implementation is naturally scoped per repo/module.
**Trade-offs:** Larger design effort upfront. But prevents costly rework when Layer 2 annotations don't fit Layer 1's composition points.
**Exploration:** quick
**Status:** captured

## D4: Type inference — build-time AND runtime

**Choice:** Support both build-time and runtime type inference. Build extension pre-computes GoapAction instances for annotation-defined workers (with compile-time validation). Runtime GOAP planner also accepts dynamically registered workers whose types are inferred at registration time. Same inference logic, different timing.
**Alternatives:**
- Build-time only — limits dynamic composition, forces recompilation for new workers
- Runtime only (embabel model) — loses build-time validation, cycles/ambiguity detected late
**Rationale:** No reason to force a choice. Build-time gives validation. Runtime gives flexibility. The planner sees all workers as GoapAction instances regardless of origin. Annotation-defined workers are pre-validated; dynamic workers validated when registered.
**Trade-offs:** Two code paths for inference (build-time via Jandex, runtime via WorkerFunction types). Must ensure identical inference semantics.
**Depends on:** D3 (GOAP in scope)
**Exploration:** deep-analysis
**Status:** captured

## D5: GOAP data flow via ContextBridge

**Choice:** GOAP-inferred workers use `ContextBridge<T>` for typed data flow. Deterministic type → context key mapping (camelCase of simple type name, e.g., `AnalysisResult` → `"analysisResult"`). The build extension generates `ContextBridge`-based input/output wiring — not evaluator-specific expressions. `@Effect("customName")` overrides the default key. Parameterized types: `List<Clause>` → `"clauseList"`. Collision detection at build time. The evaluator (JQ, MVEL, lambda) is a consumer choice orthogonal to the annotation model.
**Alternatives:**
- Generate evaluator-specific expressions (JQ `.analysisResult`, MVEL `analysisResult`) — couples annotations to a specific evaluator
- Manual key mapping on every worker — defeats the purpose of auto-inference
**Rationale:** `ContextBridge<T>` is CaseHub's existing type-aware layer for worker input/output. GOAP inference wires the bridges, the bridge handles serialization/deserialization natively. This is architecturally equivalent to embabel's type-keyed Blackboard — the type-awareness already exists in CaseHub via `ContextBridge`.
**Trade-offs:** Convention-based key naming can surprise developers who expect a different key. `@Effect` provides the escape hatch.
**Depends on:** D4 (type inference model)
**Exploration:** deep-analysis
**Status:** captured

## D6: @Bind collapsible onto @Worker methods

**Choice:** Allow `@Bind` directly on `@Worker` methods as shorthand. When both are on the same method, the build extension generates both the `Worker` and the `Binding`. The separate `@Bind`-on-default-method form remains for complex dispatch logic. `@Bind` is `@Repeatable` — a `@Worker` can have multiple bindings for different triggers.
**Alternatives:**
- Always require separate `@Bind` methods — verbose boilerplate for the common case
**Rationale:** The most common pattern is a 1:1 `@Worker` + `@Bind` pairing with no custom dispatch logic. Collapsing eliminates the boilerplate while keeping the separate form for complex cases.
**Trade-offs:** Two ways to express bindings. Convention: collapsed for simple cases, separate for complex dispatch logic.
**Exploration:** quick
**Status:** captured

## D7: @Case interface is definition-only

**Choice:** `@Case` interface generates a `CaseDefinition` CDI bean only. No `CaseHub` subclass generated. Runtime interaction goes through `CaseHubRuntime` directly. The annotated interface is a declaration, not a runtime proxy.
**Alternatives:**
- Generate a `CaseHub` subclass — couples annotations to `CaseHub`'s lifecycle API surface
- Generate a runtime proxy implementing the interface — over-engineering for declaration metadata
**Rationale:** Keeps the annotation module's scope narrow (declaration, not runtime). Consumers choose how they interact with the runtime.
**Exploration:** quick
**Status:** captured

## D8: Parameter naming via Java parameter names

**Choice:** Use Java parameter names (requires `-parameters` compiler flag, already recommended by Quarkus). No `@V` or `@Param` needed for the common case. Optional `@Param("name")` annotation for override when the parameter name doesn't match the desired context key.
**Alternatives:**
- Require `@V("name")` on every parameter (LC4j's approach) — verbose, every parameter annotated
- Require `@Param("name")` on every parameter — same verbosity
**Rationale:** `-parameters` flag is already standard in Quarkus projects. Java parameter names are sufficient for 90% of cases. `@Param` handles the remainder.
**Trade-offs:** Requires `-parameters` compiler flag. Build extension must validate the flag is present and emit a clear error if parameter names are synthetic (`arg0`, `arg1`).
**Exploration:** quick
**Status:** captured

## D9: Separate annotations module per repo

**Choice:** New `casehub-engine-annotations` module (not in engine-api). Contains annotation definitions + Quarkus build extension. Same pattern for each repo. Consumers who use builders/YAML don't pull in build-time processing.
**Alternatives:**
- Put annotations in engine-api — forces build extension on all consumers
- Put annotations in engine-api, build extension elsewhere — split creates confusion about where annotations "live"
**Rationale:** Keeps the build extension opt-in. Builder and YAML consumers are unaffected. Module convention is consistent across repos.
**Trade-offs:** One more module in the reactor. Small cost for clean separation.
**Exploration:** quick
**Status:** captured

## D10: Expression resolution — two-phase recorder

**Choice:** Two-phase recorder. STATIC_INIT builds CaseDefinition structure with raw expression strings. RUNTIME_INIT resolves strings to ExpressionEvaluators via CDI-managed ExpressionEngineRegistry. Annotations always specify JQ expressions, but the resolution layer is engine-agnostic.
**Alternatives:**
- Single-phase JQ direct — use ContextChangeTrigger(String) convenience constructor (creates JQExpressionEvaluator internally). Simpler, works, but hardcodes JQ and diverges from platform convention.
**Rationale:** Other expression engines (CEL, MVEL) are on the platform roadmap. Using ExpressionEngineRegistry means annotation-defined cases benefit automatically when new engines land. The extra phase is mechanical.
**Trade-offs:** Two recorder steps instead of one. Marginally more complex extension wiring.
**Depends on:** D5 (expression orthogonality)
**Exploration:** quick
**Status:** captured
