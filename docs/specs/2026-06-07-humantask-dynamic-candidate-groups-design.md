# Design: Dynamic candidateGroups and candidateUsers for humanTask Binding

**Issue:** engine#387  
**Date:** 2026-06-07  
**Status:** Approved (rev 2 — post code review)

## Problem

`candidateGroups` and `candidateUsers` in a `humanTask` binding are static lists — fixed at case definition time. There is no way to derive them from the case context at runtime.

```yaml
humanTask:
  candidateGroups: [irb-committee]   # hardcoded — cannot vary per case instance
```

`casehub-clinical` is the first concrete consumer: `IrbCommitteeAssignmentPolicy` (SPI) resolves the correct IRB committee per trial/site and writes the result to the case context. Without dynamic `candidateGroups`, the WorkItem routing ignores this output and always routes to the same static group.

## Solution

Apply the same pattern already used by `inputMapping`/`inputData`: the spec lives in `HumanTaskTarget`, is evaluated against the case context at event-publish time in `CaseContextChangedEventHandler`, and the resolved value is carried in `HumanTaskScheduleEvent`. `HumanTaskScheduleHandler` is unchanged in structure — it uses already-resolved data and never evaluates expressions.

`candidateUsers` gets the same treatment for symmetry.

## Data Model

### `ListEvaluator` sealed interface (new)

`api/src/main/java/io/casehub/api/model/evaluator/ListEvaluator.java`

```java
public sealed interface ListEvaluator permits ListEvaluator.StaticList, ListEvaluator.JQList {
  record StaticList(Set<String> values) implements ListEvaluator {}
  record JQList(String expression)      implements ListEvaluator {}
}
```

**Why a separate hierarchy from `ExpressionEvaluator`:** `ExpressionEvaluator` is the input type for `ExpressionEngine.evaluate(...): boolean` — every current implementation (`JQExpressionEvaluator`, `LambdaExpressionEvaluator`) represents a boolean predicate. `ListEvaluator` produces a `Set<String>`, not a boolean. Placing it in the `ExpressionEvaluator` hierarchy would be type pollution and could confuse `ExpressionEngineRegistry` dispatch. The sealed `ListEvaluator` hierarchy is exhaustively checked by the compiler — no `type()` constant or runtime dispatch needed.

When engine#439 adds dynamic `title`/`scope`, a `StringEvaluator` sealed type follows the same pattern.

### `HumanTaskTarget` changes

| Before | After |
|--------|-------|
| `Set<String> candidateGroups` | `@Nullable ListEvaluator candidateGroups` |
| `Set<String> candidateUsers` | `@Nullable ListEvaluator candidateUsers` |
| `candidateGroups()` → `Set<String>` | `candidateGroups()` → `@Nullable ListEvaluator` |
| `candidateUsers()` → `Set<String>` | `candidateUsers()` → `@Nullable ListEvaluator` |

Accessor names stay `candidateGroups()` / `candidateUsers()` — consistent with `inputMapping()` which names the field, not the evaluator type. All call sites that dereference the old `Set<String>` get a compile error, forcing explicit handling.

**Builder methods:**

```java
// Static — unchanged call sites, wraps in StaticList
builder.candidateGroups(Set.of("irb-committee"))

// Dynamic JQ — distinct name, unambiguous intent
builder.candidateGroupsExpression(".irb.candidateGroups")
```

`candidateGroups(String)` overload is **not added**. A bare string like `"irb-committee"` passed to such an overload would silently become a JQ expression, fail evaluation, and leave the PlanItem PENDING with no compiler warning. The distinct method name `candidateGroupsExpression` prevents this class of error. Same treatment for `candidateUsers` / `candidateUsersExpression`.

### `HumanTaskScheduleEvent` changes

Two new fields added after `inputData`:

```java
public record HumanTaskScheduleEvent(
    UUID caseId,
    String bindingName,
    HumanTaskTarget target,
    Map<String, Object> inputData,
    Set<String> resolvedCandidateGroups,   // null = no spec; RESOLUTION_FAILED never reaches here
    Set<String> resolvedCandidateUsers,    // null = no spec; RESOLUTION_FAILED never reaches here
    Instant caseBudgetDeadline,
    String tenancyId) {}
```

`null` = no spec was set on the binding (preserves template defaults in template mode). `RESOLUTION_FAILED` blocks event publication — the handler never sees it.

## `ListExpressionResolver` (new class)

`runtime/src/main/java/io/casehub/engine/internal/engine/ListExpressionResolver.java`

`@ApplicationScoped` — injectable and directly unit-testable. Extracted from `CaseContextChangedEventHandler` so the six behavioral branches can be tested in isolation without spinning up a full handler.

```java
@ApplicationScoped
public class ListExpressionResolver {

  // Sentinel — private, but isFailed() exposes the check cleanly.
  // Checked with ==, not .equals(), because only this reference
  // can compare equal. The unmodifiableSet wrapper prevents accidental mutation.
  static final Set<String> RESOLUTION_FAILED =
      Collections.unmodifiableSet(new HashSet<>());

  public static boolean isFailed(Set<String> result) {
    return result == RESOLUTION_FAILED;
  }

  @Inject JQEvaluator jqEvaluator;

  public @Nullable Set<String> resolve(
      CaseInstance instance, @Nullable ListEvaluator spec, String fieldName) {
    if (spec == null) return null;
    return switch (spec) {
      case StaticList s -> s.values();
      case JQList jq   -> resolveJq(instance, jq, fieldName);
    };
  }

  private Set<String> resolveJq(CaseInstance instance, JQList jq, String fieldName) { ... }
}
```

**Implementation note:** `StaticList` and `JQList` are inner records of `ListEvaluator`. The switch arms require either `import static io.casehub.api.model.evaluator.ListEvaluator.*;` or fully qualified `ListEvaluator.StaticList` / `ListEvaluator.JQList` — add the static import to avoid a confused first compile.

```java
```

### Log levels inside `resolveJq`

| Condition | Level | Rationale |
|-----------|-------|-----------|
| JQ result is non-empty string array | — | Success |
| JQ result is empty array `[]` | WARN | Transient — context may not have the value yet |
| JQ result is non-array | ERROR | Misconfiguration — expression is wrong |
| JQ evaluation throws | ERROR | Misconfiguration — invalid expression |

Empty-array is WARN because the engine may re-trigger the binding when context changes and the expression might then return valid groups. Non-array and throw are ERROR because they represent authoring mistakes that will not self-correct.

## Evaluation (`CaseContextChangedEventHandler`)

`publishHumanTaskSchedule()` injects `ListExpressionResolver` and calls it for both fields:

```java
Set<String> resolvedGroups = resolver.resolve(caseInstance, target.candidateGroups(), "candidateGroups");
Set<String> resolvedUsers  = resolver.resolve(caseInstance, target.candidateUsers(),  "candidateUsers");

if (ListExpressionResolver.isFailed(resolvedGroups)
    || ListExpressionResolver.isFailed(resolvedUsers)) {
  // Either field failed — PlanItem stays PENDING, event not published.
  // Error already logged by resolver with field name.
  return Uni.createFrom().voidItem();
}
```

Both fields are resolved before the guard check. A failure in either blocks the event — this includes the conjunction case where groups fails and users succeeds.

## Handler (`HumanTaskScheduleHandler`)

**`candidateGroupsCsv(HumanTaskTarget)` and `candidateUsersCsv(HumanTaskTarget)` removed.** Replaced by `toCsv(Set<String>)` — takes an already-resolved set.

**Inline mode** — uses resolved sets from the event directly:

```java
WorkItemCreateRequest.builder()
    .candidateGroups(toCsv(event.resolvedCandidateGroups()))
    .candidateUsers(toCsv(event.resolvedCandidateUsers()))
    ...
```

