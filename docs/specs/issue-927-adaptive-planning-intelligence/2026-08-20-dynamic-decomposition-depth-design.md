# Dynamic Decomposition Depth (ADaPT Pattern) — Design Spec

**Issue:** #936
**Date:** 2026-08-20
**Epic:** #927 (Adaptive Planning Intelligence) — Phase C (Intelligence)
**Depends on:** #929 (GOAP as DecompositionStrategy), #930 (Failure taxonomy)

---

## Summary

When a leaf task within a decomposed compound fails with a Knowledge failure and all reroutes are exhausted, promote it to a compound task and decompose it finer — instead of faulting. Implements hierarchical refinement from the ADaPT pattern (Prasad et al., NAACL 2024).

## Motivation

When "Analyse the transaction" fails, the current system reroutes to a different agent. If all agents fail, the PlanItem is faulted and the compound may fault. But the real problem is often that the task is too coarse — no single agent can handle the entire scope. Breaking it into "Extract metadata," "Identify counterparties," "Evaluate risk indicators" gives each sub-step a better chance because the scope is narrower and capability matching is more precise.

The engine architecturally supports compounds containing compounds (`PlanItemDefinition.Compound` nests arbitrarily) but does not use this as a failure recovery mechanism. Failed leaf tasks today get rerouted or faulted — never decomposed into sub-tasks.

## Design

### 1. Entry Point — WorkerOutcomeResolvedHandler

`WorkerOutcomeResolvedHandler` (`planning/handler/`) already handles PlanItem lifecycle for non-success outcomes. It receives `WorkerOutcomeResolvedEvent` which carries both `OutcomeDisposition` and `FailureCategory`. The `EXHAUSTED` disposition branch currently faults the PlanItem and evaluates compound completion.

**Handler restructuring required:** The existing handler calls `item.markFaulted()` unconditionally (line 90) before any disposition branching. Since FAULTED is terminal, `promoteToCompound()` cannot mark the item OBSOLETE after it's already FAULTED. The decomposition check must run BEFORE `markFaulted()`.

The modification restructures the handler flow: for `EXHAUSTED` disposition, check decomposition eligibility first. Only fault if decomposition is not attempted or fails.

```java
// In onWorkerOutcomeResolved() — restructured:
if (item.getStatus() != TaskStatus.RUNNING) return;

if (event.disposition() == OutcomeDisposition.EXHAUSTED
    && event.category() instanceof FailureCategory.Knowledge k
    && deeperDecompositionHandler.isResolvable()) {
  boolean decomposed = deeperDecompositionHandler.get()
      .tryDecompose(event.caseInstance(), plan, item, k);
  if (decomposed) {
    // Item marked OBSOLETE by promoteToCompound() — not FAULTED
    planItemStateChangedEvents.fireAsync(
        new PlanItemStateChangedEvent(..., TaskStatus.RUNNING, TaskStatus.OBSOLETE, ...));
    eventBus.publish(CONTEXT_CHANGED, ...);
    return;  // Skip fault + compound completion
  }
}

// Existing path: markFaulted() + disposition handling
item.markFaulted();
planItemStateChangedEvents.fireAsync(...FAULTED...);
// ... rest unchanged
```

No new event types needed. No runtime module changes. The feature is entirely within the planning module.

### 2. DeeperDecompositionHandler

New `@ApplicationScoped` bean in `planning/adaptation/`. Injected into `WorkerOutcomeResolvedHandler` via `Instance<DeeperDecompositionHandler>` with `isResolvable()` guard.

**Method:**
```java
public boolean tryDecompose(
    CaseInstance instance,
    CasePlanModel plan,
    PlanItem failedItem,
    FailureCategory.Knowledge category)
```

