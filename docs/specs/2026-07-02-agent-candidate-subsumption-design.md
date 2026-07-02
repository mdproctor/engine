# AgentCandidateFactory Subsumption Matching

**Issue:** engine#609
**Date:** 2026-07-02
**Status:** Approved

## Problem

`AgentCandidateFactory.buildCandidates()` uses `w.capabilityNames().contains(capabilityName)` — exact string equality. A worker declaring `"code-review"` grounded in a vocabulary where `"security-code-review"` specializes it is never considered for `"security-code-review"` capabilities, even though eidos's vocabulary subsumption API recognises the relationship.

The engine has two parallel capability identity systems that don't talk to each other:

1. `Worker.capabilityNames()` — `Set<String>`, flat ungrounded names
2. `AgentDescriptor.capabilities()` — `List<AgentCapability>` with `capabilityVocabulary` for vocabulary-grounded subsumption

System 1 gates dispatch. System 2 is used only for health probing (after the gate). Subsumption never influences candidate selection.

## Design

### Approach: Descriptor-only subsumption

Add subsumption matching for workers that have an `AgentDescriptor`. Workers without descriptors keep exact string matching. `MatchDegree` is not surfaced on `AgentCandidate` — subsumption is a binary include/exclude filter.

### Rationale: MatchDegree as filter, not selection signal

The factory's job is "can this worker handle this capability?" (yes/no). The routing strategy's job is "which worker handles it best?" (trust, workload, health). Match quality is a filter concern, not a selection concern.

MatchDegree is static vocabulary structure. Trust scores are learned evidence that supersede structural signals over time. `CapabilitySpecializationStore` in eidos already tracks agent competence at specific capabilities. A routing strategy that genuinely needs MatchDegree can compute it from the `agentDescriptor` already on `AgentCandidate` via `CapabilityResolver.match()` — no information is lost.

### Change 1: AgentCandidateFactory — static to CDI

Convert from static utility to `@ApplicationScoped` CDI bean. `VocabularyRegistry` is constructor-injected.

```java
@ApplicationScoped
public class AgentCandidateFactory {

    private final VocabularyRegistry vocabularyRegistry;

    @Inject
    AgentCandidateFactory(VocabularyRegistry vocabularyRegistry) {
        this.vocabularyRegistry = vocabularyRegistry;
    }

    public List<AgentCandidate> buildCandidates(
        CaseInstance caseInstance,
        CaseDefinition caseDefinition,
        List<Worker> workers,
        Capability capability,
        WorkerExecutionManager executionManager,
        CapabilityHealth capabilityHealth) { ... }
}
```

Two call sites update from static call to injected bean:
- `CaseContextChangedEventHandler.publishWorkerSchedule()` — `@Inject AgentCandidateFactory`
- `DefaultWorkOrchestrator.doSubmit()` — `@Inject AgentCandidateFactory`

### Change 2: Two-tier matching logic

Inside `buildCandidates()`, the loop becomes:

```
for each worker:
  1. Fast path: capabilityNames().contains(capabilityName) → match
  2. If no exact match AND descriptor exists:
     CapabilityResolver.resolve(descriptor.capabilities(), capabilityName, vocabularyRegistry)
     Non-null result → match (log at DEBUG)
  3. No match → skip
```

The descriptor is already resolved for health probing (`caseDefinition.agentDescriptorFor(w.name())`). The subsumption check slots between capability filter and health probe.

### Change 3: NoOpVocabularyRegistry

`@DefaultBean @ApplicationScoped` at `runtime/internal/worker/NoOpVocabularyRegistry.java`. Exact-only semantics:

- `match()` → `Exact` when equal, `None` otherwise
- `subsumes()` → `true` only when equal
- All hierarchy methods → empty collections/maps
- All registration/resolution methods → no-op

When `casehub-eidos-runtime` is on the classpath, `CdiVocabularyRegistry` wins automatically.

### Unchanged: auxiliary matching points

Three other `capabilityNames().contains()` sites stay exact-only:

- `SchedulerService.findWorkerForCapability()` — Quartz job data lookup
- `WorkflowExecutionCompletedHandler.findMatchingCapabilityBinding()` — outcome binding resolution
- `PlanningStrategyLoopControl.resolveWorkerName()` — PlanItem tracking

These are tracking/lookup helpers, not dispatch gates. Aligning them is a follow-on if needed.

## Dependencies

- `casehub-eidos-api` — already a compile dependency of `runtime`. Contains `VocabularyRegistry`, `CapabilityResolver`, `MatchDegree`, `AgentCapability`.
- No new dependencies required.

## Testing

Unit tests in `AgentCandidateFactoryTest` using a stub `VocabularyRegistry`:

1. Existing tests pass unchanged (exact match fast path)
2. Worker with vocabulary-grounded descriptor capability that subsumes requested → included
3. Worker with ungrounded descriptor capability → excluded (exact-only)
4. Worker with Specialization match → included
5. Worker without descriptor and non-matching name → excluded
6. NoOpVocabularyRegistry wired in → only exact matches