**Template mode** — after `instantiate()`, overrides routing only when resolved sets are non-null:

```java
if (event.resolvedCandidateGroups() != null) {
  workItem.candidateGroups = toCsv(event.resolvedCandidateGroups());
}
if (event.resolvedCandidateUsers() != null) {
  workItem.candidateUsers = toCsv(event.resolvedCandidateUsers());
}
```

`null` = spec was absent → template's own groups are preserved. Non-null = binding overrides routing.

## YAML Schema (`CaseDefinition.yaml`)

`candidateGroups` and `candidateUsers` change from `type: array` to `oneOf`:

```yaml
candidateGroups:
  oneOf:
    - type: array
      items: { type: string }
      description: "Static list of groups eligible to claim this WorkItem"
    - type: string
      description: "JQ expression resolving to a list of group names from case context"
candidateUsers:
  oneOf:
    - type: array
      items: { type: string }
    - type: string
      description: "JQ expression resolving to a list of user IDs from case context"
```

jsonschema2pojo generates `Object candidateGroups` and `Object candidateUsers` in `io.casehub.model.HumanTask`. **Implementation note:** verify the generated type after `mvn generate-sources` — jsonschema2pojo `oneOf` handling is version-dependent. If it does not produce `Object`, read `candidateGroups` from the raw YAML `JsonNode` directly (`node.get("candidateGroups").isArray()` vs `isTextual()`) rather than the generated class — this keeps the mapper robust against future code-gen version changes.

**`CaseDefinitionYamlMapper`** branches on the runtime type:

```java
Object rawGroups = schema.getCandidateGroups();
if (rawGroups instanceof List<?> list && !list.isEmpty()) {
  builder.candidateGroups(new LinkedHashSet<>(castStringList(list)));
} else if (rawGroups instanceof String expr && !expr.isBlank()) {
  builder.candidateGroupsExpression(expr);  // → JQList
}
// same pattern for candidateUsers
```

`castStringList()` validates elements are strings; throws `IllegalArgumentException` on bad YAML.

## YAML Usage Examples

```yaml
# Static (existing — unchanged semantics)
humanTask:
  title: "IRB Review"
  candidateGroups: [irb-committee]

# Dynamic — groups resolved from case context at runtime
humanTask:
  title: "IRB Review"
  candidateGroups: ".irb.candidateGroups"

# Dynamic — nested path
humanTask:
  title: "Site Approval"
  candidateGroups: ".site.approvalGroups"
  candidateUsers: ".trial.principalInvestigatorId | [.]"
```

## Fluent DSL Usage

```java
// Existing — unchanged
HumanTaskTarget.inline()
    .title("IRB Review")
    .candidateGroups(Set.of("irb-committee"))
    .build()

// New — dynamic
HumanTaskTarget.inline()
    .title("IRB Review")
    .candidateGroupsExpression(".irb.candidateGroups")
    .build()
```

## Testing

### Unit tests

**`HumanTaskTargetTest`**
- `candidateGroups(Set<String>)` → `candidateGroups()` returns `StaticList` with correct values
- `candidateGroupsExpression(String)` → `candidateGroups()` returns `JQList` with correct expression
- Same assertions for `candidateUsers` / `candidateUsersExpression`

**`CaseDefinitionYamlMapperTest`**
- YAML `candidateGroups: ".irb.groups"` → `JQList`
- YAML `candidateGroups: [irb-committee]` → `StaticList` (existing round-trip, updated assertions)
- YAML `candidateGroups` absent → `null`

**`ListExpressionResolverTest`** (new, unit — same package as `ListExpressionResolver`)
- `null` spec → returns `null`
- `StaticList(Set.of("a","b"))` → returns `Set.of("a","b")`, JQ never invoked
- `JQList`, context has matching array → correct `Set<String>` returned
- `JQList`, JQ returns non-array → `RESOLUTION_FAILED`, log at ERROR
- `JQList`, JQ returns empty array `[]` → `RESOLUTION_FAILED`, log at WARN
- `JQList`, JQ throws → `RESOLUTION_FAILED`, log at ERROR
- **Conjunction case:** groups returns `RESOLUTION_FAILED`, users returns valid `Set<String>` → guard fires, event not published (tested at `CaseContextChangedEventHandler` level)

