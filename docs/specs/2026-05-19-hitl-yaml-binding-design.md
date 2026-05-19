# Design: HITL YAML Binding and devtown Wiring

**Issue:** engine#293 (epic)  
**Date:** 2026-05-19  
**Sub-issues:** engine#294, engine#295, devtown#33

---

## Problem

HITL case resumption is broken in devtown. When a human completes a WorkItem, the case stalls in WAITING state. There are two gaps:

1. The YAML DSL only supports `capability` and `subCase` as binding targets. There is no `humanTask` type. Devtown's `human-approval` binding uses `capability: "human-decision:pr-approval"`, which routes through the Quartz/WorkerProvisioner path — `HumanTaskScheduleEvent` is never fired, so `HumanTaskScheduleHandler` is never invoked.
2. `casehub-engine-work-adapter` is not on devtown's classpath, so even if the event were fired, `WorkItemLifecycleAdapter` could not observe `WorkItemLifecycleEvent` to resume the case.

---

## Design

### 1. Schema — `$defs/HumanTask` and `Binding.humanTask` (engine#294)

Add `HumanTask` to `CaseDefinition.yaml`:

```yaml
HumanTask:
  type: object
  unevaluatedProperties: false
  description: >-
    A binding target that creates a WorkItem in casehub-work and resumes the case
    when the WorkItem reaches a terminal state.
  oneOf:
    - required: [title]
      not: { required: [templateRef] }
    - required: [templateRef]
      not: { required: [title] }
  properties:
    title:
      type: string
      description: "WorkItem title — inline mode"
    templateRef:
      type: string
      description: "WorkItemTemplate reference (UUID or name) — template mode"
    inputMapping:
      type: string
      description: "JQ expression mapping case context to WorkItem payload"
    outputMapping:
      type: string
      description: "JQ expression mapping WorkItem resolution to case context updates"
    candidateGroups:
      type: array
      items: { type: string }
      description: "Groups eligible to claim this WorkItem"
    candidateUsers:
      type: array
      items: { type: string }
      description: "Users eligible to claim this WorkItem"
    expiresIn:
      type: string
      description: "ISO 8601 duration after which the WorkItem expires (e.g. PT24H)"
```

Extend `Binding` with `humanTask` as a third mutually exclusive target:

```yaml
Binding:
  oneOf:
    - required: [capability]
      not: { required: [subCase, humanTask] }
    - required: [subCase]
      not: { required: [capability, humanTask] }
    - required: [humanTask]
      not: { required: [capability, subCase] }
  properties:
    ...existing...
    humanTask: { $ref: "#/$defs/HumanTask" }
```

`jsonschema2pojo` generates `io.casehub.model.HumanTask` and adds `getHumanTask()` to `io.casehub.model.Binding`.

### 2. YAML Mapper — `CaseDefinitionYamlMapper` (engine#295)

Add a `humanTask` branch in `convertBinding`, after the `subCase` branch:

```java
} else if (schemaBinding.getHumanTask() != null) {
    builder.humanTask(convertHumanTask(schemaBinding.getHumanTask()));
}
```

`convertHumanTask` maps schema fields to `HumanTaskTarget`:
- `title` present → `HumanTaskTarget.inline().title(title)...build()`
- `templateRef` present → `HumanTaskTarget.template(ref)...build()`
- `inputMapping`, `outputMapping` → `new JQExpressionEvaluator(expr)`
- `candidateGroups`, `candidateUsers` → `Set.of(...)` (null-safe)
- `expiresIn` → `Duration.parse(expiresIn)` (null-safe)

The error branch (neither capability nor subCase nor humanTask) is updated to include `humanTask` in the message.

**Tests added to `CaseDefinitionYamlMapperTest`:**
- Inline mode: YAML with `humanTask: { title: "...", outputMapping: "..." }` → `HumanTaskTarget` with correct fields
- Template mode: `humanTask: { templateRef: "my-template" }` → `HumanTaskTarget.isTemplateMode() == true`
- With optional fields: `candidateGroups`, `expiresIn` round-trip correctly

### 3. Devtown wiring (devtown#33)

**`review/src/main/resources/devtown/pr-review.yaml`**

Remove the now-unused `"human-decision:pr-approval"` capability declaration.

Change `human-approval` binding:
```yaml
# Before
- name: human-approval
  capability: "human-decision:pr-approval"

# After
- name: human-approval
  humanTask:
    title: "PR approval required"
    outputMapping: "{ humanApproval: { status: .decision } }"
```

**`app/pom.xml`**

```xml
<dependency>
  <groupId>io.casehub</groupId>
  <artifactId>casehub-engine-work-adapter</artifactId>
  <version>${project.version}</version>
</dependency>
```

This transitively brings `casehub-engine-blackboard` onto the classpath, activating `BlackboardRegistry` and plan model tracking (required by `HumanTaskScheduleHandler`).

**`app/src/test/resources/application.properties`**

Add index-dependency entries (following existing `engine-scheduler-quartz` pattern):
```properties
quarkus.index-dependency.engine-work-adapter.group-id=io.casehub
quarkus.index-dependency.engine-work-adapter.artifact-id=casehub-engine-work-adapter

quarkus.index-dependency.engine-blackboard.group-id=io.casehub
quarkus.index-dependency.engine-blackboard.artifact-id=casehub-engine-blackboard
```

---

## Flow After This Change

```
humanApproval binding fires (context matches when condition)
  → CaseContextChangedEventHandler: target is HumanTaskTarget
  → publishes HumanTaskScheduleEvent
  → HumanTaskScheduleHandler.onHumanTaskSchedule()
      creates WorkItem, saves PlanItem RUNNING, marks PlanItem RUNNING
  → Human completes WorkItem via WorkItemService.completeFromSystem()
  → WorkItemLifecycleEvent fires
  → WorkItemLifecycleAdapter.onWorkItemLifecycle()
      marks PlanItem COMPLETED, applies outputMapping to CaseContext
      publishes CONTEXT_CHANGED
  → CaseContextChangedEventHandler re-evaluates
  → merge binding fires (all conditions satisfied)
```

---

## What's Deferred

- **devtown#30** — End-to-end `@QuarkusTest`: start PR review case, complete WorkItem, verify case resumes and merge binding fires. Unblocked once devtown#33 lands.

---

## Files Touched

| File | Repo | Change |
|------|------|--------|
| `schema/src/main/resources/schema/CaseDefinition.yaml` | engine | Add `HumanTask` def + `humanTask` binding branch |
| `api/src/main/java/io/casehub/api/model/converter/CaseDefinitionYamlMapper.java` | engine | Add `humanTask` → `HumanTaskTarget` conversion |
| `api/src/test/java/io/casehub/api/model/converter/CaseDefinitionYamlMapperTest.java` | engine | Add parse tests for `humanTask` binding |
| `review/src/main/resources/devtown/pr-review.yaml` | devtown | Replace capability binding with `humanTask` |
| `app/pom.xml` | devtown | Add `casehub-engine-work-adapter` dep |
| `app/src/test/resources/application.properties` | devtown | Add `quarkus.index-dependency` entries |
