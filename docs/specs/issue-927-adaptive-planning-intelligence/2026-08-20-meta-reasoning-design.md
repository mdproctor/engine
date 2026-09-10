# Persist / Refine / Concede Meta-Reasoning — Design Spec

**Issue:** #934
**Date:** 2026-08-20
**Module:** `casehub-engine-api` (types, SPI), `casehub-engine-common` (cost computation), `casehub-engine-planning` (default implementation, integration)

---

## Summary

Introduce an `AdaptationMetaReasoner` SPI that evaluates whether adaptation is worth performing, and if so, what scope of adaptation is appropriate. Sits between the existing `AdaptationTrigger` (binary gate) and `PlanRevisionStrategy` (execution). Returns a sealed `AdaptationDecision`: Persist (plan valid, continue), Refine (adapt with scope), or Concede (abandon compound). Default implementation uses a cost-ceiling heuristic with failure-category-based scope selection.

## Motivation

The adaptation pipeline is currently reactive: trigger fires → revision runs → result applied. No evaluation of whether adaptation is *worth doing*. Three waste scenarios:

1. **8/10 steps done.** Step 9 fails transiently. EveryStepTrigger fires LLM adaptation — but optimal action is retry (Persist).
2. **2/10 steps done.** Both produced unexpected output. Plan is wrong but OnFailureTrigger hasn't fired (no step "failed" technically).
3. **Adapted 4 times already.** Cumulative LLM cost exceeds case value. Optimal action: abandon (Concede).

MPDF (AAAI 2026) formalizes the trichotomy: Persist (plan valid), Refine (adapt proportionally), Concede (abandon). This issue operationalizes it.

---

## Architecture

### Pipeline

```
trigger.evaluate(AdaptationContext) → SKIP: stop
                                    → PROCEED:
  metaReasoner.evaluate(MetaReasoningContext) → Persist: stop
                                               → Refine(scope): select strategy → revise → apply
                                               → Concede: cancelPending → faultCompound → audit
```

The trigger and meta-reasoner evaluate different signals:
- **Trigger:** divergence score, per-binding replan hints, failure status (D5, D6)
- **Meta-reasoner:** adaptation count, failure category, remaining plan value (D5)

### Module Placement

| Component | Module | Package |
|-----------|--------|---------|
| `AdaptationDecision` | `engine-api` | `io.casehub.engine.plan.adaptation` |
| `RefineScope` | `engine-api` | `io.casehub.engine.plan.adaptation` |
| `AdaptationMetaReasoner` | `engine-api` | `io.casehub.engine.plan.adaptation` |
| `MetaReasoningContext` | `engine-api` | `io.casehub.engine.plan.adaptation` |
| `AdaptationCostComputer` | `engine-common` | `io.casehub.engine.common.internal.monitoring` |
| `CostCeilingMetaReasoner` | `planning` | `io.casehub.engine.planning.adaptation` |
| `CaseHubEventType.PLAN_CONCEDED` | `engine-api` | existing enum |

**Boundary test (PP-20260727-5267d2):** `AdaptationDecision`, `RefineScope`, `AdaptationMetaReasoner`, `MetaReasoningContext` are consumer-referenced types (case definition configuration, strategy implementation) — engine-api. `AdaptationCostComputer` is internal infrastructure — engine-common. `CostCeilingMetaReasoner` is a strategy implementation — planning module (same as `ProgressGatedTrigger`, `DefaultPlanAdaptationEvaluator`).

---

## 1. AdaptationDecision Sealed Type (engine-api)

```java
package io.casehub.engine.plan.adaptation;

public sealed interface AdaptationDecision {

  String reason();

  record Persist(String reason) implements AdaptationDecision {
    public Persist { Objects.requireNonNull(reason, "reason"); }
  }

  record Refine(RefineScope scope, String reason) implements AdaptationDecision {
    public Refine {
      Objects.requireNonNull(scope, "scope");
      Objects.requireNonNull(reason, "reason");
    }
  }

  record Concede(String reason, String compoundId) implements AdaptationDecision {
    public Concede {
      Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(compoundId, "compoundId");
    }
  }
}
```

---

## 2. RefineScope Enum (engine-api)

```java
package io.casehub.engine.plan.adaptation;

public enum RefineScope {
  LOCAL,
  COMPOUND
}
```

