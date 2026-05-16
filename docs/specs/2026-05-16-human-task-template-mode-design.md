# HumanTaskTarget Template Mode — Design Spec

**Date:** 2026-05-16
**Issue:** casehubio/engine#255
**Repos changed:** casehub-work, casehub-engine (work-adapter)

---

## Problem

`HumanTaskScheduleHandler` implements inline mode for `HumanTaskTarget` bindings fully.
Template mode (`isTemplateMode() == true`) logs a warning and returns, leaving the PlanItem
PENDING indefinitely. This blocks any case plan that routes to a `WorkItemTemplate`.

`HumanTaskTarget`'s own javadoc states: "Both modes support `inputMapping` (context → task
payload) and `outputMapping` (task resolution → context update)." Implementing template mode
without honouring this contract would silently discard case context data for template-based
tasks — a correctness failure, not a missing feature.

The fix requires coordinated changes in casehub-work (template resolution and payload override)
and casehub-engine/work-adapter (handler wiring).

---

## Design

### casehub-work: WorkItemTemplateService additions

#### `findByName(String name): Optional<WorkItemTemplate>`

Queries the `work_item_template` table by name. Returns:
- `Optional.empty()` — zero matches
- `Optional.of(template)` — exactly one match
- throws `IllegalStateException("Ambiguous template name '<name>': N templates found")` — more
  than one match (configuration error; operator must deduplicate)

This is an application-level uniqueness check. A DB-level UNIQUE constraint is the right
long-term enforcement (tracked as a separate casehub-work issue).

#### `findByRef(String templateRef): Optional<WorkItemTemplate>`

Public entry point for all template resolution. Algorithm:

1. Try `UUID.fromString(templateRef)` → `findById(uuid)` if the string is a valid UUID
2. On `IllegalArgumentException` → `findByName(templateRef)`

Returns `Optional.empty()` if neither lookup finds a match. Propagates
`IllegalStateException` from `findByName` on ambiguity (caller must catch and warn).

Both methods are `@Transactional`.

#### `payloadOverride` on `toCreateRequest` and `instantiate`

The `HumanTaskTarget` contract requires `inputMapping`-produced data to reach the WorkItem
payload in template mode. `instantiate` currently copies `template.defaultPayload` with no
override mechanism.

**New 6-arg `toCreateRequest` static:**
```
toCreateRequest(template, titleOverride, assigneeIdOverride, createdBy, callerRef, payloadOverride)
```
If `payloadOverride` is non-null and non-blank → use it as the `payload` field.
Otherwise → fall back to `template.defaultPayload`.

The existing 5-arg static becomes a delegate: `toCreateRequest(..., callerRef, null)`.

**New 6-arg `instantiate` CDI method:**
```
instantiate(template, titleOverride, assigneeIdOverride, createdBy, callerRef, payloadOverride)
```
Follows the same multi-instance branch as the 5-arg: if `template.instanceCount != null`,
delegates to `multiInstanceSpawnService.createGroup` (which has no `payloadOverride` parameter —
`inputData` is not applied to multi-instance spawning in this release; tracked as a
`MultiInstanceSpawnService` enhancement). Otherwise calls `toCreateRequest` 6-arg then
`workItemService.create`. Annotated `@Transactional`.

The existing 5-arg `instantiate` delegates to the 6-arg with `null` payloadOverride (direct
`this.` call, same transaction). The existing 4-arg delegates to 5-arg as before.

**Payload semantics:** `inputData` (the pre-evaluated `inputMapping` output) wins entirely
when present; `template.defaultPayload` is the fallback. JSON merge of the two is a future
enhancement (tracked separately — see deferred items).

#### Name index on WorkItemTemplate

Add `@Index(name = "idx_work_item_template_name", columnList = "name")` to `@Table` on
`WorkItemTemplate`. Hibernate drop-and-create picks this up on next start; a migration for
environments that need it is a casehub-work concern.

---

### casehub-engine: HumanTaskScheduleHandler refactor

#### Control flow restructure

`markRunning()` currently sits in the shared path, after the template-mode guard returns
early. This works today only because the guard always returns. Once template mode is live,
template resolution failure must not advance PlanItem state — so `markRunning()` must move
inside each branch.