**Algorithm:**
1. Verify the item is within a compound — `plan.getParentOf(failedItem.getBindingName())` must be present. Note: `getParentOf()` operates on binding names (from `scopedBindings`), NOT PlanItem UUIDs.
2. Resolve `CaseDefinition` from `CaseDefinitionRegistry` using the instance's case meta model
3. Check decomposition strategy is configured — `definition.getDecompositionStrategy()` must be non-null
4. Compute depth by walking `getParentOf()` chain from `failedItem.getBindingName()`, counting compound ancestors
5. If `depth >= definition.getMaxDecompositionDepth()` (default 3) → return false
6. Resolve `DecompositionStrategy` via `EngineStrategyResolver`
7. Build `GoalDecompositionContext`:
   - `state`: working layer from `instance.getCaseContext()`
   - `depth`: computed nesting depth
   - `availableCapabilities`: from `definition.getCapabilities()` (full set — the sub-decomposition determines the narrowing)
   - `definition`: the CaseDefinition
   - `experiences`: retrieved via `Instance<CbrRetrievalService>` when resolvable (for learned cost enrichment from #937)
   - `failureReason`: from `category.reason()` — tells the decomposition strategy WHY the step failed
   - `failureMissingContext`: from `category.missingContext()` — tells the strategy what information was missing
   Note: `GoalDecompositionContext` gains two nullable String fields for failure context. Strategies that support deeper decomposition (e.g. LLM) can include the failure reason in the decomposition prompt; classical strategies (GOAP) ignore them.
8. Create a `CompoundTask` with the failed step's description as the goal name
9. Call `strategy.decompose(compoundTask, context)` — produces `DagPlan<LeafTask<JsonNode>>`
10. If empty plan, exception, or fewer than 2 sub-steps → log warning, return false. The minimum-2-step guard prevents single-step decomposition loops where the strategy returns the same step that just failed, causing repeated LLM calls until the depth limit.
11. Resolve bindings for each sub-step — same pattern as `DefaultGoalDecomposer`:
    - For each `GoalStep` in the decomposition result, call `definition.findBindingsByCapability(step.capabilityName())`
    - Filter unknown capabilities (warn and skip)
    - Multiple bindings per capability: use first in declaration order (v1 limitation, logged warning)
    - If zero resolved steps remain → return false
12. Build a `PlanItemDefinition.Compound` from the resolved steps:
    - ID: failed item's binding name (predictable lookup, per GE-20260808-94c14d)
    - Children: `Primitive` definitions for each resolved step (with placeholder `ExecutorRef.of(capabilityName, null)`, per GE-20260809-fe93ef)
    - ScopedBindings: one binding per resolved step (from `binding.getName()`)
    - Completion: `CompletionSemantics.all()`
    - DispatchMode: `CHOREOGRAPHED`
13. Call `plan.promoteToCompound(failedItem.getBindingName(), newCompound)`
14. Materialize sub-steps:
    - `PlanItemStore.save()` for persistence (with `parentCompoundId` set to the new compound's ID)
    - `plan.addPlanItem()` for the live `CasePlanModel` agenda (so binding evaluation can dispatch them)
    Both are required — persistence ensures recovery; live model enables immediate dispatch.
14. Write `PLAN_DEEPENED` EventLog
15. Return true

**Injections:**
- `Instance<CbrRetrievalService>` — optional, for CBR experiences (transparent no-op when absent)
- `EngineStrategyResolver` — for resolving the decomposition strategy
- `CaseDefinitionRegistry` — for resolving the case definition
- `PlanItemStore` — for materializing sub-step PlanItems
- `EventLogRepository` — for the audit event

### 3. CasePlanModel.promoteToCompound()

New method on `CasePlanModel` interface (default throws `UnsupportedOperationException`) with `DefaultCasePlanModel` implementation.

**Signature:**
```java
void promoteToCompound(String primitiveId, PlanItemDefinition.Compound newCompound)
```

**Implementation in DefaultCasePlanModel:**
1. Validate `primitiveId` exists in `definitions` and is a `Primitive`
2. Find parent compound via `parentIndex.get(primitiveId)` — must exist
3. Remove from all indices:
   - `definitions.remove(primitiveId)`
   - `definitionStates.remove(primitiveId)`
   - `childrenIndex.get(parentId).remove(primitiveId)` (remove as child of parent)
   - `parentIndex.remove(primitiveId)`
4. Register the new Compound: `registerDefinition(newCompound)` — this populates `definitions`, `definitionStates`, `childrenIndex`, `parentIndex` for the compound and its children
5. Re-establish parent link: `childrenIndex.get(parentId).add(newCompound.id())` and `parentIndex.put(newCompound.id(), parentId)`
6. Mark the old PlanItem (if it exists in the agenda) as OBSOLETE via `getPlanItemByBindingName()` + `markObsolete()`

### 4. CaseDefinition.maxDecompositionDepth

New field on `CaseDefinition`:
```java
Integer maxDecompositionDepth   // nullable, default 3
```

**YAML:**
```yaml
spec:
  maxDecompositionDepth: 3
```

**Builder:** `.maxDecompositionDepth(3)`

**CaseDefinitionYamlMapper:** Parse from `spec` block. Null = default 3.

**YAML Schema:** Add `maxDecompositionDepth` property to the spec object in `CaseDefinition.yaml`.

### 5. PLAN_DEEPENED Event Type

New entry in `CaseHubEventType`:
```java
PLAN_DEEPENED
```

EventLog metadata:
- `bindingName` — the binding that was decomposed
- `originalPrimitiveId` — the original Primitive definition ID
- `newCompoundId` — the new Compound definition ID
- `strategyId` — which DecompositionStrategy was used
- `subStepCount` — number of sub-steps created
- `currentDepth` — nesting depth after promotion
- `maxDepth` — the configured maximum
- `failureReason` — from the Knowledge failure category
- `failureMissingContext` — from Knowledge.missingContext() (nullable)

### 6. Depth Computation

```java
private int computeDepth(CasePlanModel plan, String bindingName) {
  int depth = 0;
  String current = bindingName;
  while (true) {
    Optional<String> parent = plan.getParentOf(current);
    if (parent.isEmpty()) break;
    current = parent.get();
    PlanItemDefinition def = plan.getDefinition(current);
    if (def instanceof PlanItemDefinition.Compound) depth++;
  }
  return depth;
}
```

Note: `getParentOf()` operates on binding names (from `scopedBindings`) and definition IDs — NOT on PlanItem UUIDs. Always start from `failedItem.getBindingName()`.

Depth 0 = leaf directly under root compound. Depth 1 = leaf under a sub-compound. Default max 3 = three levels of decomposition.

## Data Flow

```
Worker fails with Knowledge failure
  └→ WorkflowExecutionCompletedHandler.handleSemanticFailure()
       └→ Reroutes exhaust → publishes WORKER_OUTCOME_RESOLVED(EXHAUSTED, Knowledge)

WorkerOutcomeResolvedHandler.onWorkerOutcomeResolved()
  └→ disposition == EXHAUSTED, category == Knowledge
  └→ DeeperDecompositionHandler.tryDecompose()
       └→ computeDepth() < maxDecompositionDepth?
       └→ DecompositionStrategy.decompose(failedStepGoal, context)
            └→ DagPlan<LeafTask> with sub-steps
       └→ CasePlanModel.promoteToCompound(failedId, newCompound)
       └→ PlanItemStore.save() for each sub-step
       └→ EventLog(PLAN_DEEPENED)
  └→ publishes CONTEXT_CHANGED → sub-steps dispatch via normal binding evaluation
```

## Edge Cases

**No decomposition strategy configured:** `tryDecompose()` returns false → existing fault path.

**Empty decomposition result:** Strategy returns empty plan or throws → returns false → fault path. Graceful degradation.

**Transient/Infeasible failures:** Only `Knowledge` triggers decomposition. `Transient` retries. `Infeasible` faults. No behavioral change for non-Knowledge failures.

**Non-compound item:** Failed PlanItem not inside a compound → `getParentOf()` empty → returns false.

**Max depth reached:** `depth >= maxDecompositionDepth` → returns false → fault.

**Single-step decomposition rejected:** If the strategy returns fewer than 2 sub-steps, `tryDecompose()` returns false — falls through to fault. This prevents decomposition loops where the strategy returns the same step that just failed, causing repeated LLM calls at each depth level until the limit.

**Concurrent handler invocation:** `WorkerOutcomeResolvedHandler` is `@ConsumeEvent(blocking = true)` — Vert.x serializes calls. No concurrent promotion race.

**Learned cost enrichment (#937):** `DeeperDecompositionHandler` passes CBR experiences through `GoalDecompositionContext` (same pattern as `DefaultGoalDecomposer`). Enriched costs steer the sub-decomposition toward historically reliable sub-steps.

**PlanItem state:** The old PlanItem (representing the failed Primitive) is marked OBSOLETE by `promoteToCompound()`. New PlanItems are created PENDING for each sub-step. The compound completion evaluator sees the OBSOLETE item as terminal and only waits for the new sub-steps.

## Known Limitations

**Single decomposition attempt per step.** If the sub-steps also fail with Knowledge failures AND depth < maxDepth, they will decompose recursively. This is by design (hierarchical refinement), bounded by maxDecompositionDepth.

**No capability narrowing in v1.** The full capability set is passed to the re-decomposition. The strategy itself determines which capabilities to use based on the narrower goal description. True capability narrowing (only pass capabilities relevant to the sub-goal) requires capability dependency analysis — future work.

**No integration with meta-reasoner (#934).** The trigger is hardcoded: Knowledge + EXHAUSTED → decompose if eligible. When #934 lands, `DECOMPOSE_DEEPER` becomes a scope option on the `Refine` decision, giving the meta-reasoner control over when to decompose vs. replan vs. persist.

**Structural children need placeholder ExecutorRef.** Per GE-20260809-fe93ef, `Primitive` constructor requires non-null executor. Structural children use `ExecutorRef.of(capabilityName, null)`.

## Test Plan

### DeeperDecompositionHandler

1. **Happy path** — Knowledge failure, within compound, depth < max → promotes, decomposes, materializes sub-steps
2. **Non-Knowledge failure** — Transient/Infeasible → returns false, no decomposition
3. **Max depth reached** — depth >= maxDecompositionDepth → returns false
4. **No decomposition strategy** — definition.getDecompositionStrategy() null → returns false
5. **Not in compound** — getParentOf() empty → returns false
6. **Decomposition fails** — strategy throws → returns false, no crash
7. **Empty decomposition** — strategy returns empty plan → returns false
8. **CBR experiences threaded** — experiences flow to GoalDecompositionContext when CbrRetrievalService available
9. **No CbrRetrievalService** — transparent no-op, empty experiences

### CasePlanModel.promoteToCompound()

10. **Promotes Primitive to Compound** — indices updated atomically
11. **Validates primitive exists** — throws on unknown ID
12. **Validates is Primitive** — throws if target is already a Compound
13. **Validates has parent** — throws if not within a compound
14. **Children registered** — childrenIndex populated for new compound
15. **Parent link preserved** — new compound is child of original parent
16. **Old PlanItem marked OBSOLETE** — if present in agenda

### WorkerOutcomeResolvedHandler integration

17. **EXHAUSTED + Knowledge → decomposition attempted** — handler delegates before faulting
18. **EXHAUSTED + Knowledge + decomposition succeeds → no fault** — PlanItem not faulted, CONTEXT_CHANGED published
19. **EXHAUSTED + Knowledge + decomposition fails → fault** — existing path continues
20. **EXHAUSTED + Transient → no decomposition** — existing fault path
21. **REROUTE/FAULT dispositions unchanged** — no behavioral change

### CaseDefinition

22. **maxDecompositionDepth parsed from YAML** — `spec: { maxDecompositionDepth: 2 }`
23. **maxDecompositionDepth defaults to null (→ 3)** — when not specified

### DeeperDecompositionHandler — additional

24. **Single-step decomposition rejected** — strategy returns 1 step → returns false
25. **Binding resolution for sub-steps** — capabilities mapped to bindings via findBindingsByCapability()
26. **Unknown capabilities skipped** — strategy returns unknown capability → step skipped, warning logged
27. **PlanItem added to live model** — sub-step PlanItem in both PlanItemStore and CasePlanModel agenda
28. **Failure context threaded** — Knowledge reason and missingContext available in GoalDecompositionContext

### Audit

29. **PLAN_DEEPENED event logged** — with correct metadata
30. **Depth tracking accurate** — depth increments on each nested decomposition, uses bindingName not planItemId

### End-to-end

26. **Recursive decomposition** — step decomposes, sub-step fails with Knowledge, sub-step decomposes again (up to max depth)
27. **Max depth blocks further decomposition** — after 3 levels, failure faults normally

## Files Changed

| File | Change |
|------|--------|
| `planning/.../handler/WorkerOutcomeResolvedHandler.java` | Add deeper decomposition branch in EXHAUSTED path |
| `planning/.../adaptation/DeeperDecompositionHandler.java` | **New** — tryDecompose() handler |
| `planning/.../plan/CasePlanModel.java` | Add promoteToCompound() default method |
| `planning/.../plan/DefaultCasePlanModel.java` | Implement promoteToCompound() |
| `api/.../model/CaseDefinition.java` | Add maxDecompositionDepth field |
| `api/.../event/CaseHubEventType.java` | Add PLAN_DEEPENED |
| `schema/.../CaseDefinition.yaml` | Add maxDecompositionDepth property |
| `api/.../converter/CaseDefinitionYamlMapper.java` | Parse maxDecompositionDepth |
| Tests for all of the above | ~27 test cases |

## Scope Boundary

**In scope:**
- `DeeperDecompositionHandler` with tryDecompose()
- `CasePlanModel.promoteToCompound()` on interface + DefaultCasePlanModel
- `WorkerOutcomeResolvedHandler` interception for Knowledge + EXHAUSTED
- `CaseDefinition.maxDecompositionDepth`
- `PLAN_DEEPENED` event type
- YAML parsing for maxDecompositionDepth

**Out of scope:**
- Meta-reasoner integration (#934) — will add `DECOMPOSE_DEEPER` scope later
- Capability narrowing — full capability set passed to re-decomposition
- Plan repair (#935) — separate issue
- Contingent planning (#938) — separate issue

## References

- `../../../planning-core/src/main/java/io/casehub/engine/planning/handler/WorkerOutcomeResolvedHandler.java` — entry point
- `common/src/main/java/io/casehub/engine/common/internal/event/WorkerOutcomeResolvedEvent.java` — carries FailureCategory
- `../../../planning-core/src/main/java/io/casehub/engine/planning/plan/DefaultCasePlanModel.java` — replaceCompound() pattern
- `../../../planning-core/src/main/java/io/casehub/engine/planning/plan/PlanItemDefinition.java` — Primitive/Compound sealed hierarchy
- `../../../planning-core/src/main/java/io/casehub/engine/planning/adaptation/DefaultPlanAdaptationEvaluator.java` — existing adaptation flow
- `api/src/main/java/io/casehub/api/model/FailureCategory.java` — Knowledge sealed variant
- `../../../planning-core/src/main/java/io/casehub/engine/planning/decomposition/GoapDecompositionStrategy.java` — decomposition consumer
- `../../../planning-core/src/main/java/io/casehub/engine/planning/decomposition/DefaultGoalDecomposer.java` — initial decomposition pattern
- `research/2026-08-18-adaptive-planning-intelligence.md` — epic research (Section 3, Issue 9)
- GE-20260808-47dc40 — CasePlanModel decomposition vs adaptation structural differences
- GE-20260809-fe93ef — Primitive executor NPE for structural children
- GE-20260607-245588 — WORKER_RETRIES_EXHAUSTED faults CaseInstance (avoided by using WORKER_OUTCOME_RESOLVED)
- GE-20260808-94c14d — Compound.builder() defaults id to random UUID
