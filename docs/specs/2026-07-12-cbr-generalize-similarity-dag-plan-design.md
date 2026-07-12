# Design Spec: CBR Generalization, Feature Similarity, DAG Plan Structure

**Date:** 2026-07-12
**Issues:** engine#704, engine#672, engine#694
**Branch:** `issue-704-cbr-generalize-dag-plan`

## Overview

Three changes across two repos (engine and blocks) on two coordinated branches:
1. **#704** — Generalize `CbrRetrievalService` beyond `PlanCbrCase` (engine, S/Low)
2. **#672** — Surface per-feature similarity breakdown in `RetrievedExperience` (engine, S/Med)
3. **#694** — Replace flat `List<TaskNode<T>>` with `ExecutionPlan<T>` DAG (blocks, L/High)

Implementation order: #704 → #672 → #694. Each committed separately.

---

## #704 — CbrRetrievalService Generalization

### Problem

`CbrRetrievalService.retrieve()` hardcodes `PlanCbrCase.class` at the `retrieveSimilar()` call and couples `mapScoredCase()` to `PlanCbrCase`-specific fields (`planTrace()`). The underlying store (`CbrCaseMemoryStore.retrieveSimilar(query, Class<C>)`) is already generic — the bottleneck is solely the service layer.

IoT and other consumers bypass the service entirely, duplicating feature extraction, domain resolution, and caching logic.

### Design

**`CbrConfig`** gains `String cbrType()` — nullable, defaults to `"plan"` when null. Lives in `api/model/cbr/` (string-only, no class reference). YAML: `cbr: { cbrType: plan }`.

**`cbrType` vs `caseType` distinction:** `CbrConfig` already has `caseType()` — a query filter that scopes retrieval to cases stored under a specific type label (passed to `CbrQuery` to filter which stored cases to search). The new `cbrType()` serves a different purpose: it identifies which `CbrCase` Java implementation class to use for deserialization, matching the `CbrCase.cbrType()` discriminator on stored cases. A deployment may have `caseType = "medical"` (scope to medical domain cases) while `cbrType = "plan"` (deserialize as `PlanCbrCase`).

**`CbrRetrievalService`** gains a generic overload:

```java
// Existing — reads cbrType from config
public Uni<List<RetrievedExperience>> retrieve(
    CaseDefinition definition, CaseInstance instance)

// New — explicit case type for consumer usage
public <C extends CbrCase> Uni<List<RetrievedExperience>> retrieve(
    CaseDefinition definition, CaseInstance instance, Class<C> caseClass)
```

**Case type resolution:** static map in `CbrRetrievalService`:
- `"plan"` → `PlanCbrCase.class`
- `"feature-vector"` → `FeatureVectorCbrCase.class`
- `"textual"` → `TextualCbrCase.class`

Unknown `cbrType` values (not in the static map and no matching CDI registration) throw `IllegalStateException("Unknown cbrType: " + cbrType)` at resolution time in `retrieve()`. This is a configuration error — fail-fast with a clear message rather than a downstream `NullPointerException`.

Extensible via `CbrCaseTypeRegistration` CDI bean:

```java
public interface CbrCaseTypeRegistration {
    String cbrType();
    Class<? extends CbrCase> caseClass();
}
```

CDI discovery: `@Inject @All Instance<CbrCaseTypeRegistration> registrations` in `CbrRetrievalService`. At construction, registrations are merged into the static type map. Duplicate `cbrType` keys across registrations cause `IllegalStateException` at construction — fail-fast, no silent override. Built-in mappings are added first; a single registration may override a built-in key, but two registrations claiming the same key is an error.

**Generic mapping** uses `CbrCase` interface for universal fields, `instanceof` for type-specific:

