# Signal API, Implementation Routing, and Repeatable Stage

**Issues:** engine#493, engine#476, engine#482
**Branch:** `issue-493-signal-stage-routing`
**Date:** 2026-06-15
**Status:** Approved (rev 3)

---

## 1. Signal API and CDI Event Consistency (#493)

### Root Cause

#493 was filed against the pre-#491 codebase. The panels refactor (#491) changed `asJsonNode()` from flat working data to a panel document, silently breaking all JQ evaluation — including goal conditions evaluated after a signal. The fix (PR #495, merged) corrected `JQExpressionEngine.evaluate()` to evaluate against `context.panel(WORKING).asJsonNode()`.

The engine's own test suite (`SignalDedupExtendedTest.signalAfterCaseCompletionDoesNotTriggerWorker`) confirms signal → CONTEXT_CHANGED → goal evaluation → COMPLETED works correctly in the current codebase.

#493 is resolved by #491. The work here is three architectural improvements that surfaced during analysis.

### Change A — `signal()` returns `CompletionStage<Void>`

`CaseHubRuntime.signal()` is currently `void` — fire-and-forget with no completion signal. `startCase()` returns `CompletionStage<UUID>`. This inconsistency prevents callers from coordinating on signal processing completion and makes testing fragile.

**API change (breaking):**

```java
// CaseHubRuntime — was void, now async
CompletionStage<Void> signal(UUID caseId, String path, Object value);
CompletionStage<Void> signal(UUID caseId, String path, Object value,
                             String triggerChannelId, String triggerCorrelationId);
```

```java
// CaseHub convenience — delegates to runtime
CompletionStage<Void> signal(UUID caseId, String path, Object value);
```

**Internal wiring:**

`CaseHubReactor.signal()` changes from `void` to returning `Uni<Void>`. Internally uses `eventBus.<Void>request(SIGNAL_RECEIVED, ...)` which returns `Uni<Message<Void>>`, mapped to `Uni<Void>` via `.replaceWithVoid()`. `CaseHubRuntimeImpl.signal()` subscribes via `uni.subscribeAsCompletionStage()`, matching the `startCase()` pattern.

The `CompletionStage<Void>` resolves when the signal has been applied to the context, the event log written, and CONTEXT_CHANGED published. It does NOT guarantee that goal evaluation has completed — only that CONTEXT_CHANGED has been dispatched. Callers that need to await case state transitions should use Awaitility on the case status.

### Change B — Standardise CDI fire-and-forget in all handlers

Three handlers publish CONTEXT_CHANGED and fire CDI lifecycle events. Only one uses the correct fire-and-forget CDI pattern:

| Handler | Current CDI pattern | Fix |
|---------|-------------------|-----|
| `WorkflowExecutionCompletedHandler` | `.invoke(() -> fireAsync().whenComplete())` | Already correct |
| `CaseStartedEventHandler` | `.chain(() -> Uni.from(completionStage(fireAsync())))` | Change to fire-and-forget |
| `SignalReceivedEventHandler` | `.chain(() -> Uni.from(completionStage(fireAsync())))` | Change to fire-and-forget |

CDI lifecycle events are audit/observability concerns. They must never gate case state progression. A blocking CDI observer (e.g. ledger capture doing DB writes under contention) must not stall the handler's Uni chain.

Pattern applied to both handlers:
```java
.invoke(() -> {
    lifecycleEvents.fireAsync(...)
        .whenComplete((v, t) -> {
            if (t != null) LOG.warnf(t, "CaseLifecycleEvent observer failed...");
        });
})
```

### Change C — Standardise event dispatch ordering: CDI first, CONTEXT_CHANGED second

All handlers adopt the dispatch ordering from `WorkflowExecutionCompletedHandler`:

1. Dispatch CDI lifecycle events (fire-and-forget)
2. Dispatch CONTEXT_CHANGED to event bus

Both are fire-and-forget dispatches — there is no processing-order guarantee between the CDI managed executor and the Vert.x event loop. The value of CDI-first ordering is **decoupling**: both events are dispatched regardless of what happens next. With the current `.chain()` pattern in `SignalReceivedEventHandler`, the handler's Uni completion is gated on CDI observer completion — a slow observer blocks the chain and delays CONTEXT_CHANGED dispatch. The fire-and-forget pattern decouples them. The dispatch ordering is secondary; the decoupling is the point.

### Change D — Integration test: signal → direct goal completion

New test: a signal directly satisfies a goal condition with no worker or binding intermediary. The case definition has only a goal — no bindings, no workers. `signal()` sets a context value; the goal condition matches; the case transitions to COMPLETED.

This exercises the signal-to-goal pipeline that the Claudony pattern relies on and confirms #493 is resolved.

