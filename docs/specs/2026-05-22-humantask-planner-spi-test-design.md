# Design: HumanTask Planner Fix + SPI Wiring Test Stability

**Issues:** casehubio/engine#312, casehubio/engine#303
**Date:** 2026-05-22
**Status:** Approved

---

## engine#312 — PlanningStrategyLoopControl marks humanTask PlanItems RUNNING prematurely

### Problem

`PlanningStrategyLoopControl.indexSelectedForCompletion()` calls `pi.markRunning()` and
`registry.indexWorkerForCompletion()` for **every** selected binding, regardless of target type.
For `HumanTaskTarget` (and `SubCaseTarget`, `ExtensionTarget`), `resolveWorkerName()` already
returns `"unknown"` — the completion index entry is useless and the premature RUNNING transition
causes `HumanTaskScheduleHandler` to skip WorkItem creation entirely.

### Architectural Boundary

The correct boundary is:

- **CapabilityTarget:** The planner legitimately owns the RUNNING transition. The state change
  is atomic with scheduling; no external handler needs to succeed before the transition is valid.
  The idempotency guard in `WorkerScheduleEventHandler` (`EventLog.findSchedulingEvents`)
  prevents duplicate scheduling.

- **HumanTaskTarget, SubCaseTarget, ExtensionTarget:** Protocol PP-20260517-cbf836 — the PlanItem
  must not be marked RUNNING until all resolution steps succeed. Only the handler knows if the
  dependent operation (WorkItem creation, child case start) succeeded. The handler owns this
  transition.

### Fix

In `indexSelectedForCompletion()`, switch on the binding's target type:

```java
pi -> {
    if (binding.target() instanceof CapabilityTarget) {
        pi.markRunning();
        registry.indexWorkerForCompletion(caseId, pi.getWorkerName(), pi.getPlanItemId());
    }
    // HumanTaskTarget, SubCaseTarget, ExtensionTarget: handler owns the RUNNING transition.
    // Calling markRunning() here would prevent the handler from creating the WorkItem/subCase.
}
```

The filter `pi.getStatus() == PlanItemStatus.PENDING` in the stream remains unchanged — it
ensures we only act on un-dispatched items.

### Testing

**TDD order:**
1. Write a failing `@QuarkusTest` (in `casehub-blackboard`) that starts a case with a
   `humanTask:` binding, waits for the `HumanTaskScheduleEvent` to fire, and asserts a WorkItem
   was created. This will fail because the handler skips creation (PlanItem already RUNNING).
2. Apply the fix.
3. Test passes.

Existing `HumanTaskScheduleHandlerTest` verifies the positive path; the new test covers the
integration with `PlanningStrategyLoopControl`.

---

## engine#303 — SpiWiringIntegrationTest provisioner tests timing-sensitive

### Problem

Two tests use Awaitility timeouts that are too tight for cold Podman/Docker environments:

- `workerProvisionerCalledWhenNoCandidateWorkerAvailable` — 10 seconds
- `provisioningExceptionCaughtGracefully` — 5 seconds

The provisioner path is a deep reactive chain: `CONTEXT_CHANGED` event → `@ConsumeEvent`
handler → `rules()` → `loopControl.select()` → `tryProvision()` →
`reactiveWorkerProvisioner.getCapabilities()` → `provision()`. In a cold environment, the
entire chain can exceed these windows. The test structure is correct.

### Fix

Increase both timeouts to **30 seconds**. All other tests in `SpiWiringIntegrationTest` use
10–15 seconds for simpler paths; 30 seconds gives the provisioner chain adequate headroom
without making the suite meaningfully slower on warm infrastructure (the chain completes in
< 2 seconds normally).

### Testing

Tests become non-flaky by definition once timeouts are adequate. No new test logic needed.

---

## No Out-of-Scope Items

Both fixes are contained within their respective modules. No platform doc changes, no SPI
changes, no new dependencies.
