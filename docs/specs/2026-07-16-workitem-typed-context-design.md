# Typed Context for WorkItem Boundary via ContextBridge

**Issue:** engine#689
**Parent:** engine#203 (ContextBridge arc)
**Date:** 2026-07-16

## Overview

The WorkItem boundary — where CaseHub hands work to humans and receives their
decisions back — is untyped. `HumanTaskTarget.inputMapping()` produces
`Map<String, Object>`, and `WorkItem.resolution` is raw JSON text. Silent
mismatches between what the engine sends and what the human produces are caught
only at runtime, if at all.

This spec extends the ContextBridge protocol (engine#203) to the WorkItem
boundary. `HumanTaskTarget` gains `payloadType` and `resolutionType`
declarations. The engine validates data shape at both boundaries via bridge
resolution. The work repo stores type names as opaque metadata and echoes them
back — it stays bridge-agnostic.

## Design Decisions

### Separate types for payload and resolution

The input (what the human sees) and output (what the human produces) are almost
never the same shape. An AML review receives transaction details
(`AmlReviewPayload`) but produces an approve/reject decision
(`AmlReviewDecision`). A single type would force an awkward request+response
POJO that doesn't match how WorkItems actually work.

### Bridge operations on the engine side only

The work repo's core modules (api, runtime) do not depend on engine.
`ContextBridge<T>` and `BridgeResolver` live in `casehub-engine-api` and
`casehub-engine-common`. All bridge resolution and validation happens in
engine-side code. The work repo's core stores type name strings as opaque
metadata and echoes them back on completion.

The `engine-adapter` module (in the work repo) is architecturally engine-side
code — it already depends on `casehub-engine-common` and `casehub-blackboard`,
injects `BridgeResolver`'s sibling beans (`BlackboardRegistry`,
`CaseDefinitionRegistry`, `JQEvaluator`), and runs inside the engine's Quarkus
application. Bridge validation in `PlanItemCompletionApplier` (engine-adapter)
is consistent with this module's existing responsibilities.

### No serialisation at internal boundaries

Per the ContextBridge spec's serialisation boundary rule: `bridge.serialise()`
is called only at storage boundaries (WorkItem persistence) and wire boundaries.
The `HumanTaskScheduleEvent` carries data as `Object` — whatever the
inputMapping produced. The adapter serialises when it persists the WorkItem
(storage boundary). No serialise/deserialise round-trip for same-JVM transfers.

### Inline data only — linked data deferred

The bridge abstraction is agnostic about inline vs linked data. The
`payloadType`/`resolutionType` declare the shape the consumer sees; the bridge
implementation decides whether it produces that shape from inline data or by
resolving a reference. This spec implements the inline path. Linked data
(external references with resolution protocol) is tracked in engine#740.

### Naming: payloadType/resolutionType vs contextType

