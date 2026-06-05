# ActionRiskClassifier SPI — Design Spec

**Issue:** casehubio/engine#402  
**Branch:** issue-402-action-risk-classifier-spi  
**Date:** 2026-06-05 (revised after review)

---

## Problem

Workers execute autonomously. When a worker proposes a consequential action — filing a SAR,
freezing an account, deploying to production, cancelling a subscription, submitting a regulatory
report — there is no platform-level mechanism to pause and route that decision to a human before
the case advances. Every worker that needs human approval must implement bespoke approval logic
or skip the gate entirely.

`ActionRiskClassifier` fixes this at the platform layer: the worker declares intent, the engine
classifies the risk, and if approval is required the engine pauses, routes to a human via a
WorkItem, and resumes or rejects when the human responds. Workers write zero approval logic.

---

## Consumers and Requirements

| Repo | Use cases | Key constraint |
|------|-----------|----------------|
| casehub-aml | SAR filing, account freeze, law enforcement referral | MLRO sign-off; gate audit trail is a compliance artefact |
| casehub-clinical | SUSAR filing, dose modification, patient withdrawal, SAE reporting | Physician/clinician approval; regulatory deadlines (24h, 7-day) |
| devtown | Production deploy, contributor access, security escalation | Project-configurable thresholds |
| casehub-life | Spend above threshold, non-refundable bookings, contractor instruction | Household-configurable thresholds |
| casehub-openclaw | Oversight channel gate in Epic 6 end-to-end wiring | Full round-trip: classify → gate → human decision → workflow continues |

