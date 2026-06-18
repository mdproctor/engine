# Wire EXPIRED Signal to OutcomePolicy

**Issue:** engine#513
**Date:** 2026-06-18
**Status:** Approved

## Problem

`OutcomePolicy.onExpired` is declared and parsed from YAML but not wired — the engine ignores it.
Worker timeouts (`TimeoutException` from `DefaultWorkerExecutor`) currently flow through
`QuartzRetryService` as infrastructure failures: retry loop → exhaust → case FAULTED. There is no
semantic distinction between "worker crashed" and "worker timed out."

Timeouts are a semantic outcome — the worker had its chance and ran out of time. They should route
through `OutcomePolicy.onExpired` (REROUTE or FAULT), not through the infrastructure retry loop.

A timed-out worker that receives the same input at the same timeout will likely time out again.
REROUTE sends the work to a different agent with potentially different latency characteristics. If
the original worker would succeed with more time, the remedy is a longer `timeoutMs` in
`ExecutionPolicy`, not retry at the same limit.

## Signal Sources

Two sources (not mutually exclusive):

1. **Engine-internal worker timeout (this issue):** `DefaultWorkerExecutor.executeSync()` uses
   `.ifNoItem().after(Duration).fail()` which produces a `TimeoutException`. Currently falls
   through to `QuartzWorkerExecutionJob.onFailure()` → retry.

