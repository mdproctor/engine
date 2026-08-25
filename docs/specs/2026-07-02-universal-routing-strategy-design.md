# Universal Pluggable Routing Strategy Architecture — engine#634

**Date:** 2026-07-02
**Issue:** engine#634
**Status:** Design
**Depends on:** —
**Blocked by:** —
**Blocks:** blocks#30 (AI routing strategy implementations)

---

## Problem

Routing decisions across the casehub platform — who receives a task, which worker gets a case, which capability is selected, who reviews a consequential action — are implemented ad-hoc. Each concern has its own mechanism, none interoperable:

- `candidateGroups`/`candidateUsers` in humanTask bindings: sealed `ListEvaluator` type (static list or JQ expression only — no extension point)
- Worker routing: `AgentRoutingStrategy` SPI (CDI `@Priority` selection)
- SLA escalation routing: `SlaBreachPolicy` SPI in casehub-work
- Capability health routing: `CapabilityHealth` probe — demotion only, not selection
- Binding selection: `ImplementationRoutingStrategy` SPI (single `@DefaultBean`)
- Worker assignment: `WorkerSelectionStrategy` SPI (CDI `@Alternative` > config string)
- Multi-instance assignment: `InstanceAssignmentStrategy` (CDI `@Named`)
- Trust policy: `TrustRoutingPolicyProvider` (CDI `@Alternative @Priority`)
- Planning order: `PlanningStrategy` (named `id()` lookup)

A harness author cannot plug in a Drools-based candidateGroups resolver, share routing logic across human-task and worker routing, or use one routing convention across all routing points. The candidate pool construction (`AgentCandidateFactory`) is hardcoded with no extension point. Risk gate routing (`GateRequired.candidateGroups`) uses `List<String>` with no dynamic evaluation.

The full audit of all ~64 routing-related mechanisms across 5 repos is documented in `docs/specs/2026-07-02-universal-routing-strategy-audit.md`.

---

## Design Decision: Family of Domain-Specific SPIs with Shared Convention

A single generic `RoutingStrategy<I, O>` was considered and rejected. The input shapes (case context, breach context, binding list), output shapes (sealed assignment types, list of groups, deadline computation), and cardinalities (select one, select many, produce the candidate set) are fundamentally different across routing concerns. A generic erases these differences with no benefit — you cannot write shared implementations because the domain semantics differ, and you cannot compose across domains because the types are incompatible.

The shared value is in the **convention** (naming, selection, CDI registration) and the **resolution framework** (how strategies are discovered and selected), not in type unification.

Shared decision engines (Drools, etc.) work through CDI injection — a Drools module provides separate CDI beans implementing different domain-specific interfaces, sharing a `KieSession`. The Java interfaces differ because inputs/outputs differ; the rules are shared.

---

## 1. Platform Foundation — `io.casehub.platform.api.routing`

### NamedStrategy

Marker interface in `casehub-platform-api`. Pure Java, no CDI annotations:

```java
package io.casehub.platform.api.routing;

public interface NamedStrategy {
    String id();
}
```

The `id()` is the stable key used in YAML and config. Strategies are discovered by their domain-specific sub-interface type, not by the marker. The marker enables introspection (list all strategies in a deployment) and validates uniqueness per domain type.

### StrategyResolver

CDI bean in `casehub-platform` (runtime module). Same placement pattern as `PreferenceProvider` — SPI interface in platform-api, implementation in platform runtime:

```java
package io.casehub.platform.api.routing;

public interface StrategyResolver {
    <T extends NamedStrategy> T resolve(Class<T> type, String id);
    <T extends NamedStrategy> Optional<T> find(Class<T> type, String id);
    <T extends NamedStrategy> T defaultStrategy(Class<T> type);
    <T extends NamedStrategy> List<T> available(Class<T> type);
}
```

**Resolution:** Look up by `(type, id)`. If `id` is null, return the `@DefaultBean` instance. If no bean with that `id` exists, throw — fail loud, not silent fallback to a different strategy.

**Implementation (in `casehub-platform` runtime module):** `@ApplicationScoped` CDI bean. Indexes strategies by `(type, id)` at startup. Validates uniqueness — two beans of the same type with the same `id()` is a deployment error (fail at startup, not at first use).

---

## 2. New SPIs

### CandidateSetStrategy

Replaces the sealed `ListEvaluator` type. Lives in `casehub-engine-api`:

```java
package io.casehub.api.spi.routing;

public interface CandidateSetStrategy extends NamedStrategy {
    Uni<Set<String>> evaluate(CandidateSetContext context);
}

public record CandidateSetContext(
    JsonNode caseContext,
    Map<String, Object> config
) {
    public CandidateSetContext(JsonNode caseContext) {
        this(caseContext, Map.of());
    }
}
```

Returns `Uni<Set<String>>` per protocol PP-20260529-9f9627 — consistent with `AgentRoutingStrategy`, `ImplementationRoutingStrategy`, and `PlanningStrategy`. Custom strategies calling external services (REST, LDAP, Drools server) return reactive chains; built-in strategies wrap synchronous results with `Uni.createFrom().item()`.

The `config` parameter on `CandidateSetContext` carries the YAML `config:` block for named strategies. It is per-binding (defined in the case definition YAML), fixed for the lifetime of that case definition, and passed at evaluation time — not baked into the CDI singleton.

**Named `CandidateSetStrategy` for both `candidateGroups` and `candidateUsers`.** Both fields resolve to `Set<String>` with identical evaluation semantics. The semantic distinction (group names vs user IDs) comes from the field on `HumanTaskTarget`, not from the strategy type — same pattern as the existing `ListEvaluator` which already serves both fields.

**Two creation modes:**

1. **Value objects** (YAML mapper / fluent DSL) — `StaticSetStrategy` and `ExpressionSetStrategy` implement `CandidateSetStrategy` but are NOT CDI beans. They are constructed directly by the YAML mapper or builder API. Their `id()` serves identification and serialization, not StrategyResolver lookup.

2. **Named CDI beans** (StrategyResolver) — custom strategy implementations registered as `@ApplicationScoped` CDI beans with a unique `id()`. Resolved via `StrategyResolver.resolve(CandidateSetStrategy.class, id)`. Config from the YAML `config:` block is passed at evaluation time via `CandidateSetContext.config()`.

**Built-in implementations (in runtime):**

| Class | id | Mode | Description |
|-------|----|------|-------------|
| `StaticSetStrategy` | `"static"` | Value object | Wraps a fixed `Set<String>`. Factory: `StaticSetStrategy.of("compliance-team", "legal")` |
| `ExpressionSetStrategy` | `"expression"` | Value object | Holds a baked-in `ExpressionEvaluator` (created via `ExpressionEngineRegistry.create(expression, lang)` at YAML parse time). Evaluates via `ExpressionEngineRegistry.transform()` at dispatch time. Works with any registered `ExpressionEngine` (JQ, Drools, SpEL, etc.) |

**YAML surface (backward compatible):**

```yaml
# Static list — unchanged syntax
candidateGroups: [irb-committee]

# Expression — unchanged syntax, defaults to JQ via ExpressionEngine SPI
candidateGroups: ".trial.siteCommittee"

# Explicit expression language
candidateGroups:
  expression: ".trial.siteCommittee"
  lang: jq

# Different expression engine
candidateGroups:
  expression: "some-drools-expression"
  lang: drools

# Named strategy (not expression-based)
candidateGroups:
  strategy: custom-resolver
  config:
    session: irb-routing
```

The YAML mapper detects the shape and produces a `CandidateSetSpec`:
- Array → `CandidateSetSpec.Inline(StaticSetStrategy.of(...))` (value object)
- String starting with `.` → `CandidateSetSpec.Inline(ExpressionSetStrategy(...))` (value object — mapper calls `ExpressionEngineRegistry.create(expr, "jq")` and bakes the evaluator in)
- Object with `expression:` key → `CandidateSetSpec.Inline(ExpressionSetStrategy(...))` (value object — mapper calls `ExpressionEngineRegistry.create(expr, lang)`)
- Object with `strategy:` key → `CandidateSetSpec.Named(id, config)` (deferred reference — NO CDI lookup at parse time; resolved at dispatch time via `StrategyResolver`)

Only the `strategy:` path defers to StrategyResolver. The other three paths produce value objects wrapped in `CandidateSetSpec.Inline`.

**Compile-time safety trade-off:** The `strategy:` path and `candidateGroups(String)` builder overload are stringly-typed — no compile-time validation that the named strategy exists. This is an intentional trade-off for extensibility. The sealed `ListEvaluator` gave exhaustive compile-time matching but was closed to extension. Startup validation (§4 resolution semantics: "Unknown ID → startup failure") ensures mismatches fail at deployment, not at first use.