`LOCAL` — repair the specific failure (v1: falls back to ForwardReplanRevision, same as COMPOUND. Differentiated execution arrives with #935).
`COMPOUND` — re-decompose the entire compound via ForwardReplanRevision.

---

## 3. AdaptationMetaReasoner SPI (engine-api)

```java
package io.casehub.engine.plan.adaptation;

import io.casehub.platform.api.routing.NamedStrategy;

public interface AdaptationMetaReasoner extends NamedStrategy {

  AdaptationDecision evaluate(MetaReasoningContext context);

  @Override
  default String id() { return "cost-ceiling"; }
}
```

Resolved via `EngineStrategyResolver`. Explicit `Instance<AdaptationMetaReasoner>` constructor parameter required in `EngineStrategyResolver` (GE-20260810-b53fd8, same pattern as `GoalFormationStrategy`, `GoalRevisionStrategy`).

---

## 4. MetaReasoningContext (engine-api)

```java
package io.casehub.engine.plan.adaptation;

import io.casehub.api.model.FailureCategory;

public record MetaReasoningContext(
    AdaptationContext adaptationContext,
    int adaptationCount,
    int completedStepCount,
    int pendingStepCount,
    int totalStepCount,
    FailureCategory latestFailureCategory
) {
  public MetaReasoningContext {
    Objects.requireNonNull(adaptationContext, "adaptationContext");
    if (adaptationCount < 0) throw new IllegalArgumentException("adaptationCount must be >= 0");
  }

  public double remainingRatio() {
    return totalStepCount > 0 ? (double) pendingStepCount / totalStepCount : 0.0;
  }
}
```

**Fields:**
- `adaptationCount` — from `CasePlanModel.getAdaptationGeneration(compoundId)` (zero-query, D3)
- `completedStepCount`, `pendingStepCount`, `totalStepCount` — from `AdaptationContext` step lists
- `latestFailureCategory` — nullable. Present when the trigger fired due to a failure. Populated from `_diagnostics.<bindingName>.latestDiagnosis.category` in the case context. Null on successful completion triggers.

**Why no divergence score:** Divergence is the trigger's responsibility (D5). `ProgressGatedTrigger` already evaluates divergence and gates on it. Including divergence in `MetaReasoningContext` would duplicate signals and couple the meta-reasoner to monitor implementation details.

---

## 5. AdaptationConfig Extension (engine-api)

`AdaptationConfig` gains a 4th field:

```java
public record AdaptationConfig(
    String trigger,
    String revision,
    Double threshold,
    String metaReasoner    // nullable, default "cost-ceiling"
) {
  // Backward-compatible factory
  public static AdaptationConfig of(String trigger, String revision) {
    return new AdaptationConfig(trigger, revision, null, null);
  }

  public String effectiveMetaReasoner() {
    return metaReasoner != null ? metaReasoner : "cost-ceiling";
  }
}
```

`CaseDefinition` gains `maxAdaptations` (Integer, nullable — default 5 via `CostCeilingMetaReasoner.DEFAULT_MAX_ADAPTATIONS`). YAML:

```yaml
spec:
  maxAdaptations: 5
  adaptation:
    trigger: progress
    revision: forward-replan
    metaReasoner: cost-ceiling
```

---

## 6. AdaptationCostComputer (engine-common)

Static utility computing adaptation cost summary from EventLog entries. Same pattern as `DivergenceScoreComputer`.

```java
public final class AdaptationCostComputer {

  private AdaptationCostComputer() {}

  public static AdaptationCostSummary computeForCompound(
      List<EventLog> adaptationEvents, String compoundId) {
    int count = 0;
    int totalProduced = 0;
    int totalObsoleted = 0;
    for (EventLog event : adaptationEvents) {
      JsonNode meta = event.getMetadata();
      if (meta == null) continue;
      String eventCompound = meta.has("compoundId") ? meta.get("compoundId").asText() : null;
      if (!Objects.equals(compoundId, eventCompound)) continue;
      count++;
      totalProduced += meta.has("newStepCount") ? meta.get("newStepCount").asInt() : 0;
      totalObsoleted += meta.has("previousStepCount") ? meta.get("previousStepCount").asInt() : 0;
    }
    return new AdaptationCostSummary(count, totalProduced, totalObsoleted);
  }
}
```

`AdaptationCostSummary` record (engine-common):

```java
public record AdaptationCostSummary(
    int adaptationCount,
    int totalStepsProduced,
    int totalStepsObsoleted
) {}
```

**Note:** The primary cost signal is `CasePlanModel.getAdaptationGeneration()` (zero-query, in-memory). `AdaptationCostComputer` provides richer cost data from EventLog when deeper analysis is needed (e.g., LLM-backed meta-reasoner). The default `CostCeilingMetaReasoner` uses `adaptationGeneration` directly and does NOT query EventLog (D3).

---

## 7. CostCeilingMetaReasoner (planning module)

`@ApplicationScoped`, id=`"cost-ceiling"`. Default meta-reasoner implementation.

```java
@ApplicationScoped
public class CostCeilingMetaReasoner implements AdaptationMetaReasoner {

  private static final int DEFAULT_MAX_ADAPTATIONS = 5;

  @Override
  public AdaptationDecision evaluate(MetaReasoningContext context) {
    AdaptationContext ac = context.adaptationContext();
    CaseDefinition def = ac.definition();

    int maxAdaptations = def.getMaxAdaptations() != null
        ? def.getMaxAdaptations() : DEFAULT_MAX_ADAPTATIONS;

    // 1. Concede: adaptation ceiling exceeded
    if (context.adaptationCount() >= maxAdaptations) {
      return new AdaptationDecision.Concede(
          "Adaptation ceiling reached (" + context.adaptationCount()
              + "/" + maxAdaptations + ")",
          ac.compoundId());
    }

    // 2. Concede: infeasible failure
    if (context.latestFailureCategory() instanceof FailureCategory.Infeasible inf) {
      return new AdaptationDecision.Concede(
          "Infeasible failure: " + inf.reason(), ac.compoundId());
    }

    // 3. Persist: transient failure — let retry/reroute handle
    if (context.latestFailureCategory() instanceof FailureCategory.Transient) {
      return new AdaptationDecision.Persist(
          "Transient failure — retry/reroute preferred over adaptation");
    }

    // 4. Refine: knowledge failure — scope based on recurrence
    if (context.latestFailureCategory() instanceof FailureCategory.Knowledge) {
      RefineScope scope = context.adaptationCount() > 0
          ? RefineScope.COMPOUND : RefineScope.LOCAL;
      return new AdaptationDecision.Refine(scope,
          "Knowledge failure — " + (scope == RefineScope.COMPOUND
              ? "repeated, compound re-plan" : "first occurrence, local repair"));
    }

    // 5. Refine: successful completion triggered adaptation (divergence-gated)
    // Trigger already confirmed divergence warrants attention
    return new AdaptationDecision.Refine(RefineScope.COMPOUND,
        "Divergence-gated adaptation after successful completion");
  }

  @Override
  public String id() { return "cost-ceiling"; }
}
```

**Decision logic (D5 revised):**
1. Ceiling → Concede (hard limit)
2. Infeasible → Concede (failure category)
3. Transient → Persist (retry handles it)
4. Knowledge → Refine with scope escalation (first time LOCAL, repeated COMPOUND)
5. Success-triggered → Refine(COMPOUND) (divergence was high enough for trigger to fire)

---

## 8. DefaultPlanAdaptationEvaluator Integration

### 8.1 performAdaptation() Changes

After trigger returns PROCEED, insert meta-reasoning before revision:

```java
private void performAdaptation(...) {
  // ... existing: build AdaptationContext, evaluate trigger ...

  if (signal == AdaptationSignal.SKIP) { return; }

  // NEW: Meta-reasoning evaluation
  FailureCategory latestCategory = resolveLatestFailureCategory(
      instance, completedBindingName);

  int adaptationCount = plan.getAdaptationGeneration(compoundId);
  int completed = completedSteps.size();
  int pending = pendingSteps.size();
  int total = completed + pending + runningSteps.size();

  var metaContext = new MetaReasoningContext(
      adaptationContext, adaptationCount, completed, pending, total,
      latestCategory);

  AdaptationMetaReasoner metaReasoner = strategyResolver.resolve(
      AdaptationMetaReasoner.class,
      config.effectiveMetaReasoner());
  AdaptationDecision decision = metaReasoner.evaluate(metaContext);

  switch (decision) {
    case AdaptationDecision.Persist p -> {
      LOG.debugf("Meta-reasoner: Persist — %s", p.reason());
      return;
    }
    case AdaptationDecision.Concede c -> {
      LOG.infof("Meta-reasoner: Concede — %s", c.reason());
      applyConcession(caseId, tenancyId, compoundId, plan, c, config);
      return;
    }
    case AdaptationDecision.Refine r -> {
      LOG.infof("Meta-reasoner: Refine(%s) — %s", r.scope(), r.reason());
      // v1: both LOCAL and COMPOUND resolve to ForwardReplanRevision (D7)
      performRevision(caseId, tenancyId, compoundId, plan,
          instance, definition, config, currentGeneration,
          completedSteps, pendingSteps, runningSteps,
          adaptationContext, cause);
    }
  }
}
```

### 8.2 resolveLatestFailureCategory()

Reads `_diagnostics.<bindingName>.latestDiagnosis` from the case context working layer:

```java
private FailureCategory resolveLatestFailureCategory(
    CaseInstance instance, String bindingName) {
  JsonNode working = instance.getCaseContext() != null
      ? instance.getCaseContext().layer(ContextLayer.WORKING).asJsonNode() : null;
  if (working == null || !working.has("_diagnostics")) return null;
  JsonNode diag = working.get("_diagnostics");
  if (!diag.has(bindingName)) return null;
  JsonNode binding = diag.get(bindingName);
  if (!binding.has("latestDiagnosis")) return null;
  JsonNode latest = binding.get("latestDiagnosis");
  if (!latest.has("category")) return null;
  String category = latest.get("category").asText();
  String reason = latest.has("reason") ? latest.get("reason").asText() : "";
  return switch (category) {
    case "transient" -> new FailureCategory.Transient(reason);
    case "knowledge" -> {
      String missing = latest.has("missingContext")
          ? latest.get("missingContext").asText() : null;
      yield new FailureCategory.Knowledge(reason, missing);
    }
    case "infeasible" -> new FailureCategory.Infeasible(reason);
    default -> null;
  };
}
```

### 8.3 applyConcession()

New method handling the Concede path:

```java
private void applyConcession(UUID caseId, String tenancyId, String compoundId,
    CasePlanModel plan, AdaptationDecision.Concede decision, AdaptationConfig config) {
  // Cancel PENDING PlanItems (RUNNING items complete naturally — D4 review note)
  plan.faultCompound(compoundId);

  // Write PLAN_CONCEDED EventLog
  var eventLog = new EventLog();
  eventLog.setCaseId(caseId);
  eventLog.setEventType(CaseHubEventType.PLAN_CONCEDED);
  eventLog.setStreamType(EventStreamType.CASE);
  eventLog.setTimestamp(Instant.now());
  var meta = OBJECT_MAPPER.createObjectNode();
  meta.put("compoundId", compoundId);
  meta.put("reason", decision.reason());
  meta.put("adaptationCount", plan.getAdaptationGeneration(compoundId));
  meta.put("triggerStrategy", config.trigger());
  meta.put("metaReasoner", config.effectiveMetaReasoner());
  eventLog.setMetadata(meta);
  eventLogRepository.append(eventLog, tenancyId);

  // Trigger compound completion evaluation
  compoundCompletionEvaluator.evaluate(caseId, tenancyId, plan, compoundId);
}
```

**`compoundCompletionEvaluator` injection:** `DefaultPlanAdaptationEvaluator` does not currently inject `CompoundCompletionEvaluator`. Add it as a new constructor parameter.

---

## 9. CasePlanModel.faultCompound() (planning module)

New method on `CasePlanModel` interface and `DefaultCasePlanModel`:

```java
// CasePlanModel interface
void faultCompound(String compoundId);
```

Implementation in `DefaultCasePlanModel`:

```java
public void faultCompound(String compoundId) {
  PlanItemDefinition def = getDefinition(compoundId);
  if (!(def instanceof PlanItemDefinition.Compound compound)) {
    throw new IllegalArgumentException("Not a compound: " + compoundId);
  }

  // Cancel PENDING PlanItems under this compound
  for (String bindingName : compound.scopedBindings().keySet()) {
    findPlanItemByBindingName(bindingName).ifPresent(item -> {
      if (item.getStatus() == TaskStatus.PENDING) {
        item.markCancelled();
      }
    });
  }

  // Fault the compound definition status
  tryDefinitionTransition(compoundId, TaskStatus.FAULTED);
}
```

**RUNNING PlanItems:** Not cancelled. They complete naturally — their output is discarded because the compound is already faulted. This is consistent with `replaceCompound()` which preserves RUNNING PlanItems (D4 review note).

---

## 10. Fault-Aware Completion Propagation

`CompoundCompletionEvaluator.evaluateCompletion()` currently transitions parent compounds to COMPLETED when all required children reach terminal status. Under `All` completion semantics, a FAULTED child is terminal — the parent completes even though a child faulted. This is incorrect for Concede: a compound with a FAULTED child should propagate the fault upward, not complete.

**Fix:** After `evaluateCompletion()` determines all required children are terminal, check whether any required child is FAULTED or CANCELLED (not COMPLETED). If so, the parent should be faulted rather than completed.

```java
// In CompoundCompletionEvaluator, after determining completion criteria met:
boolean anyChildFaulted = requiredChildren.stream()
    .anyMatch(c -> c.getStatus() == TaskStatus.FAULTED
        || c.getStatus() == TaskStatus.CANCELLED);

if (anyChildFaulted && semantics instanceof CompletionSemantics.All) {
  plan.tryDefinitionTransition(compoundId, TaskStatus.FAULTED);
} else {
  plan.tryDefinitionTransition(compoundId, TaskStatus.COMPLETED);
}
```

**MOfN and FirstWins:** These semantics already handle partial failure — they complete when the threshold is met regardless of individual child faults. No change needed.

---

## 11. EngineStrategyResolver Registration

Add `Instance<AdaptationMetaReasoner>` as a constructor parameter:

```java
@Inject
public EngineStrategyResolver(
    // ... existing parameters ...
    Instance<AdaptationMetaReasoner> metaReasoners,
    Instance<NamedStrategy> allStrategies) {
  // ...
  registerStrategies(metaReasoners);
  registerRemainingStrategies(allStrategies);
}
```

Per GE-20260810-b53fd8, explicit typed `Instance<>` is required — `Instance<NamedStrategy>` catch-all misses beans registered under sub-interfaces.

---

## 12. CaseDefinitionYamlMapper Changes

Parse `metaReasoner` from `adaptation:` block:

```yaml
spec:
  maxAdaptations: 5
  adaptation:
    trigger: progress
    revision: forward-replan
    metaReasoner: cost-ceiling
    threshold: 0.3
```

YAML presets updated:
- `adaptation: adaptive` → `{trigger: every-step, revision: forward-replan, metaReasoner: cost-ceiling}`
- `adaptation: conservative` → `{trigger: on-failure, revision: forward-replan, metaReasoner: cost-ceiling}`

`maxAdaptations` parsed from `spec:` level (alongside existing `maxDecompositionDepth`).

---

## 13. CaseDefinition Changes

```java
// New field
private Integer maxAdaptations;

public Integer getMaxAdaptations() { return maxAdaptations; }

// Builder
public CaseDefinition.Builder maxAdaptations(int maxAdaptations) { ... }
```

---

## 14. EventLog Event Type

`CaseHubEventType` gains `PLAN_CONCEDED`. Metadata:

```json
{
  "compoundId": "investigation-compound",
  "reason": "Adaptation ceiling reached (5/5)",
  "adaptationCount": 5,
  "triggerStrategy": "progress",
  "metaReasoner": "cost-ceiling"
}
```

---

## 15. Graceful Degradation

| Condition | Behavior |
|-----------|----------|
| No `AdaptationConfig` on definition | No adaptation, no meta-reasoning |
| `AdaptationConfig` without `metaReasoner` | Default `"cost-ceiling"` via `effectiveMetaReasoner()` |
| No `maxAdaptations` on definition | Default 5 via `CostCeilingMetaReasoner.DEFAULT_MAX_ADAPTATIONS` |
| No failure category in `_diagnostics` | `latestFailureCategory = null` → Refine(COMPOUND) on success path |
| META-REASONER SPI not resolvable | Fall through to existing behavior (revise directly) |

---

## 16. Testing Strategy

### 16.1 AdaptationDecision Unit Tests (engine-api)

- Sealed type construction and pattern matching
- Validation (null reason, null scope)

### 16.2 CostCeilingMetaReasoner Unit Tests (planning)

- Concede on adaptation ceiling (count >= max)
- Concede on Infeasible failure
- Persist on Transient failure
- Refine(LOCAL) on first Knowledge failure (adaptationCount == 0)
- Refine(COMPOUND) on repeated Knowledge failure (adaptationCount > 0)
- Refine(COMPOUND) on success-triggered adaptation (null failure category)
- Default maxAdaptations when CaseDefinition has none
- Custom maxAdaptations from CaseDefinition

### 16.3 DefaultPlanAdaptationEvaluator Integration Tests (planning)

- Trigger SKIP → no meta-reasoning, no revision
- Trigger PROCEED → meta-reasoner Persist → no revision
- Trigger PROCEED → meta-reasoner Refine → revision runs
- Trigger PROCEED → meta-reasoner Concede → PlanItems cancelled, PLAN_CONCEDED event
- FailureCategory resolved from _diagnostics context
- Missing _diagnostics → null category → Refine(COMPOUND)

### 16.4 CasePlanModel.faultCompound() Unit Tests (planning)

- PENDING PlanItems marked CANCELLED
- RUNNING PlanItems preserved
- COMPLETED PlanItems preserved
- Compound definition status transitions to FAULTED
- Non-compound throws IllegalArgumentException

### 16.5 Fault-Aware Completion Propagation Tests (planning)

- All semantics: child FAULTED → parent FAULTED (not COMPLETED)
- All semantics: all children COMPLETED → parent COMPLETED (unchanged)
- MOfN semantics: one child FAULTED, threshold met by others → parent COMPLETED
- FirstWins semantics: one child COMPLETED → parent COMPLETED regardless of faults

### 16.6 YAML/Builder Tests

- AdaptationConfig with metaReasoner field
- AdaptationConfig backward compatibility (2-arg factory)
- CaseDefinition.maxAdaptations from YAML
- Preset expansions (adaptive, conservative)

---

## 17. Scope Boundaries

**In scope:**
- `AdaptationDecision` sealed type (Persist, Refine, Concede)
- `RefineScope` enum (LOCAL, COMPOUND)
- `AdaptationMetaReasoner` SPI with `MetaReasoningContext`
- `CostCeilingMetaReasoner` default implementation
- `AdaptationCostComputer` static utility (engine-common)
- `AdaptationConfig.metaReasoner` field
- `CaseDefinition.maxAdaptations` field
- `CasePlanModel.faultCompound()` method
- Fault-aware `CompoundCompletionEvaluator` propagation for `All` semantics
- `CaseHubEventType.PLAN_CONCEDED`
- `EngineStrategyResolver` registration
- YAML/builder support
- `DefaultPlanAdaptationEvaluator` integration

**Out of scope:**
- LOCAL → dedicated RepairStrategy (#935)
- LLM-backed meta-reasoner (future named strategy)
- Token cost tracking in EventLog (#934 additive, not blocking)
- Unifying DeeperDecompositionHandler with meta-reasoner (#935)
- Cross-compound cost aggregation

---

## References

- `../../../planning-core/src/main/java/io/casehub/engine/planning/adaptation/DefaultPlanAdaptationEvaluator.java` — integration point
- `api/src/main/java/io/casehub/engine/plan/adaptation/AdaptationTrigger.java` — existing trigger SPI
- `api/src/main/java/io/casehub/engine/plan/adaptation/AdaptationSignal.java` — trigger return type
- `api/src/main/java/io/casehub/engine/plan/adaptation/AdaptationContext.java` — existing context
- `api/src/main/java/io/casehub/api/model/AdaptationConfig.java` — pipeline config
- `api/src/main/java/io/casehub/api/model/FailureCategory.java` — failure taxonomy (#930)
- `../../../planning-core/src/main/java/io/casehub/engine/planning/adaptation/ProgressGatedTrigger.java` — divergence trigger
- `../../../planning-core/src/main/java/io/casehub/engine/planning/adaptation/DeeperDecompositionHandler.java` — failure-path decomposition (#936)
- `runtime/src/main/java/io/casehub/engine/internal/routing/EngineStrategyResolver.java` — strategy resolution
- `common/src/main/java/io/casehub/engine/common/internal/monitoring/DivergenceScoreComputer.java` — on-demand computation pattern
- `research/2026-08-18-adaptive-planning-intelligence.md` §2.3 — MPDF, SOFAI-LM, TART taxonomy
- GE-20260814-d2b419 — AdaptationCause sealed type constraints
- GE-20260810-b53fd8 — EngineStrategyResolver explicit Instance<> requirement
- GE-20260808-47dc40 — decomposition vs adaptation structural differences
- PP-20260601-81b9e5 — SPI evolution default methods
- PP-20260727-5267d2 — plan-definition types in engine-api, execution in engine-common
- decisions-934.md — D1-D8 captured decisions