The ContextBridge architecture spec (#203, § Boundary Points subsection 2) uses a
single `contextType` field in its WorkItem design projection. This spec refines
that projection with separate `payloadType` and `resolutionType` because
WorkItems have two distinct data shapes: the input (what the human sees) and the
output (what the human produces). A single `contextType` cannot represent both.
The architecture spec's WorkItem projection should be updated to reflect this
dual-type design.

## HumanTaskTarget — Type Declarations

`HumanTaskTarget` gains two fields:

```java
private final Class<?> payloadType;      // nullable — null means untyped (Map)
private final Class<?> resolutionType;   // nullable — null means untyped (JsonNode)
```

### Java DSL

```java
HumanTaskTarget.inline()
    .title("Review transaction")
    .payloadType(AmlReviewPayload.class)
    .resolutionType(AmlReviewDecision.class)
    .inputMapping(".transaction")
    .outputMapping(".decision")
    .candidateGroups(Set.of("aml-reviewers"))
    .build()
```

### YAML

```yaml
humanTask:
  title: "Review transaction"
  payloadType: io.casehub.aml.AmlReviewPayload
  resolutionType: io.casehub.aml.AmlReviewDecision
  inputMapping: ".transaction"
  outputMapping: ".decision"
  candidateGroups: ["aml-reviewers"]
```

`CaseDefinitionYamlMapper.convertHumanTask()` resolves both via
`Class.forName()` — fail-fast at definition load time if the class is not on the
classpath. This requires domain types (e.g., `io.casehub.aml.AmlReviewPayload`)
on the engine's classpath at startup. Domain types live in shared API modules
that the engine depends on — the same constraint that exists for worker context
types (workers declare `fn().apply(ctx -> ...)` with concrete types). The
trade-off is intentional: definition-time failure catches misconfigured YAML
before any cases are created.

### Template mode

When `templateRef` is set, `payloadType`/`resolutionType` still apply — they are
orthogonal to the template. The template controls WorkItem structure (title,
form, schema); the type declarations control bridge validation at the engine
boundary.

The template's `inputDataSchema`/`outputDataSchema` (JSON Schema) and the
engine's `payloadType`/`resolutionType` (Java type) validate at different
lifecycle points:

1. **Engine dispatch** (payloadType): bridge validates the payload before it
   leaves the engine — at `HumanTaskScheduleEvent` publish time
2. **Work repo submission** (outputDataSchema): JSON Schema validates the
   human's resolution at form submission time, in the work repo

These validations are independent and sequential, not concurrent. If both are
configured with conflicting constraints, dispatch may succeed but form submission
may reject (or vice versa) — this is a definition error, not a runtime conflict.

## Input Path

### HumanTaskScheduleEvent

```java
public record HumanTaskScheduleEvent(
    UUID caseId,
    String tenancyId,
    String bindingName,
    HumanTaskTarget target,
    Map<String, Object> inputData, // unchanged
    String payloadTypeName,        // nullable — new
    String resolutionTypeName,     // nullable — new
    Set<String> resolvedCandidateGroups,
    Set<String> resolvedCandidateUsers,
    Instant caseBudgetDeadline,
    Instant expiresAtDeadline,
    String resolvedTitle,
    String resolvedScope,
    java.time.Duration resolvedExpiresIn) {}
```

`inputData` stays as `Map<String, Object>` — `evaluateInputMapping()` always
produces a Map, and the bridge validates then discards the typed result (no
serialisation at this internal boundary). `payloadTypeName` and
`resolutionTypeName` are the class names from `HumanTaskTarget`, passed through
for storage on the WorkItem.

### Engine validation

In `CaseContextChangedEventHandler.publishHumanTaskSchedule()`:

```
inputMapping evaluates → data (Map<String, Object>)
if payloadType != null and inputMapping != null:
    bridge = bridgeResolver.resolveByType(payloadType)
    bridge.initialise(caseContext, MAPPER.valueToTree(data))  // validates shape
    // result discarded — no serialise at internal boundary
publish event with data + payloadTypeName + resolutionTypeName
```

The bridge operation is `initialise()` per the ContextBridge architecture spec's
boundary summary: WorkItem payload is a CaseContext → Human boundary, matching
the Worker input boundary pattern. The typed result is discarded — the raw Map
flows through the event unchanged. This validate-and-discard pattern avoids
serialisation at the internal boundary while still catching shape mismatches.

Validation semantics are structural compatibility, not strict field presence:
extra fields pass (forward evolution tolerance), missing optional fields get
null, hard type mismatches fail. This matches `JacksonPojoBridge`'s default
configuration (`FAIL_ON_UNKNOWN_PROPERTIES = false`). Custom bridge
implementations can enforce stricter validation if needed.

When `inputMapping` is null, `evaluateInputMapping()` returns `Map.of()` (empty
map). Validation is skipped in this case — there is no meaningful data to
validate. The empty Map flows through as today.

If validation fails, the HumanTask is not scheduled. Log a warning, PlanItem
stays PENDING — same error handling as the current inputMapping evaluation
failure path.

## Output Path

### Data carrier changes

`resolutionTypeName` must flow from the work repo back to the engine through the
completion chain. The following types gain a `resolutionTypeName` field (String,
nullable):

- **`WorkItemRef`** — gains `payloadTypeName` and `resolutionTypeName` (9 → 11
  field record). Both are needed: `resolutionTypeName` is load-bearing for
  engine-side bridge validation on completion; `payloadTypeName` is informational
  for consumers (UI rendering, form selection).
- **`WorkItemEvent`** — exposes `resolutionTypeName()` via default method
  delegating to `ref().resolutionTypeName()`.
- **`ActionGateApprovedEvent`** — gains `resolutionTypeName` for gate WorkItems
  that declare a resolution type.

`WorkflowExecutionCompleted` does not change — the gate re-fire path uses
deferred output from `PendingActionGate`, not the WorkItem resolution.

### Work-engine-adapter (work repo)

On WorkItem completion, the adapter:
1. Reads `WorkItem.resolution` (JSON text) + `WorkItem.resolutionTypeName`
   (nullable) from the WorkItem entity
2. Populates `WorkItemRef` with both fields
3. Passes the ref to the engine — the adapter does not deserialise or validate

For gate WorkItems, `ActionGateCompletionApplier` reads
`ref.resolutionTypeName()` and includes it in `ActionGateApprovedEvent`.

### Engine validation — PlanItem path

WorkItem completions do NOT flow through `WorkflowExecutionCompleted` fan-out.
The flow is direct and sequential:

```
WorkItemEvent observed by WorkItemLifecycleAdapter
  → PlanItemCompletionApplier.apply(caseId, planItemId, status, ref)
    → bridge validation (new — before status transition)
    → applyStatus(item, status)
    → applyOutputMapping(item, ref, instance)
    → publish CONTEXT_CHANGED
```

`PlanItemCompletionApplier` is in the work repo's `engine-adapter` module but is
architecturally engine-side code (see §Design Decisions). It already injects
`BlackboardRegistry`, `CaseDefinitionRegistry`, `JQEvaluator`, and
`ReactiveCrossTenantCaseInstanceRepository` from engine-common. Adding
`BridgeResolver` is consistent with its existing dependencies.

In `PlanItemCompletionApplier.apply()`, bridge validation happens **before**
the PlanItem status transition:

```
if ref.resolutionTypeName() != null:
    bridge = bridgeResolver.resolveByTypeName(ref.resolutionTypeName())
    resolutionJson = MAPPER.readTree(ref.resolution())  // String → JsonNode
    bridge.deserialise(resolutionJson)                   // validates shape
    // fail-fast if resolution doesn't match declared type

applyStatus(item, status)       // PlanItem transitions to COMPLETED
applyOutputMapping(item, ref, instance)  // outputMapping runs on validated data
```

There is no fan-out ordering concern: `PlanItemCompletionApplier.apply()` is a
single `@Transactional` method that calls `applyStatus()` and
`applyOutputMapping()` sequentially. The `PlanItemCompletionHandler` in the
blackboard module handles WORKER completions (via `WORKER_EXECUTION_FINISHED`
fan-out) — a separate path that is not involved in WorkItem completions.

`BridgeResolver.resolveByTypeName()` must throw on `ClassNotFoundException`
instead of silently falling back to `MapBridge`. The current silent fallback
contradicts the fail-fast contract — if a class is renamed between WorkItem
creation and completion (days later), the output path must fail explicitly, not
silently skip validation.

### Engine validation — gate path

In `ActionGateApprovedHandler.onActionGateApproved()`, if
`event.resolutionTypeName()` is non-null, bridge validation runs against
`event.workItemResolution()` before re-firing `WorkflowExecutionCompleted`.

### Failure mode

If resolution validation fails, the PlanItem stays in its current state (not
COMPLETED) — validation runs before the status transition. The WorkItem in the
work repo is already COMPLETED because the human finished their work. This state
divergence is expected: the engine owns workflow state (PlanItem), the work repo
owns task lifecycle (WorkItem). These are different concerns.

The validation failure writes a `workItemValidationFailed` signal to the case
context with the following payload:

```json
{
  "workItemId": "<UUID>",
  "bindingName": "<binding>",
  "resolutionTypeName": "io.casehub.aml.AmlReviewDecision",
  "error": "Cannot deserialize value of type ... from Object"
}
```

Case definitions that use typed resolutions SHOULD bind on
`contextChange(".workItemValidationFailed")` to handle failures — e.g.,
re-dispatch the binding (which creates a new WorkItem via the standard
`HumanTaskScheduleEvent` path), notify the user, or escalate. The re-dispatch
is the standard CaseHub reactive pattern: signal → binding condition match →
action.

If no binding reacts to the signal, the PlanItem remains DELEGATED indefinitely.
This is a known consequence, not a platform bug — it matches the behavior of any
unhandled external event. The platform logs a warning with the full signal
payload for operational visibility.

## Work Repo Changes

### WorkItem entity

Two new nullable columns:

```java
@Column(name = "payload_type_name")
public String payloadTypeName;

@Column(name = "resolution_type_name")
public String resolutionTypeName;
```

Both are opaque metadata to the work repo — stored and echoed, not interpreted.
The work repo already has `inputDataSchema`/`outputDataSchema` (JSON Schema) for
its own validation; the type names serve the engine's bridge protocol.

Flyway migration in the work repo to add the columns. The engine has no
migration tooling (Hibernate manages schema).

### WorkItemCreateRequest

Two new fields on the builder:

```java
public Builder payloadTypeName(final String v)    { ... }
public Builder resolutionTypeName(final String v) { ... }
```

`HumanTaskScheduleHandler.createInline()` reads `payloadTypeName` and
`resolutionTypeName` from `HumanTaskScheduleEvent` and sets them on the builder.
Template mode follows the same pattern in `handleTemplateMode()`.

### Adapter changes

On WorkItem creation:
- `HumanTaskScheduleHandler` reads `payloadTypeName` and `resolutionTypeName`
  from `HumanTaskScheduleEvent`
- Sets them on `WorkItemCreateRequest.Builder`
- The work repo persists them on the `WorkItemEntity` entity

On WorkItem completion:
- `WorkItemSpiAdapter.toRef()` maps the entity's `payloadTypeName` and
  `resolutionTypeName` to the `WorkItemRef` record
- `WorkItemRef` now carries `resolutionTypeName` — the completion chain reads it

### WorkItemRef

`WorkItemRef` gains two fields: `payloadTypeName` and `resolutionTypeName` (both
String, nullable) — 9 → 11 field record. All `toRef()` mapping sites and
constructor call sites must be updated. `resolutionTypeName` is load-bearing for
the engine's bridge validation on completion. `payloadTypeName` is informational
for consumers (UI rendering, form selection) — a UI rendering a WorkItem needs
both the payload type (to display the right view) and the resolution type (to
render the right form).

## Backward Compatibility

Both `payloadType` and `resolutionType` are nullable. When absent:
- `inputMapping` produces `Map<String, Object>` as today — no bridge validation
- Resolution is raw JSON text (String) as today — no bridge validation
- The adapter creates WorkItems with null `payloadTypeName`/`resolutionTypeName`
- Zero behaviour change for existing case definitions

Existing YAML definitions with `humanTask:` blocks continue to work unchanged —
the new fields are optional.

When `payloadType` is set to `Map.class`, `MapBridge` is resolved — effectively
a no-op validation (same as untyped).

## Test Strategy

### Unit tests (engine)

- `HumanTaskTargetTest` — builder validation: `payloadType`/`resolutionType`
  stored correctly, nullable by default, YAML round-trip
- `CaseDefinitionYamlMapperTest` — `payloadType`/`resolutionType` parsed from
  YAML, `ClassNotFoundException` on invalid class, null when absent
- Bridge validation in `CaseContextChangedEventHandler` — inputMapping output
  validated against `payloadType`, fail-fast on mismatch, pass-through when
  `payloadType` is null

### Integration tests (engine)

- Extend `HumanTaskTargetDispatchTest` — typed payload flows through
  `HumanTaskScheduleEvent` with correct `payloadTypeName`/`resolutionTypeName`,
  untyped path unchanged
- New typed round-trip test:
  - Define a HumanTask with `payloadType` and `resolutionType`
  - Verify inputMapping output validated against payloadType
  - Verify event carries type names
  - Verify recording adapter receives typed metadata
  - Simulated completion with valid resolution → outputMapping runs, context
    updated
  - Simulated completion with invalid resolution → fail-fast, PlanItem not
    completed

### Work repo tests

- WorkItem entity — `payloadTypeName`/`resolutionTypeName` persisted and
  retrieved
- Adapter — type names passed through on create and echoed on completion
- Migration — new columns added, null for existing rows

### Edge cases

- `payloadType` set but `inputMapping` is null → validation skipped, empty Map
  flows through as today (no meaningful data to validate)
- `resolutionType` set but resolution is null/empty → validation fails,
  completion rejected
- `payloadType` set to `Map.class` → `MapBridge` resolved, effectively no-op
  validation
- Template mode with typed payload — template `inputDataSchema` (JSON Schema)
  and engine `payloadType` (Java type) coexist, both validate independently

## Relationship to Other Issues

| Issue | Relationship |
|-------|-------------|
| #203 | Parent — ContextBridge protocol |
| #740 | Linked data reference protocol — deferred, bridge abstraction is agnostic |
| #690 | SubCase typed context — sibling boundary, same ContextBridge arc |
| #691 | Typed signals — sibling boundary, same ContextBridge arc |
| #692 | Connectors — sibling boundary, same ContextBridge arc |