**Expression SPI integration:** `ExpressionSetStrategy` is not JQ-specific. It holds a pre-created `ExpressionEvaluator` and delegates to `ExpressionEngineRegistry.transform()` at evaluation time. The `ExpressionEvaluator.type()` carries the language identifier for dispatch. JQ is the default when no `lang` is specified — backward compatible with existing YAML. New expression engines (Drools, SpEL) are plugged in by registering an `ExpressionEngine` CDI bean, not by adding a new `CandidateSetStrategy` implementation.

Two orthogonal extension points:
- `ExpressionEngine` SPI — language pluggability (how expressions are evaluated)
- `CandidateSetStrategy` SPI — evaluation-model pluggability (expression vs custom strategy)

### CandidateSetSpec — binding-level storage type

`HumanTaskTarget` stores `CandidateSetSpec`, not `CandidateSetStrategy` directly. This keeps `HumanTaskTarget` a pure data model — no CDI proxies, serializable, usable in non-CDI contexts (tests, tooling).

```java
package io.casehub.api.spi.routing;

public sealed interface CandidateSetSpec {
    record Inline(CandidateSetStrategy strategy) implements CandidateSetSpec {}
    record Named(String strategyId, Map<String, Object> config) implements CandidateSetSpec {}
}
```

Two variants:

- **`Inline`** — wraps a value-object strategy (`StaticSetStrategy`, `ExpressionSetStrategy`). Ready to evaluate directly. Created by the YAML mapper for array, string, and expression shapes, and by the fluent DSL for typed strategy arguments.

- **`Named`** — a deferred reference to a CDI bean, carrying the strategy ID and config map. Resolved via `StrategyResolver` at dispatch time. Created by the YAML mapper for the `strategy:` shape, and by the fluent DSL for `candidateGroups(String)`.

**Resolution at dispatch time** (in `CaseContextChangedEventHandler.publishHumanTaskSchedule()`):

```java
CandidateSetSpec spec = target.candidateGroups();
Uni<Set<String>> result = switch (spec) {
    case CandidateSetSpec.Inline inline ->
        inline.strategy().evaluate(new CandidateSetContext(caseContext));
    case CandidateSetSpec.Named named -> {
        CandidateSetStrategy resolved = strategyResolver.resolve(
            CandidateSetStrategy.class, named.strategyId());
        yield resolved.evaluate(new CandidateSetContext(caseContext, named.config()));
    }
};
```

This is consistent with case-level resolution (§9): case-level stores String IDs on `CaseDefinition`, binding-level stores `CandidateSetSpec.Named` on `HumanTaskTarget`. Both resolve at dispatch time via `StrategyResolver`. Value objects (`Inline`) bypass resolution entirely — they are pure data.

**Builder API:**

```java
// Value object — Inline
.candidateGroups(StaticSetStrategy.of("irb-committee"))  // stores CandidateSetSpec.Inline(strategy)
.candidateGroups(ExpressionSetStrategy.jq(".trial.site")) // stores CandidateSetSpec.Inline(strategy)

// Named reference — Named
.candidateGroups("custom-resolver")                       // stores CandidateSetSpec.Named(id, Map.of())
.candidateGroups("custom-resolver", Map.of("session", "irb")) // stores CandidateSetSpec.Named(id, config)
```

### CandidateMatchingStrategy

Replaces the hardcoded `AgentCandidateFactory` two-tier matching algorithm. Lives in `casehub-engine-api`:

```java
package io.casehub.api.spi.routing;

public interface CandidateMatchingStrategy extends NamedStrategy {
    Uni<List<Worker>> match(CandidateMatchingContext context);
}

public record CandidateMatchingContext(
    String capabilityName,
    List<Worker> workers,
    CaseDefinition caseDefinition
) {}
```

Returns `Uni<List<Worker>>` per protocol PP-20260529-9f9627 — consistent with all other routing SPIs in the platform. Custom matching strategies that consult external capability registries, ML services, or remote vocabulary servers return reactive chains; built-in strategies wrap synchronous results with `Uni.createFrom().item()`.

The context carries only what matching needs: which capability is requested, which workers are available, and the case definition (for per-case matching configuration). Health probing (`CapabilityHealth`), running-job counts (`WorkerExecutionManager`), and `AgentCandidate` construction are orchestration concerns that remain in `AgentCandidateFactory` — they happen AFTER matching returns.

**Built-in implementations (in runtime):**