New structure:

```
onHumanTaskSchedule(event):
  resolve plan (existing guards — return on miss)
  resolve planItem (existing guards — return on miss)
  if target.isTemplateMode():
    handleTemplateMode(item, target, event)
  else:
    handleInlineMode(item, target, event)
```

#### `handleTemplateMode(PlanItem, HumanTaskTarget, HumanTaskScheduleEvent)`

1. Call `workItemTemplateService.findByRef(target.templateRef())`
   - catch `IllegalStateException` (ambiguous name) → WARN + return
   - if `Optional.empty()` → WARN + return
2. `item.markRunning()` — catch `IllegalStateException` → WARN + return
3. `callerRef = CallerRef.encode(event.caseId(), item.getPlanItemId())`
4. `workItemTemplateService.instantiate(template, target.title(), null, "casehub-engine", callerRef, serializePayload(event.inputData()))`
5. Log: `WorkItem created (template={id}) for binding callerRef={ref}`

`target.title()` is used as `titleOverride` — null in most template-mode cases (falls back to
`template.name`), but non-null when the binding author explicitly sets a title override on top
of the template.

`serializePayload(event.inputData())` reuses the existing private method. Returns null when
`inputData` is null or empty, causing `instantiate` to fall back to `template.defaultPayload`.

#### `handleInlineMode(PlanItem, HumanTaskTarget, HumanTaskScheduleEvent)`

Existing `markRunning()` + `createInline()` logic extracted verbatim into this private method.
No behavioural change.

#### `WorkItemTemplateService` injection

`@Inject WorkItemTemplateService workItemTemplateService` added alongside the existing
`@Inject WorkItemService workItemService`.

---

## Error handling

All error paths leave the PlanItem PENDING. The binding remains eligible for re-evaluation
on the next `CONTEXT_CHANGED` tick. No PlanItem state is advanced before the WorkItem is
successfully queued.

| Condition | Handler action |
|-----------|---------------|
| Template ref not found (UUID or name) | WARN log, return |
| Ambiguous template name (>1 match) | WARN log, return |
| `markRunning()` throws `IllegalStateException` | WARN log, return |
| `instantiate` throws unexpectedly | propagates — Vert.x event bus logs it |

---

## Testing

### casehub-work

New tests in `WorkItemTemplateServiceTest` (or equivalent service test class):

- `findByRef_validUuid_delegatesToFindById`
- `findByRef_validName_delegatesToFindByName`
- `findByRef_unknownRef_returnsEmpty`
- `findByName_uniqueMatch_returnsTemplate`
- `findByName_noMatch_returnsEmpty`
- `findByName_multipleMatches_throwsIllegalStateException`
- `toCreateRequest_payloadOverride_nonNull_usesOverride`
- `toCreateRequest_payloadOverride_null_usesTemplateDefault`
- `toCreateRequest_payloadOverride_blank_usesTemplateDefault`

### casehub-engine / work-adapter

New tests in `HumanTaskScheduleHandlerTest` (existing H2 + casehub-work-testing infra):

- `templateMode_byUuid_createsWorkItem_andMarksPlanItemRunning`
- `templateMode_byName_createsWorkItem_andMarksPlanItemRunning`
- `templateMode_withInputData_usesInputDataAsPayload`
- `templateMode_nullInputData_usesTemplateDefaultPayload`
- `templateMode_templateNotFound_planItemStaysPending`
- `templateMode_ambiguousName_planItemStaysPending`

Existing inline mode tests are unaffected — the refactor is structural only.

---

## Deferred items (GitHub issues required before leaving brainstorming)

1. **casehub-work:** DB-level `UNIQUE` constraint on `WorkItemTemplate.name`. Currently
   enforced at the application level in `findByName`; a DB constraint is the correct long-term
   guarantee.

2. **casehub-work / casehub-engine:** JSON merge semantics for `defaultPayload` + `inputData`.
   Currently `inputData` wins entirely when present, discarding `template.defaultPayload`.
   A proper deep-merge (template as base, inputData as overrides) may be desirable when
   templates define rich payload structure and bindings supply partial context.
