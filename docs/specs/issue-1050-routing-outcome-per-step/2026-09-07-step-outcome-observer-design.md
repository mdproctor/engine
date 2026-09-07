# StepOutcomeObserver — Per-Step Outcome Observation SPI

**Issue:** casehubio/engine#1050
**Date:** 2026-09-07

## Problem

The engine provides case-level outcome observation (`CaseOutcomeObserver`) and per-step routing
outcome recording (`RoutingOutcomeRecorder`), but no SPI for per-step outcome observation with
sufficient context for CBR recording. `CaseOutcomeObserver` fires once at case terminal state —
consumers get the final case file snapshot and the full plan trace. `RoutingOutcomeRecorder` fires
per-step but carries only `AgentRoutingContext` — no `caseType`, no context snapshot as `Map`,
insufficient data for feature extraction.

Consumer need (fsitrading C6a): record each worker execution step as its own CBR case with features
extracted from the context snapshot at that step's point in time. This enables step-level retrieval —
"last time agent X executed step Y under conditions Z, the outcome was W" — which is more actionable
for plan adaptation than incident-level memory.

**Correction to #1050 description:** `RoutingOutcomeRecorder` already fires per-step
(`WorkflowExecutionCompletedHandler.fireOutcomeRecorder()` lines 221 and 463). The blocker is not
that it doesn't fire per-step, but that no SPI exists for step-level outcome observation with
sufficient context for CBR recording.

## Design

### New SPI: `StepOutcomeObserver`

Package: `io.casehub.api.spi`

Symmetric with `CaseOutcomeObserver`. The engine discovers all `@ApplicationScoped
StepOutcomeObserver` beans via CDI and calls `onStepOutcome(StepOutcomeEvent)` after each worker
execution completes — on both success and failure paths.

```java
public interface StepOutcomeObserver {
    void onStepOutcome(StepOutcomeEvent event);
}
```

### `StepOutcomeEvent`

Package: `io.casehub.api.spi`

Record carrying primitives and a Map — same shape as `CaseOutcomeEvent`. No engine domain types
(no `CaseDefinition`, no `CaseInstance`).

```java
public record StepOutcomeEvent(
    UUID caseId,
    String tenancyId,
    String caseType,
    String bindingName,
    String capabilityName,       // @Nullable — null for JudgmentTarget traces
    String workerName,
    RoutingOutcome outcome,
    Map<String, Object> contextSnapshot,  // working layer at step execution time
    Duration executionDuration   // @Nullable
) {}
```

Fields:

| Field | Source | Notes |
|-------|--------|-------|
| `caseId` | `caseInstance.getUuid()` | |
| `tenancyId` | `caseInstance.tenancyId` | |
| `caseType` | `caseInstance.getCaseMetaModel().getName()` | Consumer uses this to find their CaseDefinition/CbrConfig |
| `bindingName` | `event.bindingName()` | Step identity |
| `capabilityName` | `extractCapabilityTag()` | Nullable — null for JudgmentTarget |
| `workerName` | `worker.name()` | Which agent executed |
| `outcome` | `RoutingOutcome.SUCCESS` or `FAILURE` | Mapped from WorkerOutcome |
| `contextSnapshot` | working layer snapshot as Map | **Pre-output-application** on success path; current snapshot on failure path |
| `executionDuration` | `extractDurationMs()` converted | Nullable — from protocolMetadata |

### Context Snapshot Timing

**Success path:** working layer snapshot captured before `contextOutputApplier.apply()`. The feature
vector describes the conditions under which the decision was made, not the world after execution.
CBR queries match on input conditions. Plan adaptation compares current conditions against stored
conditions — both sides must be input conditions for comparison to be meaningful.

**Failure path:** Current snapshot at time of failure handling. No output was applied, so there is
no before/after distinction. The snapshot captures what the step saw when it failed.

### Default Bean

```java
@DefaultBean
@ApplicationScoped
public class NoOpStepOutcomeObserver implements StepOutcomeObserver {
    @Override
    public void onStepOutcome(StepOutcomeEvent event) {
        // transparent no-op
    }
}
```

Location: `runtime/src/main/java/io/casehub/engine/internal/worker/NoOpStepOutcomeObserver.java`
(alongside `NoOpCaseOutcomeObserver`).

### Integration in `WorkflowExecutionCompletedHandler`

Injection:

```java
@Inject Instance<StepOutcomeObserver> stepOutcomeObserver;
```

`Instance<>` with `isResolvable()` guard — same pattern as `RoutingOutcomeRecorder`. Fire-and-forget
with exception isolation (catch and log).

New private method:

```java
private void fireStepOutcomeObserver(
    CaseInstance caseInstance,
    Worker worker,
    String bindingName,
    RoutingOutcome outcome,
    Map<String, Object> contextSnapshot,
    Long executionDurationMs) {
  if (stepOutcomeObserver.isUnsatisfied()) {
    return;
  }
  String capabilityName = extractCapabilityTag(caseInstance, worker, bindingName);
  Duration duration = executionDurationMs != null
      ? Duration.ofMillis(executionDurationMs) : null;
  try {
    stepOutcomeObserver.get().onStepOutcome(new StepOutcomeEvent(
        caseInstance.getUuid(),
        caseInstance.tenancyId,
        caseInstance.getCaseMetaModel().getName(),
        bindingName,
        capabilityName,
        worker.name(),
        outcome,
        contextSnapshot,
        duration));
  } catch (Exception err) {
    LOG.warnf(err,
        "Step outcome observation failed for caseId=%s worker=%s binding=%s",
        caseInstance.getUuid(), worker.name(), bindingName);
  }
}
```

