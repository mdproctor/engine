# Wire PlanAdapter into CbrRetrievalService Pipeline

**Issue:** engine#738
**Date:** 2026-07-18
**Depends on:** neocortex#161 (add `caseType` to `PlanAdapter.adapt()` — delivered)

## Problem

`PlanAdapter` SPI exists in `casehub-neocortex-memory-api` but the engine never calls it.
`CbrRetrievalService` retrieves past cases and maps them directly to `RetrievedExperience`,
skipping the CBR Reuse phase entirely. Applications that implement `PlanAdapter` (e.g.
casehub-life `LifePlanAdapter`) must call it manually from their case start flow.

## Design

### Pipeline Modification

```
Before:  extract features → build query → retrieve → map to RetrievedExperience → cache
After:   extract features → build query → retrieve → map (with per-case adaptation for PlanCbrCase) → cache
```

Adaptation is conditional on the retrieved case type:
- `PlanCbrCase` → call `planAdapter.adapt(caseType, scored, features)` on each `ScoredCbrCase`
- All other case types (feature-vector, textual) → map directly as today, no adaptation

### ExperiencePlanStep Enrichment

`ExperiencePlanStep` (engine-api) gains two nullable String fields to carry adaptation signal:

```java
public record ExperiencePlanStep(
    String bindingName,
    String capabilityName,
    String workerName,
    String stepOutcome,
    int priority,
    Map<String, Object> parameters,
    String adaptationAction,    // nullable — RETAINED, SUBSTITUTED, BOOSTED, SUPPRESSED, ADDED
    String adaptationReason     // nullable — human-readable explanation
) { ... }
```

- `null` for both fields → no adaptation applied (non-plan types, or raw retrieval)
- String values by convention match `AdaptationAction` enum names from neocortex — no type
  dependency. This is required because `ExperiencePlanStep` lives in `casehub-engine-api`,
  which has no dependency on `casehub-neocortex-memory-api` where `AdaptationAction` is
  defined. Using the enum directly would couple a core API module to neocortex internals.
- REMOVED steps are filtered out entirely (not mapped to `ExperiencePlanStep`)
- ADDED steps are included in the plan trace but are adapter recommendations, not historical
  observations — `ExperienceAnalyser` must exclude them from statistical computation
  (see §ExperienceAnalyser Adaptation Filtering below)
- Flows through to routing strategies via `RetrievedExperience.planTrace`
- Persists into EventLog metadata via `WorkerScheduleEvent.experiences`

A convenience constructor is provided for the unadapted case (the majority of call sites):

```java
public ExperiencePlanStep(String bindingName, String capabilityName,
    String workerName, String stepOutcome, int priority,
    Map<String, Object> parameters) {
    this(bindingName, capabilityName, workerName, stepOutcome,
         priority, parameters, null, null);
}
```

### CbrRetrievalService Wiring

**Injection:** `PlanAdapter` injected as a constructor parameter alongside `CbrCaseMemoryStore`.
Same injection pattern — blocking SPI interface directly, called on the worker pool via
`runSubscriptionOn()`. Per GE-20260706-abaddc, inject the interface directly to avoid
`@DefaultBean` bridge resolution issues in external JARs.

**Pipeline insertion** in `retrieveInternal()`: adaptation is combined with mapping inside
`mapScoredCase()` — no separate pipeline stage or intermediate type. The pipeline remains
a single `.map()` call, with `caseType` and `features` threaded through:

```java
.map(scoredCases -> mapResults(scoredCases, caseType, features))
```

This eliminates the need for a wrapper type to carry both `ScoredCbrCase` and `AdaptedPlan`
between pipeline stages. Adaptation and mapping are conceptually one operation: "transform
scored CBR cases into retrieved experiences."

**Mapping change** in `mapScoredCase()`:
- When `cbrCase instanceof PlanCbrCase`: call `planAdapter.adapt(caseType, scored, features)`,
  then map `AdaptedStep` → `ExperiencePlanStep` including `action.name()` and `reason`.
  Filter out REMOVED steps. On adapter failure: log and fall back to raw `PlanTrace` →
  `ExperiencePlanStep` mapping (adaptation fields null).
- When non-plan case type: map as today (adaptation fields null, empty plan trace)

**Error handling:** Adapter failure on a single scored case is caught and logged — that case
falls back to raw (unadapted) mapping. Consistent with the service's existing "CBR failure
never blocks case progression" pattern.

**Test constructor:** The existing package-private constructor gains `PlanAdapter` as a third
parameter for unit test injection.

### ExperienceAnalyser Adaptation Filtering

`ExperienceAnalyser.workerSuccessRates()` computes per-worker success rates from plan trace
steps. After adaptation wiring, ADDED steps in the trace are adapter recommendations — they
were never executed in the historical case. Without filtering, they create phantom historical
evidence that inflates confidence in routing decisions.