2. **Qhorus CommitmentExpiredEvent (future, qhorus#281):** When a Qhorus commitment deadline
   passes. A new CDI observer in engine runtime publishes directly to `WORKER_EXECUTION_FINISHED`
   with `WorkerOutcome.Expired` — a direct injection into the worker completion pipeline, not
   the signal bridge pattern used by `QhorusMessageSignalBridge`. The observer must resolve all
   `WorkflowExecutionCompleted` fields from the commitment, including `bindingName` (needed for
   `_outcomes` keying). Whether this requires additional metadata on the Qhorus commitment is a
   qhorus#281 concern.

This issue implements source 1 only. The design accommodates source 2 without changes — both
paths converge on the same `WorkerOutcome.Expired` entry point.

## Design

### 1. WorkerOutcome.Expired variant

Add `Expired(String reason)` to the sealed `WorkerOutcome` interface:

```java
public sealed interface WorkerOutcome
    permits WorkerOutcome.Success, WorkerOutcome.Declined,
            WorkerOutcome.Failed, WorkerOutcome.Expired {
  record Success() implements WorkerOutcome {}
  record Declined(String reason) implements WorkerOutcome {}
  record Failed(String reason) implements WorkerOutcome {}
  record Expired(String reason) implements WorkerOutcome {}
}
```

### 2. WorkerResult.expired factories

```java
public static WorkerResult expired(final String reason) {
    return new WorkerResult(Map.of(), null, new WorkerOutcome.Expired(reason));
}

public static WorkerResult expired(final String reason, final Map<String, Object> partialOutput) {
    return new WorkerResult(partialOutput, null, new WorkerOutcome.Expired(reason));
}
```

### 3. Timeout conversion in DefaultWorkerExecutor

The timeout originates in `DefaultWorkerExecutor.executeSync()` (`.ifNoItem().after(Duration).fail()`).
The executor owns the timeout — it should own the semantic conversion. The `WorkerExecutor` SPI
contract is `Uni<WorkerResult>` — timeouts are outcomes, not exceptions that should leak through
the SPI boundary.

Convert inside `executeSync()`:

```java
private Uni<WorkerResult> executeSync(...) {
    return Uni.createFrom().item(() -> { ... })
        .runSubscriptionOn(virtualThreads)
        .ifNoItem().after(Duration.ofMillis(timeoutMs)).fail()
        .onFailure(TimeoutException.class)
        .recoverWithItem(t -> WorkerResult.expired(
            "Worker timed out after " + timeoutMs + "ms"));
}
```

This means `QuartzWorkerExecutionJob` (and any future scheduler adapter) never sees
`TimeoutException` — only `WorkerResult` with `WorkerOutcome.Expired`. The Quartz adapter's
`onFailure` path handles only genuine infrastructure exceptions. No adapter duplication.

### 4. WorkflowExecutionCompletedHandler — Expired branch

The outcome fork at the top of `onWorkflowExecutionCompletedHandler` uses a negative check —
any non-Success outcome routes to `handleSemanticFailure`. This is future-safe: a hypothetical
5th variant enters `handleSemanticFailure` where the exhaustive switch produces a compile error,
instead of silently falling through to the success path. Consistent with
`PlanItemCompletionHandler` which uses the same `!(instanceof Success)` pattern.

```java
if (!(event.outcome() instanceof WorkerOutcome.Success)) {
  return handleSemanticFailure(event, traceId);
}
```

Inside `handleSemanticFailure`, all extractions use a single consolidated exhaustive switch —
one place to add a future variant, co-located values, one exhaustiveness check:

```java
final String outcomeStatus;
final String reason;
final OutcomeAction action;
final CaseHubEventType eventType;

switch (event.outcome()) {
    case WorkerOutcome.Declined d -> {
        outcomeStatus = "DECLINED";
        reason = d.reason();
        action = policy.onDecline();
        eventType = CaseHubEventType.WORKER_OUTCOME_DECLINED;
    }
    case WorkerOutcome.Failed f -> {
        outcomeStatus = "FAILED";
        reason = f.reason();
        action = policy.onFailure();
        eventType = CaseHubEventType.WORKER_OUTCOME_FAILED;
    }
    case WorkerOutcome.Expired e -> {
        outcomeStatus = "EXPIRED";
        reason = e.reason();
        action = policy.onExpired();
        eventType = CaseHubEventType.WORKER_OUTCOME_EXPIRED;
    }
    case WorkerOutcome.Success s -> throw new IllegalStateException(
        "Success should not reach handleSemanticFailure");
}
```

The `WorkResult` construction for `WorkerStatusListener` also uses an exhaustive switch to
prevent information loss at the SPI boundary (EXPIRED must not silently map to FAILED):

```java
final WorkResult workResult = switch (event.outcome()) {
    case WorkerOutcome.Declined d -> WorkResult.declined(
        event.idempotency(), worker.getName(), caseInstance.getUuid());
    case WorkerOutcome.Failed f -> WorkResult.failed(
        event.idempotency(), worker.getName(), caseInstance.getUuid());
    case WorkerOutcome.Expired e -> WorkResult.expired(
        event.idempotency(), worker.getName(), caseInstance.getUuid());
    case WorkerOutcome.Success s -> throw new IllegalStateException(
        "Success should not reach handleSemanticFailure");
};
```

Everything else in `handleSemanticFailure` — `_outcomes` writing, history, excludedAgents,
episodic panel, event log, OutcomeDisposition resolution, `WORKER_OUTCOME_RESOLVED` publishing —
works unchanged (already parameterised by `outcomeStatus` and `action`).

### 5. Supporting changes

- **`CaseHubEventType.WORKER_OUTCOME_EXPIRED`** — new enum value.
- **`WorkStatus.EXPIRED`** — new enum value, needed by `WorkResult` (the SPI type that
  `WorkerStatusListener` consumers receive). Not for `_outcomes.status` (which uses raw strings).
- **`WorkResult.expired()`** — new factory method, symmetric with `declined()` and `failed()`.
- **`OutcomePolicy` javadoc** — remove "not yet wired" qualifier from `onExpired`.
- **`WorkflowExecutionCompleted` javadoc** — update to include `Expired` in the outcome list.
- **CLAUDE.md** — document EXPIRED in the Worker Outcome Handling section.

### 6. Qhorus dovetail

The future Qhorus bridge is a new CDI observer in engine runtime that publishes directly to
`WORKER_EXECUTION_FINISHED` — not the signal bridge pattern used by `QhorusMessageSignalBridge`
(which calls `runtime.signal()` for context mutation). A commitment expiration is a worker
completion event, not a context signal.

The observer:

1. `@ObservesAsync CommitmentExpiredEvent` in engine runtime
2. Resolves `CaseInstance`, `Worker`, `bindingName`, `idempotency` from the commitment metadata
3. Publishes `WorkflowExecutionCompleted` with `WorkerOutcome.Expired` to `WORKER_EXECUTION_FINISHED`

The `bindingName` is critical — without it, `_outcomes` keying breaks. Whether the Qhorus
commitment already carries this metadata or needs to be extended is a qhorus#281 concern.

No engine changes needed beyond what this issue implements — the handler already processes
`WorkerOutcome.Expired` generically.

## What's Not Changing

- No new event bus addresses — EXPIRED reuses `WORKER_EXECUTION_FINISHED` and
  `WORKER_OUTCOME_RESOLVED`.
- No new handler classes — existing handlers branch on the new outcome variant.
- No changes to `QuartzRetryService` — it stays reserved for infrastructure failures.
- No changes to `OutcomePolicy` record structure — `onExpired` is already declared.
- No changes to YAML schema — `onExpired` is already parsed.
- `_outcomes.status` continues to use raw strings — `WorkStatus.EXPIRED` is for the `WorkResult`
  SPI type, not for `_outcomes`.
