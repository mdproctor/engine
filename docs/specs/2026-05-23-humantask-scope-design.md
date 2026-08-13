# Design Spec — HumanTaskTarget scope propagation (engine#330)

**Date:** 2026-05-23
**Issue:** casehubio/engine#330
**Branch:** issue-330-humantask-scope

## Problem

`HumanTaskScheduleHandler` creates WorkItems without setting `scope`. `WorkItem.scope` defaults
to null, so `ExpiryLifecycleService.buildBreachContext()` resolves preferences at `Path.root()`
for all HITL-created WorkItems. Applications whose `SlaBreachPolicy` calls `ctx.preferences()`
get org-level defaults instead of the case-type preferences they declared.

## Design

The fix is pure plumbing: the scope is already modelled end-to-end (`WorkItem.scope`,
`WorkItemCreateRequest.scope`, platform `Path` convention). This spec fills the gap from
YAML definition through to WorkItem creation.

## Changes

### 1. `CaseDefinition.yaml` — add optional `scope` to `HumanTask`

Add under `HumanTask.properties`:

```yaml
scope:
  type: string
  description: >-
    Hierarchical scope path for SLA preference resolution
    (e.g. "casehubio/devtown/pr-review"). Null resolves preferences at root scope.
    Follow the platform convention: org / app / case-type.
```

`jsonschema2pojo` regenerates `io.casehub.model.HumanTask` automatically — no manual edit
to the generated class.

### 2. `HumanTaskTarget` — add `scope` field

Add to `casehub-engine-api`:

- `private final String scope` — nullable; null means unscoped (root)
- `Builder.scope(String)` method
- `scope()` accessor

Type is `String` (not `Path`). `HumanTaskTarget` is in the pure-Java API tier
(`casehub-engine-api`). Introducing a `casehub-platform-api` dep for type safety would
create a cross-foundation coupling with no architectural benefit — the format contract
(`"casehubio/devtown/pr-review"`) is documented and enforced at the application layer.

### 3. `CaseDefinitionYamlMapper.convertHumanTask()` — propagate scope

Add one line after the existing field mappings:

```java
if (schema.getScope() != null) {
    builder.scope(schema.getScope());
}
```

No format validation here — scope format enforcement is casehub-work's responsibility.
Null propagates naturally (scope absent in YAML → null in target → null in WorkItem → root fallback).

### 4. `HumanTaskScheduleHandler` — set scope on WorkItem

**Inline mode** (`createInline`): add `.scope(target.scope())` to `WorkItemCreateRequest.builder()` chain.

**Template mode** (`handleTemplateMode`): add `workItem.scope = target.scope()` alongside
the existing `workItem.callerRef` manual assignment, before `workItem.persist()`.

### 5. No changes required to

- `HumanTaskScheduleEvent` — already carries `HumanTaskTarget target` (scope included after step 2)
- `WorkItemLifecycleAdapter` — scope is read by work's expiry service, not the adapter
- `WorkItemTemplateService.instantiate()` — scope is set on the returned `WorkItemEntity` directly

## YAML example

```yaml
bindings:
  - name: irb-review
    humanTask:
      title: "IRB Ethics Review"
      scope: "casehubio/clinical/adverse-event"
      candidateGroups: [ethics-committee]
      expiresIn: PT72H
```

## Tests

### `HumanTaskTargetTest`
- `builder_withScope_accessorReturnsScope()`
- `builder_withoutScope_accessorReturnsNull()`

### `CaseDefinitionYamlMapperTest`
- `humanTask_withScope_parsedAndPreserved()` — YAML with `scope` field round-trips correctly
- `humanTask_withoutScope_scopeIsNull()` — existing tests unaffected, scope null by default

### `HumanTaskScheduleHandlerTest`
- `inlineMode_withScope_workItemScopeSet()` — `created.scope` equals value from `HumanTaskTarget`
- `templateMode_withScope_workItemScopeSet()` — `workItem.scope` equals value from `HumanTaskTarget`
- Existing tests: scope null → `created.scope` null (regression check)

### `HumanTaskTargetValidationTest` (in `CaseDefinitionYamlMapperTest`)
- `humanTask_scope_noValidationApplied()` — any string passes (format is work's concern)