| Class | id | Description |
|-------|----|-------------|
| `ExactMatchStrategy` | `"exact"` | `worker.capabilityNames().contains(capabilityName)`. Fast path, no vocabulary dependency. |
| `SubsumptionMatchStrategy` | `"subsumption"` | Current two-tier algorithm: exact match first, then `VocabularyRegistry`-grounded subsumption fallback. `@DefaultBean` — preserves existing behaviour. Injects `VocabularyRegistry` via CDI. |

**`AgentCandidateFactory` orchestration pipeline** (after this change):

1. Resolve `CandidateMatchingStrategy` via `StrategyResolver` (per case definition)
2. Call `strategy.match(context)` → matched `List<Worker>`
3. For each matched worker: look up `AgentDescriptor`, probe `CapabilityHealth` — exclude `Unavailable`, map status to `AgentHealth`
4. For each healthy worker: get running-job count from `WorkerExecutionManager`
5. Construct `AgentCandidate` records

Matching determines "does this worker support this capability?" — a pure capability compatibility question. Health, load, and candidate construction are the factory's orchestration layer.

**YAML surface:**

```yaml
candidateMatching: exact
# or
candidateMatching: subsumption   # default when absent
```

---

## 3. Retrofit Existing SPIs

Seven existing SPIs extend `NamedStrategy` and gain `id()`. Selection model standardised to named resolution via `StrategyResolver`.

### Engine SPIs

**AgentRoutingStrategy:**
- Current: CDI `@Priority` — highest wins globally
- Change: `extends NamedStrategy`. Selectable per case definition.
- IDs: `LeastLoadedAgentStrategy` → `"least-loaded"`, `TrustWeightedAgentStrategy` → `"trust-weighted"`, `SemanticAgentRoutingStrategy` → `"semantic"`, `DispositionAwareRoutingStrategy` (quarkmind) → `"disposition"`

**ImplementationRoutingStrategy:**
- Current: single `@DefaultBean`
- Change: `extends NamedStrategy`. Selectable per case definition.
- IDs: `NoOpImplementationRoutingStrategy` → `"run-all"`, `TrustWeightedImplementationRoutingStrategy` → `"trust-weighted"`

**PlanningStrategy:**
- Current: already has named `getId()` lookup
- Change: `extends NamedStrategy`, rename `getId()` → `id()` for convention consistency. Resolve via `StrategyResolver` instead of direct `Instance<>` iteration.
- IDs: `DefaultPlanningStrategy` → `"default"`, `SequentialPlanningStrategy` → `"sequential"` (unchanged)

**TrustRoutingPolicyProvider:**
- Current: CDI `@Alternative @Priority` with 7 domain-repo implementations
- Change: `extends NamedStrategy`. Selectable per capability.
- IDs: `DefaultTrustRoutingPolicyProvider` → `"default"`, `DevtownTrustRoutingPolicyProvider` → `"devtown"`, `AmlTrustRoutingPolicyProvider` → `"aml"`, `ClinicalTrustRoutingPolicyProvider` → `"clinical"`, `LifeTrustRoutingPolicyProvider` → `"life"`, `QuarkMindTrustRoutingPolicyProvider` → `"quarkmind"`, `DeploymentTrustRoutingPolicyProvider` → `"deployment"`

### Work SPIs

**WorkerSelectionStrategy:**
- Current: CDI `@Alternative` > config string fallback
- Change: `extends NamedStrategy`. Config string becomes the `id()`.
- IDs: `LeastLoadedStrategy` → `"least-loaded"`, `ClaimFirstStrategy` → `"claim-first"`, `RoundRobinStrategy` → `"round-robin"`, `SemanticWorkerSelectionStrategy` → `"semantic"`

**InstanceAssignmentStrategy:**
- Current: CDI `@Named` qualifier
- Change: `extends NamedStrategy`, `@Named` replaced by `id()`. Resolve via `StrategyResolver`.
- IDs: `PoolAssignmentStrategy` → `"pool"`, `ExplicitListAssignmentStrategy` → `"explicit"`, `RoundRobinAssignmentStrategy` → `"round-robin"`, `CompositeInstanceAssignmentStrategy` → `"composite"`

**ClaimSlaPolicy:**
- Current: config property switch among 4 built-ins
- Change: `extends NamedStrategy`.
- IDs: `ContinuationPolicy` → `"continuation"`, `FreshClockPolicy` → `"fresh-clock"`, `SingleBudgetPolicy` → `"single-budget"`, `PhaseClockPolicy` → `"phase-clock"`