```java
private <C extends CbrCase> RetrievedExperience mapScoredCase(ScoredCbrCase<C> scored) {
    CbrCase c = scored.cbrCase();
    List<ExperiencePlanStep> trace = (c instanceof PlanCbrCase plan)
        ? mapPlanTrace(plan.planTrace()) : List.of();
    return new RetrievedExperience(
        c.problem(), c.solution(), c.outcome(), c.confidence(),
        scored.score(), c.features(), trace,
        scored.featureSimilarities());
}
```

`planTrace` is empty (`List.of()`) for non-plan case types. This is intentionally correct — non-plan types have no plan trace data, and routing strategies that don't use plan traces simply ignore the field. All other `RetrievedExperience` fields (`problem`, `solution`, `outcome`, `confidence`, `similarityScore`, `features`) are universal across all `CbrCase` implementations. If future case types require type-specific data beyond the common fields, sealed `RetrievedExperience` subtypes can be introduced at that point.

**Callers unchanged** — `CaseContextChangedEventHandler` and `DefaultWorkOrchestrator` still call the 2-arg overload.

### Files Changed

| File | Change |
|------|--------|
| `api/.../cbr/CbrConfig.java` | Add `cbrType()` field |
| `api/.../cbr/CbrCaseTypeRegistration.java` | New CDI marker interface |
| `runtime/.../CbrRetrievalService.java` | Generic overload, generic mapping, type resolution |
| `runtime/.../CbrRetrievalServiceTest.java` | Tests for generic retrieval path |
| `runtime/.../CaseDefinitionYamlMapper.java` | Parse `cbrType` from YAML |

---

## #672 — Feature-Level Similarity Breakdown

### Problem

`CbrSimilarityScorer.score()` computes per-feature local similarities in its weighted-sum loop, then discards them. Only the aggregate score survives to `RetrievedExperience`. Routing strategies can't inspect which features drove a match.

### Design

**`CbrSimilarityScorer`** — new `scoreDetailed()` returning a breakdown record:

```java
public record SimilarityBreakdown(double score, Map<String, Double> featureSimilarities) {
    public SimilarityBreakdown {
        featureSimilarities = Map.copyOf(featureSimilarities);
    }
}

public static SimilarityBreakdown scoreDetailed(
    Map<String, Object> queryFeatures, Map<String, Object> caseFeatures,
    Map<String, Double> weights, CbrFeatureSchema schema,
    Map<String, LocalSimilarityFunction> overrides)
```

Existing `score()` delegates to `scoreDetailed().score()`. The loop captures each feature's weighted contribution: `featureSimilarities.put(featureName, weight * localSim / totalWeight)` (normalized so values sum to overall score).

**`ScoredCbrCase<C>`** — gains `Map<String, Double> featureSimilarities`:

```java
public record ScoredCbrCase<C extends CbrCase>(
    C cbrCase, double score, boolean reranked,
    Map<String, Double> featureSimilarities)
```

Existing 2-arg and 3-arg constructors delegate with `Map.of()`. Field is never null.

**Reranking preservation:** `ScoredCbrCase.withReranked()` carries `featureSimilarities` through:

```java
public ScoredCbrCase<C> withReranked() {
    return new ScoredCbrCase<>(cbrCase, score, true, featureSimilarities);
}
```

`RerankingCbrCaseMemoryStore` preserves the original feature similarities when constructing the reranked result:

```java
new ScoredCbrCase<C>(original.cbrCase(), sigmoidScore, false, original.featureSimilarities()).withReranked()
```

After reranking, `featureSimilarities` values still describe the per-feature breakdown from the original feature-based scoring. They no longer sum to `score` (which is now the sigmoid-transformed cross-encoder score). This is intentional — the feature breakdown provides diagnostic value independent of the reranked score.

**`InMemoryCbrCaseMemoryStore`** — calls `scoreDetailed()`, passes `breakdown.featureSimilarities()` to `ScoredCbrCase`.

