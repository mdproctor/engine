# Design Spec — Case Signal Bridge (engine#349)

**Date:** 2026-05-25
**Issue:** casehubio/engine#349
**Branch:** issue-349-case-signal-sink
**Also closes:** engine#338 (ESCALATED→FAULTED), engine#339 (M-of-N group outcomes)

---

## Problem

Five signal gaps prevent external events from reaching running and waiting cases:

1. Qhorus human messages never reach the engine — no observer bridges `MessageReceivedEvent` to case signal
2. WorkItem ESCALATED incorrectly terminates the PlanItem — WorkItem goes back to PENDING but adapter marks PlanItem FAULTED
3. WAITING cases ignore CONTEXT_CHANGED — the guard in `CaseContextChangedEventHandler` blocks all evaluation regardless of why the case is waiting
4. `WorkItemGroupLifecycleEvent` has no observer — M-of-N SpawnGroup outcomes never reach engine
5. External signals (`CaseHubRuntime.signal()`) cannot unblock WAITING cases

## Root Cause

`CaseContextChangedEventHandler` conflates two concerns:

1. **Prevent duplicate dispatches** of already-active bindings — this belongs in `LoopControl`
2. **Block all event processing** for non-RUNNING cases — this is harmful

The guard `if (!state.equals(RUNNING)) return void` is the single root cause of gaps 3 and 5. Gaps 1 and 4 are missing bridges. Gap 2 is a semantic error in the adapter.

## Design

### Core Fix: Move Dedup to LoopControl

Push dedup responsibility from the WAITING guard into `LoopControl`, which already owns dispatch decisions. Relax the guard to delegate to the active LoopControl implementation.

**Execution flow after the fix:**

```
External event arrives
  → adapter/bridge translates to case context change
  → CONTEXT_CHANGED fires (regardless of case state)
    → CaseContextChangedEventHandler calls loopControl.select(planCtx, eligible)
      → ChoreographyLoopControl: returns empty list if state != RUNNING (unchanged)
      → PlanningStrategyLoopControl: handles RUNNING + WAITING
          → PlanItem-status filter removes already-dispatched bindings
          → fresh eligible bindings dispatched normally
```

### Change 1: PlanExecutionContext — add CaseStatus

**File:** `api/src/main/java/io/casehub/api/engine/PlanExecutionContext.java`

```java
// Before:
public record PlanExecutionContext(UUID caseId, CaseDefinition definition, CaseContext context)

// After:
public record PlanExecutionContext(UUID caseId, CaseDefinition definition,
                                   CaseContext context, CaseStatus caseStatus)
```

`CaseStatus` is already in `casehub-engine-api` (Tier 1) — no new dependency. Breaking change to
the constructor; one call site updated in `CaseContextChangedEventHandler`.

### Change 2: CaseContextChangedEventHandler — remove hardcoded guard

**File:** `runtime/.../handler/CaseContextChangedEventHandler.java`

```java
// Before:
if (!caseInstance.getState().equals(CaseStatus.RUNNING)) {
    return Uni.createFrom().voidItem();
}
PlanExecutionContext planCtx = new PlanExecutionContext(
    caseInstance.getUuid(), definition, caseInstance.getCaseContext());

// After:
PlanExecutionContext planCtx = new PlanExecutionContext(
    caseInstance.getUuid(), definition,
    caseInstance.getCaseContext(), caseInstance.getState());
// No guard — loopControl.select() returns empty list for non-evaluated states.
```

LoopControl owns the state-eligibility decision. The handler has no lifecycle opinion.

### Change 3: ChoreographyLoopControl — state check inside select()

**File:** `runtime/.../engine/ChoreographyLoopControl.java`

```java
@Override
public Uni<List<Binding>> select(PlanExecutionContext ctx, List<Binding> eligible) {
    if (ctx.caseStatus() != CaseStatus.RUNNING) {
        return Uni.createFrom().item(List.of());
    }
    return Uni.createFrom().item(eligible);
}
```

Pure choreography cases (no blackboard/PlanItem tracking) retain RUNNING-only semantics. No dedup
mechanism exists for this path — WAITING is not relaxed here.

### Change 4: PlanningStrategyLoopControl — state check + PlanItem filter

**File:** `blackboard/.../control/PlanningStrategyLoopControl.java`

```java
@Override
public Uni<List<Binding>> select(PlanExecutionContext ctx, List<Binding> eligible) {
    CaseStatus status = ctx.caseStatus();
    if (status != CaseStatus.RUNNING && status != CaseStatus.WAITING) {
        return Uni.createFrom().item(List.of());
    }
    // ... existing stage gating, addPlanItemIfAbsent ...
    return stageLifecycleEvaluator.evaluate(plan, ctx)
        .chain(() -> planningStrategy.select(plan, ctx, gatedEligible))
        .map(selected -> filterToDispatchable(plan, selected))   // NEW
        .invoke(dispatchable -> indexSelectedForCompletion(caseId, dispatchable, plan));
}

private List<Binding> filterToDispatchable(CasePlanModel plan, List<Binding> selected) {
    return selected.stream()
        .filter(b -> plan.getPlanItemByBindingName(b.getName())
            .map(pi -> pi.getStatus() == PlanItemStatus.PENDING)
            .orElse(true))
        .toList();
}
```

