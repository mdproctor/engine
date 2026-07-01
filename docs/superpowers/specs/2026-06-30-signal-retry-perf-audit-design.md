# Signal Retry Threading, Planning Perf, and Audit Keys

**Issues:** #620 (S/Med), #621 (XS/Low), #622 (XS/Low)
**Date:** 2026-06-30

## #620 — signalId threading through retry/exhaust path

### Problem

`signalAndAwait()` hangs when a worker throws and retries are exhausted. The success path threads signalId through `WorkflowExecutionCompleted` → `WorkflowExecutionCompletedHandler.recordCompletion()`. The failure path drops it because `WorkerRetryContext` has no `signalId` field.

Three sub-paths lose signalId:
1. **Exhaust** — `QuartzRetryService` publishes `WorkerRetriesExhaustedEvent` (no signalId) → handler never calls `recordCompletion()`
2. **Retry reschedule** — works by accident (re-reads EventLog), but signalId is not in the Quartz JobDataMap explicitly
3. **Guard quarantine** — `WorkerScheduleEventHandler` publishes `WorkerRetriesExhaustedEvent` without signalId despite having `event.signalId()`

### Design

| File | Change |
|------|--------|
| `WorkerRetryContext` | Add `signalId` (UUID, nullable). Add `withSignalId()`. `from()` reads from JobDataMap. |
| `WorkerRetriesExhaustedEvent` | Add `signalId`. Reorder fields: `(caseId, tenancyId, workerId, idempotency, bindingName, signalId)` per protocol `spi-event-tenancyid-component-order`. |
| `QuartzRetryService.rescheduleWorker()` | Put `signalId` in JobDataMap. |
| `QuartzRetryService.applyRetryDecision()` | Pass `ctx.signalId()` to `WorkerRetriesExhaustedEvent`. |
| `QuartzWorkerExecutionJob.execute()` | Build `effectiveRetryCtx` with signalId. |
| `WorkerRetriesExhaustedEventHandler` | Inject `SignalSettlementTracker`, call `recordCompletion(signalId)` when non-null. |
| `WorkerScheduleEventHandler` guard path | Pass `event.signalId()` to `WorkerRetriesExhaustedEvent`. |

Protocol fix: `WorkerRetriesExhaustedEvent` currently has `tenancyId` at position 5 — violates `spi-event-tenancyid-component-order` (must be position 2).

## #621 — SequentialPlanningStrategy perf

### Problem

`select()` calls `plan.getAllPlanItems()` → `List.copyOf(itemsById.values())` (O(n) allocation) → streams to build `Map<String, PlanItem>` — on every context-change cycle. `CasePlanModel.getPlanItemByBindingName()` exists but filters to active-only, which the strategy can't use (needs to see COMPLETED to skip forward).

### Design

| File | Change |
|------|--------|
| `CasePlanModel` | Add `findPlanItemByBindingName(String)` as `default` method → `Optional.empty()`. Any-status lookup. Per SPI evolution protocol. |
| `DefaultCasePlanModel` | Rename `activeByBinding` → `latestByBinding`. Remove lazy cleanup in `hasActivePlanItem`. Implement `findPlanItemByBindingName` from `latestByBinding`. |
| `SequentialPlanningStrategy.select()` | Replace map construction with `plan.findPlanItemByBindingName()` per binding. O(1) per lookup. |

## #622 — Bulk signal event log audit keys

### Problem

`buildBulkSignalEventLog()` stores `{"type": "bulk_signal"}` as payload — no record of what keys were updated. The individual signal handler records a JSON patch diff, but the bulk handler records nothing.

### Design

| File | Change |
|------|--------|
| `SignalReceivedEventHandler.buildBulkSignalEventLog()` | Accept `Map<String, Object> updates`. Payload: `{"type": "bulk_signal", "updates": {…}}`. Metadata: `{"updatedKeys": […]}`. |
| Call site in `applyBulkSignalUnderLock()` | Pass `event.updates()`. |