AML and clinical require specific approver roles (`candidateGroups`) and approval deadlines
(`expiresIn`). The gate mechanism is a **WorkItem** — these fields are already first-class on
`WorkItemCreateRequest`. Qhorus oversight channels are the right mechanism for routing escalation
(engine#377/#383); action approval is a structured HITL concern owned by `casehub-work`.
OpenClaw and Life can bridge WorkItems to messaging channels on their side.

---

## Design

### 1. Worker-facing API

**`WorkerResult`** replaces `Map<String, Object>` as the return type for all worker functions.

```java
// api/src/main/java/io/casehub/api/model/WorkerResult.java
public record WorkerResult(
    Map<String, Object> output,
    @Nullable PlannedAction plannedAction) {

  public static WorkerResult of(Map<String, Object> output) {
    return new WorkerResult(output, null);
  }

  public static WorkerResult of(Map<String, Object> output, PlannedAction action) {
    return new WorkerResult(output, action);
  }
}
```

Workers without consequential actions return `WorkerResult.of(output)` — behaviour unchanged.
Workers proposing actions return `WorkerResult.of(output, PlannedAction.of(...))`.

This is a **deliberate breaking change** to all worker function signatures:
- `Function<Map<String,Object>, Map<String,Object>>` → `Function<Map<String,Object>, WorkerResult>`
- `Agent.execute(Map<String,Object>)` → returns `WorkerResult`
- Workflow workers always return `WorkerResult.of(output)` — no PlannedAction in v1 (workflow DSL
  extension tracked as follow-up)

**Worker migration** — mechanical, no behaviour change: `return map` → `return WorkerResult.of(map)`.
No compatibility shim is provided. All consumer repos update their workers when taking the new engine
version. The engine has no external users so a coordinated same-release migration is correct.

Note: `WorkerContext.declareAction()` (thread-local side channel) was evaluated as an alternative.
Rejected because workflow workers run on a different thread from `WorkerExecutionContext.set()` —
thread-local values don't survive the async boundary. `WorkerResult` travels with the event; no
thread-safety concern.

**`PlannedAction`** — what the worker declares:

```java
// api/src/main/java/io/casehub/api/spi/PlannedAction.java
public record PlannedAction(
    @Nullable String workerId,      // null from worker; populated by engine before classify()
    @Nullable UUID caseId,          // null from worker; populated by engine before classify()
    String description,             // "Cancel Netflix subscription for household account"
    String actionType,              // "subscription.cancel", "sar.file", "spend.transfer"
    Map<String, Object> context     // {"amount": 49.99, "provider": "Netflix", ...}
) {
  /** Worker-facing factory — workerId and caseId populated by engine before classify(). */
  public static PlannedAction of(
      String description, String actionType, Map<String, Object> context) {
    return new PlannedAction(null, null, description, actionType, context);
  }

  /** Engine enrichment — called before passing to the classifier. */
  public PlannedAction withIdentity(String workerId, UUID caseId) {
    return new PlannedAction(workerId, caseId, description, actionType, context);
  }
}
```

`workerId` and `caseId` are engine metadata populated via `withIdentity()` before `classify()` is
called. Classifiers receive a fully populated `PlannedAction`. `context` is `Map<String, Object>`
— AML and clinical pass structured values (nested maps, numbers), not flat strings.

---

### 2. SPI

#### Interfaces

```java
// api/src/main/java/io/casehub/api/spi/ActionRiskClassifier.java (blocking, convenience)
public interface ActionRiskClassifier {
  RiskDecision classify(PlannedAction action);
}

// api/src/main/java/io/casehub/api/spi/ReactiveActionRiskClassifier.java (primary — called by engine)
public interface ReactiveActionRiskClassifier {
  Uni<RiskDecision> classify(PlannedAction action);
}
```

Reactive is primary because classifiers do async work (DB queries for risk scores, config lookups,
external API calls). The blocking interface is bridged automatically by the chained classifier.

```java
// api/src/main/java/io/casehub/api/spi/RiskDecision.java
public sealed interface RiskDecision {
  record Autonomous() implements RiskDecision {}

  record GateRequired(
      String reason,                          // human-readable, shown as WorkItem title context
      boolean reversible,                     // purely presentational — no engine routing logic
      @Nullable List<String> candidateGroups, // null = no group restriction on WorkItem
      @Nullable Duration expiresIn,           // null = no expiry on WorkItem
      @Nullable String scope                  // SLA preference key, null = no SLA
  ) implements RiskDecision {}
}
```

**`reversible` is purely presentational.** The engine serialises it into the WorkItem payload so
the approver UI can show "This action cannot be undone." It does not affect engine routing, approval
logic, or WorkItem creation in any way. Classifiers that want irreversible actions to require higher
approver tiers encode that policy in `candidateGroups` — the engine does not infer it from
`reversible`.

#### Classifier composition — `@RiskClassifier` qualifier

Multiple classifiers can be active simultaneously (e.g., a hospital deployment with both AML and
clinical classifiers). CDI cannot resolve two `@ApplicationScoped ActionRiskClassifier` beans
without a qualifier.

Solution: `@RiskClassifier` qualifier in `api/spi/`. Consumer implementations use
`@RiskClassifier @ApplicationScoped`. The engine ships `ChainedReactiveActionRiskClassifier`
which injects `@RiskClassifier Instance<ActionRiskClassifier>` — no circular dependency because
`ChainedReactiveActionRiskClassifier` implements `ReactiveActionRiskClassifier`, not
`ActionRiskClassifier`.

```java
// api/src/main/java/io/casehub/api/spi/RiskClassifier.java
@Qualifier
@Retention(RUNTIME)
@Target({METHOD, FIELD, PARAMETER, TYPE})
public @interface RiskClassifier {}
```

```java
// engine/internal/worker/ChainedReactiveActionRiskClassifier.java
@ApplicationScoped  // NOT @DefaultBean — displaces the autonomous default when any @RiskClassifier bean exists
public class ChainedReactiveActionRiskClassifier implements ReactiveActionRiskClassifier {

  @Inject @RiskClassifier Instance<ActionRiskClassifier> classifiers;

  @Override
  public Uni<RiskDecision> classify(PlannedAction action) {
    if (classifiers.isUnsatisfied()) {
      // No consumer classifiers registered — always Autonomous (safe default)
      return Uni.createFrom().item(new Autonomous());
    }
    return Uni.createFrom()
        .item(() ->
            StreamSupport.stream(classifiers.spliterator(), false)
                .map(c -> c.classify(action))
                .reduce(new Autonomous(), this::mostRestrictive))
        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    // Blocking classifiers (DB queries, API calls) must run off the Vert.x IO thread.
    // runSubscriptionOn offloads the entire chain to the worker pool.
  }

  private RiskDecision mostRestrictive(RiskDecision a, RiskDecision b) {
    if (!(b instanceof GateRequired gb)) return a;
    if (!(a instanceof GateRequired ga)) return b;
    return narrower(ga, gb);
  }

  /**
   * When two classifiers both return GateRequired, the one with the narrower candidateGroups
   * wins entirely — its reason, reversible, expiresIn, and scope are all used as-is.
   *
   * Union semantics are wrong for compliance: ["mlro"] ∪ ["physician"] = ["mlro", "physician"]
   * means either role can approve, which breaks both AML (MLRO authority) and clinical (physician
   * authority). The stricter requirement takes precedence; the losing classifier's gate is
   * subsumed. Case definition authors must structure multi-domain actions into sequential workers
   * with separate gates when different approver pools are required.
   *
   * Tie-breaking order: fewest candidates → shortest expiresIn → CDI iteration order (first wins).
   */
  private GateRequired narrower(GateRequired a, GateRequired b) {
    int sizeA = a.candidateGroups() == null ? Integer.MAX_VALUE : a.candidateGroups().size();
    int sizeB = b.candidateGroups() == null ? Integer.MAX_VALUE : b.candidateGroups().size();
    if (sizeA != sizeB) return sizeA < sizeB ? a : b;
    // Equal group count: shorter expiry is more restrictive
    if (a.expiresIn() != null && b.expiresIn() != null) {
      return a.expiresIn().compareTo(b.expiresIn()) <= 0 ? a : b;
    }
    if (a.expiresIn() != null) return a; // deadline is more restrictive than no deadline
    if (b.expiresIn() != null) return b;
    return a; // CDI iteration order: first wins
  }
}
```

**Consumer pattern:**

```java
@RiskClassifier
@ApplicationScoped
public class AmlActionRiskClassifier implements ActionRiskClassifier {
  @Override
  public RiskDecision classify(PlannedAction action) {
    if ("sar.file".equals(action.actionType())) {
      return new GateRequired(
          "SAR submission — MLRO sign-off required",
          false,
          List.of("mlro", "senior-analyst"),
          Duration.ofHours(24),
          "casehubio/aml/sar-submission");
    }
    return new Autonomous();
  }
}
```

**No separate `DefaultActionRiskClassifier` needed.** `ChainedReactiveActionRiskClassifier`
returns `Autonomous` via the `isUnsatisfied()` guard when no consumer provides a `@RiskClassifier`
classifier — adding a `@DefaultBean @RiskClassifier` fallback would cause `isUnsatisfied()` to
always return false, breaking the startup warning and making the guard dead code. The chain IS the
default. Add `ChainedReactiveActionRiskClassifier` to the beans table in
PP-20260514-engine-spi-noops-defaultbean.

#### Classifier failure — fail-safe

If `classify()` throws or the reactive `Uni` fails, the engine treats the failure as:

```java
new GateRequired("Classifier error — manual review required before proceeding",
    true, null, null, null)
```

This fail-safe default is the only acceptable choice for AML/clinical. A classifier failure that
allows an action to proceed autonomously could have regulatory consequences. The error is logged at
ERROR level with the classifier class name and full stack trace. The SPI Javadoc must specify this
behaviour so classifier implementors know not to throw to bypass the gate.

---

### 3. Engine integration

**`WorkflowExecutionCompleted`** event gains `@Nullable PlannedAction plannedAction`. The event
retains its existing `Map<String,Object> output` field — `WorkerResult` is unpacked at the
`QuartzWorkerExecutionJob` boundary and does not propagate as a type beyond it.

**`QuartzWorkerExecutionJob`**: function and agent workers return `WorkerResult`. Workflow workers
wrap output as `WorkerResult.of(output)`. The `plannedAction` field of `WorkflowExecutionCompleted`
is set from `WorkerResult.plannedAction()`.

**`WorkflowExecutionCompletedHandler`** forks before applying any output:

```
if (event.plannedAction() == null):
    → existing path, unchanged

else if (instance.getPendingActionGate() != null):
    → ERROR log: concurrent gate unsupported in v1
    → proceed as if plannedAction == null (existing path)

else:
    enrichedAction = event.plannedAction().withIdentity(worker.getName(), instance.getUuid())
    classify(enrichedAction) →

      Autonomous:
          → existing path, unchanged

      GateRequired:
          → gate path (see §4)

      classifier throws:
          → fail-safe GateRequired("Classifier error — ...", true, null, null, null)
          → gate path
```

**Concurrent gate constraint (v1):** `CaseInstance` holds one `pendingActionGate`. If a second
worker returns a `PlannedAction` while a gate is already pending, the engine logs an ERROR and
proceeds as `Autonomous` for the second action. The first gate takes precedence. Multi-gate support
is deferred. Case definition authors must avoid designs where two concurrent workers may
simultaneously propose actions requiring gates.

**`WorkerOutputApplier`** — no longer needed. The gate approval path re-fires
`WorkflowExecutionCompleted` with `plannedAction=null`, which goes through the existing completion
path. No output application logic is duplicated or extracted.

---

### 4. Pending gate state and gate initiation

**`PendingActionGate`** record in `common/internal/model/`:

```java
public record PendingActionGate(
    long gateId,                       // EventLog id of ACTION_GATE_PENDING entry; embedded in callerRef
    String workerId,
    String idempotency,
    Map<String, Object> deferredOutput,
    PlannedAction plannedAction        // fully enriched (workerId + caseId populated)
) {}
```

`Worker` is intentionally absent. `Worker.getFunction().getValue()` can be a lambda —
not Jackson-serializable. The approved handler retrieves the `Worker` at resolution time via
`caseDefinitionRegistry.getCaseDefinition(instance.getCaseMetaModel())` filtered by `workerId`.
No serialization required.

**`CaseInstance`** gains `private PendingActionGate pendingActionGate` with getter/setter.

**`CaseInstanceEntity`** (JPA): new `pending_action_gate` column — nullable JSON blob (`@Column`,
serialized via Jackson). No migration needed — schema is `drop-and-create`.

**`InMemoryCaseInstanceRepository`**: handles the new field naturally.

**Gate path in `WorkflowExecutionCompletedHandler`** when `GateRequired` fires:

1. Write `ACTION_GATE_PENDING` EventLog entry (`EventStreamType.CASE`) — the compliance audit record.
   See §7 for exact payload structure.
2. Populate `PendingActionGate` on `CaseInstance`; save.
3. Publish `ActionGateScheduleEvent` to event bus.
4. **Do not** apply output to case context.
5. **Do not** fire `CONTEXT_CHANGED`.
6. **Do not** call `workerStatusListener` — the worker is not yet done.
7. Fire `CaseLifecycleEvent(ACTION_GATE_PENDING)` for observability.

---

### 5. `CallerRef` sealed hierarchy — structural change

`CallerRef` is currently `record CallerRef(UUID caseId, String planItemId)`. Adding a gate format
requires a sealed hierarchy because a gate ref has no `planItemId` field.

**New structure:**

```java
// work-adapter/src/main/java/io/casehub/workadapter/CallerRef.java
public sealed interface CallerRef permits PlanItemCallerRef, GateCallerRef {

  UUID caseId();

  static @Nullable CallerRef parse(String raw) {
    if (raw == null) return null;
    Matcher pi = PI_PATTERN.matcher(raw);
    if (pi.matches()) return new PlanItemCallerRef(UUID.fromString(pi.group(1)), pi.group(2));
    Matcher gate = GATE_PATTERN.matcher(raw);
    if (gate.matches()) return new GateCallerRef(UUID.fromString(gate.group(1)), Long.parseLong(gate.group(2)));
    return null;
  }
}

// case:{caseId}/pi:{planItemId}
public record PlanItemCallerRef(UUID caseId, String planItemId) implements CallerRef {
  public static String encode(UUID caseId, String planItemId) {
    return "case:" + caseId + "/pi:" + planItemId;
  }
}

// case:{caseId}/gate:{gateId}
public record GateCallerRef(UUID caseId, long gateId) implements CallerRef {
  public static String encode(UUID caseId, long gateId) {
    return "case:" + caseId + "/gate:" + gateId;
  }
}
```

**Call site impact** — all existing usages of `ref.planItemId()` and `CallerRef.encode()` require
pattern-matching updates. Affected files (production + test):

- `WorkItemLifecycleAdapter` (3 parse call sites, each using `ref.caseId()` + `ref.planItemId()`)
- `HumanTaskScheduleHandler` (2 `CallerRef.encode()` call sites → `PlanItemCallerRef.encode()`)
- `HumanTaskRecoveryService` (1 `CallerRef.encode()` → `PlanItemCallerRef.encode()`)
- `PlanItemCompletionApplier` (receives `planItemId` from caller — callers update, not this class)
- All test call sites (~12 across `WorkItemLifecycleAdapterTest`, `HumanTaskScheduleHandlerTest`,
  `HumanTaskRecoveryServiceTest`)

This is a real structural refactoring, not a trivial extension. It is explicitly scoped as part of
this issue.

---

### 6. Gate mechanism (work-adapter)

**`ActionGateWorkItemHandler`** — new class in `work-adapter/`,
`@ConsumeEvent(ACTION_GATE_SCHEDULE, blocking=true) @Transactional`:

- Creates a WorkItem directly via `WorkItemService.create()`.
- **No PlanItem. No BlackboardRegistry.** The gate is a first-class work-adapter concept.
- callerRef: `GateCallerRef.encode(event.caseId(), event.gateId())` →
  `"case:{caseId}/gate:{gateId}"`.
- title: `GateRequired.reason()`.
- candidateGroups, expiresAt (from `expiresIn`), scope: from `GateRequired`.
- payload: full `PlannedAction` + `reversible` serialized as JSON (human sees what the agent
  proposed, including the description and actionType — not just the context map).
- Tenancy: `ActionGateScheduleEvent` carries `tenancyId`. The handler establishes tenant context
  via the same `@Transactional` boundary pattern as `HumanTaskScheduleHandler`.

**`WorkItemLifecycleAdapter.onWorkItemLifecycle()` restructured:**

```java
CallerRef ref = CallerRef.parse(workItem.callerRef);
if (ref == null) return;

// Gate refs bypass the blackboard guard — gates have no PlanItem
if (ref instanceof GateCallerRef gateRef) {
    actionGateCompletionApplier.apply(gateRef, status, workItem);
    return;
}

// Existing PlanItemCallerRef path — blackboard guard applies
if (registry.get(ref.caseId()).isEmpty()) { ... return; }
PlanItemCallerRef piRef = (PlanItemCallerRef) ref;
applier.apply(piRef.caseId(), piRef.planItemId(), status, workItem);
```

The gate check must come **before** the blackboard guard, not after. Without this ordering, gate
WorkItem lifecycle events silently die when no active `CasePlanModel` is registered — which is
always the case for gates (they create no PlanItem).

**`ActionGateCompletionApplier`** — new class in `work-adapter/`, `@ApplicationScoped`:

| WorkItemStatus | Action |
|----------------|--------|
| COMPLETED | publish `ActionGateApprovedEvent(caseId, gateId, workItemResolution)` |
| REJECTED | publish `ActionGateRejectedEvent(caseId, gateId, resolution)` |
| CANCELLED | same as REJECTED |
| EXPIRED | publish `ActionGateExpiredEvent(caseId, gateId)` |

Work-adapter publishes events back to the engine runtime — output application stays in the engine.

---

### 7. Gate resolution (engine runtime)

**`ActionGateApprovedHandler`** — `@ConsumeEvent(ACTION_GATE_APPROVED)`:

1. Load `CaseInstance`; read `pendingActionGate`. If null, log WARN and return (idempotent).
2. **Terminal state guard:** if `instance.getState()` is COMPLETED, FAULTED, or CANCELLED: log
   WARN ("gate resolved on terminated case — discarding deferred output"), clear gate, save, return.
3. Write `ACTION_GATE_APPROVED` EventLog entry (`EventStreamType.CASE`). See §8 for payload.
4. Write `actionGateApproved: {actionType, workerId, resolution}` to case context — downstream
   workers and case definitions can observe who approved and what the resolution was.
5. Clear `pendingActionGate`, save `CaseInstance`.
6. Re-publish `WorkflowExecutionCompleted(instance, worker, idempotency, deferredOutput, plannedAction=null)`
   to `WORKER_EXECUTION_FINISHED`.

Step 6 re-enters the normal completion machinery with `plannedAction=null` — no re-classification,
no re-gating. `WorkflowExecutionCompletedHandler` applies the deferred output, calls
`workerStatusListener.onWorkerCompleted()`, calls `caseResumptionService.resumeIfWaiting()`, fires
`CaseLifecycleEvent(WORKER_EXECUTION_COMPLETED)` and `WorkerDecisionEvent`, and publishes
`CONTEXT_CHANGED`. `PlanItemCompletionHandler` in the blackboard (if present) handles
`WORKER_EXECUTION_FINISHED` and marks the PlanItem COMPLETED. `StageAutocompleteEvaluator` fires
normally. No duplication of completion logic; no new coupling to the blackboard.

**`ActionGateRejectedHandler`** — `@ConsumeEvent(ACTION_GATE_REJECTED)`:

1. Load `CaseInstance`. Terminal state guard (same as approved handler).
2. Call `workerStatusListener.onWorkerCompleted(WorkResult.faulted(idempotency, workerId, caseId))`
   — external observers (Claudony, monitoring) see a faulted completion, not a silent disappearance.
3. Write `ACTION_GATE_REJECTED` EventLog entry (`EventStreamType.CASE`).
4. Write `actionGateRejected: {actionType, reason, workerId, resolution}` to case context.
5. Clear `pendingActionGate`, save.
6. Publish `ACTION_GATE_REJECTED` on event bus (also consumed by blackboard module — see §9).
7. Fire `CONTEXT_CHANGED`.

**Case definitions react via `contextChange(".actionGateRejected")`** — same pattern as
`workItemEscalated`. Case definitions using consequential workers **must** include a rejection
handler binding. If no binding reacts, the case stalls — this is the same behaviour as any
unhandled context change. There is no engine fallback in v1. Document as a required convention.

**`ActionGateExpiredHandler`** — same shape as rejected: `workerStatusListener.onWorkerCompleted(faulted)`,
write `actionGateExpired` to context, fire `ACTION_GATE_EXPIRED`, fire `CONTEXT_CHANGED`.

---

### 8. Case termination with pending gate

`CaseStatusChangedHandler.onCaseStatusChangedHandler()` already handles terminal transitions
(COMPLETED, FAULTED, CANCELLED). Add: if `instance.getPendingActionGate() != null` at termination
time, publish `ActionGateCancelledEvent(caseId, gateId)`.

**`ActionGateCancelledHandler`** in work-adapter: receives `ActionGateCancelledEvent`, looks up
and cancels the gate WorkItem via `WorkItemService.cancel(callerRef)`. This prevents orphaned
WorkItems resolving after case termination. If the WorkItem is already terminal (already approved
or rejected before the case terminated), cancellation is a no-op.

The gate approval/rejection handlers already include terminal state guards (§7) — if a WorkItem
resolves after case termination (race condition), the handlers detect the terminal state and discard
the deferred output.

---

### 9. Blackboard integration (new handlers in `casehub-engine-blackboard`)

Gate rejection/expiry must mark the associated PlanItem terminal so `StageAutocompleteEvaluator`
can complete the stage. The engine runtime does not depend on the blackboard module, so this is
done via two new event bus handlers in `casehub-engine-blackboard`:

**`ActionGateRejectedPlanItemHandler`** — `@ConsumeEvent(ACTION_GATE_REJECTED, blocking=true)`:
- Finds the `CasePlanModel` for `caseId` in `BlackboardRegistry`.
- Locates the PlanItem by `workerId` (the worker whose action was rejected).
- Calls `item.markFaulted()` — gate rejection is a terminal failure for the worker.
- Saves via `PlanItemStore`.
- `StageAutocompleteEvaluator` fires on the next CONTEXT_CHANGED (which the engine runtime fires).

**`ActionGateExpiredPlanItemHandler`** — same shape for expiry.

Gate approval is handled automatically: re-publishing `WorkflowExecutionCompleted` to
`WORKER_EXECUTION_FINISHED` triggers `PlanItemCompletionHandler` (already in the blackboard), which
marks the PlanItem COMPLETED. No new handler needed for the approval path.

---

### 10. EventLog payload structures

All gate events use `EventStreamType.CASE`.

**`ACTION_GATE_PENDING` payload:**
```json
{
  "gateId": 12345,
  "workerId": "risk-analysis-worker",
  "idempotency": "abc123hash",
  "deferredOutput": { "riskScore": 0.87, "recommendation": "APPROVE" },
  "plannedAction": {
    "description": "File SAR for account ACC-123",
    "actionType": "sar.file",
    "context": { "accountId": "ACC-123", "amount": 50000, "currency": "GBP" }
  },
  "gateRequired": {
    "reason": "SAR submission to regulator — MLRO sign-off required",
    "reversible": false,
    "candidateGroups": ["mlro", "senior-analyst"],
    "expiresInSeconds": 86400,
    "scope": "casehubio/aml/sar-submission"
  }
}
```

**`ACTION_GATE_APPROVED` payload:**
```json
{
  "gateId": 12345,
  "workerId": "risk-analysis-worker",
  "approvedBy": "user-mlro-001",
  "resolution": "{\"approverNote\": \"Evidence reviewed and approved for submission\"}"
}
```

`approvedBy` is sourced from `workItem.assignee` — the user who claimed and completed the WorkItem.
If null (completed without explicit claim), the handler falls back to parsing
`resolution.completedBy` if present, otherwise omits the field. For AML compliance, MLROs must
claim the WorkItem before completing it; null `assignee` on a SAR approval WorkItem should be
logged as a data integrity warning.

**`ACTION_GATE_REJECTED` payload:**
```json
{
  "gateId": 12345,
  "workerId": "risk-analysis-worker",
  "rejectedBy": "user-mlro-001",
  "resolution": "{\"reason\": \"Insufficient evidence — request further investigation\"}"
}
```

`rejectedBy` follows the same sourcing rule as `approvedBy`: `workItem.assignee`, fallback to
`resolution.completedBy`.

**`ACTION_GATE_EXPIRED` payload:**
```json
{
  "gateId": 12345,
  "workerId": "risk-analysis-worker",
  "expiresAt": "2026-06-06T10:00:00Z"
}
```

---

### 11. New event types

Added to `CaseHubEventType`:

```java
ACTION_GATE_PENDING,
ACTION_GATE_APPROVED,
ACTION_GATE_REJECTED,
ACTION_GATE_EXPIRED,
ACTION_GATE_CANCELLED,
```

---

### 12. New event bus addresses

Added to `EventBusAddresses`:

```java
ACTION_GATE_SCHEDULE   = "action-gate.schedule"
ACTION_GATE_APPROVED   = "action-gate.approved"
ACTION_GATE_REJECTED   = "action-gate.rejected"
ACTION_GATE_EXPIRED    = "action-gate.expired"
ACTION_GATE_CANCELLED  = "action-gate.cancelled"
```

---

### 13. Deployment constraint and startup warning

Returning `GateRequired` requires `casehub-work-adapter` on the classpath. If absent,
`ActionGateScheduleEvent` fires with no handler — the case stalls. The default classifier always
returns `Autonomous`, so deployments without work-adapter are not broken by default.

**Startup warning:** A `@Singleton @Startup` observer in the engine runtime checks:
- Are any `@RiskClassifier ActionRiskClassifier` beans registered? (`!classifiers.isUnsatisfied()`)
  This is true only when a consumer has provided at least one qualified classifier — the chain
  itself does not carry `@RiskClassifier`, and there is no `@DefaultBean` fallback.
- Is `WorkItemService` unavailable? (`Instance<WorkItemService>.isUnsatisfied()`)
- If both true: log `WARN("ActionRiskClassifier beans detected but casehub-work-adapter is not " +
  "on the classpath. GateRequired decisions will stall indefinitely.")`

---

### 14. Testing strategy

**Unit tests (no container):**
- `ChainedReactiveActionRiskClassifier`: empty chain → Autonomous; single classifier; two
  classifiers — Autonomous + Autonomous → Autonomous; Autonomous + GateRequired → GateRequired;
  GateRequired + GateRequired → merged (most restrictive candidateGroups, shorter expiresIn)
- `WorkflowExecutionCompletedHandler`: plannedAction=null → existing path (no classify);
  plannedAction + Autonomous → existing path; plannedAction + GateRequired → gate path
  (verify EventLog written, pendingActionGate set, no CONTEXT_CHANGED published)
- `ActionGateApprovedHandler`: re-fires WorkflowExecutionCompleted with plannedAction=null;
  terminal state guard (COMPLETED/FAULTED/CANCELLED → discards); null pendingActionGate → idempotent
- `CallerRef.parse()`: pi format → PlanItemCallerRef; gate format → GateCallerRef; invalid → null

**Integration tests** (`@QuarkusTest`, pattern from `SpiWiringIntegrationTest`):

Gate wiring test — inner `@Alternative @Priority(1) @ApplicationScoped` classifier that records
`classify()` calls, returns a static `GateRequired`. Verify:
- `classify()` was called with the enriched `PlannedAction` (workerId and caseId non-null)
- `ActionGateScheduleEvent` was published
- `pendingActionGate` is set on the case
- No `CONTEXT_CHANGED` was published

Gate approval path — fire `ActionGateApprovedEvent` manually; verify deferred output applied to
case context, `WORKER_EXECUTION_FINISHED` re-fired, PlanItem COMPLETED (if blackboard active).

Gate rejection path — fire `ActionGateRejectedEvent`; verify `actionGateRejected` in case context,
`workerStatusListener` called with FAULTED, PlanItem FAULTED (if blackboard active).

**Work-adapter tests** (mock `WorkItemService` — no real casehub-work deployment needed):
- `ActionGateWorkItemHandler`: creates WorkItem with correct callerRef, title, candidateGroups,
  expiresAt, payload (full PlannedAction + reversible)
- `WorkItemLifecycleAdapter` gate routing: gate callerRef routes to `ActionGateCompletionApplier`
  BEFORE the blackboard guard; pi callerRef routes normally

---

### 15. Files changed

**`api/` module:**
- New: `model/WorkerResult.java`
- New: `spi/PlannedAction.java`
- New: `spi/RiskClassifier.java` (qualifier)
- New: `spi/ActionRiskClassifier.java`
- New: `spi/ReactiveActionRiskClassifier.java`
- New: `spi/RiskDecision.java`
- Changed: `model/event/CaseHubEventType.java` — add `ACTION_GATE_*` types
- Changed: `model/ai/Agent.java` — `execute()` returns `WorkerResult`
- Changed: `model/ai/AgentBuilder.java`

**`common/` module:**
- New: `internal/model/PendingActionGate.java`
- New: `internal/event/ActionGateScheduleEvent.java`
- New: `internal/event/ActionGateApprovedEvent.java`
- New: `internal/event/ActionGateRejectedEvent.java`
- New: `internal/event/ActionGateExpiredEvent.java`
- New: `internal/event/ActionGateCancelledEvent.java`
- Changed: `internal/model/CaseInstance.java` — `pendingActionGate` field
- Changed: `internal/event/EventBusAddresses.java` — `ACTION_GATE_*` addresses

**`engine/runtime/` module:**
- New: `internal/worker/DefaultActionRiskClassifier.java` (`@DefaultBean @RiskClassifier @ApplicationScoped`)
- New: `internal/worker/ChainedReactiveActionRiskClassifier.java` (`@ApplicationScoped`)
- New: `internal/engine/handler/ActionGateApprovedHandler.java`
- New: `internal/engine/handler/ActionGateRejectedHandler.java`
- New: `internal/engine/handler/ActionGateExpiredHandler.java`
- New: `internal/startup/ActionGateDeploymentHealthCheck.java` (startup warning)
- Changed: `internal/engine/handler/WorkflowExecutionCompletedHandler.java`
- Changed: `internal/engine/handler/CaseStatusChangedHandler.java` — cancel gate on termination

**`scheduler-quartz/` module:**
- Changed: `QuartzWorkerExecutionJob.java` — handle `WorkerResult`, pass `plannedAction`

**`work-adapter/` module:**
- New: `ActionGateWorkItemHandler.java`
- New: `ActionGateCompletionApplier.java`
- New: `ActionGateCancelledHandler.java`
- Changed: `CallerRef.java` → sealed `CallerRef` interface + `PlanItemCallerRef` + `GateCallerRef`
- Changed: `WorkItemLifecycleAdapter.java` — gate routing before blackboard guard + call site updates
- Changed: `HumanTaskScheduleHandler.java` — `CallerRef.encode()` → `PlanItemCallerRef.encode()`
- Changed: `HumanTaskRecoveryService.java` — same
- Changed: all test files with `CallerRef` usage (~12 test call sites)

**`casehub-engine-blackboard/` module:**
- New: `ActionGateRejectedPlanItemHandler.java`
- New: `ActionGateExpiredPlanItemHandler.java`

**`persistence-hibernate/` module:**
- Changed: `CaseInstanceEntity.java` — `pending_action_gate` nullable JSON column

**`persistence-memory/` module:**
- Changed: `InMemoryCaseInstanceRepository.java`

**Protocols and docs:**
- Updated: PP-20260514-engine-spi-noops-defaultbean — add `DefaultActionRiskClassifier` to table
- Updated: `docs/CLAUDE.md` — document `ActionRiskClassifier` SPI, `@RiskClassifier` pattern,
  `GateRequired` deployment constraint, concurrent gate v1 limitation

---

### 16. Deferred issues

| Issue | Description |
|-------|-------------|
| Workflow PlannedAction support | Workflow workers return `WorkerResult.of(output)` in v1. Requires workflow DSL extension to declare intent mid-execution. |
| Lightweight gate path without work-adapter | Qhorus oversight channel as alternative gate mechanism for deployments without casehub-work. |
| Multi-gate support | v1 supports one pending gate per case. Multiple simultaneous gated workers requires `List<PendingActionGate>` and WorkItem-to-gate correlation rework. |
| Consumer-provided ReactiveActionRiskClassifier | `ChainedReactiveActionRiskClassifier` is `@ApplicationScoped` without `@DefaultBean`, so there is no way for a consumer to displace it. Consumers with complex reactive classifiers that can't be expressed as blocking `ActionRiskClassifier` are blocked. If this use case arises, the chain could delegate to `@RiskClassifier ReactiveActionRiskClassifier` beans alongside blocking ones. |