**Success path call site** (after `fireOutcomeRecorder`, before `personalitySignalRecorder`):

The working layer snapshot must be captured **before** `contextOutputApplier.apply()` — insert
a new local variable alongside the existing `contextBefore` (which captures the full context):

```java
Map<String, Object> workingLayerBefore = MAPPER.convertValue(
    caseInstance.getCaseContext().layer(ContextLayer.WORKING).asJsonNode(), MAP_TYPE);
// ... then contextOutputApplier.apply() ...
fireStepOutcomeObserver(
    caseInstance, worker, bindingName,
    RoutingOutcome.SUCCESS,
    workingLayerBefore,
    extractDurationMs(event));
```

This matches `CaseOutcomeEvent`'s snapshot approach: working layer only via
`layer(ContextLayer.WORKING).asJsonNode()` converted to `Map<String, Object>` with
`MAPPER.convertValue()`. The existing `contextBefore` captures the full context (all layers) —
`StepOutcomeEvent` scopes to the working layer because that's where domain state lives and
where feature extraction operates.

**Failure path call site** (after `fireOutcomeRecorder`, before `personalitySignalRecorder`):

```java
fireStepOutcomeObserver(
    caseInstance, worker, bindingName,
    RoutingOutcome.FAILURE,
    MAPPER.convertValue(
        caseInstance.getCaseContext().layer(ContextLayer.WORKING).asJsonNode(), MAP_TYPE),
    extractDurationMs(event));
```

### Outcome Mapping

| WorkerOutcome | RoutingOutcome passed to StepOutcomeObserver |
|---------------|---------------------------------------------|
| Success | SUCCESS |
| Completed | SUCCESS |
| Declined | FAILURE |
| Failed | FAILURE |
| Expired | FAILURE |

This matches the existing `fireOutcomeRecorder` mapping — SUCCESS for success path,
FAILURE for all non-success. The consumer can discriminate further by querying EventLog
if needed (the step's `WORKER_EXECUTION_COMPLETED` entry carries the specific outcome type).

### Module Impact

| Module | Change |
|--------|--------|
| `api` | `StepOutcomeObserver` interface, `StepOutcomeEvent` record |
| `runtime` | `NoOpStepOutcomeObserver`, two call sites in `WorkflowExecutionCompletedHandler` |

No changes to `engine-common`, `persistence-*`, `planning`, or any other module.

### Testing

1. **Unit test:** `StepOutcomeObserverTest` in `runtime/src/test/java/` — same pattern as
   `CaseOutcomeObserverTest`. Define a `CaseHub` subclass with a worker, inject a recording
   `StepOutcomeObserver` (`@Alternative @Priority(1)`), verify:
   - Observer fires on worker success with `RoutingOutcome.SUCCESS`
   - Observer fires on worker failure with `RoutingOutcome.FAILURE`
   - `contextSnapshot` is non-empty and contains expected keys
   - `caseType`, `bindingName`, `workerName` are populated correctly
   - Observer exception does not block case progression

2. **No-op default test:** Verify `NoOpStepOutcomeObserver` is discovered when no consumer
   implementation exists (covered by existing test infrastructure).

### Consumer Usage (fsitrading example — not part of this PR)

```java
@ApplicationScoped
public class FsiStepOutcomeObserver implements StepOutcomeObserver {
    @Inject CbrCaseMemoryStore cbrStore;
    @Inject FsiFeatureExtractor featureExtractor;

    @Override
    public void onStepOutcome(StepOutcomeEvent event) {
        Map<String, FeatureValue> features =
            featureExtractor.extractFromSnapshot(event.contextSnapshot());
        if (features.isEmpty()) return;

        PlanCbrCase stepCase = new PlanCbrCase(
            event.caseType(),
            event.bindingName() + "→" + event.workerName(),
            event.outcome().name(),
            null,
            features,
            List.of(new PlanTrace(
                event.bindingName(), event.capabilityName(),
                event.workerName(), event.outcome().name(), 0, Map.of(), null)),
            null, event.workerName());

        cbrStore.store(stepCase, event.caseType(), "step-retain",
            new MemoryDomain("fsi"), event.tenancyId(),
            event.caseId().toString(), Path.root());
    }
}
```

## References

- [CaseOutcomeObserver](../../api/src/main/java/io/casehub/api/spi/CaseOutcomeObserver.java) — case-level symmetric SPI
- [RoutingOutcomeRecorder](../../api/src/main/java/io/casehub/api/spi/routing/RoutingOutcomeRecorder.java) — existing per-step routing recorder (thin context)
- [CbrCaseRetainObserver](../../runtime/src/main/java/io/casehub/engine/internal/memory/CbrCaseRetainObserver.java) — case-level CBR recording
- [WorkflowExecutionCompletedHandler](../../runtime/src/main/java/io/casehub/engine/internal/engine/handler/WorkflowExecutionCompletedHandler.java) — integration point
- [GE-20260706-56a75c] — WorkerOutcomeResolvedEvent fires only for non-success outcomes
- [PP-20260723-c4c1cf] — virtual-thread handler convention (@RunOnVirtualThread + void)
- [PP-20260514-engine-spi-noops-defaultbean] — @DefaultBean no-op convention