### Consistency-only (not load-bearing)

**WorkerExecutionRoutingStrategy:** Add `extends NamedStrategy`, `FirstSupportedRoutingStrategy` → `id()="first-supported"`. No per-case selectability — included for convention alignment only. Single implementation, no harness author needs to select alternatives.

---

## 4. YAML Surface and Resolution Semantics

### Case-level strategy selection

```yaml
name: clinical-trial
namespace: casehubio/clinical

agentRouting: trust-weighted
implementationRouting: trust-weighted
planningStrategy: sequential
candidateMatching: subsumption

capabilities:
  - name: irb-review
    trustPolicy: clinical
  - name: data-entry
```

Each case-level strategy key is optional. When absent, `StrategyResolver.defaultStrategy(type)` returns the `@DefaultBean` instance — existing behaviour preserved.

### Binding-level strategy selection

```yaml
bindings:
  - name: irb-review
    humanTask:
      candidateGroups: [irb-committee]
      candidateUsers: ".trial.assignedReviewers"

  - name: dynamic-review
    humanTask:
      candidateGroups:
        strategy: custom-resolver
        config:
          session: irb-routing
```

### Per-capability trust policy

```yaml
capabilities:
  - name: irb-review
    trustPolicy: clinical
```

Resolves `TrustRoutingPolicyProvider` by `id` for that capability. When absent, default policy applies.

### Fluent DSL equivalent

```java
CaseDefinition.builder()
    .agentRouting("trust-weighted")
    .binding("irb-review", Binding.builder()
        .humanTask(HumanTaskTarget.builder()
            .candidateGroups(StaticSetStrategy.of("irb-committee"))
            .candidateUsers(ExpressionSetStrategy.jq(".trial.assignedReviewers"))
            .build())
        .build())
    .binding("dynamic-review", Binding.builder()
        .humanTask(HumanTaskTarget.builder()
            .candidateGroups("custom-resolver")  // resolved by StrategyResolver at runtime
            .build())
        .build())
    .build();
```

Each `candidateGroups()` / `candidateUsers()` call sets a single `CandidateSetSpec` on the builder (last-wins, same as the existing `ListEvaluator` pattern). One spec per field per binding. Typed arguments produce `CandidateSetSpec.Inline`; String arguments produce `CandidateSetSpec.Named`.

### Resolution semantics

- YAML-specified ID → `StrategyResolver.resolve(type, id)` → exact match or throw
- No ID specified → `StrategyResolver.defaultStrategy(type)` → `@DefaultBean` instance
- Unknown ID → startup failure (not silent fallback)
- Duplicate IDs for the same type → startup failure

No CDI `@Priority` fallback chain. Existing `@Alternative @Priority` annotations on strategy implementations remain during migration — they control CDI resolution when no strategy is named. Naming via `id()` is the primary selection mechanism.

**@Priority deprecation:** `@Priority`-based strategy selection is deprecated as of this change. New strategy implementations must not use `@Alternative @Priority` for strategy selection — they must declare `id()` and be resolved via `StrategyResolver`. Existing `@Priority` annotations are removed as part of the consumer repo migration (§6). Once all consumers are migrated, the `@Priority` fallback path in `StrategyResolver` is removed. The garden protocol (§7) codifies this: "Selection models that bypass this convention (CDI `@Priority` override, `@Named` qualifier, config property switch) are not to be used for new routing strategies."

---

## 5. GateRequired.candidateGroups — CandidateSetStrategy Integration

`RiskDecision.GateRequired.candidateGroups` changes from `List<String>` to `CandidateSetStrategy`. This ensures the same dynamic evaluation available for humanTask routing is available for oversight gate routing.

**Before:**
```java
RiskDecision.GateRequired(reason, reversible, List.of("compliance-team"), expiresIn, scope)
```

**After:**
```java
RiskDecision.GateRequired(reason, reversible, StaticSetStrategy.of("compliance-team"), expiresIn, scope)
```

**Migration convenience:** `StaticSetStrategy.of(String...)` makes the change a one-line migration for all 6 consumer classifiers. No logic changes, no new dependencies — `StaticSetStrategy` is in engine-api alongside `CandidateSetStrategy`.