**Change:** `workerSuccessRates()` skips steps where `adaptationAction` equals `"ADDED"`.
These steps are recommendations, not observations, and must not feed statistical models.

SUBSTITUTED steps (where the adapter changed the worker assignment) also carry misattribution
risk — the historical outcome is attributed to the substituted worker, not the original. This
is a quality concern tracked as a follow-up (see §Deferred Items).

### Explicit Non-Goals

- **No engine-side CDI event.** `TrackingPlanAdapter` decorator in neocortex already fires
  `CbrAdaptationRecorded`. The engine firing it separately would risk duplicate events.
  Persistent audit flows through `ExperiencePlanStep.adaptationAction` into EventLog metadata.

- **No new dependency.** Engine runtime already depends on `casehub-neocortex-memory-api`
  (for `CbrCaseMemoryStore`, `ScoredCbrCase`, etc.) and `casehub-neocortex-memory` (for
  `NoOpCaseMemoryStore`). `PlanAdapter` and `NoOpPlanAdapter` are in those same modules.

- **No YAML configuration.** Adaptation activates automatically when `CbrConfig` is present
  and the case type is `PlanCbrCase`. No new config properties needed.

- **No changes to `RetrievedExperience`.** The record is unchanged. Adaptation signal flows
  through the existing `planTrace` field via the enriched `ExperiencePlanStep`.

- **No full consumer adaptation-awareness (this spec).** Downstream consumers
  (`CbrRoutingPromptSection`, `TrustWeightedAgentStrategy`, `CbrAgentRoutingStrategy`)
  are not updated to interpret `adaptationAction`/`adaptationReason`, with one exception:
  `ExperienceAnalyser` filters ADDED steps to prevent phantom historical evidence (see
  §ExperienceAnalyser Adaptation Filtering). Carrying the data now avoids a second record
  change when consumers are updated. Full consumer adaptation-awareness is tracked as
  follow-up issues (see §Deferred Items).

## Files Changed

### engine-api (`api/`)

| File | Change |
|------|--------|
| `ExperiencePlanStep.java` | Add nullable `adaptationAction` and `adaptationReason` fields, convenience constructor |
| `ExperienceAnalyser.java` | Skip ADDED steps in `workerSuccessRates()` |
| `ExperiencePlanStepTest.java` | Test new fields, null defaults, convenience constructor |
| `ExperienceAnalyserTest.java` | Test ADDED step filtering; update `step()` helper to use convenience constructor |
| `RetrievedExperienceTest.java` | Update constructor calls to use convenience constructor |

### engine runtime (`runtime/`)

| File | Change |
|------|--------|
| `CbrRetrievalService.java` | Inject `PlanAdapter`, adapt within `mapScoredCase()`, update mapping |
| `CbrRetrievalServiceTest.java` | Test adaptation wiring, error isolation, non-plan passthrough |

### engine-ledger (`ledger/`)

| File | Change |
|------|--------|
| `TrustWeightedAgentStrategyTest.java` | Update constructor calls to use convenience constructor |

## Testing Strategy

1. **ExperiencePlanStep construction** — verify nullable adaptation fields, convenience constructor
2. **Adaptation wiring** — PlanCbrCase results are adapted, non-plan types pass through
3. **caseType threading** — resolved caseType reaches the adapter
4. **Error isolation** — adapter failure on one case does not block others; falls back to raw mapping
5. **NoOp passthrough** — with `NoOpPlanAdapter`, behavior is identical to today (RETAINED action)
6. **Feature reuse** — extracted features are passed to both the query and the adapter
7. **ExperienceAnalyser ADDED filtering** — ADDED steps excluded from success rate computation
8. **Existing tests pass** — no regression in current CbrRetrievalService behavior

## Deferred Items

| Issue | Description |
|-------|-------------|
| [wsp-casehub-engine#1](https://github.com/mdproctor/wsp-casehub-engine/issues/1) | `ExperienceAnalyser`: handle SUBSTITUTED steps — outcome misattributed to substituted worker |
| [wsp-casehub-engine#2](https://github.com/mdproctor/wsp-casehub-engine/issues/2) | `CbrRoutingPromptSection`: annotate adapted steps in LLM routing prompt |

## References

- GE-20260706-abaddc — `@DefaultBean` injection resolution in external JARs
- GE-20260706-56a75c — `WorkerOutcomeResolvedEvent` fires only for non-success outcomes
- neocortex#161 — add `caseType` to `PlanAdapter.adapt()` (delivered)
- neocortex#85 — original PlanAdapter SPI (delivered)
- engine#707 — experience flow to workers (separate concern)
