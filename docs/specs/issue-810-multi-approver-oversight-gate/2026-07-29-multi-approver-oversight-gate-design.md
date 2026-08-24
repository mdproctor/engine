# Multi-Approver Oversight Gate — Design Spec

**Issue:** engine#810
**Date:** 2026-07-29
**Branch:** issue-810-multi-approver-oversight-gate

## Problem

The engine's `ActionRiskClassifier` creates a single WorkItem for action approval. High-risk actions (SOC containment, AML sanctions, clinical treatment authorization) need M-of-N multi-party approval with quorum semantics. All three layers exist independently — engine has `RiskDecision.GateRequired`, work has `WorkItemSpawnGroup` with M-of-N counting, UI has `<blocks-approval-gate>` with quorum display. What's missing is the connector service bridging engine governance to work mechanism.

## Architecture

Three tiers, clean boundaries:

| Tier | Owns | Changes |
|------|------|---------|
| **Engine-api** | `QuorumConfig` — governance semantics (how many must agree) | New record on `RiskDecision.GateRequired` |
| **Work-api** | `MultiInstanceConfig` — mechanism (instance/required counts, assignment) | New `createMultiInstance()` on `WorkItemCreator` |
| **Work engine-adapter** | Translation layer — governance to mechanism | `ActionGateWorkItemHandler` + `WorkItemLifecycleAdapter` |

## Engine-Side: `QuorumConfig`

New record in `io.casehub.api.spi`:

```java
public record QuorumConfig(
    int instances,
    int required,
    @Nullable OnThresholdReached onThresholdReached,  // null = KEEP
    boolean allowSameAssignee                         // default false
) {
    public QuorumConfig {
        if (instances < 2) throw new IllegalArgumentException("instances must be >= 2");
        if (required < 1 || required > instances)
            throw new IllegalArgumentException("required must be 1..instances");
    }
}
```

Uses the existing `io.casehub.api.model.OnThresholdReached` enum (KEEP, CANCEL). The work-api enum adds SUSPEND; this is a work-tier mechanism not exposed in the governance layer. The adapter maps between the two enums.

Added as `@Nullable QuorumConfig quorum` on `RiskDecision.GateRequired`. Null = single-approver (backward compatible). The full `GateRequired` record already flows through `ActionGateScheduleRequest` — no event changes needed.

No `assignmentStrategy` on `QuorumConfig` — that's a work-tier mechanism detail. The adapter picks a sensible default ("pool").

## Work-Side: `WorkItemCreator.createMultiInstance()`

New default method on `WorkItemCreator`:

```java
default WorkItemRef createMultiInstance(WorkItemCreateRequest parentRequest, MultiInstanceConfig config) {
    throw new UnsupportedOperationException("Multi-instance creation not supported");
}
```

Implementation:

1. Checks for existing active WorkItem with the same `callerRef` — if found, returns its ref (idempotent)
2. Creates parent WorkItem from `parentRequest` — same callerRef as today (`case:{caseId}/gate:{gateId}`)
3. Creates `config.instanceCount()` child WorkItems inheriting title, payload, scope, expiresAt from parent
4. Children get **no callerRef** — prevents individual child completions from triggering gate resolution
5. Creates `WorkItemSpawnGroup` entity with M-of-N counters
6. Runs `InstanceAssignmentStrategy` (default "pool") to distribute candidateGroups across children
7. Returns `WorkItemRef` for the parent — the adapter needs only the parent ref for logging; group details are internal to the work engine

**Why not SpawnPort?** `SpawnPort.spawn()` takes `SpawnRequest(UUID parentId, String idempotencyKey, List<ChildSpec> children)` where `ChildSpec(UUID templateId, String callerRef, Map<String, Object> overrides)` requires a pre-registered `templateId`. Gate work items are programmatic — no template. `createMultiInstance()` is the programmatic counterpart to SpawnPort's template-based spawning.

**Idempotency:** The parent's `callerRef` serves as the natural idempotency key. The implementation checks `findActiveByCallerRef()` before creating. Note: the single-approver `create()` path has the same pre-existing gap — callerRef-based dedup is a general improvement, not specific to multi-instance.

## Adapter Bridge

### Creation — `ActionGateWorkItemHandler.onActionGateSchedule()`

When `event.gateRequired().quorum() != null`:
- Maps `QuorumConfig` → `MultiInstanceConfig` (governance to mechanism):
  - `instances` → `instanceCount`
  - `required` → `requiredCount`
  - `parentRole` → `COORDINATOR` (the parent gate WorkItem tracks group status; only children are claimable approval items)
  - `onThresholdReached` → maps engine-api enum to work-api enum (KEEP→KEEP, CANCEL→CANCEL; SUSPEND is work-only, not exposed in governance)
  - `allowSameAssignee` → passthrough
  - `assignmentStrategyName` → "pool" (default; adapter picks the strategy, not the classifier)
- Calls `workItemCreator.createMultiInstance(request, config)` instead of `create(request)`
- Single-approver path is unchanged

### Completion — `WorkItemLifecycleAdapter.onWorkItemGroupLifecycle()`

Add gate routing before existing PlanItem routing:
- Parse `callerRef` from the group event (echoed from parent WorkItem)
- If `GateCallerRef`: route to a new `ActionGateCompletionApplier.applyGroupCompletion(GateCallerRef, GroupStatus, String tenancyId)` method

The existing `apply(GateCallerRef, WorkItemStatus, WorkItemRef, String)` on `ActionGateCompletionApplier` handles single-approver gates. Group events carry `GroupStatus` (not `WorkItemStatus`) and have no individual `WorkItemRef`, so a separate method is needed:

```java
public void applyGroupCompletion(GateCallerRef gateRef, GroupStatus status, String tenancyId) {
    switch (status) {
        case COMPLETED -> publishApproved(gateRef, null, null, null, tenancyId);
        case REJECTED  -> publishRejected(gateRef, null, null, tenancyId);
    }
}
```

For group completions:
- `approvedBy` → null (v1 limitation — individual approver identities exist in child WorkItem lifecycle events on the work side)
- `workItemResolution` → null (no single resolution from a group)
- `resolutionTypeName` → null (typed resolution is not supported for multi-approver gates in v1 — there is no single WorkItemRef to extract it from)

`ActionGateApprovedEvent.approvedBy` must be annotated `@Nullable` — the handler (`ActionGateApprovedHandler`) already null-checks with a fallback to "unknown", but the record field itself should be type-correct.

### Cancellation — Gate obsolescence with multi-instance

When a gate becomes obsolete (case terminated, superseded by a new gate), the engine fires gate cancellation. The adapter's cancellation path must cascade to the spawn group:

1. `workItemCreator.obsoleteByCallerRef(callerRef)` obsoletes the parent WorkItem
2. The work engine's spawn group cascade mechanism obsoletes all child WorkItems in the group

This relies on the work engine cascading parent obsolescence to the spawn group — when a parent WorkItem with an active `WorkItemSpawnGroup` is obsoleted, children are cancelled automatically. This is a work-engine responsibility, not an adapter concern.

If cascading is not yet implemented in the work engine, it must be added as a prerequisite for this feature. The alternative — having the adapter call `SpawnPort.cancelGroup()` — is wrong because the adapter would need to track the groupId or look it up, coupling the adapter to spawn internals.

### CallerRef routing — why children must NOT carry the gate ref

The parent WorkItem carries `callerRef = "case:{caseId}/gate:{gateId}"`. If children inherited this, each child's terminal event would trigger `ActionGateCompletionApplier` — the first child to complete would approve the gate, defeating M-of-N.

Instead: children get no callerRef. Individual child events are handled by `MultiInstanceCoordinator` (counter updates). The group event fires when quorum is reached and carries the parent's callerRef — that's what triggers gate resolution.

## `ChainedActionRiskClassifier` Quorum Comparison

The chain resolver's `narrower()` method gains quorum comparison as the **first** comparison branch, before candidateGroups/expiresIn. Quorum fundamentally changes the approval burden — a quorum(3,2) gate with a large candidate pool is more restrictive than a single-approver gate with a small pool, because multi-party agreement is inherently harder.

Comparison order:
1. **Quorum presence:** any quorum is more restrictive than no quorum (multi-party > single-party)
2. **Both have quorum → `required` count:** higher `required` wins (3-of-5 > 2-of-5)
3. **Equal `required` → `instances` count:** lower `instances` wins (2-of-2 unanimous is more restrictive than 2-of-5, because the approval ratio is higher — 100% vs 40%)
4. **Equal quorum (or both no quorum):** fall through to existing candidateGroups size comparison
5. **expiresIn** (existing logic, unchanged)

## Test Strategy

**Engine-api:**
- `QuorumConfig` validation (instances >= 2, required bounds, edge cases)
- `RiskDecision.GateRequired` backward compat — null quorum identical to today

**Work-api:**
- `createMultiInstance()` — parent + N children created, spawn group correct, children have no callerRef
- Pool assignment distributes candidateGroups to all children

**Engine-adapter (integration):**
- `ActionGateWorkItemHandler` — single path unchanged, multi path creates group
- `WorkItemLifecycleAdapter.onWorkItemGroupLifecycle()` — gate COMPLETED → approved, REJECTED → rejected

**Engine-runtime:**
- `ChainedActionRiskClassifier` narrower with quorum variants

**End-to-end:**
- Classifier returns `GateRequired(quorum=QuorumConfig(3, 2, ...))` → 3 approval WorkItems created → 2 approve → gate approved → case resumes
- Rejection: 2 reject → quorum impossible → gate rejected → case handles rejection

## Not In Scope

Each deferred item must be filed as a tracked issue before implementation begins.

- **Dynamic instance count from candidate group membership** — classifier computes numbers; instance count is static in `QuorumConfig` for v1. Issue: TBD
- **`approvedBy` list aggregation on engine events** — v1 `ActionGateApprovedEvent.approvedBy` is null for multi-approver gates; individual approver audit trail lives in child WorkItem lifecycle events on the work side. SOC, AML, and clinical compliance use cases will need engine-side aggregation. Issue: TBD
- **Typed resolution for multi-approver gates** — v1 does not support `resolutionTypeName` for group completions because there is no single `WorkItemRef` to extract it from. Typed resolution validation in `ActionGateApprovedHandler` is skipped when `resolutionTypeName` is null. Issue: TBD
- **YAML schema for quorum** — classifiers are Java code. Issue: TBD
- **Escalation policy changes** — existing `expiresIn` + work SLA policies apply per-child. Issue: TBD

## Garden Context

- **GE-20260607-326c7e** — `GateRequired` restrictiveness: fewer candidateGroups = more restrictive. Quorum comparison follows the same pattern.
- **GE-20260613-29d3b5** — Gate handlers clear `pendingActionGate` before consumers run. Multi-approver doesn't change this — the gate is still cleared once (on group event, not individual child events).
- **routing-strategy-convention** — `NamedStrategy` + `StrategyResolver` pattern. `InstanceAssignmentStrategy` already follows this convention.