**`QdrantCbrCaseMemoryStore`** — behavior varies by retrieval mode:
- `FEATURE_ONLY`: client-side `CbrSimilarityScorer.scoreDetailed()` — populate `featureSimilarities` from the full breakdown
- `SEMANTIC_ONLY`: pure vector retrieval — `Map.of()` (no feature scoring occurs)
- `HYBRID`: both vector and feature scoring via `ScoreFusion` — populate `featureSimilarities` from the raw `scoreDetailed()` breakdown (same values as FEATURE_ONLY). The values do NOT sum to any component of the fused score because `ScoreFusion` applies non-linear transformations (min-max normalization in convex combination, rank-based in RRF) that destroy the linear relationship. The breakdown is diagnostic — it shows which features contributed to the feature-based similarity, independent of the fusion result

**`RetrievedExperience`** — gains `Map<String, Double> featureSimilarities`:

```java
public record RetrievedExperience(
    String problem, String solution, String outcome, Double confidence,
    double similarityScore, Map<String, Object> features,
    List<ExperiencePlanStep> planTrace,
    Map<String, Double> featureSimilarities)
```

Compact constructor: `featureSimilarities = featureSimilarities != null ? Map.copyOf(featureSimilarities) : Map.of();`

**`CbrRetrievalService.mapScoredCase()`** — passes `scored.featureSimilarities()` through.

### Invariant

`featureSimilarities` values are weighted per-feature contributions from `CbrSimilarityScorer.scoreDetailed()`. For `FEATURE_ONLY` retrieval (in-memory store and Qdrant FEATURE_ONLY mode) the values sum to `similarityScore`. For `HYBRID` retrieval, the values represent the raw feature-based breakdown and do NOT sum to the fused score — `ScoreFusion` applies non-linear transformations (min-max normalization, reciprocal rank) that break linear decomposition. For `SEMANTIC_ONLY` retrieval, the map is empty. After reranking, the feature breakdown is preserved but does not sum to the reranked score. In all non-FEATURE_ONLY cases, `featureSimilarities` serves a diagnostic purpose — it shows which features drove the feature-based similarity, independent of the final score.

### Files Changed

| File | Change |
|------|--------|
| `casehub-neocortex-memory-api/.../CbrSimilarityScorer.java` | Add `SimilarityBreakdown`, `scoreDetailed()` |
| `casehub-neocortex-memory-api/.../ScoredCbrCase.java` | Add `featureSimilarities` field, update `withReranked()` |
| `casehub-neocortex-memory-cbr-inmem/.../inmem/InMemoryCbrCaseMemoryStore.java` | Use `scoreDetailed()` |
| `casehub-neocortex-memory-qdrant/.../QdrantCbrCaseMemoryStore.java` | Populate per retrieval mode |
| `casehub-neocortex-memory-cbr-crossencoder/.../RerankingCbrCaseMemoryStore.java` | Preserve `original.featureSimilarities()` through reranking |
| `api/.../routing/RetrievedExperience.java` | Add `featureSimilarities` field |
| `runtime/.../CbrRetrievalService.java` | Pass through feature similarities |
| Tests across all affected modules |

---

## #694 — DAG Plan Structure (ExecutionPlan)

### Problem

`DecompositionStrategy<T>` returns `List<TaskNode<T>>` — a flat ordered list. No way to express task dependencies or parallel execution. "A and B can run in parallel, C depends on both" is inexpressible.

### Repository

All changes in `casehub-blocks` (`/Users/mdproctor/claude/casehub/blocks`). Separate branch `issue-694-dag-plan-structure` in blocks.

### Core Type — ExecutionPlan