**`HumanTaskScheduleHandlerTest`**
- Inline: `resolvedCandidateGroups = Set.of("irb-committee")` → WorkItem created with `"irb-committee"` CSV
- Inline: `resolvedCandidateGroups = null` → WorkItem created with null candidateGroups
- Template: `resolvedCandidateGroups = Set.of("committee-a")` → `workItem.candidateGroups` overridden
- Template: `resolvedCandidateGroups = null` → `workItem.candidateGroups` left as template default (not overridden)

### Integration tests

**`HumanTaskTargetDispatchTest`** (`@QuarkusTest`)

Inline mode:
- Case context contains `.irb = {candidateGroups: ["irb-committee"]}`, binding uses `candidateGroupsExpression: ".irb.candidateGroups"` → event carries `resolvedCandidateGroups = Set.of("irb-committee")`
- JQ expression evaluates to non-array → event not published, PlanItem stays PENDING

Template mode (new):
- `resolvedCandidateGroups` non-null → instantiated WorkItem has `candidateGroups` overridden to resolved CSV
- `resolvedCandidateGroups` null (spec absent on template binding) → WorkItem retains template's own candidateGroups

## Files Changed

| File | Change |
|------|--------|
| `api/.../evaluator/ListEvaluator.java` | New — sealed interface with `StaticList`, `JQList` inner records |
| `api/.../model/HumanTaskTarget.java` | `candidateGroups`/`candidateUsers` → `@Nullable ListEvaluator`; builder adds `candidateGroupsExpression`/`candidateUsersExpression` |
| `api/.../converter/CaseDefinitionYamlMapper.java` | Branch on `Object` type; call `candidateGroupsExpression()` for string path |
| `schema/.../schema/CaseDefinition.yaml` | `oneOf` for candidateGroups/candidateUsers |
| `common/.../event/HumanTaskScheduleEvent.java` | Add `resolvedCandidateGroups`, `resolvedCandidateUsers` |
| `runtime/.../engine/ListExpressionResolver.java` | New — `@ApplicationScoped`, injectable, testable |
| `runtime/.../handler/CaseContextChangedEventHandler.java` | Inject `ListExpressionResolver`; resolve + guard before publish |
| `work-adapter/.../HumanTaskScheduleHandler.java` | Use resolved fields; `toCsv(Set<String>)`; template override |
| `api/src/test/.../HumanTaskTargetTest.java` | Updated accessor assertions; new JQ builder tests |
| `api/src/test/.../CaseDefinitionYamlMapperTest.java` | New YAML expression test cases; updated static-list assertions |
| `runtime/src/test/.../ListExpressionResolverTest.java` | New — six behavioral unit tests + conjunction case |
| `work-adapter/src/test/.../HumanTaskScheduleHandlerTest.java` | Updated event construction; new template-override cases |
| `work-adapter/src/test/.../HumanTaskScheduleHandlerAtomicityTest.java` | Updated event construction |
| `runtime/src/test/.../HumanTaskTargetDispatchTest.java` | New inline + template integration test cases |
| `casehub/garden: docs/protocols/casehub/yaml-humantask-binding-type.md` | Update PP-20260520-b2a932 — document dynamic candidateGroups/candidateUsers |

## Out of Scope

- Dynamic `title`, `scope`, `expiresIn` — same `ListEvaluator`/`StringEvaluator` pattern; tracked in engine#439
- Platform doc update (casehub-engine.md) — tracked in parent#188
- Surfacing resolution failures as observable events (structured error signals vs log-only) — known gap shared with template-not-found (engine#297); not addressed here