---

## 2. ImplementationRoutingStrategy SPI (#476)

### Problem

When multiple workers register for the same capability, the engine schedules all of them. Application repos are forced to implement workarounds (QuarkMind's `StrategyTrustRouter` — ~300 lines). This is an engine concern: selecting which implementation handles a capability is symmetric to selecting which worker instance handles a task (`AgentRoutingStrategy`).

### SPI Design

Package: `io.casehub.api.spi.routing` (alongside `AgentRoutingStrategy`)

```java
public interface ImplementationRoutingStrategy {
    Uni<ImplementationSelection> select(
        ImplementationRoutingContext context,
        List<ImplementationCandidate> candidates);
}
```

**Supporting types:**

```java
public record ImplementationRoutingContext(
    UUID caseId,
    String capabilityName,
    JsonNode caseContext       // working panel JSON — matches AgentRoutingContext
) {}

public record ImplementationCandidate(
    String bindingName,
    String workerName,
    String capabilityName     // matches AgentRoutingContext field naming
) {}

public sealed interface ImplementationSelection {
    record Selected(List<String> bindingNames) implements ImplementationSelection {
        Selected {
            if (bindingNames.isEmpty())
                throw new IllegalArgumentException("Use RunNone for empty selection");
            bindingNames = List.copyOf(bindingNames);
        }
    }
    record RunAll() implements ImplementationSelection {}
    record RunNone() implements ImplementationSelection {}
}
```

**`Selected(List<String>)`** covers both single and subset selection. Compact constructor enforces non-empty and defensive copy via `List.copyOf()` — sealed types are API surface.

**`RunAll`** preserves current behaviour — all implementations run. Default fallback when the strategy cannot decide.

**`RunNone`** — all candidates are inappropriate. The capability is skipped entirely for this evaluation cycle. Covers the case where all implementations are untrusted or unavailable.

### Routing Pipeline

The relationship between `ImplementationRoutingStrategy` and `AgentRoutingStrategy` is a two-stage pipeline — separate concerns applied sequentially:

```
Binding eligibility → Stage gating
  → [ImplementationRouting: which binding(s) for this capability?]
  → PlanningStrategy: priority/ordering
  → [AgentRouting: which worker instance for this binding?]
  → Worker scheduling
```

### Default

`NoOpImplementationRoutingStrategy` — `@DefaultBean @ApplicationScoped`, returns `RunAll`. Zero behaviour change without a strategy on classpath.

Located in `runtime/src/main/java/io/casehub/engine/internal/routing/`.

### Integration Point

`PlanningStrategyLoopControl.select()` — implementation routing runs at step 3.5: after `stageLifecycleEvaluator.evaluate()` (step 3) and before `planningStrategy.select()` (step 4). Stage lifecycle evaluation can change which stages are ACTIVE, affecting binding eligibility — routing must operate on the post-evaluation state.

The routing filters **bindings before PlanItem creation**, not PlanItems after creation. This avoids a create-then-cancel anti-pattern:

1. Stage gating → gated-eligible bindings
2. `stageLifecycleEvaluator.evaluate()` → stage states updated
3. Group gated-eligible bindings by capability name (extracted from `CapabilityTarget`)
4. Groups with a single binding → pass through
5. Groups with >1 binding → build `ImplementationCandidate` list, call `select()`
6. If `Selected(bindingNames)` → keep only selected bindings, discard rest
7. If `RunAll` → keep all (current behaviour)
8. If `RunNone` → discard all bindings in the group
9. Create PlanItems only for surviving bindings via `addPlanItemIfAbsent()`

No PlanItem is ever created and then cancelled — the routing decision happens upstream of PlanItem creation.

### Failure Fallback

If the selected implementation's worker fails and retries are exhausted, the engine does NOT automatically try another implementation. The PlanItem goes FAULTED. Retry-at-routing-level (tracking which implementations have been tried) is explicitly out of scope for v1. File as tracked issue.

### Contract Tests

Abstract contract test in `api/src/test/java/io/casehub/api/spi/routing/` — verifies:
- Single candidate → returns `Selected` with that candidate
- Empty candidates → throws or returns `RunAll`
- Null context → throws

### Engine Unit Tests

In `runtime/src/test/java/io/casehub/engine/internal/routing/`:
- `NoOpImplementationRoutingStrategy` returns `RunAll`
- Verify `NoOpImplementationRoutingStrategy` is `@DefaultBean`

### Blackboard Integration Tests

In `blackboard/src/test/java/io/casehub/blackboard/it/`:
- Case with two bindings targeting the same capability, different workers
- Recording `ImplementationRoutingStrategy` selects one → only the selected worker runs
- Routing → agent routing pipeline test: routing picks the binding, agent routing picks the worker within that binding
- `RunNone` test: strategy returns `RunNone` → no PlanItem created, no worker scheduled

### Future Work (tracked issues, not in this branch)

- `TrustWeightedImplementationStrategy` in `casehub-engine-ledger` — four-phase trust maturity model, symmetric with `TrustWeightedAgentStrategy`. File as engine issue.
- QuarkMind migration — delete `StrategyTrustRouter`, `StrategySelector`, `StrategyTrustObserver` (~300 lines). File as quarkmind issue.
- Failure fallback to alternative implementation after retry exhaustion. File as engine issue.

---

## 3. Repeatable Stage (#482)

### Prerequisite: Stage–PlanItem Auto-Registration

**Critical gap (not specific to repeatable stages):** `Stage.addRequiredItem()` and `Stage.addPlanItem()` are only called from test code. Zero production call sites. `StageAutocompleteEvaluator.evaluate()` checks `stage.getRequiredItemIds().contains(changedItemId)` — with an empty `requiredItemIds`, autocomplete never fires in production for ANY stage.

**Fix (precursor commit, before #482):** `PlanningStrategyLoopControl.select()` automatically registers PlanItems with their owning stage. After `addPlanItemIfAbsent()` returns `true`, if the binding name is in a stage's `containedBindingNames`, the loop control calls:

```java
stage.addPlanItem(planItem.getPlanItemId());
stage.addRequiredItem(planItem.getPlanItemId());
```

This makes autocomplete work for ALL stages in production, not just in tests. Without this, neither repeatable nor non-repeatable stages autocomplete in production.

Committed as a separate fix before #482 since it's a general blackboard correctness issue. File as a separate engine issue.

### Problem

CMMN supports repeatable plan items (§8.5.3) — a Stage that can activate more than once during a case lifecycle. The current Stage has a single lifecycle: PENDING → ACTIVE → COMPLETED/TERMINATED. No mechanism to re-activate after completion.

QuarkMind's game loop needs a per-tick "decision stage" that activates on each `game.state` signal, runs workers, autocompletes, and re-activates on the next tick.

### Model Change

`Stage` gains two fields:

```java
private final boolean repeatable;  // set at construction, immutable
private final AtomicInteger instanceIndex = new AtomicInteger(0);
```

Builder addition only — no fluent setter:
```java
public Builder repeatable(boolean repeatable) {
    this.repeatable = repeatable;
    return this;
}
```

`repeatable` is a lifecycle-model flag set at construction time. Unlike `withEntryCondition()` and `withAutocomplete()` (which configure behaviour), `repeatable` changes the fundamental lifecycle model — a stage that wasn't constructed as repeatable should never become repeatable. The `Builder.repeatable()` method is the only way to set it.

### V1 Constraint: No Nested Stages or Milestones in Repeatable Stages

Repeatable stages with nested stages or milestones require complex lifecycle interleaving (nested stage reset semantics, milestone re-evaluation after parent reset). Runtime enforcement only — the builder lacks containment context at construction time (nested stages and milestones are added after construction via `addNestedStage()` and `addMilestone()`). `StageAutocompleteEvaluator` logs a warning and skips reset if a repeatable stage has non-empty `containedStageIds` or `containedMilestoneIds`. File nested-repeatable as tracked issue.

### Lifecycle Extension

Standard: `PENDING → ACTIVE → COMPLETED` (terminal)

Repeatable: `PENDING → ACTIVE → COMPLETED → PENDING → ACTIVE → COMPLETED → ...`

When a repeatable Stage reaches COMPLETED, it resets to PENDING with an incremented instance index.

### Reset Mechanism

New method on `Stage`:

```java
public boolean resetForRepetition() {
    if (!repeatable) return false;
    if (status.compareAndSet(StageStatus.COMPLETED, StageStatus.PENDING)) {
        instanceIndex.incrementAndGet();
        containedPlanItemIds.clear();
        requiredItemIds.clear();
        containedMilestoneIds.clear();
        activatedAt = null;
        completedAt = null;
        return true;
    }
    return false;
}
```

All containment sets are cleared — new PlanItems (with new UUIDs from `PlanItem.create()`) will be registered by the auto-registration fix above. Previous-instance PlanItems remain in the agenda in terminal states — `filterToDispatchable()` skips them, and they're visible for audit/CBR.

### Where Reset Is Called

`StageAutocompleteEvaluator` — after marking a Stage COMPLETED:

1. Capture `int completingIndex = stage.getInstanceIndex()` BEFORE reset
2. Publish `StageCompletedEvent(caseId, stage, completingIndex)`
3. If `stage.isRepeatable()`, call `resetForRepetition()`

The completion event fires with the captured index — not read from the mutable stage after reset.

### Event Record Changes

`StageCompletedEvent` and `StageActivatedEvent` gain an explicit `instanceIndex` field:

```java
public record StageCompletedEvent(UUID caseId, Stage stage, int instanceIndex) {}
public record StageActivatedEvent(UUID caseId, Stage stage, int instanceIndex) {}
```

The existing Javadoc already warns that `stage` is passed by reference and is mutable. The `instanceIndex` field captures a snapshot value at publish time — handlers use this field for the completing/activating instance, not `stage.getInstanceIndex()` which may have advanced.

`StageLifecycleEvaluator.activatePendingStages()` captures the index when publishing:
```java
eventBus.publish(BlackboardEventBusAddresses.STAGE_ACTIVATED,
    new StageActivatedEvent(ctx.caseId(), stage, stage.getInstanceIndex()));
```

### Instance Tracking

- `Stage.getInstanceIndex()` — current instance (0-based)
- Event log payload includes `instanceIndex` for each STAGE_ACTIVATED / STAGE_COMPLETED entry

### Re-activation Semantics with Persistent Entry Conditions

A repeatable stage with a persistent entry condition (e.g. a lambda that tests `context.get("game.state") != null`) will re-activate on every CONTEXT_CHANGED after reset — including those triggered by the previous instance's worker output being applied. This is correct behaviour for QuarkMind's game loop (per-output-change re-activation). Consumers who want signal-gated activation need entry conditions that test for new values (e.g. a version counter or changed flag), not the existence of existing values.

### Concurrency Policy

Sequential only: previous instance must complete before next activates. `StageLifecycleEvaluator.activatePendingStages()` naturally enforces this — a reset Stage starts in PENDING, and the entry condition re-evaluates on the next CONTEXT_CHANGED cycle.

Known timing race: `WORKER_EXECUTION_FINISHED` fan-out means auto-registration and `resetForRepetition()` may interleave on different threads (`WorkflowExecutionCompletedHandler` on event loop, `PlanItemCompletionHandler` on worker thread with `blocking = true`). The race is self-healing — at worst one evaluation cycle is skipped before the next CONTEXT_CHANGED correctly re-registers PlanItems.

Concurrent mode (overlapping instances) is a future extension. File as tracked issue.

### YAML Support

`BlackboardPlanConfigurer` supports `repeatable: true` in Stage YAML schema. `CaseDefinitionYamlMapper` maps it to `Stage.Builder.repeatable(true)`.

### Tests

**Unit tests (`StageTest`):**
- `resetForRepetition` on repeatable stage → status PENDING, index incremented, all containment sets cleared
- `resetForRepetition` on non-repeatable stage → returns false, no state change
- `resetForRepetition` on non-COMPLETED stage → returns false
- `repeatable` flag is immutable — only settable via Builder

**`StageLifecycleEvaluator` tests:**
- Repeatable COMPLETED stage (after reset) → re-evaluates entry condition on next cycle
- Non-repeatable COMPLETED stage → skipped (current behaviour preserved)

**`StageAutocompleteEvaluator` tests:**
- Autocomplete on repeatable stage → fires StageCompletedEvent with correct instanceIndex, resets to PENDING
- Instance index increments on each completion
- StageCompletedEvent.instanceIndex captures the completing instance, not the post-reset index

**`DefaultCasePlanModel` tests:**
- `addPlanItemIfAbsent` creates new PlanItems after a terminal PlanItem exists for the same binding (confirms the contract supports repetition — terminal PlanItem in `activeByBinding` is evicted)

**Integration test (`blackboard/`):**
- Signal N times → verify N Stage activations and N completions in event log, each with incrementing instance index
- Verify workers inside the Stage run once per activation
- Auto-registration: PlanItems created by `addPlanItemIfAbsent` are automatically registered as required items on their owning stage

### Future Work (tracked issues)

- Concurrent repetition mode (overlapping instances) — engine issue
- `signalAndAwait()` imperative alternative — engine issue (mentioned in #482)
- Nested stages and milestones inside repeatable stages — engine issue
- `instanceIndex` recovery on JVM restart — engine issue (minor: most cases complete before restart; recovery would replay event log to find last recorded index)

---

## Execution Order

1. **Stage–PlanItem auto-registration** — precursor blackboard correctness fix (new engine issue)
2. **#493** — signal API + CDI consistency
3. **#476** — ImplementationRoutingStrategy SPI
4. **#482** — Repeatable Stage (depends on auto-registration fix)

Each issue is committed separately with `Refs #N` / `Closes #N`.