```java
package io.casehub.blocks.agentic.plan;

public record ExecutionPlan<T>(
    Map<String, ExecutionNode<T>> nodes
) {
    public enum JoinType { ALL_OF, ANY_OF }

    public record ExecutionNode<T>(
        String id,
        TaskNode.LeafTask<T> task,
        Set<String> dependsOn,
        JoinType joinType
    ) {
        public ExecutionNode {
            Objects.requireNonNull(id);
            Objects.requireNonNull(task);
            dependsOn = dependsOn != null ? Set.copyOf(dependsOn) : Set.of();
            if (joinType == null) joinType = JoinType.ALL_OF;
        }
    }

    public ExecutionPlan {
        Objects.requireNonNull(nodes);
        if (nodes.isEmpty())
            throw new IllegalArgumentException("nodes must not be empty");
        nodes = Map.copyOf(nodes);
        // Validate references, detect cycles, ensure entry nodes exist
    }

    /** Nodes with no predecessors — computed from the graph, not stored. */
    public Set<String> entryNodeIds() {
        return nodes.values().stream()
            .filter(n -> n.dependsOn().isEmpty())
            .map(ExecutionNode::id)
            .collect(Collectors.toUnmodifiableSet());
    }

    /** Nodes that no other node depends on — the plan's terminal nodes. */
    public Set<String> exitNodeIds() {
        Set<String> referenced = nodes.values().stream()
            .flatMap(n -> n.dependsOn().stream())
            .collect(Collectors.toSet());
        return nodes.keySet().stream()
            .filter(id -> !referenced.contains(id))
            .collect(Collectors.toUnmodifiableSet());
    }
}
```

**JoinType semantics:**
- `ALL_OF` — node fires when every predecessor completes (conjunction, fork-join)
- `ANY_OF` — node fires when at least one predecessor succeeds (disjunction, structural alternatives)

**Validation** (compact constructor):
1. Non-empty nodes map
2. All `dependsOn` references resolve to existing node IDs
3. No cycles (topological sort)
4. At least one entry node (nodes with empty `dependsOn`)

### Factory Methods

```java
ExecutionPlan.sequence(List<LeafTask<T>>)              // A → B → C
ExecutionPlan.parallel(List<LeafTask<T>>)              // {A, B, C}
ExecutionPlan.singleton(LeafTask<T>)                   // single node
ExecutionPlan.fromList(List<LeafTask<T>>)              // migration: flat list → sequential
ExecutionPlan.sequentialMerge(List<ExecutionPlan<T>>)   // sub-plans chained sequentially
```

Node IDs auto-generated: `"node-0"`, `"node-1"`, etc. `sequentialMerge` re-prefixes node IDs per sub-plan to avoid collisions (`"sub0-node-0"`, `"sub1-node-0"`, etc.) and connects each sub-plan's exit nodes as predecessors of the next sub-plan's entry nodes.

### DecompositionStrategy Change

```java
// Before
Uni<List<TaskNode<T>>> decompose(TaskNode<T> compound, DecompositionContext<T> context);

// After
Uni<ExecutionPlan<T>> decompose(TaskNode<T> compound, DecompositionContext<T> context);
```

Strategy updates:
- `IdentityDecomposition` → for `LeafTask` inputs, returns `ExecutionPlan.singleton(leaf)`. For `CompoundTask` inputs, throws `UnsupportedOperationException` — IdentityDecomposition is a no-op placeholder used by non-HTN builders (LoopBuilder, SupervisorBuilder, SequenceBuilder, ParallelBuilder, ConditionalBuilder, VotingBuilder, DebateBuilder) that never invoke decomposition at runtime. The throw guards against accidental misuse.
- `StaticDecomposition` → delegates to matching method strategy; returns the delegate's `ExecutionPlan<T>` directly (it does not produce a flat list — it selects a method guard and forwards to `method.strategy().decompose()`)
- `LlmDecomposition` → wraps as `ExecutionPlan.sequence()` (DAG-aware LLM decomposition is future)

### Pattern Builder Changes

- `HtnBuilder.flatten()` → returns `ExecutionPlan<T>`. Recursive decomposition collects each child's `ExecutionPlan` and merges them via `ExecutionPlan.sequentialMerge()` — each sub-plan's exit nodes become predecessors of the next sub-plan's entry nodes, preserving current sequential behavior.
- `SequenceBuilder` → internal plan via `ExecutionPlan.sequence()`
- `ParallelBuilder` → internal plan via `ExecutionPlan.parallel()`
- `AbstractPatternBuilder` — no new public method; builders consume the plan internally

