# Failure Cascade Design

Covers engine#502 (agent exclusion), #503 (structured failure state), #504 (OutcomePolicy), #506 (failure goals → COMPLETED).

## Problem

The engine has two worker outcome paths: success (apply output) and exception (retry/fault). A third category is missing: the worker ran correctly but produced a semantic non-success — an agent declined the work or tried and failed. This is not an infrastructure fault. The response is to try a different agent, not retry the same one.

Four issues build this end-to-end:

- **#502** — routing strategies must exclude agents that previously declined/failed for this case's capability
- **#503** — structured failure state (status, attempts, history, excludedAgents) written to the blackboard
- **#504** — configurable OutcomePolicy per binding: REROUTE (try different agent) or FAULT (fail immediately), with a max reroute attempts limit
- **#506** — failure goal satisfaction produces COMPLETED with failure metadata, not FAULTED

## Prerequisite: binding name through dispatch chain

`PlanningStrategyLoopControl.resolveWorkerName()` picks the first matching worker at PlanItem creation time. The routing strategy picks the actual worker at dispatch time. For rerouting these always differ — the first worker is excluded. The completion index maps `workerName → planItemId`, so the rerouted worker's completion is orphaned.

Fix: thread binding name through the dispatch chain and use `CasePlanModel.getPlanItemByBindingName()` for PlanItem lookup in the completion handler.

Additionally: `WorkflowExecutionCompletedHandler.findMatchingCapabilityBinding()` does a capability-name fuzzy match — returns the first binding whose capability matches any of the worker's capabilities. This breaks when multiple bindings target the same capability. Since binding name is now threaded through the event, replace all uses of `findMatchingCapabilityBinding()` with a direct `definition.getBindings().stream().filter(b -> b.getName().equals(bindingName))` lookup.

### Changes

| Component | Change |
|-----------|--------|
| `WorkerScheduleEvent` | Add `String bindingName` field |
| `CaseContextChangedEventHandler.scheduleWorker()` | Pass binding name to WorkerScheduleEvent |
| `WorkerScheduleEventHandler` | Put `bindingName` in Quartz job data map |
| `QuartzWorkerExecutionJob` | Extract `bindingName`, include in `WorkflowExecutionCompleted` |
| `WorkflowExecutionCompleted` | Add `String bindingName` field |
| `PlanItemCompletionHandler.onWorkerFinished()` | Use `plan.getPlanItemByBindingName(event.bindingName())` instead of completion index |
| `WorkflowExecutionCompletedHandler` | Replace all `findMatchingCapabilityBinding()` calls with direct binding name lookup via `event.bindingName()` |
| `WorkerOutcomeResolvedHandler` | Use binding name for PlanItem lookup |

This fixes the multi-worker completion tracking bug for the success path and all new semantic failure paths. The infrastructure fault path (`WorkerRetriesExhaustedEvent` → `WorkerRetryExhaustionHandler`) retains the worker-name lookup via the completion index — the binding name is available in the Quartz job data map but `WorkerRetriesExhaustedEvent` doesn't carry it. That is a separate fix outside this spec's scope.

## 1. WorkerOutcome

Sealed type on `WorkerResult`:

```java
// api/src/main/java/io/casehub/api/model/WorkerOutcome.java
public sealed interface WorkerOutcome {
    record Success() implements WorkerOutcome {}
    record Declined(String reason) implements WorkerOutcome {}
    record Failed(String reason) implements WorkerOutcome {}
}
```

`WorkerResult` gains an `outcome` field. Existing factory methods default to `Success`. New factory methods enforce `plannedAction == null`:

```java
public static WorkerResult declined(String reason) {
    return new WorkerResult(Map.of(), null, new WorkerOutcome.Declined(reason));
}

public static WorkerResult failed(String reason) {
    return new WorkerResult(Map.of(), null, new WorkerOutcome.Failed(reason));
}

public static WorkerResult declined(String reason, Map<String, Object> partialOutput) {
    return new WorkerResult(partialOutput, null, new WorkerOutcome.Declined(reason));
}

public static WorkerResult failed(String reason, Map<String, Object> partialOutput) {
    return new WorkerResult(partialOutput, null, new WorkerOutcome.Failed(reason));
}
```

Constructor validates: if outcome is not `Success`, `plannedAction` must be null.

`WorkStatus` gains `DECLINED` and `FAILED` values. `WorkResult` gains factory methods `WorkResult.declined(correlationKey, workerId, caseId)` and `WorkResult.failed(correlationKey, workerId, caseId)`.

### WorkerStatusListener reporting

