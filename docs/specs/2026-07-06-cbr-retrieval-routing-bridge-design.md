# CBR Retrieval → Routing Bridge Design

**Issue:** casehubio/engine#478
**Date:** 2026-07-06
**Status:** Proposed

## Problem

Routing strategies (`ImplementationRoutingStrategy`, `AgentRoutingStrategy`) make decisions
without historical case context. The CBR Retain step (engine#477, `CaseOutcomeObserver`) writes
completed case outcomes to `CbrCaseMemoryStore`, but nothing reads them back at routing time.
The CBR Retrieve-to-Reuse bridge is missing.

### Issue #478 corrections

The original issue references `CaseRetriever` (from `casehub-neocortex-rag-api`) and a type
`RetrievedCase` that does not exist. `CaseRetriever` is a text-based RAG retriever — wrong
abstraction. The actual CBR retrieval surface is `CbrCaseMemoryStore.retrieveSimilar(CbrQuery,
Class<C>)` from `casehub-neocortex-memory-api`, which is already on the engine runtime classpath.
This design uses the correct types: `CbrQuery`, `ScoredCbrCase<PlanCbrCase>`, `PlanTrace`.

### Existing gap: `ImplementationRoutingContext` missing `tenancyId`

`AgentRoutingContext` already carries `tenancyId` (with Javadoc referencing "tenant-scoped CBR
routing"). `ImplementationRoutingContext` does not. This design fixes the gap.

## Approach

**Context enrichment (push-based).** The engine retrieves CBR data and injects it into routing
contexts before the strategy runs. Strategies receive pre-fetched `List<RetrievedExperience>`
and use or ignore it.

**Why push, not pull:** Trust scoring uses pull (`TrustScoreSource.getScore(workerId,
capabilityName)`) because the strategy already has both lookup keys. CBR retrieval requires
`CaseDefinition.CbrConfig` + `CaseContext` for feature extraction — data the engine has at
the call site but a strategy would need to re-resolve via repository lookups. The two patterns
differ because the data dependencies differ.

## Design

### 1. Feature extraction types

In `api/src/main/java/io/casehub/api/model/cbr/`, as a cohesive CBR module:

```java
public sealed interface FeatureExtractor
    permits JqFeatureExtractor, LambdaFeatureExtractor {
    String type();
}
```

Sealed — `CbrRetrievalService` dispatches via `instanceof` pattern matching, so the
compiler enforces exhaustiveness. Third-party extraction modes are not supported; the
two modes (declarative JQ, programmatic lambda) cover YAML and Java DSL respectively.

**JQ mode** — for YAML definitions. Carries `Map<String, String>` of feature name → JQ
expression, evaluated against the working layer at retrieval time.

```java
public record JqFeatureExtractor(Map<String, String> featureExpressions)
    implements FeatureExtractor {
    public static final String TYPE = "jq";
    @Override public String type() { return TYPE; }
    // Validates non-null, non-empty, defensive copy
}
```

**Lambda mode** — for Java FuncDSL. Carries `Function<CaseContext, Map<String, Object>>`.
Not serialisable (same constraint as `LambdaExpressionEvaluator`). The `CaseContext`
parameter follows the same contractual read-only pattern as `LambdaExpressionEvaluator` —
mutations during extraction are undefined behavior.

```java
public final class LambdaFeatureExtractor implements FeatureExtractor {
    public static final String TYPE = "lambda";
    private final Function<CaseContext, Map<String, Object>> fn;
    @Override public String type() { return TYPE; }
    public Map<String, Object> extract(CaseContext context) { return fn.apply(context); }
}
```

Dispatched by `CbrRetrievalService` via sealed pattern matching — not via a registry.

### 2. CbrConfig

In `api/src/main/java/io/casehub/api/model/cbr/CbrConfig.java`:

```java
public record CbrConfig(
    FeatureExtractor featureExtractor,
    int topK,                    // default 5
    double minSimilarity,        // default 0.0, range [0,1]
    Map<String, Double> weights, // per-feature weight overrides
    String domain,               // MemoryDomain name — nullable, defaults to EpisodicMemoryConfig.domain
    String caseType,             // CbrQuery caseType — nullable, defaults to CaseDefinition.getName()
    double vectorWeight          // blend factor, default 0.5, range [0,1]
) {}
```

**Builder** enforces mutual exclusivity between JQ and lambda modes:

- `feature(String name, String jqExpr)` — accumulates JQ expressions. Throws if lambda already set.
- `featureExtractor(Function<CaseContext, Map<String, Object>>)` — sets lambda. Throws if JQ features already added.
- `build()` — requires at least one mode. Validates:
  - All weight values ≥ 0 (fail-fast; `CbrQuery` compact constructor rejects negative weights at retrieval time — catching here prevents delayed runtime failures)
  - `domain`, if non-null, must be non-blank (the `MemoryDomain` constructor rejects blank strings)
  - `minSimilarity` in [0, 1], `vectorWeight` in [0, 1], `topK` ≥ 1

**YAML usage:**
```yaml
cbr:
  features:
    opponent_posture: ".agent.intel.enemy.posture"
    enemy_build_order: ".agent.intel.enemy.build"
  weights:
    opponent_posture: 2.0
  topK: 5
  minSimilarity: 0.3
```

**Java FuncDSL usage:**
```java
CaseDefinition.builder()
    .cbrConfig(CbrConfig.builder()
        .featureExtractor(ctx -> Map.of(
            "opponent_posture", ctx.layer(WORKING).get("agent.intel.enemy.posture")))
        .topK(5)
        .build())
```

### 3. Engine-owned result types

In `api/src/main/java/io/casehub/api/spi/routing/`:

```java
public record RetrievedExperience(
    String problem,
    String solution,
    String outcome,
    Double confidence,
    double similarityScore,       // [-1, 1]
    Map<String, Object> features,
    List<ExperiencePlanStep> planTrace
) {}

public record ExperiencePlanStep(
    String bindingName,
    String capabilityName,
    String workerName,
    String stepOutcome,
    int priority,
    Map<String, Object> parameters
) {}
```

These mirror `ScoredCbrCase<PlanCbrCase>` and `PlanTrace` structurally but are engine-owned.
No `casehub-neocortex-memory-api` dependency from `engine-api`.

### 4. Routing context changes

Three records gain fields:

```java
// PlanExecutionContext — gains experiences
public record PlanExecutionContext(
    UUID caseId, CaseDefinition definition, CaseContext caseContext,
    CaseStatus caseStatus, String tenancyId,
    List<RetrievedExperience> experiences) {}

// ImplementationRoutingContext — gains tenancyId + experiences
public record ImplementationRoutingContext(
    UUID caseId, String capabilityName, JsonNode caseContext,
    String tenancyId, List<RetrievedExperience> experiences) {}

// AgentRoutingContext — gains experiences
public record AgentRoutingContext(
    UUID caseId, String capabilityName, JsonNode caseContext,
    String tenancyId, List<RetrievedExperience> experiences) {}
```

Breaking changes to record constructors. Production construction sites:
- `PlanExecutionContext`: `CaseContextChangedEventHandler.rules()` (1 site)
- `ImplementationRoutingContext`: `PlanningStrategyLoopControl.applyImplementationRouting()` (1 site)
- `AgentRoutingContext`: 3 sites:
  - `CaseContextChangedEventHandler.publishWorkerSchedule()` — event-driven routing, receives retrieved experiences
  - `DefaultWorkOrchestrator.doSubmit()` — direct/synchronous orchestration path; inject `CbrRetrievalService`, retrieve experiences before routing (same pattern as the event-driven path)
  - `WorkflowExecutionCompletedHandler.fireOutcomeRecorder()` — outcome recording, not a routing decision; passes `List.of()` for experiences

Test construction sites (~22 sites across ~14 files — all mechanical, adding `List.of()` for experiences):
- `PlanExecutionContext` (~13 sites): `ChoreographyLoopControlTest`, `DefaultPlanningStrategyTest`, `ImplementationRoutingTest`, `PlanningStrategyContractTest`, `StageLifecycleEvaluatorTest`, `BindingGatingTest` (7 sites), `BlackboardPlanConfigurerTest`, `PlanConfigurerBlackboardTest`
- `AgentRoutingContext` (~7 sites): `AgentRoutingStrategyContractTest` (4 sites), `LeastLoadedAgentStrategyTest`, `TrustWeightedAgentStrategyTest`, `SemanticAgentRoutingStrategyTest`
- `ImplementationRoutingContext` (~2 sites): `NoOpImplementationRoutingStrategyTest`, `TrustWeightedImplementationRoutingStrategyTest`

### 5. CbrRetrievalService

In `runtime/src/main/java/io/casehub/engine/internal/routing/CbrRetrievalService.java`.
`@ApplicationScoped`. Injects `JQEvaluator` and `ReactiveCbrCaseMemoryStore`.

**`retrieve(CaseDefinition, CaseInstance) → Uni<List<RetrievedExperience>>`**

**Failure recovery:** CBR retrieval is advisory enrichment — a store failure must never block
case progression. The entire reactive chain is wrapped with `.onFailure().recoverWithItem()`:

```java
return buildAndExecuteQuery(...)
    .onFailure().recoverWithItem(t -> {
        LOG.warnf(t, "CBR retrieval failed for case definition '%s' — proceeding without experiences",
                  definition.getName());
        return List.of();
    });
```

This matches the engine's advisory-enrichment pattern (`tryProvision()` in
`CaseContextChangedEventHandler` catches `ProvisioningException` and falls back rather than
failing the handler). Without this, a Qdrant timeout or serialization error would propagate
through `rules()` → `onFailure().invoke(LOG.errorf(...))` and block the entire
CONTEXT_CHANGED processing — no bindings fire, the case stops progressing.

**Steps:**

1. Read `CbrConfig` from definition — null → return empty list
2. Extract features via `FeatureExtractor` (sealed switch):
   - `JqFeatureExtractor`: evaluate each expression **independently** against working layer
     via `JQEvaluator.eval()`. Per-expression semantics:
     - JQ path returns value → include in feature map
     - JQ path returns null (missing path — JQ's null propagation) → skip feature, log at
       DEBUG level ("Feature '{}' evaluated to null for case definition '{}' — skipped")
     - `JQEvaluator.eval()` returns `ValidationResult.error()` → skip feature, log at WARN
       level ("Feature expression '{}' failed: {} — skipped")
     - Partial results are valid: a 4-of-5 feature vector is better than no vector. Only if
       ALL features resolve to null/error → empty features → return empty list.
   - `LambdaFeatureExtractor`: call `extract(caseContext)` — returns the full map atomically.
     No per-feature granularity (the lambda controls its own error handling). If the lambda
     throws, the outer failure recovery (above) catches it.
   Empty features → return empty list
3. Resolve `domain`: `CbrConfig.domain` → `EpisodicMemoryConfig.domain` fallback → null.
   If null: **log warning** ("CbrConfig present but domain unresolvable for case definition
   '{}' — CBR retrieval skipped") and return empty list. This distinguishes "CBR not configured"
   (no CbrConfig, no log) from "CBR misconfigured" (CbrConfig present, domain missing).
4. Convert domain string to `MemoryDomain`: `new MemoryDomain(resolvedDomainString)`.
   The `MemoryDomain` constructor validates non-null, non-blank — but step 3 already handles
   null, and `CbrConfig.Builder` validates non-blank if provided. This conversion bridges
   the engine-owned `String` config to the neocortex `MemoryDomain` type.
5. Resolve `caseType` (config → `CaseDefinition.getName()` fallback)
6. Build `CbrQuery` with `tenancyId`, `domain` (as `MemoryDomain`), `caseType`, `features`,
   `topK`, `weights`, `minSimilarity`, `vectorWeight`. The `notBefore` and `problem` fields
   are passed as `null` — not surfaced in `CbrConfig` v1 (temporal filtering and text-based
   similarity boost are deferred; both are optional in `CbrQuery`).
7. Call `cbrStore.retrieveSimilar(query, PlanCbrCase.class)`
8. Map `ScoredCbrCase<PlanCbrCase>` → `RetrievedExperience`, `PlanTrace` → `ExperiencePlanStep`

### 6. Data flow

```
CaseContextChangedEventHandler.rules()
  │
  ├─ cbrRetrievalService.retrieve(definition, caseInstance)  ← single retrieval
  │    └─ Uni<List<RetrievedExperience>>
  │
  ├─ PlanExecutionContext(... experiences)
  │    └─ loopControl.select(planCtx, eligible)
  │         └─ PlanningStrategyLoopControl.select()
  │              ├─ applyImplementationRouting()
  │              │    └─ ImplementationRoutingContext(... ctx.tenancyId(), ctx.experiences())
  │              │         └─ implementationRoutingStrategy.select(routingCtx, candidates)
  │              └─ PlanningStrategy.select(plan, ctx, routed)
  │                   └─ ctx.experiences() available
  │
  └─ for each dispatched binding:
       └─ publishByTarget(... experiences)   ← gains List<RetrievedExperience> parameter
            ├─ case CapabilityTarget:
            │    └─ publishWorkerSchedule(... experiences)
            │         └─ AgentRoutingContext(... experiences)
            │              └─ agentRoutingStrategy.select(ctx, candidates)
            ├─ case SubCaseTarget:     (experiences not threaded — no agent routing)
            ├─ case HumanTaskTarget:   (experiences not threaded — no agent routing)
            └─ case ExtensionTarget:   (experiences not threaded — no agent routing)
```

Experiences retrieved once, threaded explicitly. No caching, no shared mutable state.

### 7. CaseDefinition integration

`CaseDefinition` gains:
- `private CbrConfig cbrConfig` field (nullable)
- `getCbrConfig()` / `setCbrConfig()` accessors
- `Builder.cbrConfig(CbrConfig)` method

`CaseDefinitionYamlMapper` maps the `cbr:` YAML block to `CbrConfig` using the builder's
`feature()` method for JQ mode. Lambda mode is Java-only (not serialisable from YAML).

JSON Schema in `schema/` gains a `cbr` object type for jsonschema2pojo generation.

### 8. Zero overhead

When `CaseDefinition.getCbrConfig()` is null:
- `CbrRetrievalService.retrieve()` returns `Uni.createFrom().item(List.of())` immediately
- No feature extraction, no `CbrQuery` construction, no `CbrCaseMemoryStore` call
- `experiences` is `List.of()` on all routing contexts
- Existing routing strategies unaffected — `TrustWeightedImplementationRoutingStrategy` and
  `TrustWeightedAgentStrategy` ignore the `experiences` field

## Test Strategy

### Unit tests (api)
- `CbrConfigBuilderTest` — mutual exclusivity, validation, defaults
- `JqFeatureExtractorTest` — immutability, null/empty rejection
- `LambdaFeatureExtractorTest` — invocation, null rejection
- `RetrievedExperienceTest` — bounds, defensive copies
- `ExperiencePlanStepTest` — validation

### YAML mapping tests (api)
- `CaseDefinitionYamlMapperCbrTest` — `cbr:` block → `CbrConfig`, missing block → null

### Bridge tests (runtime)
- `CbrRetrievalServiceTest` — JQ extraction, lambda extraction, query construction,
  result mapping, all early-return paths (null config, empty features, null domain),
  **failure recovery** (store throws → returns empty list, case progresses),
  **partial JQ extraction** (3-of-5 features null → proceeds with 2 features),
  **all JQ features null** (→ empty features → returns empty list)

### End-to-end @QuarkusTest (runtime)
- `CbrRoutingIntegrationTest` (YAML path) — full CONTEXT_CHANGED flow with
  `InMemoryCbrCaseMemoryStore` pre-loaded, recording strategies verify experiences arrive
- `CbrRoutingFuncDslIntegrationTest` (Java DSL path) — same flow with lambda extractor
- `CbrRoutingNoCbrConfigTest` — no CBR config → strategies receive empty experiences

### Existing test updates (~22 sites across ~14 files)
All mechanical: add `List.of()` for the new `experiences` parameter.
- `PlanExecutionContext` sites: `ChoreographyLoopControlTest`, `DefaultPlanningStrategyTest`, `ImplementationRoutingTest`, `PlanningStrategyContractTest`, `StageLifecycleEvaluatorTest`, `BindingGatingTest` (7 sites), `BlackboardPlanConfigurerTest`, `PlanConfigurerBlackboardTest`
- `AgentRoutingContext` sites: `AgentRoutingStrategyContractTest` (4 sites), `LeastLoadedAgentStrategyTest`, `TrustWeightedAgentStrategyTest`, `SemanticAgentRoutingStrategyTest`
- `ImplementationRoutingContext` sites: `NoOpImplementationRoutingStrategyTest`, `TrustWeightedImplementationRoutingStrategyTest`

## Deferred

| Concern | Issue |
|---------|-------|
| **Case-lifetime CBR caching** — the initial implementation retrieves on every `CONTEXT_CHANGED` invocation. For QuarkMind (500ms ticks), this means a CBR retrieval per tick including JQ evaluation and a potential Qdrant network call. QuarkMind **cannot deploy CBR routing until caching is implemented.** The caching design (invalidation on feature change, case lifecycle boundaries) warrants its own spec. | File as follow-on |
| `CbrFeatureSchema` registration — without schema registration, `CbrSimilarityScorer` returns 1.0 for all cases (graceful degradation, not a crash), making feature-weighted similarity scoring ineffective. Schema auto-registration from `CbrConfig` is blocked on type information (`FeatureField` requires numeric ranges and categorical/numeric/text classification that JQ expressions don't carry). Options: extend `CbrConfig` with field type metadata, or make schema registration the application's responsibility. | File as follow-on |
| `CbrQuery.notBefore` (temporal filtering) and `CbrQuery.problem` (text-based similarity boost) — intentionally not surfaced in `CbrConfig` v1. Both are optional in `CbrQuery` (null = no filter/no boost). | File as follow-on |
| Feature-level similarity breakdown in `RetrievedExperience` | File as follow-on |
| `CbrConfig` validation at registration time (warn if domain unresolvable) | File as follow-on |
