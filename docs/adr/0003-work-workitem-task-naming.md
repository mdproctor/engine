# 0003 — Work, WorkItem, and Task naming — broker-level vocabulary

Date: 2026-04-22
Status: Accepted

## Context and Problem Statement

casehub-core (the predecessor project, now being retired) had a `TaskBroker`
class with a `WorkerSelectionStrategy` SPI and a `TaskScheduler` for routing
work to workers. As part of the migration to casehub-engine, these concepts
must be re-implemented. In parallel, casehub-engine and quarkus-workitems
independently designed the same worker-selection SPI and aligned on a shared
`casehub-work-api` library — which uses `WorkBroker`, `WorkerSelectionStrategy`,
and `WorkerCandidate` as its canonical names. Issue #121 captured this design
using `TaskBroker` as a working name. This ADR closes that open naming
decision: casehub-core's `TaskBroker` is not ported; `WorkBroker` from
casehub-work-api is adopted as its replacement.

## Decision Drivers

* `casehub-work-api` already uses `Work`-prefixed names — adopting them
  directly eliminates any translation layer between casehub-engine and the
  shared SPI
* `WorkItem` has well-understood human-inbox connotations (Jira, Azure DevOps)
  and should remain scoped to that specialisation
* `Task` is unambiguous at the sub-step level and should stay there
* The naming must work for both automated-agent and human-worker scenarios
  without retrofitting

## Considered Options

* **Option A — `Work` + `WorkItem` + `Task`**: three-level hierarchy, aligned
  with casehub-work-api
* **Option B — Unified `WorkItem`**: everything is a WorkItem; automated tasks
  are `AutomatedWorkItem`, human tasks are `HumanWorkItem`
* **Option C — Introduce `TaskBroker`**: use the working name from #121;
  accept mismatch with casehub-work-api

## Decision Outcome

Chosen option: **Option A**, because it maps exactly onto the casehub-work-api
naming already agreed with treblereel, and gives each term a precise,
non-overlapping meaning.

### Positive Consequences

* `WorkBroker` from `casehub-work-core` replaces casehub-core's `TaskBroker`
  with no port required — the shared SPI is the implementation
* casehub-core's `WorkerSelectionStrategy` maps directly to casehub-work-api's
  `WorkerSelectionStrategy` — same interface, aligned name
* `WorkItem` retains its human-inbox specialisation in the WorkItems module
* `Task` is reserved for sub-steps within a `Work` unit — unambiguous scope
* Clean architecture from day one; no legacy name surfaces in casehub-engine

### Negative Consequences / Tradeoffs

* Issue #121 uses `TaskBroker` in its title and body — it remains a valid
  design reference but its terminology is superseded by this ADR
* `Work` as a generalised concept requires documentation; the hierarchy
  (Work → WorkItem, Work → Task) is not self-evident

## Pros and Cons of the Options

### Option A — `Work` + `WorkItem` + `Task`

* ✅ Zero translation layer against casehub-work-api
* ✅ `WorkItem` retains its precise human-inbox meaning
* ✅ `Task` is unambiguous at sub-step level
* ❌ `Work` as a top-level noun needs explicit documentation

### Option B — Unified `WorkItem`

* ✅ Single concept for everything assignable
* ❌ Conflicts with established tooling connotations
* ❌ Mismatches casehub-work-api — requires a translation layer

### Option C — Introduce `TaskBroker`

* ✅ Consistent with #121 working name
* ❌ Permanent mismatch with casehub-work-api
* ❌ Introduces debt from day one; would require renaming later

## Links

* casehubio/engine#121 — open design issue whose naming decision this ADR closes
* `io.casehub:casehub-work-api` — shared SPI whose naming this ADR adopts
* ADR-0001, ADR-0002 — prior casehub-engine decisions (blackboard series)
