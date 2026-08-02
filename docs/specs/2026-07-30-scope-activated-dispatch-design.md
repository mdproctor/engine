# Scope-Activated Binding Dispatch — Design Spec

> **Issue:** casehubio/engine#822
> **Parent epic:** casehubio/engine#821 (lifecycle scopes — remaining wiring)
> **Date:** 2026-07-30
> **Status:** Approved

## Problem

`CompoundActivatedEvent` is published when a compound transitions PENDING→RUNNING
(via `CompoundLifecycleEvaluator`), but no handler consumes it. Bindings with
`ScopeActivatedTrigger` never dispatch because `CaseContextChangedEventHandler`
filters to `ContextChangeTrigger` only (line 218).

CASE-scoped `ScopeActivatedTrigger` bindings also have no dispatch path —
`CaseStartedEventHandler` publishes `CONTEXT_CHANGED` but scope-activated
bindings are filtered out before reaching the planning module.

## Design Decision

**Integrate scope-activated dispatch into `PlanningStrategyLoopControl.select()`**
rather than creating a new event handler.

Rationale: scope activation is a planning decision — "this compound just activated,
so these bindings are now eligible." The existing module boundary is clean:
`LoopControl.select()` (planning) decides WHAT dispatches; runtime decides HOW
(agent routing, worker scheduling). Creating a runtime handler would require
crossing back into planning for PlanItem creation — a circular dependency workaround.

The existing `CaseContextChangedEventHandler.publishByTarget()` pipeline handles
scope-activated bindings without modification — they're `CapabilityTarget` (validated
at build time), carry lifecycle scope/execution mode on `WorkerScheduleEvent`, and
flow through agent routing identically to context-change bindings.

**Planning module dependency:** Scope-activated dispatch requires the planning module
on the classpath. In choreography-only deployments (`ChoreographyLoopControl`),
scope-activated bindings do not fire. This is by design — compounds are a planning
concept, so scope-activated trigger evaluation without the planning module has no
meaning. Case definitions containing `ScopeActivatedTrigger` bindings are valid
regardless of deployment topology; the trigger type is a definition concern, not a
deployment concern.

**Divergence from issue #822:** The issue body proposes a `CompoundActivatedEventHandler`
in the runtime module. This spec replaces that approach — integrating into
`PlanningStrategyLoopControl.select()` avoids the circular dependency back into
planning for PlanItem creation. Issue #822 will be updated with a comment linking
to this spec and explaining the divergence.

## Changes

### 1. `CompoundLifecycleEvaluator.evaluate()` — return activated compounds

Change return type from `void` to `List<PlanItemDefinition.Compound>`.

Each compound whose CAS transition succeeds (`tryDefinitionTransition(PENDING, RUNNING)`
returns `true`) is added to the return list. The `CompoundActivatedEvent` continues to
be published for observability — it stops being the dispatch mechanism.

`activatePendingCompounds()` also changes from `void` to
`List<PlanItemDefinition.Compound>` — it collects the activated compounds from the
CAS transition loop and returns them to `evaluate()`.

Migrate expression evaluation from raw `Instance<ExpressionEngine>` iteration to
`ExpressionEngineRegistry`. The current `evaluateCondition()` manually iterates
`Instance<ExpressionEngine>` with type matching — the same dispatch logic that
`ExpressionEngineRegistry` encapsulates with null-safety and error reporting. Since
this spec already introduces `ExpressionEngineRegistry` into the planning module
(for `when` guard evaluation), consolidating `CompoundLifecycleEvaluator` to use
the same abstraction eliminates two evaluation patterns in the same module.

```java
public List<PlanItemDefinition.Compound> evaluate(CasePlanModel plan, PlanExecutionContext ctx) {
    List<PlanItemDefinition.Compound> activated = activatePendingCompounds(plan, ctx);
    terminateRunningCompounds(plan, ctx);
    return activated;
}
```

### 2. `LoopControl.select()` contract and `PlanningStrategyLoopControl` — merge scope-activated bindings

**Interface contract update:** Update `LoopControl.select()` Javadoc. The return
value is "the bindings to fire" — not "a subset of the input." Implementations may
augment the eligible set with bindings that become eligible through planning evaluation
(e.g., scope-activated bindings triggered by compound activation). The
`ChoreographyLoopControl` fallback remains a pure pass-through — it does not evaluate
scope-activated bindings, which is correct since compounds are a planning concept.

After compound lifecycle evaluation, two new collection paths:

**CASE scope (first call only):** Inside the existing `markConfigured()` block
(CAS — fires once per case), scan `ctx.definition().getBindings()` for bindings with
`ScopeActivatedTrigger` + `lifecycleScope == CASE`. Evaluate `when` guard. Add to a
`scopeActivated` list.

**COMPOUND scope (on each activation):** For each compound returned by
`evaluate()`, get `scopedBindings().keySet()`. Find bindings with
`ScopeActivatedTrigger` whose name is in that set. Evaluate `when` guard.
Add to `scopeActivated` list.

Merge `scopeActivated` into the eligible list before gating. The rest of
the existing pipeline (compound gating, PlanItem creation, compound dispatch,
filterAndIndexForDispatch) handles them identically.

New dependency: inject `ExpressionEngineRegistry` into `PlanningStrategyLoopControl`
for `when` guard evaluation on scope-activated bindings.

**Scope-activated binding validation:** In `PlanningStrategyLoopControl.select()`,
immediately after the `markConfigured()` block, verify that every binding with
`ScopeActivatedTrigger` + `lifecycleScope == COMPOUND` appears in at least one
compound's `scopedBindings()`. At this point both `ctx.definition().getBindings()`
and `plan.getAllCompounds()` are available. This cannot be checked earlier —
`CaseDefinition` (API module) contains bindings but no compound definitions;
compounds are created by `BlackboardPlanConfigurer` during `markConfigured()` and
registered with the `CasePlanModel`. Fail with a clear error naming the orphaned
binding and the expected compound association. Silent failure modes in definition
wiring are a debugging nightmare.