`onWorkerCompleted(WorkResult)` is called for all outcomes including DECLINED/FAILED. "Completed" means "execution finished" — the `WorkResult.status` carries the outcome distinction. No SPI signature change. For FAULT policy: `onWorkerCompleted(WorkResult.declined(...))` is called first (execution finished with decline), then case-level fault is handled separately via `CASE_STATUS_CHANGED`. `onWorkerStalled()` is NOT called — the worker isn't stalled, the policy chose to fault.

### Propagation

`QuartzWorkerExecutionJob.onSuccess()` extracts the outcome from `WorkerResult` and includes it in `WorkflowExecutionCompleted`. The event carries outcome + bindingName alongside the existing fields.

## 2. OutcomePolicy

Record on `Binding`:

```java
// api/src/main/java/io/casehub/api/model/OutcomePolicy.java
public record OutcomePolicy(
    OutcomeAction onDecline,
    OutcomeAction onFailure,
    OutcomeAction onExpired,
    int maxRerouteAttempts
) {
    public OutcomePolicy() {
        this(OutcomeAction.REROUTE, OutcomeAction.REROUTE, OutcomeAction.REROUTE, 3);
    }
}

// api/src/main/java/io/casehub/api/model/OutcomeAction.java
public enum OutcomeAction { REROUTE, FAULT }
```

Default when absent from a binding: `OutcomePolicy(REROUTE, REROUTE, REROUTE, 3)`.

