# Goal-Capability Filtering in GoalFailureRecorder

**Issue:** engine#860
**Depends on:** eidos#135 (CLOSED — `AgentGoal.capabilities()` field added)

## Problem

`GoalFailureRecorder` records DECLINE signals for ALL agent goals on every
worker failure, regardless of which capability the worker was executing.
This makes goal abandonment equivalent to agent-level exclusion — no
per-goal discrimination.

## Design

### Signature change

`GoalFailureRecorder.record()` gains a `capabilityName` parameter:

```java
public void record(CaseInstance caseInstance, String workerName,
                   String capabilityName, WorkerOutcome<?> outcome)
```

### Filtering semantics

The goal loop filters before recording:

| `goal.capabilities()` | `capabilityName` | Record? |
|------------------------|------------------|---------|
| empty (universal)      | any value        | yes     |
| empty (universal)      | null             | yes     |
| `["cap-a", "cap-b"]`  | `"cap-a"`        | yes     |
| `["cap-a", "cap-b"]`  | `"cap-c"`        | no      |
| `["cap-a"]`            | null             | yes     |

Null `capabilityName` records for all goals (defensive fallback — same as
current behavior). This handles edge cases where the binding has no
`CapabilityTarget`.

### Call site

`WorkflowExecutionCompletedHandler.handleSemanticFailure()` already calls
`extractCapabilityTag(caseInstance, worker, bindingName)` for
`personalitySignalRecorder`. Pass the same value to `goalFailureRecorder`:

```java
String capabilityTag = extractCapabilityTag(caseInstance, worker, bindingName);
personalitySignalRecorder.record(caseInstance, worker.name(), capabilityTag, event.outcome());
goalFailureRecorder.record(caseInstance, worker.name(), capabilityTag, event.outcome());
```

### Dependency

`casehub-eidos-api:0.2-SNAPSHOT` must include the `capabilities` field on
`AgentGoal` (eidos commit `3d47268`). Force-refresh with `mvn -U` if the
local cache is stale.

## Test plan

Update `GoalFailureRecorderTest`:
- Existing `decline_recordsDeclineForEachGoal` — add capabilities to goals,
  pass matching capabilityName → both recorded
- New: goal with non-matching capability → not recorded
- New: goal with empty capabilities (universal) → always recorded
- New: null capabilityName → all goals recorded (backward compat)

## Out of scope

- `GoalAbandonmentEvaluator` is unchanged — it evaluates per-goal signal
  counts regardless of how they were recorded
- YAML mapping of `AgentGoal.capabilities` is handled by eidos#135