**Evaluation timing:** The classifier returns a `CandidateSetStrategy` (e.g., `StaticSetStrategy.of("compliance-team")` or a dynamic strategy). The engine evaluates the strategy in `WorkflowExecutionCompletedHandler.handleGate()`, where `CaseInstance` (and therefore case context) is available. The resolved `Set<String>` is passed in `ActionGateScheduleRequest` alongside the `GateRequired` record. `ActionGateWorkItemHandler` receives the already-resolved groups and creates the WorkItem — it does not need case context and does not evaluate the strategy itself.

---

## 6. Consumer Impact and Migration

### Engine — internal changes (no consumer impact)

- `AgentCandidateFactory` delegates matching to `CandidateMatchingStrategy`
- `CaseContextChangedEventHandler.publishHumanTaskSchedule()` uses `CandidateSetStrategy.evaluate()`
- `CaseContextChangedEventHandler.publishWorkerSchedule()` resolves `AgentRoutingStrategy` via `StrategyResolver`
- `PlanningStrategyLoopControl` resolves via `StrategyResolver`
- `CaseDefinitionYamlMapper` parses new YAML strategy syntax

### Engine-api — breaking SPI changes

- `AgentRoutingStrategy extends NamedStrategy` — implementations must add `id()`
- `ImplementationRoutingStrategy extends NamedStrategy` — implementations must add `id()`
- `TrustRoutingPolicyProvider extends NamedStrategy` — implementations must add `id()`
- `ListEvaluator` sealed type removed, replaced by `CandidateSetSpec` (sealed: `Inline(CandidateSetStrategy)` | `Named(strategyId, config)`)
- `RiskDecision.GateRequired.candidateGroups` changes from `List<String>` to `CandidateSetStrategy`

### Work-api — breaking SPI changes

- `WorkerSelectionStrategy extends NamedStrategy` — implementations must add `id()`
- `InstanceAssignmentStrategy extends NamedStrategy`, `@Named` replaced by `id()`
- `ClaimSlaPolicy extends NamedStrategy` — implementations must add `id()`

### Consumer repo migration (mechanical)

| Repo | What changes | Effort |
|------|-------------|--------|
| casehub-engine-ledger | `TrustWeightedAgentStrategy` adds `id()="trust-weighted"`, `TrustWeightedImplementationRoutingStrategy` adds `id()="trust-weighted"`, `DefaultTrustRoutingPolicyProvider` adds `id()="default"` | XS |
| casehub-engine-ai | `SemanticAgentRoutingStrategy` adds `id()="semantic"` | XS |
| casehub-devtown | `DevtownActionRiskClassifier` — `StaticSetStrategy.of(...)` for candidateGroups. `DevtownTrustRoutingPolicyProvider` adds `id()="devtown"` | XS |
| casehub-aml | Same pattern as devtown | XS |
| casehub-clinical | Same pattern as devtown | XS |
| casehub-life | Same pattern as devtown | XS |
| casehub-soc | `SocActionRiskClassifier` — `StaticSetStrategy.of(...)` | XS |
| casehub-iot | `IoTActionRiskClassifier` — `StaticSetStrategy.of(...)` | XS |
| quarkmind | `DispositionAwareRoutingStrategy` adds `id()="disposition"`, `QuarkMindTrustRoutingPolicyProvider` adds `id()="quarkmind"` | XS |
| casehub-ops | `DeploymentTrustRoutingPolicyProvider` adds `id()="deployment"` | XS |
| casehub-work (runtime) | 4 `WorkerSelectionStrategy` impls add `id()`. 4 `InstanceAssignmentStrategy` impls: `@Named` → `id()`. 4 `ClaimSlaPolicy` impls add `id()`. | S |
| casehub-blocks | Future — blocks#30. `LlmSelectedRouting` wrapper adds `id()="llm-selected"` | Deferred |

Every consumer change is the same pattern: add one `id()` method returning a string literal, or wrap a `List<String>` in `StaticSetStrategy.of(...)`. No logic changes, no behavioural changes.

---

## 7. Documentation and Protocol

### PLATFORM.md — Capability Ownership Table

New entry:

> **Routing Strategy Resolution** — `casehub-platform-api` (`io.casehub.platform.api.routing`)
>
> `NamedStrategy` marker interface and `StrategyResolver` CDI bean. All per-case-selectable routing strategies extend `NamedStrategy` and are resolved by `id` via `StrategyResolver`. Resolution order: YAML-specified ID → `@DefaultBean` fallback. Domain-specific strategy interfaces live in their owning module (`engine-api`, `work-api`); the shared convention lives in `platform-api`.

### PLATFORM.md — Step 4 Platform Consistency Rules

