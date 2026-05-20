# 0001 — humanTask as a first-class YAML binding target for human-in-the-loop gates

Date: 2026-05-20
Status: Accepted

## Context and Problem Statement

The engine YAML DSL needed a way to express bindings that create a casehub-work WorkItem for
a human to complete, then resume the case when the WorkItem reaches a terminal state. Three
approaches were available: reuse the existing `capability` target with a naming convention,
add a first-class binding target type, or introduce a dedicated WorkerProvisioner.

## Decision Drivers

* **Type safety over convention** — foundational platform code should enforce structure at the
  schema layer, not rely on undocumented naming patterns that tooling cannot validate
* **Explicit dispatch path** — the engine should be able to route `humanTask` bindings directly
  to the correct handler without runtime convention matching
* **Structured domain model** — human task fields (`title`, `templateRef`, `outputMapping`,
  `candidateGroups`, `expiresIn`) are distinct from capability fields and deserve their own
  schema definition with inline validation (e.g. title XOR templateRef)

## Considered Options

* **Option A** — Naming convention on `capability`: use `capability: "human-decision:*"` as a
  convention; per-harness WorkerProvisioner intercepts the name and creates WorkItems
* **Option B** — First-class `humanTask` binding target: third `oneOf` branch in the schema;
  `CaseDefinitionYamlMapper` converts to `HumanTaskTarget`; dispatched via `HumanTaskScheduleEvent`
* **Option C** — Inline `HumanTaskWorkerProvisioner`: specific provisioner intercepts named
  capabilities and creates WorkItems, keeping `capability` as the only binding target type

## Decision Outcome

Chosen option: **Option B**, because it enforces the human-task contract at the schema layer
(title XOR templateRef, typed optional fields) rather than at runtime through a naming
convention. The `capability` target retains its clear meaning (automated worker), and
`humanTask` bindings are unambiguous to readers, tooling, and the engine dispatcher.

### Positive Consequences

* Schema validation rejects malformed human task bindings at load time (inline mode without
  title, both title and templateRef present, etc.)
* `CaseContextChangedEventHandler` dispatches `humanTask` bindings via a direct type match
  (`HumanTaskTarget`) — no string matching or convention interpretation at runtime
* Adding `outputMapping`, `candidateGroups`, `candidateUsers`, `expiresIn` as typed schema
  fields enables IDE completion and validation for case definition authors
* `casehub-engine-work-adapter` is activated by classpath presence alone — no harness code
  required beyond the Maven dependency

### Negative Consequences / Tradeoffs

* Schema and mapper changes are required each time a new binding target type is introduced
  (vs. convention-based approaches where new types are free)
* The YAML subset constraint tightens: the schema must be kept in sync with `HumanTaskTarget`
  as that class evolves

## Pros and Cons of the Options

### Option A — Naming convention on capability

* ✅ Zero schema changes — any capability name works
* ✅ Per-harness provisioners are already the extension pattern for WorkerProvisioner
* ❌ Convention is invisible to schema validation — `capability: "human-decision:typo"` passes schema
* ❌ WorkerProvisioner path does not fire `HumanTaskScheduleEvent` — requires a separate bridge
* ❌ Each harness must implement a matching WorkerProvisioner; no shared implementation path

### Option B — First-class humanTask binding target (chosen)

* ✅ Schema-enforced: inline/template mode, required fields, mutual exclusion
* ✅ Direct dispatch path through existing `HumanTaskScheduleEvent` / `HumanTaskScheduleHandler`
* ✅ Shared implementation in `casehub-engine-work-adapter` — harnesses opt in via dependency only
* ❌ Requires schema + mapper changes per new binding target type
* ❌ `HumanTaskTarget` fields must stay in sync with the schema definition

### Option C — HumanTaskWorkerProvisioner

* ✅ Keeps the `capability` target as the only binding type
* ❌ WorkerProvisioner lifecycle (Quartz job scheduling, worker function execution) is designed
  for automated workers — adapting it for long-lived human tasks requires significant workarounds
* ❌ No natural path for `outputMapping` to reach the case context at completion
* ❌ Per-harness provisioner implementation; no shared code reuse

## Links

* Protocol PP-20260520-b2a932 — `yaml-humantask-binding-type.md` (casehubio/parent)
* Protocol PP-20260520-5d0b91 — `hitl-runtime-assembly.md` (casehubio/parent)
* engine#293 — HITL YAML binding + devtown wiring
* engine#297 — follow-up: improve error handling for malformed humanTask bindings