### Sub-plan Merge Semantics

When `HtnBuilder.flatten()` recursively decomposes a compound task with multiple children, each child produces an `ExecutionPlan<T>`. These are merged **sequentially** via `ExecutionPlan.sequentialMerge()`:

Given children [A, B, C] decomposing to sub-plans:
- Sub-plan A: `a1 → a2`
- Sub-plan B: `b1`
- Sub-plan C: `c1 → c2`

Sequential merge result: `a1 → a2 → b1 → c1 → c2`

This preserves current `HtnBuilder.flatten()` behavior (flat list concatenation by decomposition order). The `ExecutionPlan` structure enables future parallel merge semantics (#695) without changing the type — only the merge strategy changes.

### Scope Boundary

`HtnBuilder` topologically sorts the plan into a flat list of candidates for `ExecutionModel.candidateSupplier()`, maintaining backward-compatible sequential execution. The execution driver (`OrchestratedDriver`) is unchanged — it receives candidates from the model, not plans. DAG-aware parallel execution is #695.

### Relationship to #700 (Shared Orchestration Types)

The shared-orchestration-types spec (#700) identifies #694 as the natural vehicle for a shared Plan type and defers Plan design to this issue. `ExecutionPlan<T>` is currently parameterized on `TaskNode.LeafTask<T>` — a blocks-internal sealed type — making it blocks-specific by construction.

The promotion path to a shared type depends on blocks#51 (`LeafTask implements TaskDescriptor`). Once `LeafTask` implements `TaskDescriptor`, `ExecutionPlan` can be reparameterized on `TaskDescriptor` and promoted to `engine-api`. Until then, placing it in `engine-api` would create a dependency from engine-api on blocks types, which inverts the module hierarchy. The blocks-internal location is architecturally correct for now — not a backward-compatibility deferral, but a staging gate on a real dependency.

### Package

`io.casehub.blocks.agentic.plan` — new package for `ExecutionPlan`, `ExecutionNode`, `JoinType`.

### Files Changed

| File | Change |
|------|--------|
| `blocks/.../plan/ExecutionPlan.java` | New — core DAG type |
| `blocks/.../decomposition/DecompositionStrategy.java` | Return type → `ExecutionPlan<T>` |
| `blocks/.../decomposition/IdentityDecomposition.java` | `singleton(leaf)` for leaf inputs; throw for compounds |
| `blocks/.../decomposition/StaticDecomposition.java` | Forward delegate's `ExecutionPlan<T>` |
| `blocks/.../decomposition/LlmDecomposition.java` | Return `ExecutionPlan.sequence()` |
| `blocks/.../pattern/HtnBuilder.java` | `flatten()` returns `ExecutionPlan`, topological sort for candidates |
| `blocks/.../pattern/SequenceBuilder.java` | Use plan internally |
| `blocks/.../pattern/ParallelBuilder.java` | Use plan internally |
| Tests for all of the above |

---

## Cross-Cutting

### Implementation Order

1. #704 (engine) — CBR generalization
2. #672 (engine) — feature similarity breakdown
3. #694 (blocks) — DAG plan structure

### Branch Strategy

- Engine: `issue-704-cbr-generalize-dag-plan` (current branch, #704 and #672)
- Blocks: `issue-694-dag-plan-structure` (created when #694 implementation begins)

No cross-repo dependency edge — #694 is structurally independent. Engine changes (#704, #672) can land first; blocks changes (#694) can land in any order relative to engine.

### Deferred Items

- **#695** — DAG-aware parallel execution (depends on #694)
- **DAG-aware LLM decomposition** — future enhancement to `LlmDecomposition` returning non-sequential plans (within #695 scope)
- **blocks#51** — `LeafTask implements TaskDescriptor` (prerequisite for promoting `ExecutionPlan` to shared type)