New entry:

> **Routing strategies:** Any SPI where a harness author selects among alternative implementations per case or per binding must extend `NamedStrategy` (platform-api), declare a stable `id()`, and ship a `@DefaultBean` no-op or sensible-default implementation. Resolve via `StrategyResolver`, never via direct `Instance<>` iteration or CDI `@Priority` override. YAML surface uses `strategyField: "strategy-id"` for simple selection, or `strategyField: { strategy: "id", config: {...} }` for parameterised selection.

### Garden Protocol — `routing-strategy-convention.md`

Scope: platform (all casehubio repos).

Rule: Per-case or per-binding selectable strategies extend `NamedStrategy`, declare `id()`, ship `@DefaultBean` default, resolve via `StrategyResolver`. Selection models that bypass this convention (CDI `@Priority` override, `@Named` qualifier, config property switch) are not to be used for new routing strategies.

Non-members: `ActionRiskClassifier` (chain composition), `@DefaultBean`-only SPIs (single-bean replacement), `ContextDiffStrategy` (deployment-level config), access control policies, data providers, delivery infrastructure.

---

## 8. Scope Boundaries

### In scope for engine#634

- `NamedStrategy` + `StrategyResolver` in casehub-platform-api / casehub-platform
- `CandidateSetStrategy` + `CandidateMatchingStrategy` in engine-api
- Retrofit 7 existing SPIs to extend `NamedStrategy`
- `GateRequired.candidateGroups` → `CandidateSetStrategy`
- YAML mapper changes for strategy selection syntax
- Engine-internal wiring changes (resolve via `StrategyResolver`)
- PLATFORM.md update
- Garden protocol

### Out of scope

- blocks#30 — AI routing strategy implementations (trust-weighted, LLM-selected, CBR-enriched). Depends on this issue landing first.
- engine#439 — dynamic title/scope/expiresIn. Related but independent. engine#439 makes humanTask binding fields (title, scope, expiresIn) dynamic via JQ expressions. Those fields use `ExpressionEvaluator` + `ExpressionEngineRegistry`, which is ALREADY an open extension point — new expression languages are added by registering an `ExpressionEngine` CDI bean. The sealed-type-extensibility argument that motivates replacing `ListEvaluator` with `CandidateSetStrategy` does not apply: `ExpressionEvaluator` is not sealed, and `ExpressionEngineRegistry` already dispatches to any registered language. If a custom non-expression strategy for title/scope is ever needed (unlikely — these are simple transformations, not routing decisions), it can be addressed independently.
- engine#636 — WorkerRuntime.spawnCase/awaitCase. Orthogonal.
- engine#637 — SequentialPlanningStrategy test fix. Orthogonal.
- `ActionRiskClassifier` chain composition model — different pattern (chain composition, most-restrictive-wins), unaffected.
- `@DefaultBean`-only SPIs — not per-case selectable, unaffected. Includes: `ExclusionPolicy`, `CapabilityHealth`, `SlaBreachPolicy` (single `@DefaultBean NoOpSlaBreachPolicy`, deployment-wide displacement). `SlaBreachPolicy` IS a routing mechanism (its `EscalateTo` decision changes candidateGroups) and is correctly listed in the problem statement as part of the ad-hoc landscape, but it doesn't need `NamedStrategy` because it's not per-case selectable — one implementation per deployment, selected by `@DefaultBean` displacement.
- `ContextDiffStrategy` — deployment-level config switch, stays as-is.

**casehub-qhorus (~16 mechanisms in audit §3):** Out of scope. The qhorus routing mechanisms fall into categories that don't warrant `NamedStrategy`: fan-out delivery (ChannelGateway — all backends receive, not selection), access control gates (MessageTypePolicy, AllowedWritersPolicy — determines what CAN flow, not where), direct ID lookup (ConnectorChannelBackend keys), and `@DefaultBean`-only SPIs (ObligorTrustPolicy, WatchdogAlertRouter, AutoChannelPolicy, CommitmentAttestationPolicy). None have multiple competing implementations where a harness author selects per-case.

**casehub-eidos (~10 mechanisms in audit §4):** Out of scope. Eidos mechanisms are data providers and utilities that feed INTO routing strategies, not routing decisions themselves: `CapabilityResolver` (static utility with fixed algorithm), `VocabularyRegistry` (concrete `@ApplicationScoped` class), `AgentRegistry` (discovery, not selection), `AgentStateStore`/`BehavioralSignalStore` (data stores), `DefaultCapabilityHealth` (6-step evaluation pipeline consumed by `AgentCandidateFactory`, already listed under `@DefaultBean`-only SPIs).

