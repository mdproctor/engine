# 0002 — Stage Binding Gating: Convention over Configuration

Date: 2026-04-21
Status: Accepted

## Context and Problem Statement

casehub-blackboard supports two distinct operational modes: a reactive
choreography model (bindings fire whenever their trigger conditions are met)
and a CMMN/Kogito-style flexible process model (all possible work fragments
are declared at design time; stages gate which fragments are currently
available). Both are valid patterns for different use cases. The question is
how to select between them — and whether selection should be global, per-case,
or per-stage.

The Kogito flexible process pattern (inspiration: blog.kie.org/2020/07/flexible-
processes-in-kogito) declares all possible workflow fragments upfront at design
time. Stages act as scope containers: when a stage is ACTIVE, its declared
fragments become available. When it is not, they are blocked. Free-floating
fragments (not declared in any stage) are always available.

## Decision Drivers

* Avoid a global mode flag that forces every case into one execution model
* Allow reactive and CMMN-style gating to coexist within the same case
* Keep the API surface minimal — no extra configuration object or enum
* Make the design-time intent explicit without requiring boilerplate
* The Kogito/CMMN pattern requires declared fragment-to-stage assignment at
  design time via `BlackboardPlanConfigurer.configure()`

## Considered Options

* **Option A — Explicit mode flag on `CasePlanModel`** — `plan.setStageGated(true/false)`
  selects the execution model for the entire case
* **Option B — Mode enum** — `CaseExecutionMode { CHOREOGRAPHY, STAGE_GATED, HYBRID }`
  declared at case definition time
* **Option C — Convention: binding declaration IS the opt-in** — if a Stage
  has `containedBindingNames` declared, those bindings are gated on that stage
  being ACTIVE; undeclared bindings are always free-floating

## Decision Outcome

Chosen option: **Option C — Convention over configuration**, because the
presence of binding declarations on a Stage is self-describing intent. No flag,
no enum. A Stage with declared bindings is a gating stage (Kogito/CMMN model).
A Stage without them is lifecycle-only (observability, autocomplete, event
publishing). Mixed within the same case is natural and requires no extra
configuration.

### Positive Consequences

* Three modes emerge from one mechanism, with no mode flag
* `BlackboardPlanConfigurer.configure()` is the single design-time declaration
  point — adding `stage.addBinding(name)` opts that stage into gating
* A case can mix gating stages and lifecycle-only stages freely
* Pure choreography requires no changes — simply add no binding declarations
* Aligns directly with the Kogito flexible process model where fragments are
  declared in ad-hoc subprocesses at design time

### Negative Consequences / Tradeoffs

* A Stage with no binding declarations is silently lifecycle-only — a developer
  who expects gating must know to call `addBinding()`. The distinction is
  implicit, not enforced at the API level.
* The loop control must scan all stages' `containedBindingNames` on every
  `select()` cycle to determine free-floating vs staged status — O(stages ×
  bindings) per cycle, acceptable at realistic stage counts.

## Pros and Cons of the Options

### Option A — Explicit mode flag on `CasePlanModel`

* ✅ Explicit and hard to miss
* ❌ Binary — forces the whole case into one model; hybrid within a case requires
  Option B or C anyway
* ❌ Adds surface area to `CasePlanModel` interface

### Option B — Mode enum

* ✅ Explicit and discoverable
* ❌ Hybrid mode still requires per-stage or per-binding declarations, making
  the enum redundant alongside Option C
* ❌ Three separate code paths to maintain in the loop control

### Option C — Convention: binding declaration IS the opt-in (chosen)

* ✅ No extra API surface — `addBinding()` on Stage is the entire mechanism
* ✅ Hybrid is the default: stages with declarations gate, stages without don't
* ✅ Design-time intent is visible in `BlackboardPlanConfigurer.configure()`
* ❌ Implicit: developers must know the convention to use gating correctly

## Links

* Kogito Flexible Processes inspiration: https://blog.kie.org/2020/07/flexible-processes-in-kogito-part-1.html
* casehubio/engine#76 — casehub-blackboard implementation
* casehubio/engine#84 — Stage/Milestone/Goal alignment (future)
* PR-J (pending) — implements `Stage.addBinding()` and loop control gating