### 3. `CompoundActivatedEvent` — add `scopedBindingNames`

Add `Set<String> scopedBindingNames` field for symmetry with
`CompoundCompletedEvent` and for observability consumers. Populated
from `compound.scopedBindings().keySet()` in `CompoundLifecycleEvaluator`.

### 4. `CaseContextChangedEventHandler.rules()` — null signalId for scope-activated bindings

In the dispatch loop after `select()`, check `binding.getOn() instanceof
ScopeActivatedTrigger`. If true, pass `null` as the `signalId` to `publishByTarget()`.
This excludes scope-activated bindings from signal settlement tracking.

Rationale: scope activation is a secondary effect of compound lifecycle evaluation,
not a direct effect of the triggering signal. The signal caused a context change;
compound activation is a planning decision that emerged from evaluating that change.
Tracking settlement through that causal boundary would mean a signal's completion
depends on planning outcomes — the wrong abstraction level. This also prevents
settlement hangs when non-TRANSIENT execution modes (PERSISTENT, REINVOKED) are
implemented in future issues (#824, #826).

`CaseContextChangedEventHandler.publishByTarget()` handles scope-activated
bindings as-is beyond the signalId change:
- `CapabilityTarget` switch case handles the dispatch
- `publishWorkerSchedule()` threads lifecycle scope and execution mode to
  `WorkerScheduleEvent`
- Scoped worker registry check falls through on first dispatch (no existing session)

## Edge Cases

| Case | Behaviour |
|------|-----------|
| `when` guard false at activation | Binding skipped. Scope activation is one-time — if the guard fails, the binding does not dispatch. Users wanting conditional-within-scope should use `ContextChangeTrigger` with compound gating instead. |
| CASE-scoped `when` guard timing | The guard evaluates against the case context at the first `select()` call — the initial `StartCase` payload. Context produced by worker output is not yet available. Bindings requiring worker-produced context should use `ContextChangeTrigger` with compound gating instead of `ScopeActivatedTrigger`. |
| Multiple compounds activate in same cycle | All activated compounds' scope-activated bindings collected and dispatched. PlanItem CAS prevents duplicates. |
| Repeatable compound re-activates | **Not yet supported.** `addPlanItemIfAbsent()` rejects new PlanItems when the existing item is COMPLETED (active-or-completed guard). `filterAndIndexForDispatch()` also skips terminal PlanItems when the case is RUNNING. The `repeatable` field exists on `PlanItemDefinition.Compound` but no code transitions compounds COMPLETED→PENDING. This affects all scoped bindings on repeatable compounds, not just scope-activated ones. Filed as casehubio/engine#TBD — PlanItem recycling on compound re-activation. |
| Signal settlement (`signalId`) | Scope-activated bindings dispatch with `signalId = null` — excluded from signal settlement tracking. The triggering signal's settlement tracks only the direct context-change bindings. Compound activation is a planning-level secondary effect; the `rules()` dispatch loop checks `binding.getOn() instanceof ScopeActivatedTrigger` and passes null signalId for those bindings. |
| Concurrent `select()` calls | Thread-safe via `ConcurrentHashMap` and CAS operations on `DefaultCasePlanModel`. No `CaseEvaluationSerializer` needed — scope-activated bindings ride the existing evaluation, not a separate event. |
| CASE-scoped binding on case start | `markConfigured()` fires once per case → first `select()` call collects CASE-scoped bindings. This happens when `CaseStartedEventHandler` publishes `CONTEXT_CHANGED` → `CaseContextChangedEventHandler` → `LoopControl.select()`. |

## Test Plan

### Unit tests (planning module)

- `CompoundLifecycleEvaluatorTest`: verify `evaluate()` returns activated compounds
- `PlanningStrategyLoopControlTest` (or new focused test):
  - Compound-scoped `ScopeActivatedTrigger` binding dispatches when compound activates
  - Case-scoped `ScopeActivatedTrigger` binding dispatches on first `select()` call
  - `when` guard respected — false guard prevents dispatch
  - Non-scope-activated bindings (ContextChangeTrigger) unaffected
  - Only bindings owned by the activated compound dispatch (not other compounds')
  - COMPOUND-scoped `ScopeActivatedTrigger` binding not in any compound's `scopedBindings()` fails at plan configuration with clear error

### Integration tests (`LifecycleScopeIntegrationTest`)

- End-to-end: compound activation → scope-activated worker dispatches and runs
- End-to-end: case start → case-scoped worker dispatches and runs
- `when` guard blocks dispatch in integration context

## Files Changed

| File | Module | Change |
|------|--------|--------|
| `LoopControl.java` | api | Update Javadoc — return value may include scope-activated bindings |
| `CompoundLifecycleEvaluator.java` | planning | Return `List<Compound>`, migrate to `ExpressionEngineRegistry`, populate `scopedBindingNames` on event |
| `CompoundLifecycleEvaluatorTest.java` | planning | Update for new return type and `ExpressionEngineRegistry` injection |
| `PlanningStrategyLoopControl.java` | planning | Collect + merge scope-activated bindings, inject `ExpressionEngineRegistry`, validate orphaned COMPOUND-scoped bindings after `markConfigured()` |
| `CompoundActivatedEvent.java` | common | Add `scopedBindingNames` field |
| `CaseContextChangedEventHandler.java` | runtime | Pass `null` signalId for `ScopeActivatedTrigger` bindings in dispatch loop |
| `LifecycleScopeIntegrationTest.java` | planning (test) | New test cases for both scopes |