**casehub-connectors (~7 mechanisms in audit §5):** Out of scope. Connector mechanisms use direct ID lookup (`ConnectorService.send(connectorId, ...)`, `WebhookRouter.dispatch(id, ...)`, `ChatPlatformService.platform(id)`) — the caller specifies the target, there's no strategy-based selection. `InboundWorkItemPolicy` is a `@DefaultBean`-only SPI (no default impl — inert without consumer code).

### Consumer repo migration

Mechanical — tracked per-repo. Each consumer change is add `id()` or wrap in `StaticSetStrategy.of(...)`. No logic changes required.

---

## 9. CaseDefinition Model Changes

### New fields on `CaseDefinition`

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `agentRouting` | `String` (nullable) | `null` → `@DefaultBean` | Strategy ID for `AgentRoutingStrategy` |
| `implementationRouting` | `String` (nullable) | `null` → `@DefaultBean` | Strategy ID for `ImplementationRoutingStrategy` |
| `planningStrategy` | `String` (nullable) | `"default"` | Already exists — unchanged, now resolved via `StrategyResolver` |
| `candidateMatching` | `String` (nullable) | `null` → `"subsumption"` | Strategy ID for `CandidateMatchingStrategy` |

`CaseDefinition` stores **String IDs**, not strategy interfaces. This is consistent with the existing `planningStrategy` field (already a nullable String) and keeps `CaseDefinition` a pure data model — no CDI dependencies, serializable, testable.

### Per-capability trust policy on capability model

```java
// In the capabilities section of CaseDefinition YAML
capabilities:
  - name: irb-review
    trustPolicy: clinical   // String ID for TrustRoutingPolicyProvider
```

Stored as `String trustPolicy` on the capability model object. Nullable — absent means default policy.

### Resolution timing: dispatch time (lazy)

Strategy IDs are resolved to strategy beans via `StrategyResolver` at dispatch time, NOT at YAML parse time. This means:

1. `CaseDefinitionYamlMapper` parses the YAML and stores String IDs on `CaseDefinition` — no CDI lookups during parsing
2. `CaseContextChangedEventHandler` resolves `AgentRoutingStrategy` from the case definition's `agentRouting` field at dispatch time: `strategyResolver.resolve(AgentRoutingStrategy.class, caseDefinition.agentRouting())`
3. Unknown strategy IDs fail at startup via `StrategyResolver` uniqueness validation — all referenced IDs are validated against the deployed bean set when case definitions are registered

### Wiring changes

`CaseContextChangedEventHandler` currently has `@Inject AgentRoutingStrategy agentRoutingStrategy` — a single global instance. This changes to `@Inject StrategyResolver strategyResolver`, with per-case resolution:

```java
// Before
agentRoutingStrategy.select(context, candidates)

// After
AgentRoutingStrategy strategy = strategyResolver.resolve(
    AgentRoutingStrategy.class,
    caseDefinition.agentRouting());  // null → defaultStrategy()
strategy.select(context, candidates)
```

Same pattern applies to `PlanningStrategyLoopControl` (already similar — migrates from `Instance<>` iteration to `StrategyResolver`), `AgentCandidateFactory` (resolves `CandidateMatchingStrategy`), and trust policy resolution in the routing pipeline.

---

## 10. Validation Against blocks#30

blocks#30 expects to implement the engine-api routing strategy SPIs. The existing blocks routing code validates the design:

- `LlmSelectedRouting<T>` (blocks agentic layer) → wraps in `LlmSelectedAgentRoutingStrategy implements AgentRoutingStrategy` with `id()="llm-selected"`. The blocks `RoutingDecision` (Selected | Unresolvable | Escalate) maps 1:1 to engine `AgentAssignment` (Assigned | Unresolvable | EscalateToOversight).
- `TrustRoutingPolicyResolver` (static utility in engine) → used internally by `TrustWeightedAgentStrategy` (which becomes `id()="trust-weighted"`). No change needed — the resolver stays internal.
- CBR-enriched routing → new `AgentRoutingStrategy` impl with `id()="cbr-semantic"`, selectable per case definition in YAML.

All three are selectable per case definition: `agentRouting: llm-selected` in YAML, resolved via `StrategyResolver.resolve(AgentRoutingStrategy.class, "llm-selected")`.
