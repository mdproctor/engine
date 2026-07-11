# CBR-Informed Routing Pipeline — End-to-End Wiring

**Epic:** #706 | **Issues:** #703, #505
**Date:** 2026-07-11

## Problem

The CBR retrieval path is fully wired (`CbrRetrievalService` → `AgentRoutingContext.experiences()`),
but two gaps prevent end-to-end operation:

1. **No retain path.** There is no case-level retain step — only `CbrRoutingOutcomeRecorder`
   writes per-routing-decision entries. The store is never populated with case-level outcomes.
2. **Strategies bypass the pipeline.** `CbrAgentRoutingStrategy` and `CbrRoutingPromptSection`
   make their own redundant `CbrCaseMemoryStore.retrieveSimilar()` queries, ignoring the
   pre-retrieved `context.experiences()` the engine already provides.

## Architecture

```
Case closes                               Case dispatches
    │                                           │
    ▼                                           ▼
CaseStatusChangedHandler              CaseContextChangedEventHandler
    │                                           │
    ▼                                           ▼
CaseOutcomeObserver.onOutcome()       CbrRetrievalService.retrieve()
    │                                           │
    ▼                                           ▼
CbrCaseRetainObserver ──store()──►    ◄──retrieveSimilar()──
    │                  CbrCaseMemoryStore                   │
    ▼                                                       ▼
PlanCbrCase(problem,              List<RetrievedExperience>
  solution, outcome,                        │
  features, planTrace)                      ▼
                                  AgentRoutingContext.experiences()
                                            │
                              ┌─────────────┼─────────────┐
                              ▼             ▼             ▼
                     CbrAgentRouting  CbrRoutingPrompt
                     Strategy         Section
                     (reads)          (reads)
```

### Recording granularity

After #505, a single recording mechanism remains:

| Mechanism | Granularity | When | What it stores |
|-----------|-------------|------|----------------|
| `CbrCaseRetainObserver` (#703) | Per-case | Case terminal state | Full plan trace, all capability bindings/workers/outcomes |

`CbrRoutingOutcomeRecorder` (per-routing-decision) is removed as part of #505 — its entries
use incompatible domain/caseType and are not retrievable by `CbrRetrievalService`. The retain
observer provides the comprehensive plan trace that strategies need.

### Design boundaries

**Outcome string alignment.** The retain observer maps `TaskStatus` to outcome strings:
COMPLETED→"SUCCESS", FAULTED→"FAILURE", REJECTED→"DECLINED", CANCELLED→"CANCELLED",
OBSOLETE→"OBSOLETE". `CbrAgentRoutingStrategy.OUTCOME_WEIGHTS` maps: SUCCESS=1.0,
GATE_EXPIRED=0.5, GATE_REJECTED=0.25, FAILURE=0.0. "DECLINED", "CANCELLED", and "OBSOLETE"
are not in this map and default to 0.0 via `getOrDefault()` — equivalent to FAILURE for routing
purposes. This is intentional: all represent negative outcomes from an agent routing perspective.
If differentiated scoring is needed (e.g., DECLINED agents may still suit other cases), extend
`OUTCOME_WEIGHTS` as part of #505 when the strategy is refactored to consume
`context.experiences()`.

## Issue #703 — CBR Retain Observer

### CaseOutcomeEvent enrichment

Add `tenancyId` (String) to `CaseOutcomeEvent`. Required for:
- `PlanItemStore.findByCaseId(caseId, tenancyId)`
- `CbrCaseMemoryStore.store()` tenancy parameter

Update `CaseStatusChangedHandler.fireOutcomeObservers()` to pass `caseInstance.tenancyId`.

### CbrCaseRetainObserver

**Location:** `runtime/src/main/java/io/casehub/engine/internal/memory/CbrCaseRetainObserver.java`

**CDI:** `@ApplicationScoped`, implements `CaseOutcomeObserver`

**Injections:**
- `CbrCaseMemoryStore` — direct injection (matches `CbrRetrievalService` pattern;
  `NoOpCbrCaseMemoryStore` `@DefaultBean` handles absent store transparently)
- `CaseDefinitionRegistry` — look up `CaseDefinition` by caseType
- `PlanItemStore` — reconstruct plan trace
- `JQEvaluator` — JQ feature extraction

**Flow:**
1. Look up `CaseDefinition` via `registry.findByName(event.caseType())`:
   - `Optional.empty()` → log warning ("definition not registered at case close"), return early
   - `IllegalArgumentException` (ambiguous name) → catch, log warning, return early
2. Get `CbrConfig` from `CaseDefinition` — return early if null (CBR not configured)
3. Resolve domain: `CbrConfig.domain()`, falling back to `EpisodicMemoryConfig.domain()`.
   If null → log warning, return early. Same resolution as `CbrRetrievalService.resolveDomain()`.
4. Extract features from `caseFileSnapshot` using `CbrConfig.featureExtractor()`:
   - `JqFeatureExtractor`: convert Map→JsonNode, apply JQ expressions via `JQEvaluator`
   - `LambdaFeatureExtractor`: wrap Map in a read-only `SnapshotCaseContext` adapter
   If features are empty → log warning ("CbrConfig present but all features evaluated to
   empty for case definition '{name}'"), return early. Mirrors `CbrRetrievalService`'s
   empty-features guard — a featureless entry would have degenerate similarity scoring
   and would never be meaningfully retrieved.
5. Query `PlanItemStore.findByCaseId(event.caseId(), event.tenancyId())`
6. Build capability name lookup map from `CaseDefinition.getBindings()`:
   filter to `CapabilityTarget` bindings, map `bindingName → capability().name()`.
   Filter `PlanItemRecord` list to records matching ALL of:
   (a) terminal (`status.isTerminal()`),
   (b) present in the capability name map,
   (c) `executorName != null` (plan items that reached terminal state before worker assignment
   — e.g., CANCELLED before dispatch — carry no routing signal and would produce
   `"→null"` in the solution string).
   Non-capability bindings (SubCase, HumanTask, Extension) are excluded — `PlanTrace` requires
   non-null `capabilityName`, and only capability bindings participate in agent routing.
   Active plan items (PENDING, RUNNING, DELEGATED, SUSPENDED) are excluded — they never
   completed and provide no routing signal.
   **If the filtered list is empty, return early** (log at DEBUG). An empty trace means no
   capability bindings completed with an assigned worker — there is nothing meaningful to
   retain. This also avoids `PlanCbrCase` validation failure: the `solution` synthesis
   (step 8) would produce an empty string, which the compact constructor rejects.
7. Map each filtered `PlanItemRecord` to `PlanTrace`:
   - `bindingName` → `record.bindingName()`
   - `capabilityName` → from capability name lookup map
   - `workerName` → `record.executorName()`
   - `stepOutcome` → map `TaskStatus`: COMPLETED→"SUCCESS", FAULTED→"FAILURE",
     REJECTED→"DECLINED", CANCELLED→"CANCELLED", OBSOLETE→"OBSOLETE"
   - `priority` → 0
   - `parameters` → `Map.of()`
8. Construct `PlanCbrCase`:
   - `problem` → `event.caseType()` (case definition name — semantic identity for retrieval)
   - `solution` → synthesized plan summary: "{binding}→{worker}({outcome})" per trace entry,
     joined with ", " (e.g., "assignAgent→worker-1(SUCCESS), reviewCase→worker-2(FAILURE)")
   - `outcome` → `event.outcomeLabel()` (terminal status: "COMPLETED", "FAULTED", "CANCELLED")
   - `confidence` → null
   - `features` → from step 4
   - `planTrace` → from step 7
9. Call `cbrStore.store()`:
   - `cbrCase` → `PlanCbrCase` from step 8
   - `caseType` → `event.caseType()`
   - `entityId` → `"case-retain"`
   - `domain` → `new MemoryDomain(resolvedDomain)` from step 3
   - `tenantId` → `event.tenancyId()`
   - `caseId` → `event.caseId().toString()`

**Error handling:** All exceptions caught and logged. Never blocks case progression.

### SnapshotCaseContext

Minimal read-only `CaseContext` adapter for Lambda feature extraction at retain time.
Wraps `Map<String, Object>` from `CaseOutcomeEvent.caseFileSnapshot()`. Lives in
`runtime/internal/memory/`.

**Behavioral contract:** Only the WORKING layer is populated (from `caseFileSnapshot`).
All other layer accessors (`layer(ContextLayer.STABLE)`, etc.) return empty maps/null values.
Mutation methods throw `UnsupportedOperationException`. This contract must be documented in
`SnapshotCaseContext` Javadoc — Lambda feature extractors that access non-WORKING layers will
receive empty results at retain time, producing potentially incomplete feature sets compared to
runtime extraction. This is inherent to the retain-time context: only the final working layer
snapshot is available after case close.

## Issue #505 — Strategies Consume context.experiences()

Changes are in blocks only.

### CbrAgentRoutingStrategy

**Remove:** `CbrCaseMemoryStore`, `RoutingFeatureExtractor` injections, `topK`/`minSimilarity`
config properties (retrieval config is on `CbrConfig`, not strategy config).

**Change `doSelect()`:** Replace `tryCbrStore()` with analysis of `context.experiences()`.
The `analyseByType()` method adapts to work on `RetrievedExperience.planTrace()` →
`ExperiencePlanStep` instead of `ScoredCbrCase<PlanCbrCase>` → `PlanTrace`.

**Keep:** `AgentGraphQuery` fallback, trust classification flow.

### CbrRoutingPromptSection

**Remove:** `CbrCaseMemoryStore`, `RoutingFeatureExtractor` injections, `topK`/`minSimilarity`
config properties.

**Change `render()`:** Format from `context.experiences()` instead of querying store.
Same formatting logic, adapted to `RetrievedExperience` / `ExperiencePlanStep`.

### CbrRoutingOutcomeRecorder — remove

After #505, `CbrRoutingOutcomeRecorder` writes entries that nothing reads:
- The strategy no longer queries the store directly (reads `context.experiences()` instead)
- `CbrRetrievalService` queries by `CbrConfig` domain and case definition name, which does
  not match outcome recorder entries (`MemoryDomain(capabilityName)` and case UUID)

The retain observer (#703) provides a superset of the routing signal: full plan trace at case
close vs. single trace entry per decision. Per-decision entries accumulating as dead data in
the store is not acceptable — remove `CbrRoutingOutcomeRecorder` as part of #505.

If per-decision incremental learning is needed in the future, it should be re-implemented
with `CbrConfig`-aligned storage parameters (domain, caseType) so entries are retrievable
by `CbrRetrievalService`.

### RoutingFeatureExtractor + TextOnlyFeatureExtractor — remove

After removing `CbrRoutingOutcomeRecorder` and the `RoutingFeatureExtractor` injections from
`CbrAgentRoutingStrategy` and `CbrRoutingPromptSection`, zero consumers of
`RoutingFeatureExtractor` remain. Both the interface and its `@DefaultBean` implementation
(`TextOnlyFeatureExtractor`) are dead code — remove as part of #505 cleanup.

## Execution Order

1. **#703** — adds the missing retain path
2. **#505** — removes redundant strategy queries

#705 (RoutingFeatureExtractor promotion) is moot — after #505 removes
`CbrRoutingOutcomeRecorder` and the strategy/prompt section injections, zero consumers
of `RoutingFeatureExtractor` remain. Both `RoutingFeatureExtractor` (interface) and
`TextOnlyFeatureExtractor` (`@DefaultBean` implementation) are removed as part of #505
cleanup. Close #705 as superseded.

## Testing

### #703
- Unit test `CbrCaseRetainObserver`: mock store, verify `store()` called with correct
  `PlanCbrCase` fields (features, plan trace, outcome) and correct store parameters
  (`caseType`, `entityId`, `domain`, `tenantId`, `caseId`)
- Test: no `CbrConfig` → no store call
- Test: `CaseDefinition` not found (`Optional.empty()`) → log warning, no store call
- Test: ambiguous name (`IllegalArgumentException`) → log warning, no store call
- Test: domain unresolvable → log warning, no store call
- Test: store exception → logged, not propagated
- Test: plan trace mapping — verify `TaskStatus` → outcome string mapping
- Test: non-capability bindings filtered from plan trace
- Test: active (non-terminal) plan items filtered from plan trace
- Test: null `executorName` plan items filtered from plan trace
- Test: empty filtered trace (all records filtered) → no store call, debug log
- Test: empty features (all JQ expressions null/error) → no store call, warn log
- Test: JQ feature extraction from snapshot Map
- Test: Lambda feature extraction via `SnapshotCaseContext`
- Test: `CaseOutcomeEvent` with tenancyId round-trip
- Test: `problem` and `solution` field values in constructed `PlanCbrCase`

### #505
- Unit test `CbrAgentRoutingStrategy`: provide experiences via context, verify correct agent
  selection based on plan trace analysis
- Test: empty experiences → unresolvable (falls through to graph/trust)
- Test: experiences with plan traces → highest success rate worker selected
- Unit test `CbrRoutingPromptSection`: provide experiences via context, verify formatted output
- Test: empty experiences → null (no prompt section)
- Verify `CbrRoutingOutcomeRecorder` removed — no remaining references in blocks production code
- Verify `RoutingFeatureExtractor` and `TextOnlyFeatureExtractor` removed — zero consumers remain
- Verify `RoutingOutcomeRecorder` SPI still exists (other implementations may use it)

## Garden entries referenced

- **GE-20260706-56a75c** — `WorkerOutcomeResolvedEvent` fires only for non-success outcomes.
  Not directly relevant to #703 (which uses `CaseOutcomeObserver`, not event bus), but validates
  the architecture: case-level retain uses the observer SPI, not event bus addresses.
