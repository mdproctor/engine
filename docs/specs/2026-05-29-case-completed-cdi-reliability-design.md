# Design: CaseCompleted CDI Event Reliability Fix

**Issue:** engine#393  
**Date:** 2026-05-29  
**Status:** Approved (rev 2)

---

## Problem

`CaseLifecycleEvent(eventType="CaseCompleted")` is not reliably delivered to `@ObservesAsync` observers in `@QuarkusTest` environments. The root cause is that `CaseStatusChangedHandler` fires the CDI event inside `.invoke()`, which discards the `CompletionStage` returned by `fireAsync()`. The Vert.x event bus considers the message processed before CDI observers have run.

---

## Root cause

In `CaseStatusChangedHandler.onCaseStatusChangedHandler()`:

```java
.invoke(() -> {
    eventBus.publish(eventBusAddress, caseInstance);      // fire-and-forget: OK
    lifecycleEvents.fireAsync(new CaseLifecycleEvent()); // CompletionStage DISCARDED
});
```

`Event.fireAsync()` returns a `CompletionStage` that completes when all `@ObservesAsync` observers have run. Discarding it means the handler's Uni completes before observers run, making delivery non-deterministic.

---

## Fix

Restructure the final step of `CaseStatusChangedHandler.onCaseStatusChangedHandler()`:

1. Keep `eventBus.publish()` calls in `.invoke()` — they are fire-and-forget downstream processing
2. Move `lifecycleEvents.fireAsync()` into a trailing `.chain()` that awaits the `CompletionStage`
3. Log failures before recovering — silent swallowing is wrong for audit infrastructure

```java
.invoke(() -> {
    String eventBusAddress = resolveStateAsString(newState);
    if (eventBusAddress != null) {
        eventBus.publish(eventBusAddress, caseInstance);
    }
    if (newState == CaseStatus.RUNNING) {
        eventBus.publish(EventBusAddresses.CONTEXT_CHANGED,
            new CaseContextChangedEvent(caseInstance, caseInstance.getCaseContext().asJsonNode()));
    }
})
.chain(() ->
    Uni.createFrom()
        .completionStage(() -> lifecycleEvents.fireAsync(new CaseLifecycleEvent(
            caseInstance.getUuid(),
            resolveCommandType(newState),
            resolveEventType(newState),
            newState.name(),
            null,
            "System",
            traceId)))
        .onFailure()
        .invoke(t -> LOG.warnf(t,
            "CaseLifecycleEvent observer failed for caseId=%s event=%s",
            caseInstance.getUuid(), resolveEventType(newState)))
        .recoverWithNull()
        .replaceWithVoid())
```

**What this guarantees:** the `@ConsumeEvent` handler's Uni does not complete until all CDI observers have run. Any internal engine code that chains off this Uni gets deterministic delivery. For tests, see the test strategy below.

---

## File changed

`runtime/src/main/java/io/casehub/engine/internal/engine/handler/CaseStatusChangedHandler.java`

One method restructured. No other files.

---

## Tests

Add a `@QuarkusTest` in `runtime/src/test/java/io/casehub/engine/CaseLifecycleCdiEventTest.java`.

**Test strategy:** Poll the CDI capture bean directly — NOT the DB/case status. The DB state update (step 1 of the Mutiny chain) commits before CDI event delivery (step 4). A test that polls DB for COMPLETED status and then checks the capture bean will still race. The correct approach:

```java
// Correct: poll for the CDI event itself
await().atMost(5, SECONDS)
    .until(() -> captureBean.events().stream()
        .anyMatch(e -> "CaseCompleted".equals(e.eventType())));
```

**Capture bean:** `@ApplicationScoped` static inner class with `CopyOnWriteArrayList<CaseLifecycleEvent>` (GE-20260522-bc642c — `ArrayList` is not thread-safe for `@ObservesAsync`), reset in `@BeforeEach`.

**Test needs:** a minimal `CaseHub` subclass with one worker, one success goal, and the in-memory persistence alternatives active.

---

## Out of scope — follow-up issue needed

Five other production handlers have the identical `.invoke()` discard pattern for `fireAsync()`. This issue fixes only `CaseStatusChangedHandler` because it is the reported failure point. The others need a follow-up issue:

| Handler | Notes |
|---------|-------|
| `GoalReachedEventHandler` | `GoalReached` CDI event |
| `MilestoneReachedEventHandler` | `MilestoneReached` CDI event |
| `SignalReceivedEventHandler` | `SignalReceived` CDI event (mixed with eventBus.publish) |
| `CaseStartedEventHandler` | `CaseStarted` CDI event (mixed with eventBus.publish) |
| `WorkflowExecutionCompletedHandler` | `WorkerCompleted` CDI event |

A follow-up issue should fix all five in one batch. File it as part of this issue's implementation.