Handles RUNNING and WAITING. `filterToDispatchable` prevents re-dispatch of bindings whose PlanItems
are already in RUNNING, DELEGATED, COMPLETED, FAULTED, or CANCELLED state. Only PENDING PlanItems
(not yet dispatched) pass through.

**Why this is safe for WAITING:** When a WAITING case receives a CONTEXT_CHANGED (e.g., from a
Qhorus human message via the bridge), bindings with in-flight PlanItems are filtered out. Only
genuinely new, unstarted bindings fire. The `CaseResumptionService` WAITING→RUNNING lifecycle
transition is unchanged — it still fires when the specific waited-on work completes.

**Pre-existing timing race (tracked as engine#364):** A second CONTEXT_CHANGED arriving before a
HumanTask/SubCase handler marks its PlanItem DELEGATED will find the PlanItem still PENDING and
re-dispatch. The `filterToDispatchable` filter prevents re-dispatch *after* the handler runs but
not *before* (the PENDING window). This race exists today for RUNNING cases and is not made worse
by this change. Fix: `PlanItemStatus.DISPATCHING` transient state (engine#364).

### Change 5: WorkItemLifecycleAdapter — ESCALATED fix + group observer

**File:** `work-adapter/.../WorkItemLifecycleAdapter.java`

**Fix ESCALATED (engine#338):**

```java
// Before — ESCALATED incorrectly treated as terminal:
if (status != COMPLETED && status != REJECTED && status != CANCELLED
    && status != EXPIRED   && status != ESCALATED) return;

// After — ESCALATED removed; WorkItem re-enters PENDING with new groups, PlanItem stays DELEGATED:
if (status != COMPLETED && status != REJECTED && status != CANCELLED
    && status != EXPIRED) return;
```

ESCALATED is not terminal. The WorkItem goes back to PENDING with new candidate groups. The PlanItem
stays DELEGATED — the task is still alive, just re-assigned. When the WorkItem reaches a true
terminal state (COMPLETED, REJECTED, or EXPIRED resolved as Fail), the adapter processes it normally.

**Add group lifecycle observer (engine#339):**

```java
public void onWorkItemGroupLifecycle(@ObservesAsync WorkItemGroupLifecycleEvent event) {
    GroupStatus status = event.groupStatus();
    if (status != GroupStatus.COMPLETED && status != GroupStatus.REJECTED) return;

    CallerRef ref = CallerRef.parse(event.callerRef());
    if (ref == null) return;

    CasePlanModel plan = registry.get(ref.caseId()).orElse(null);
    if (plan == null) {
        LOG.debugf("No CasePlanModel for caseId=%s — group outcome ignored", ref.caseId());
        return;
    }

    PlanItem item = plan.getPlanItem(ref.planItemId()).orElse(null);
    if (item == null) {
        LOG.warnf("PlanItem %s not found in case %s for group outcome", ref.planItemId(), ref.caseId());
        return;
    }

    boolean transitioned = applyGroupStatus(item, status);
    if (!transitioned) return;

    CaseInstance instance = caseInstanceRepository.findByUuid(ref.caseId())
        .await().atMost(TIMEOUT);
    if (instance == null) { /* warn and return */ return; }

    eventBus.publish(CONTEXT_CHANGED,
        new CaseContextChangedEvent(instance, instance.getCaseContext().asJsonNode()));
}

private boolean applyGroupStatus(PlanItem item, GroupStatus status) {
    try {
        return switch (status) {
            case COMPLETED -> { item.markCompleted(); yield true; }
            case REJECTED  -> { item.markFaulted();   yield true; }
            default        -> false;
        };
    } catch (IllegalStateException e) {
        LOG.warnf("Cannot transition PlanItem %s for GroupStatus %s: %s", ...);
        return false;
    }
}
```

No output mapping for group events — `WorkItemGroupLifecycleEvent` carries aggregate counts, not
per-instance resolution JSON. Case definitions that need per-instance resolution bind on the parent
WorkItem's `WorkItemLifecycleEvent` separately.

No new dependencies — `WorkItemGroupLifecycleEvent` and `GroupStatus` are in `casehub-work-api`,
already on the work-adapter classpath.

### Change 6: CaseChannel — channel naming constant

**File:** `api/src/main/java/io/casehub/api/model/CaseChannel.java`

```java
public static final String CASE_CHANNEL_PREFIX = "case-";

public static String channelName(UUID caseId, String purpose) {
    return CASE_CHANNEL_PREFIX + caseId + "/" + purpose;
}
```

`ClaudonyReactiveCaseChannelProvider` and `QhorusMessageSignalBridge` both reference this constant.
If the naming convention changes, both are updated from one place.

### Change 7: QhorusMessageSignalBridge — new CDI observer

**File:** `runtime/src/main/java/io/casehub/engine/internal/bridge/QhorusMessageSignalBridge.java`

```java
@ApplicationScoped
public class QhorusMessageSignalBridge {

    static final String SIGNAL_PATH = "channelMessage";

    @Inject CaseHubRuntime runtime;

    public void onMessage(@ObservesAsync MessageReceivedEvent event) {
        if (!isCommitmentResolving(event.messageType())) return;

        UUID caseId = extractCaseId(event.channelName());
        if (caseId == null) return;

        Map<String, Object> payload = buildPayload(event);
        runtime.signal(caseId, SIGNAL_PATH, payload);
    }

    private static boolean isCommitmentResolving(MessageType type) {
        return type == MessageType.RESPONSE || type == MessageType.DONE
            || type == MessageType.DECLINE  || type == MessageType.FAILURE;
    }

    private static UUID extractCaseId(String channelName) {
        if (channelName == null || !channelName.startsWith(CaseChannel.CASE_CHANNEL_PREFIX))
            return null;
        int slash = channelName.indexOf('/', CaseChannel.CASE_CHANNEL_PREFIX.length());
        String uuidStr = slash > 0
            ? channelName.substring(CaseChannel.CASE_CHANNEL_PREFIX.length(), slash)
            : channelName.substring(CaseChannel.CASE_CHANNEL_PREFIX.length());
        try { return UUID.fromString(uuidStr); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static Map<String, Object> buildPayload(MessageReceivedEvent event) {
        Map<String, Object> m = new HashMap<>();
        m.put("messageType", event.messageType().name());
        m.put("content",     event.content());
        m.put("senderId",    event.senderId());
        m.put("channelId",   event.channelId().toString());
        m.put("channelName", event.channelName());
        if (event.correlationId() != null) m.put("correlationId", event.correlationId());
        return m;
    }
}
```

**Commitment-resolving types (RESPONSE, DONE, DECLINE, FAILURE):** These four types resolve a
Commitment. COMMAND/QUERY/STATUS/EVENT/HANDOFF are not outcome signals. EVENT has null content
per PP-20260508-90428f. HANDOFF transfers obligation — the receiving agent's outcome arrives later.

**Signal path `channelMessage`:** Written to `context["channelMessage"]`. Case definitions bind on
this path via `on: contextChange(".channelMessage")`. A protocol entry should be created at
implementation time to document this convention.

**No new dependency:** `MessageReceivedEvent` and `MessageType` are in `casehub-qhorus-api`,
already on `casehub-engine-api`'s classpath. Engine-runtime inherits transitively. The bridge is
a dead CDI observer bean in deployments without qhorus-runtime — zero cost.

**Thread safety:** `@ObservesAsync` fires on a CDI managed executor thread (non-Vert.x IO thread).
`eventBus.publish()` is Vert.x thread-safe. No blocking annotation needed.

## What Remains Unchanged

- `CaseResumptionService` — WAITING→RUNNING lifecycle transition unchanged, still keyed on
  correlationKey. The case stays formally WAITING while processing new events; it transitions
  to RUNNING when the specific waited-on work completes.
- `PendingWorkRegistry` — unchanged
- `SignalReceivedEventHandler` — no WAITING-specific logic needed. Signals fire CONTEXT_CHANGED,
  which now reaches WAITING cases via PlanningStrategyLoopControl.
- All existing tests for RUNNING cases — no behavioral change

## Files Changed

| File | Change |
|------|--------|
| `api/.../engine/PlanExecutionContext.java` | Add `caseStatus` field |
| `api/.../model/CaseChannel.java` | Add `CASE_CHANNEL_PREFIX` constant + `channelName()` factory |
| `runtime/.../handler/CaseContextChangedEventHandler.java` | Remove guard, add `caseStatus` to PlanExecutionContext constructor call |
| `runtime/.../engine/ChoreographyLoopControl.java` | Add state check inside `select()` |
| `blackboard/.../control/PlanningStrategyLoopControl.java` | Add state check + `filterToDispatchable` |
| `work-adapter/.../WorkItemLifecycleAdapter.java` | Remove ESCALATED from terminal list, add group observer |
| `runtime/.../bridge/QhorusMessageSignalBridge.java` | New file |

## Issues Closed

- engine#349 — CaseSignalSink SPI (signal bridge)
- engine#338 — ESCALATED→FAULTED incorrect mapping
- engine#339 — WorkItemGroupLifecycleEvent not observed

## Issues Filed

- engine#364 — PlanItem timing race (PENDING window, deferred)
