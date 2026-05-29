# Design: WorkerDecisionEntry — ledger entry after each worker execution

**Issue:** casehubio/engine#390  
**Branch:** issue-382-sxs-batch  
**Date:** 2026-05-29  
**Depends on:** #382 (TrustRoutingPolicy in api — done)

---

## Problem

When a CaseHub worker completes successfully, there is no tamper-evident `LedgerEntry`
with `actorId = workerId` and `capabilityTag = capability` that attestations can reference.
`TrustScoreJob` computes trust scores from `LedgerEntry` records grouped by `actorId`, applying
`LedgerAttestation` records via `ledgerEntryId`. Without a worker-specific entry, post-investigation
attestations (e.g. "SAR was FLAGGED") have no anchor. Trust scores cannot be updated from
investigation outcomes.

A `CaseLedgerEntry` is currently written for "WorkerExecutionCompleted" (via `CaseLifecycleEvent`),
but it has wrong `actorType = HUMAN` and no `capabilityTag` — it's a case audit record, not
a trust scoring record. Adding `WorkerDecisionEntry` as a distinct type separates the two concerns
cleanly.

---

## Design

### New CDI event — `casehub-engine-common`

```java
// casehub-engine-common/src/main/java/io/casehub/engine/common/spi/event/WorkerDecisionEvent.java
public record WorkerDecisionEvent(
    UUID caseId, String workerId, String capabilityTag, String traceId) {}
```

Fired from `WorkflowExecutionCompletedHandler` alongside the existing `CaseLifecycleEvent`. Placing
it in `casehub-engine-common` keeps the event decoupled from the ledger module — observers are
optional, following the same pattern as `CaseLifecycleEvent`.

### `WorkflowExecutionCompletedHandler` changes — `casehub-engine` runtime

1. Inject `Event<WorkerDecisionEvent> workerDecisionEvents`.
2. Add `extractCapabilityTag(CaseInstance, Worker)` — same binding lookup as `resolveConflictStrategy`
   but returns the capability name (nullable if no matching binding found).
3. Fire `workerDecisionEvents.fireAsync(new WorkerDecisionEvent(caseId, workerId, capabilityTag, traceId))`
   in the `.invoke()` chain alongside `lifecycleEvents.fireAsync`.
4. **Fix `CaseLifecycleEvent` actorId**: change from `worker.getName()` to `"system"` for worker
   completion events. The lifecycle entry records that the system applied the worker's output; the
   `WorkerDecisionEntry` is the trust-scoring record. This eliminates double-counting in
   `TrustScoreJob.findAllEvents()` without changing `casehub-ledger`.

### New entity — `casehub-engine-ledger`

```java
@Entity
@Table(name = "worker_decision_entry")
@DiscriminatorValue("WORKER_DECISION")
public class WorkerDecisionEntry extends LedgerEntry {
    @Column(name = "worker_id", nullable = false)
    public String workerId;

    @Column(name = "capability_tag")
    public String capabilityTag;

    @Column(name = "case_id", nullable = false)
    public UUID caseId;
}
```

JOINED inheritance — `worker_decision_entry` table joined on `ledger_entry.id`. No migration in
this repo (`drop-and-create` handles it). Consumer deployments (AML) own their migration.

### New observer — `casehub-engine-ledger`

`WorkerDecisionEventCapture` — `@ApplicationScoped`, `@ObservesAsync @Transactional`:

- Uses `ledgerRepo.findLatestBySubjectId(caseId)` for sequence number — crosses all `LedgerEntry`
  subclasses, not just `CaseLedgerEntry`. This is correct because sequence is per `subjectId`.
- Sets `actorId = workerId`, `actorType = SYSTEM`, `actorRole = "WORKER"`, `subjectId = caseId`,
  `entryType = EVENT`.
- Gated on `ledgerConfig.enabled()`.

### Double-counting fix

`TrustScoreJob.findAllEvents()` returns all `LedgerEntry` records with `entryType = EVENT`.
Previously `CaseLedgerEntry("WorkerExecutionCompleted")` had `actorId = worker.getName()`.
After this change, that entry has `actorId = "system"` and will not be included in the per-actor
grouping. Only `WorkerDecisionEntry` carries the worker's `actorId`. One entry per worker execution,
no double-counting.

---

## Sequence number coordination

`CaseLedgerEventCapture` uses `ledgerRepo.findLatestByCaseId()` which queries only `CaseLedgerEntry`.
`WorkerDecisionEventCapture` uses `ledgerRepo.findLatestBySubjectId()` which queries ALL
`LedgerEntry` subclasses by `subjectId`. The two observers run asynchronously on managed threads;
both are `@Transactional` so the sequence numbers are computed inside a transaction and protected
from races via `@Transactional` boundaries and the `sequenceNumber` column's natural ordering.

---

## Testing

- Direct-method unit tests for `WorkerDecisionEventCapture` (no `@QuarkusTest`).
- Verify: `actorId = workerId`, `actorType = SYSTEM`, `capabilityTag` populated, `sequenceNumber > 0`.
- Verify: `CaseLedgerEntry` for "WorkerExecutionCompleted" has `actorId = "system"` after change.
- Integration test: fire event, verify entry persisted with correct fields.

---

## Protocols satisfied

- **engine-spi-noops-defaultbean**: `WorkerDecisionEventCapture` is a CDI observer, not a SPI bean — no @DefaultBean needed.
- **flyway-consumer-numbering**: No migration in this repo. Consumer deployments own the `worker_decision_entry` migration (V2001+ in their Flyway range).
- **spi-reactive-blocking-io**: `@ObservesAsync` delivers on a managed executor thread — blocking JPA and `@Transactional` are safe here. Same pattern as `CaseLedgerEventCapture`.
