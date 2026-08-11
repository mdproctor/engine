# Design: WorkerStarted CaseLifecycleEvent + causedByEntryId

**Issue:** engine#389  
**Date:** 2026-05-29  
**Status:** Approved (rev 2 — clean architecture)

---

## Problem

When an external provisioner successfully allocates a worker, no `CaseLifecycleEvent` is fired. This means the audit ledger has no record of external worker provisioning succeeding, and claudony cannot establish a causal link between the Qhorus COMMAND that triggered provisioning and the resulting ledger entry.

---

## Design decisions

**`ProvisionResult` instead of `causedByEntryId` on `Worker`.** `Worker` is a case-definition artifact; adding a provisioning-outcome field to it conflates two roles. The provisioner SPI returns `ProvisionResult` — a typed outcome envelope. `Worker` stays a definition carrier.

**`CaseLifecycleEvent` stays at 7 fields.** `causedByEntryId` would be null for every event except `WorkerStarted` and only meaningful to claudony's ledger observer. Shared events must not carry consumer-specific fields. Claudony's provisioner stores `causedByEntryId` in an in-memory map on provision; its ledger capture drains the map when it observes `WorkerStarted`.

**`traceId` captured before the reactive chain.** The `CaseLifecycleEvent` Javadoc is explicit: OTel trace ID must be captured synchronously before `fireAsync()`. Capturing inside `.flatMap()` would produce `null` after a real async call crosses a thread boundary.

---

## Changes

### 1. `ProvisionResult` — new record in `api/spi/`

```java
package io.casehub.api.spi;

public record ProvisionResult(UUID causedByEntryId) {
    public static ProvisionResult empty() { return new ProvisionResult(null); }
}
```

### 2. `WorkerProvisioner` and `ReactiveWorkerProvisioner` — change return type

```java
// blocking
ProvisionResult provision(Set<String> capabilities, ProvisionContext context);

// reactive
Uni<ProvisionResult> provision(Set<String> capabilities, ProvisionContext context);
```

Breaking change. All implementors and contract tests update.

### 3. No-op defaults — return `ProvisionResult.empty()`

`NoOpWorkerProvisioner.provision()` and `NoOpReactiveWorkerProvisioner.provision()` return `ProvisionResult.empty()`.

### 4. `CaseContextChangedEventHandler.tryProvision()` — fire `WorkerStarted` event

Two new injections:
- `@Inject Event<CaseLifecycleEvent> lifecycleEvents`
- `@Inject LedgerTraceIdProvider traceIdProvider`

```java
private Uni<Void> tryProvision(final CaseInstance caseInstance, final Capability capability) {
    final String traceId = traceIdProvider.currentTraceId().orElse(null); // capture before chain
    return reactiveWorkerProvisioner
        .getCapabilities()
        .flatMap(caps -> {
            if (!caps.contains(capability.getName())) return Uni.createFrom().voidItem();
            ...
            return reactiveWorkerProvisioner
                .provision(caps, provisionContext)
                .flatMap(result -> {
                    lifecycleEvents.fireAsync(new CaseLifecycleEvent(
                        caseInstance.getUuid(),
                        "ProvisionWorker",   // verb-noun PascalCase — consistent with convention
                        "WorkerStarted",
                        caseInstance.getState().name(),
                        null,
                        "System",
                        traceId));
                    return Uni.createFrom().voidItem();
                });
        })
        ...
}
```

`WorkerStarted` always fires when provisioning succeeds — the event records "provisioner allocated a worker" regardless of whether `causedByEntryId` is set.

### 5. `CaseContextChangedEventHandlerRoutingTest` — add missing mocks

```java
@Mock Event<CaseLifecycleEvent> lifecycleEvents;
@Mock LedgerTraceIdProvider traceIdProvider;
// in @BeforeEach:
when(traceIdProvider.currentTraceId()).thenReturn(Optional.empty());
```

### 6. `CaseLifecycleEvent` — no changes

Stays at 7 fields. **No** `causedByEntryId`. All existing call sites unmodified.

### 7. Contract tests — update for new return type

`WorkerProvisionerContractTest` and `ReactiveWorkerProvisionerContractTest`: assertions on return value update to `ProvisionResult`.

---

## Naming

| Event | commandType | Where fired | Semantics |
|-------|-------------|-------------|-----------|
| `WorkerExecutionStarted` | `ExecuteWorker` | `QuartzWorkerExecutionJobListener` | Quartz job begins — internal worker about to run |
| `WorkerStarted` | `ProvisionWorker` | `CaseContextChangedEventHandler.tryProvision()` | External provisioner succeeded |

---

## Tests

- `WorkerTest`: no changes (no new `Worker` fields)
- `CaseContextChangedEventHandlerRoutingTest`: add `@Mock Event<CaseLifecycleEvent>` + `@Mock LedgerTraceIdProvider`; add test asserting `WorkerStarted` fires when provisioner returns
- Contract tests: update `ProvisionResult` assertions
- No-op tests: `DefaultWorkerSpiImplementationsTest` — update assertions for `ProvisionResult.empty()`

---

## Cross-repo

File claudony issue: provisioner stores `(caseId → causedByEntryId)` in a `ConcurrentMap` on provision; `ClaudonyLedgerEventCapture` drains by caseId when it observes `WorkerStarted`. No new types needed in `casehub-engine-common`. Timing is safe — store happens before the event fires.

**Note:** `causedByEntryId` will be `null` in all `ProvisionResult` instances until engine#231 threads Qhorus trigger context through to `ProvisionContext`. The event and SPI are in place; the values arrive with that follow-on.

---

## Out of scope

- claudony implementation (tracked in new claudony issue)
- `CaseLedgerEntry.causedByEntryId` — not a field in this repo's ledger entry