`onExpired` is declared for forward compatibility. The EXPIRED signal is not wired in this batch — it requires either an SLA timeout mechanism (engine-internal) or `CommitmentExpiredEvent` (qhorus#281).

### YAML

```yaml
bindings:
  - name: security-review
    capability: security-review
    outcomePolicy:
      onDecline: REROUTE
      onFailure: REROUTE
      maxRerouteAttempts: 2
    on:
      contextChange: ".securityReview == null"
```

### DSL

```java
Binding.builder()
    .name("security-review")
    .capability(Capability.of("security-review"))
    .outcomePolicy(new OutcomePolicy(REROUTE, REROUTE, REROUTE, 2))
    .on(new ContextChangeTrigger(".securityReview == null"))
    .build()
```

### Changes

| Component | Change |
|-----------|--------|
| `Binding` | Add `OutcomePolicy outcomePolicy` field + builder method |
| `CaseDefinitionYamlMapper` | Map `outcomePolicy` from YAML schema |
| Schema model (`io.casehub.model`) | Add `OutcomePolicy` to JSON schema |

## 3. Failure state at `_outcomes.<bindingName>`

Engine-managed namespace in the working panel, keyed by **binding name** (not capability name). Workers never write to `_outcomes.*`. The original binding's trigger condition (e.g., `.securityReview == null`) is unaffected — it evaluates against worker output keys, not `_outcomes.*`.

### Why binding name, not capability name

Two bindings can target the same capability with different OutcomePolicies:

```yaml
bindings:
  - name: initial-review
    capability: security-review
    outcomePolicy: { onDecline: FAULT }
  - name: deep-review
    capability: security-review
    outcomePolicy: { onDecline: REROUTE, maxRerouteAttempts: 3 }
```

Keying by capability name would merge their exclusion lists and attempt counts. The binding is the unit of dispatch and the unit that carries OutcomePolicy — the key must match.

### Schema

```json
{
  "_outcomes": {
    "initial-review": {
      "status": "DECLINED",
      "attempts": 1,
      "history": [
        {
          "agent": "claude-analyst",
          "status": "DECLINED",
          "reason": "unsupported language",
          "timestamp": "2026-06-17T10:00:00Z",
          "partialOutput": null
        }
      ],
      "excludedAgents": ["claude-analyst"]
    }
  }
}
```

`status` values: `DECLINED`, `FAILED`, `REROUTES_EXHAUSTED`.

### Partial output

When a worker returns a non-success outcome with non-empty output (e.g., `new WorkerResult(partialOutput, null, new WorkerOutcome.Failed(...))`), the partial output is included in the history entry at `partialOutput`. It is NOT applied to the case context — only written to history for observability. The working panel only changes via `_outcomes.*` for non-success outcomes; worker output keys remain unaffected until a successful completion.

### Why `_outcomes` and not the capability/binding key directly

If failure state were written to `.securityReview` (the key the binding trigger checks), the trigger `.securityReview == null` would stop matching. The binding wouldn't re-fire. By using `_outcomes.initial-review`, the trigger is unaffected — the binding naturally re-fires through normal CONTEXT_CHANGED evaluation.

### Accumulation rules

- `status` — most recent outcome
- `attempts` — incremented on each dispatch+outcome cycle
- `history` — appends (never overwrites); includes `partialOutput` when non-empty
- `excludedAgents` — accumulates agent IDs from all non-success outcomes

### Repeatable stage interaction

`_outcomes` data persists in the working panel across repeatable stage resets. Excluded agents from iteration N carry over to iteration N+1. This is a known limitation — cross-iteration exclusion is incorrect (agents that declined iteration N's work may handle iteration N+1's work). The fix (clearing `_outcomes` entries for the stage's bindings on `StageActivatedEvent` with `instanceIndex > 0`) requires cross-module coordination and is tracked as a follow-up issue (engine#517).

## 4. WorkflowExecutionCompletedHandler branching

Outcome check runs **before** PlannedAction check:

```
if outcome is Declined/Failed → handleSemanticFailure()
if plannedAction != null → handleWithPlannedAction() (existing)
else → success path (existing)
```

All binding lookups in the handler use the direct `event.bindingName()` path — `findMatchingCapabilityBinding()` is no longer called from any new or existing code path in this handler.

### handleSemanticFailure()

1. Look up binding directly: `definition.getBindings().stream().filter(b -> b.getName().equals(event.bindingName()))`
2. Resolve `OutcomePolicy` from binding (default if absent)
3. Resolve action for this outcome type (`onDecline` or `onFailure`)
4. Read existing `_outcomes.<bindingName>` from working panel (or create)
5. Append to history (including `partialOutput` if non-empty), increment attempts, add worker to excludedAgents
6. Write updated `_outcomes.<bindingName>` to working panel
7. Persist event log (`WORKER_OUTCOME_DECLINED` or `WORKER_OUTCOME_FAILED` — new `CaseHubEventType` values, distinct from existing `WORKER_EXECUTION_FAILED` which is infrastructure failure)
8. Record episodic: `EpisodicPanelUpdater.recordWorkerCompletion(ctx, workerName, "DECLINED")`
9. Call `workerStatusListener.onWorkerCompleted(WorkResult.declined(...))`
10. Fire CDI lifecycle events (fire-and-forget)
11. Dispatch by policy:

| Policy result | Action |
|---------------|--------|
| FAULT | Publish `CASE_STATUS_CHANGED(FAULTED)` + `WORKER_OUTCOME_RESOLVED(FAULT)` |
| REROUTE, attempts < max | Publish `WORKER_OUTCOME_RESOLVED(REROUTE)` |
| REROUTE, attempts >= max | Write `status: REROUTES_EXHAUSTED` to `_outcomes`, publish `WORKER_OUTCOME_RESOLVED(EXHAUSTED)` |

### OutcomeDisposition

```java
// common/src/main/java/io/casehub/engine/common/internal/event/OutcomeDisposition.java
public enum OutcomeDisposition { REROUTE, EXHAUSTED, FAULT }
```

FAULT is NOT routed through `WORKER_RETRIES_EXHAUSTED`. Infrastructure fault (exceptions, retry exhaustion) and semantic fault (OutcomePolicy FAULT) are distinct event types with different lookup strategies and observability semantics. `WorkerRetriesExhaustedEvent` carries `(caseId, workerId, idempotency)` with no binding name or outcome reason — wrong shape for the semantic fault path.

For FAULT disposition: `WorkflowExecutionCompletedHandler` publishes `CASE_STATUS_CHANGED(FAULTED)` for case-level terminal transition (handled by `CaseStatusChangedHandler` — persists, closes channels, cancels triggers). The `WORKER_OUTCOME_RESOLVED(FAULT)` event handles PlanItem lifecycle only. No CONTEXT_CHANGED — case is terminal, binding evaluation is a no-op.

## 5. PlanItemCompletionHandler branching

On `WORKER_EXECUTION_FINISHED`, the handler checks the outcome **before** any PlanItem transition:

```java
if (!(event.outcome() instanceof WorkerOutcome.Success)) {
    return; // WorkerOutcomeResolvedHandler owns PlanItem lifecycle for non-success outcomes
}
```

For **Success**: marks COMPLETED, stage autocomplete (existing behavior). Uses `plan.getPlanItemByBindingName(event.bindingName())` for lookup (prerequisite change).

For **Declined/Failed**: returns immediately. No PlanItem transition. The `WORKER_OUTCOME_RESOLVED` event (published by `WorkflowExecutionCompletedHandler`) is consumed by `WorkerOutcomeResolvedHandler` which handles PlanItem lifecycle.

This eliminates the fan-out race — only one handler touches PlanItem state per outcome type.

## 6. WorkerOutcomeResolvedHandler

New handler in `casehub-blackboard`. `@ConsumeEvent(value = EventBusAddresses.WORKER_OUTCOME_RESOLVED, blocking = true)` — consistent with `PlanItemCompletionHandler` and `WorkerRetryExhaustionHandler` which also modify PlanItem state under `blocking = true`.

1. Find PlanItem by binding name via `CasePlanModel.getPlanItemByBindingName(event.bindingName())` — `CaseInstance` is available directly from the event (follows the established pattern; all major events carry it)
2. Mark FAULTED (RUNNING → FAULTED — valid existing transition, no state machine change)
3. Remove completion index entry
4. Branch by disposition:

| Disposition | Stage autocomplete | CONTEXT_CHANGED |
|-------------|-------------------|-----------------|
| REROUTE | No — replacement PlanItem will be created when binding re-fires | Yes — `new CaseContextChangedEvent(event.caseInstance(), event.caseInstance().getCaseContext().snapshot(), ContextPanel.WORKING)` |
| EXHAUSTED | Yes — no replacement coming | Yes — allows failure-handler bindings to fire |
| FAULT | Yes — no replacement coming | No — case is terminal, evaluation is a no-op |

CONTEXT_CHANGED must be published by the handler (not by `handleSemanticFailure()`) because the PlanItem must be FAULTED first — otherwise `addPlanItemIfAbsent` rejects the replacement (the active PlanItem is still RUNNING). Since `eventBus.publish()` is fire-and-forget with no ordering guarantee, only the handler can safely sequence the PlanItem fault before the re-evaluation trigger.

### Why no PlanItem state machine change

The reroute mechanism reuses existing transitions:

1. PlanItem-1: RUNNING → FAULTED (valid)
2. CONTEXT_CHANGED → binding trigger matches (trigger checks worker output key, not `_outcomes`)
3. `addPlanItemIfAbsent`: PlanItem-1 is FAULTED (terminal) → allows new PlanItem-2 (PENDING)
4. `registerWithOwningStages`: PlanItem-2 added to stage required items (stage now has PlanItem-1 FAULTED + PlanItem-2 PENDING — autocomplete deferred)
5. `filterToDispatchable`: PlanItem-2 is PENDING → passes
6. Routing: filter excluded agents from `_outcomes.<bindingName>.excludedAgents` → dispatches different worker
7. PlanItem-2 → RUNNING → eventually COMPLETED or another reroute cycle

### Stage autocomplete interaction

When disposition is REROUTE, stage autocomplete is NOT called for the FAULTED PlanItem. The replacement PlanItem-2 is added to the stage's `requiredItemIds` when it's created via `registerWithOwningStages`. The stage has both PlanItem-1 (FAULTED) and PlanItem-2 (PENDING/RUNNING) — not all terminal, so autocomplete doesn't fire.

When disposition is EXHAUSTED or FAULT, stage autocomplete IS called. If the FAULTED PlanItem is the last required item, the stage autocompletes.

## 7. Agent exclusion — pre-routing filter

In `CaseContextChangedEventHandler.publishWorkerSchedule()`, after `AgentCandidateFactory.buildCandidates()` and before `agentRoutingStrategy.select()`:

```java
JsonNode outcomes = workingPanel.path("_outcomes").path(binding.getName());
if (outcomes.has("excludedAgents")) {
    Set<String> excluded = StreamSupport.stream(
            outcomes.get("excludedAgents").spliterator(), false)
        .map(JsonNode::asText)
        .collect(Collectors.toSet());
    candidates = candidates.stream()
        .filter(c -> !excluded.contains(c.workerId()))
        .toList();
}
if (candidates.isEmpty()) {
    return tryProvision(caseInstance, capability, triggerChannelId, triggerCorrelationId);
}
```

Uses **binding name** (available as `binding.getName()` in `publishWorkerSchedule()`), not capability name. All routing strategies (LeastLoaded, TrustWeighted, Semantic) benefit automatically. No routing strategy interface change.

## 8. Failure goals → COMPLETED not FAULTED

`GoalReachedEventHandler.evaluateCompletion()`: when a failure goal expression is satisfied, publish `CaseStatusChanged` with `CaseStatus.COMPLETED` and goal metadata. Success goals also carry metadata for consistency.

### CaseStatusChanged

Add two nullable fields via overloaded constructor:

```java
public record CaseStatusChanged(
    CaseInstance instance,
    String oldStatus,
    String newStatus,
    String satisfiedGoalName,
    GoalKind satisfiedGoalKind
) {
    public CaseStatusChanged(CaseInstance instance, String oldStatus, String newStatus) {
        this(instance, oldStatus, newStatus, null, null);
    }
}
```

All 7 existing call sites use the 3-arg constructor — unchanged. `GoalReachedEventHandler` uses the 5-arg constructor for both success and failure goals.

### CaseStatusChangedHandler

Extracts goal metadata from `CaseStatusChanged` and propagates to:
1. Event log metadata: `put("goalName", ...).put("goalKind", ...)`
2. `CaseOutcomeEvent.metadata()`: `Map.of("goalName", ..., "goalKind", ...)` (currently always `Map.of()`)

Outcome observers distinguish: `outcomeLabel=COMPLETED` + `goalKind=failure` → process completed with negative outcome. `outcomeLabel=FAULTED` → system fault. Clear semantic separation.

### Changes

| Component | Change |
|-----------|--------|
| `CaseStatusChanged` | Add `satisfiedGoalName`, `satisfiedGoalKind` nullable fields + 3-arg overload |
| `GoalReachedEventHandler` | Both goal types publish COMPLETED with metadata; failure no longer publishes FAULTED |
| `CaseStatusChangedHandler` | Extract goal metadata, propagate to EventLog metadata and `CaseOutcomeEvent.metadata()` |

## New types and events

| Type | Module | Purpose |
|------|--------|---------|
| `WorkerOutcome` (sealed interface) | api/model | Worker declares outcome |
| `OutcomePolicy` (record) | api/model | Binding-level reroute/fault config |
| `OutcomeAction` (enum) | api/model | REROUTE or FAULT |
| `OutcomeDisposition` (enum) | common/internal/event | REROUTE, EXHAUSTED, or FAULT — pre-resolved by WorkflowExecutionCompletedHandler |
| `WorkerOutcomeResolvedEvent` (record) | common/internal/event | caseInstance, workerId, bindingName, capabilityName, OutcomeDisposition |
| `WORKER_OUTCOME_RESOLVED` | EventBusAddresses | New event bus address |
| `WORKER_OUTCOME_DECLINED` | CaseHubEventType | New event type — semantic decline (distinct from `WORKER_EXECUTION_FAILED` which is infrastructure) |
| `WORKER_OUTCOME_FAILED` | CaseHubEventType | New event type — semantic failure |
| `WorkerOutcomeResolvedHandler` (class) | blackboard/handler | PlanItem lifecycle on reroute/exhaust/fault |

## Consumer usage

### Case definition (YAML)

```yaml
workers:
  - name: claude-analyst
    capabilities: [security-review]
    agent: { model: claude }
  - name: gpt-analyst
    capabilities: [security-review]
    agent: { model: gpt }

bindings:
  - name: security-review
    capability: security-review
    outcomePolicy:
      onDecline: REROUTE
      maxRerouteAttempts: 2
    on:
      contextChange: ".securityReview == null"

  - name: review-escalation
    capability: human-escalation
    on:
      contextChange: "._outcomes.\"security-review\".status == \"REROUTES_EXHAUSTED\""

goals:
  - name: review-blocked
    kind: failure
    condition: "._outcomes.\"security-review\".status == \"REROUTES_EXHAUSTED\""

completion:
  success:
    anyOf:
      - name: review-done
        condition: ".securityReview != null"
  failure:
    anyOf:
      - name: review-blocked
```

### Worker function

```java
.function(input -> {
    if (!canHandle(input)) {
        return WorkerResult.declined("unsupported language: " + input.get("language"));
    }
    try {
        Map<String, Object> result = performReview(input);
        return WorkerResult.of(result);
    } catch (ReviewException e) {
        // Partial output preserved in _outcomes.history for observability
        return WorkerResult.failed("review error: " + e.getMessage(),
            Map.of("partialFindings", e.getPartialFindings()));
    }
})
```

## Deferred

- **EXPIRED signal** (engine#513): requires SLA timeout or qhorus#281 `CommitmentExpiredEvent`. `OutcomePolicy.onExpired` is declared but not wired.
- **Success recording in `_outcomes`** (engine#514): on successful completion after rerouting, updating `_outcomes.status` to COMPLETED for observability. Not required for correctness.
- **Qhorus commitment bridge** (engine#515): `QhorusMessageSignalBridge` could translate DECLINE/FAILED speech acts to `WorkerOutcome` — connects the Qhorus channel path to the same failure cascade.
- **Repeatable stage `_outcomes` clearing** (engine#517): `_outcomes` persists across repeatable stage resets. Clearing entries for the stage's bindings on `StageActivatedEvent` with `instanceIndex > 0` requires cross-module coordination.
