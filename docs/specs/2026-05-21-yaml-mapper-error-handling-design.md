# Design: CaseDefinitionYamlMapper humanTask error handling

**Issue:** engine#297  
**Branch:** issue-297-yaml-mapper-error-handling  
**Date:** 2026-05-21

---

## Problem

`convertHumanTask` has three validation gaps:

1. **Conflicting fields** — if both `title` and `templateRef` are present in YAML, the mapper silently picks template mode and ignores `title`. The JSON Schema `oneOf` catches this at schema level, but Jackson deserialises both fields without enforcing it, so the mapper is never told.

2. **Invalid `expiresIn` format** — `Duration.parse()` throws `DateTimeParseException` (extends `RuntimeException`, not `IllegalArgumentException`). The catch block in `convertBinding` catches `IllegalStateException | IllegalArgumentException` only, so `DateTimeParseException` bypasses it. The binding name never appears in the error; callers see a raw parse failure with no context.

3. **Non-positive `expiresIn`** — `Duration.parse("PT-1H")` and `Duration.parse("PT0S")` succeed but produce semantically invalid values. No validation currently exists.

---

## Design

**Location:** all three checks go directly in `convertHumanTask`. Errors thrown as `IllegalArgumentException`; the existing `convertBinding` catch block adds the binding name (`"Binding 'X' has invalid humanTask: ..."`).

### Validation 1 — conflicting fields

At the top of `convertHumanTask`, before the builder decision:

```java
if (schema.getTitle() != null && schema.getTemplateRef() != null) {
    throw new IllegalArgumentException(
        "humanTask cannot specify both title and templateRef " +
        "— use inline mode (title) or template mode (templateRef), not both");
}
```

### Validation 2 — `expiresIn` format

Replace the bare `Duration.parse()` call:

```java
try {
    duration = Duration.parse(schema.getExpiresIn());
} catch (DateTimeParseException e) {
    throw new IllegalArgumentException(
        "invalid expiresIn '" + schema.getExpiresIn() +
        "' — must be ISO-8601 duration (e.g. PT24H, PT1H30M)", e);
}
```

### Validation 3 — `expiresIn` positivity

Immediately after parsing:

```java
if (duration.isNegative() || duration.isZero()) {
    throw new IllegalArgumentException(
        "expiresIn must be positive, got '" + schema.getExpiresIn() + "'");
}
```

---

## Error message examples (after `convertBinding` wrapping)

```
Binding 'irb-approval' has invalid humanTask: humanTask cannot specify both title
and templateRef — use inline mode (title) or template mode (templateRef), not both

Binding 'irb-approval' has invalid humanTask: invalid expiresIn 'P1D'
— must be ISO-8601 duration (e.g. PT24H, PT1H30M)

Binding 'irb-approval' has invalid humanTask: expiresIn must be positive, got 'PT0S'
```

---

## Tests

Three new unit tests in `CaseDefinitionYamlMapperTest` (pure JUnit5, no QuarkusTest):

| Test | Input | Expected |
|------|-------|----------|
| `humanTaskBinding_withBothTitleAndTemplateRef_throwsIllegalArgument` | YAML with both `title` and `templateRef` | `IllegalArgumentException` with binding name and "cannot specify both" |
| `humanTaskBinding_withInvalidExpiresInFormat_throwsIllegalArgument` | `expiresIn: "P1D"` | `IllegalArgumentException` with binding name and bad value |
| `humanTaskBinding_withNonPositiveExpiresIn_throwsIllegalArgument` | `expiresIn: "PT0S"` | `IllegalArgumentException` with binding name and "must be positive" |

All tests use YAML string input through `CaseDefinitionYamlMapper.load()` to exercise the full path including the binding-name wrapper.

---

## Scope

No changes to `HumanTaskTarget`, `convertBinding`, or any other class. Single method edit in `CaseDefinitionYamlMapper`.
