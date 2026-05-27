# 0002 — Stage Autocomplete Triggers on Any Terminal PlanItem State

Date: 2026-05-26
Status: Accepted

## Context and Problem Statement

`PlanItemCompletionHandler.evaluateStageAutocomplete()` previously triggered stage
autocomplete only when all required PlanItems reached `COMPLETED`. If a required item
faulted or was rejected, the stage stalled indefinitely — no stage-level signal fired,
no downstream evaluation occurred.

With the addition of `PlanItemStatus.REJECTED` (engine#338), there are now four
terminal states: COMPLETED, REJECTED, FAULTED, CANCELLED. Keeping the COMPLETED-only
gate means stages stall whenever any required item reaches a non-success terminal state,
which is increasingly common as the work adapter distinguishes intentional refusals from
timeouts.

## Decision Drivers

* A stage whose required items are all settled (no more work possible) should always
  propagate — regardless of how each item terminated
* Case definitions already receive the PlanItem status and case context; downstream
  logic can inspect outcomes and branch accordingly
* Stalled stages require manual operator intervention with no self-healing path

## Considered Options

* **Option A** — Trigger on any terminal state (COMPLETED, REJECTED, FAULTED, CANCELLED)
* **Option B** — Trigger on COMPLETED only (existing behaviour); raise a separate signal
  for non-success terminal states
* **Option C** — Make the autocomplete trigger condition configurable per-Stage

## Decision Outcome

Chosen option: **Option A**, because stages represent units of work, not units of
success. When all items are settled, the stage has concluded — the outcome is the
business concern of the case definition, not the engine's scheduling layer.

### Positive Consequences

* Stages always advance when their work is done, regardless of outcome
* Case definitions gain a clear signal to implement compensation, retry, or escalation
  flows using existing context inspection
* No manual operator intervention required for faulted or rejected item paths

### Negative Consequences / Tradeoffs

* **Runtime behavioural change on deploy:** cases in flight that have a required item
  in FAULTED or CANCELLED state will now trigger stage autocomplete on the next
  evaluation cycle. Operators monitoring stage RUNNING status will see stages complete
  that previously stalled. This is the correct long-term behaviour but is visible
  immediately on upgrade.
* Case definitions that relied on stages stalling after a fault (e.g. to hold for
  operator remediation) will need explicit compensation steps to replicate that
  behaviour.

## Pros and Cons of the Options

### Option A — Trigger on any terminal state

* ✅ Stages always resolve; no indefinite stalls
* ✅ Consistent semantics: settled = done
* ❌ Runtime-visible change for in-flight cases on deploy

### Option B — COMPLETED-only trigger (status quo)

* ✅ No change to existing in-flight behaviour
* ❌ Stages stall on fault or rejection; requires manual intervention
* ❌ Increasingly broken as REJECTED becomes a first-class state

### Option C — Configurable trigger per Stage

* ✅ Backward compatible; existing definitions unchanged
* ❌ Adds configuration surface; most definitions want Option A semantics
* ❌ Complexity disproportionate to benefit at current scale

## Links

* engine#338 — WorkItemLifecycleAdapter status collapse fix (introduces PlanItemStatus.REJECTED)
* engine#369 — umbrella tracking issue for this correctness batch
