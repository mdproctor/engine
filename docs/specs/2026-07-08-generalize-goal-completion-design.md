# Generalize GoalBasedCompletion — Design Spec

**Issue:** #582
**Date:** 2026-07-08

## Problem

`GoalBasedCompletion` hardcodes two named fields — `success` (→ COMPLETED) and `failure`
(→ FAULTED). `GoalKind` is a closed enum with only these two values. Domains that need
additional terminal outcomes (ESCALATED, REFERRED, WITHDRAWN) cannot express them.

The completion routing is implicit: the `success` field name maps to COMPLETED, the
`failure` field name maps to FAULTED. `GoalKind` on `Goal` is audit metadata only — it
is not consulted by the completion evaluation. These two roles (classification label and
terminal status mapping) are disconnected.

## Design

### GoalKind: enum → interface + built-in enum

`GoalKind` becomes an interface with two methods and two built-in constants:

```java
/**
 * Implementations must provide value-based equals/hashCode — GoalKind instances
 * serve as map keys in GoalBasedCompletion.
 */
public interface GoalKind {
    String value();
    CaseStatus terminalStatus();

    GoalKind SUCCESS = StandardGoalKind.SUCCESS;
    GoalKind FAILURE = StandardGoalKind.FAILURE;

    static GoalKind of(String value, CaseStatus terminalStatus) {
        return new DefaultGoalKind(value, terminalStatus);
    }

    static GoalKind fromValue(String value) {
        try {
            return StandardGoalKind.fromValue(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "Unknown GoalKind: " + value
                    + " — custom kinds must be created with GoalKind.of(value, terminalStatus)");
        }
    }
}
```

`StandardGoalKind` is a public enum implementing `GoalKind`:

```java
public enum StandardGoalKind implements GoalKind {
    SUCCESS("success", CaseStatus.COMPLETED),
    FAILURE("failure", CaseStatus.FAULTED);

    private final String value;
    private final CaseStatus terminalStatus;

    StandardGoalKind(String value, CaseStatus terminalStatus) {
        this.value = value;
        this.terminalStatus = terminalStatus;
    }

    @Override public String value() { return value; }
    @Override public CaseStatus terminalStatus() { return terminalStatus; }

    public static StandardGoalKind fromValue(String value) {
        for (StandardGoalKind kind : values()) {
            if (kind.value.equals(value)) return kind;
        }
        throw new IllegalArgumentException("Unknown StandardGoalKind: " + value);
    }
}
```

`DefaultGoalKind` is a package-private record used by the `GoalKind.of()` factory
(YAML mapper creates these for custom kinds):

```java
record DefaultGoalKind(String value, CaseStatus terminalStatus) implements GoalKind {
    DefaultGoalKind {
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(terminalStatus, "terminalStatus must not be null");
    }
}
```

### GoalBasedCompletion<K extends GoalKind>

The class becomes generic with an insertion-ordered map. Iteration order is evaluation
priority — first satisfied expression wins.

```java
public class GoalBasedCompletion<K extends GoalKind> implements CaseCompletion {

    private final LinkedHashMap<K, GoalExpression> goals;

    private GoalBasedCompletion(LinkedHashMap<K, GoalExpression> goals) {
        this.goals = goals;
    }

    public Map<K, GoalExpression> getGoals() {
        return Collections.unmodifiableMap(goals);
    }

    public static <K extends GoalKind> Builder<K> builder() {
        return new Builder<>();
    }

    public static class Builder<K extends GoalKind> {
        private final LinkedHashMap<K, GoalExpression> goals = new LinkedHashMap<>();

        public Builder<K> goal(K kind, GoalExpression expression) {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(expression, "expression must not be null");
            if (goals.containsKey(kind)) {
                throw new IllegalStateException(
                    "Duplicate goal kind: " + kind.value());
            }
            goals.put(kind, expression);
            return this;
        }

        public GoalBasedCompletion<K> build() {
            if (goals.isEmpty()) {
                throw new IllegalStateException(
                    "GoalBasedCompletion requires at least one goal kind");
            }
            return new GoalBasedCompletion<>(new LinkedHashMap<>(goals));
        }
    }
}
```

### CaseDefinition.Builder

```java
// Convenience — standard success/failure, failure-first evaluation order
public Builder completion(GoalExpression success, GoalExpression failure) {
    var builder = GoalBasedCompletion.<StandardGoalKind>builder();
    if (failure != null) builder.goal(StandardGoalKind.FAILURE, failure);
    if (success != null) builder.goal(StandardGoalKind.SUCCESS, success);
    this.completion = builder.build();
    return this;
}

// Full control — any kind
public Builder completion(GoalBasedCompletion<?> completion) {
    this.completion = completion;
    return this;
}

// Predicate-based (unchanged)
public Builder completion(String when) {
    this.completion = new PredicateBasedCompletion(new JQExpressionEvaluator(when));
    return this;
}
```

### Goal.kind

Field type changes from `GoalKind` (enum) to `String`. The kind is a classification
label — audit metadata only, not consulted by completion evaluation. `GoalKind`
(with `terminalStatus()`) is a completion-map concern, not a goal classification concern.
This cleanly separates the two roles identified in the Problem section and eliminates
a parsing circular dependency (Goal construction no longer needs terminal status that
lives in the completion block).

```java
public class Goal {
    private final String kind;

    public String getKind() { return kind; }

    public static class Builder {
        public Builder kind(String kind) {
            this.kind = kind;
            return this;
        }

        public Builder kind(GoalKind kind) {
            return kind(kind.value());
        }
    }
}
```

The `kind(GoalKind)` overload preserves compile-time safety for the programmatic API.
Existing call sites `Goal.builder().kind(GoalKind.SUCCESS)` compile unchanged.

`Goal.equals()` changes `kind == goal.kind` (reference equality) to
`Objects.equals(kind, goal.kind)` (value equality).

### GoalReachedEventHandler

The hardcoded two-branch if/else becomes a map iteration:

```java
private Uni<Void> evaluateCompletion(CaseInstance caseInstance, CaseCompletion completion) {
    if (!(completion instanceof GoalBasedCompletion<?> gbc)) {
        return Uni.createFrom().voidItem();
    }

    return reactiveEventLogRepository
        .findByCaseAndTypes(caseInstance.getUuid(), Set.of(GOAL_REACHED), caseInstance.tenancyId)
        .chain(eventLogs -> {
            Set<String> reachedGoals = eventLogs.stream()
                .map(el -> el.getMetadata().get("name").asText())
                .collect(Collectors.toSet());

            for (var entry : gbc.getGoals().entrySet()) {
                GoalKind kind = entry.getKey();
                GoalExpression expr = entry.getValue();
                if (isGoalExpressionSatisfied(expr, reachedGoals)) {
                    String satisfiedGoalName = findSatisfiedGoalName(expr, reachedGoals);
                    eventBus.publish(
                        EventBusAddresses.CASE_STATUS_CHANGED,
                        new CaseStatusChanged(
                            caseInstance,
                            caseInstance.getState().name(),
                            kind.terminalStatus().name(),
                            satisfiedGoalName,
                            kind.value()));
                    return Uni.createFrom().voidItem();
                }
            }
            return Uni.createFrom().voidItem();
        });
}
```

### CaseStatusChanged — denormalized for transport

```java
public record CaseStatusChanged(
    CaseInstance instance,
    String oldStatus,
    String newStatus,
    String satisfiedGoalName,
    String satisfiedGoalKind) {

    public CaseStatusChanged(CaseInstance instance, String oldStatus, String newStatus) {
        this(instance, oldStatus, newStatus, null, null);
    }
}
```

`GoalKind` as a typed interface is a definition/evaluation concern. The event
transport layer carries the string value. Every downstream consumer already calls
`.value()` — EventLog metadata, CaseOutcomeEvent metadata.

### CaseStatusChangedHandler

`fireOutcomeObservers` parameter changes from `GoalKind goalKind` to `String goalKind`.
The metadata map construction simplifies — already receives a string, no `.value()` call.

### YAML schema

`CaseCompletion` definition changes from fixed `success`/`failure` properties to an
open structure:

```yaml
CaseCompletion:
  type: object
  description: >
    Maps goal kinds to goal expressions. Document order determines evaluation
    priority — first satisfied expression wins. Built-in kinds (success, failure)
    have implicit terminal status mappings. Custom kinds require an explicit
    'status' field.
  properties:
    doneWhen:
      type: string
      description: "Optional JQ predicate over CaseContext as an override/shortcut"
  additionalProperties:
    description: >
      Each additional property is a goal kind name mapped to a GoalExpression.
      Built-in kinds (success, failure) have implicit terminal status.
      Custom kinds must include a 'status' field.
    oneOf:
      - $ref: "#/$defs/GoalExpression"
      - type: object
        properties:
          status:
            type: string
            enum: [COMPLETED, FAULTED]
          allOf:
            type: array
            items: { type: string }
            minItems: 1
          anyOf:
            type: array
            items: { type: string }
            minItems: 1
        required: [status]
```

Example YAML:

```yaml
goals:
  - name: fraud-detected
    condition: ".fraudScore > 0.8"
    kind: failure
  - name: review-needed
    condition: ".needsReview == true"
    kind: escalated
  - name: investigation-complete
    condition: ".decision != null"
    kind: success

completion:
  failure:
    anyOf: [fraud-detected]
  escalated:
    status: FAULTED
    anyOf: [review-needed]
  success:
    allOf: [investigation-complete]
```

### CaseDefinitionYamlMapper

The completion parsing changes from reading fixed `success`/`failure` fields to
iterating the completion map entries. `doneWhen` and goal kind entries are mutually
exclusive — if both are present, the mapper throws.

```java
if (completionNode != null && completionNode.isObject()) {
    String doneWhen = completionNode.has("doneWhen")
        ? completionNode.get("doneWhen").asText() : null;

    // Collect goal kind entries (everything except doneWhen)
    var builder = GoalBasedCompletion.builder();
    boolean hasGoalEntries = false;
    var fields = completionNode.fields();
    while (fields.hasNext()) {
        var entry = fields.next();
        String kindValue = entry.getKey();
        if ("doneWhen".equals(kindValue)) continue;
        hasGoalEntries = true;

        GoalKind kind = resolveGoalKind(kindValue, entry.getValue());
        GoalExpression expr = parseGoalExpression(entry.getValue(), goalMap);
        builder.goal(kind, expr);
    }

    if (doneWhen != null && hasGoalEntries) {
        throw new IllegalArgumentException(
            "Completion block cannot mix 'doneWhen' with goal kind entries"
            + " — use one completion mechanism per definition");
    }

    if (doneWhen != null) {
        def.setCompletion(new PredicateBasedCompletion(
            new JQExpressionEvaluator(doneWhen)));
    } else if (hasGoalEntries) {
        def.setCompletion(builder.build());
    }
}
```

`resolveGoalKind`: for `"success"` and `"failure"`, returns `StandardGoalKind` and
rejects an explicit `status` field (standard kinds have implicit terminal status —
explicit override is an error). Rejects `"doneWhen"` as a goal kind name (reserved).
For custom kinds, reads the `status` field and returns
`GoalKind.of(kindValue, CaseStatus.valueOf(status))`. Missing `status` on a custom
kind is a validation error.

`parseGoalExpression(JsonNode, Map<String, Goal>)`: new method — reads `allOf`/`anyOf`
string arrays from the raw JSON node and resolves goal names against `goalMap`. Ignores
the `status` field (consumed by `resolveGoalKind`, not relevant to the expression).
Distinct from the existing `convertGoalExpression(io.casehub.model.GoalExpression, ...)`
which takes the generated model type.

Goal parsing simplifies — with `Goal.kind` now `String`, the mapper uses
`sg.getKind()` directly (returns `String` from the generated model after the schema
enum→string change). No `GoalKind.fromValue()` needed; no circular dependency with
the completion block.

#### Evaluation order — behavioral change

Document order in the completion block determines evaluation priority. The current
engine hardcodes failure-before-success regardless of YAML order. After this change,
a YAML file with `success` before `failure` evaluates success first — if both goals
are satisfied simultaneously, the case enters COMPLETED instead of FAULTED.

This is intentional: making evaluation order explicit and author-controlled is the
design goal. Existing YAML files should list failure before success to preserve the
current "failure wins on tie" behavior. The spec's example YAML and the convenience
builder `completion(GoalExpression success, GoalExpression failure)` both use
failure-first order as the recommended default.

### Goal kind in YAML

The `Goal` schema `kind` field changes from `enum: [success, failure]` to
`type: string`. Built-in kinds are documented as convention. Custom kinds are
validated against the completion block entries.

### Validation (DefaultCaseDefinitionRegistry)

Generalized checks:

1. **Goal not referenced** — warn if a Goal isn't referenced in any completion
   entry's GoalExpression (unchanged behavior, broader scope)
2. **Kind mismatch** — warn if a Goal with `kind: X` is referenced in a completion
   entry keyed by `Y` (the Goal says it's one kind but the completion uses it as another)
3. **Missing status** — error if a completion entry uses a non-standard kind with
   no explicit `status` mapping (caught during YAML parsing, not registry validation)
4. **Status on standard kind** — error if a completion entry for `success` or `failure`
   includes an explicit `status` field (standard kinds have implicit terminal status;
   contradictory overrides are rejected, not silently ignored)
5. **Reserved kind name** — error if a completion entry uses a reserved name
   (`doneWhen`) as a goal kind. Reserved names occupy the same JSON namespace as
   goal kind entries and would be silently swallowed without this check
6. **Mutual exclusion** — error if the completion block contains both `doneWhen` and
   goal kind entries. A case definition uses one completion mechanism — predicate-based
   (`doneWhen`) or goal-based (kind entries) — not both
7. **Empty completion** — error if GoalBasedCompletion has no entries (caught by builder)

### Domain extension example

```java
public enum AmlGoalKind implements GoalKind {
    SUCCESS("success", CaseStatus.COMPLETED),
    FAILURE("failure", CaseStatus.FAULTED),
    ESCALATED("escalated", CaseStatus.FAULTED),
    REFERRED("referred", CaseStatus.COMPLETED);

    private final String value;
    private final CaseStatus terminalStatus;

    AmlGoalKind(String value, CaseStatus terminalStatus) {
        this.value = value;
        this.terminalStatus = terminalStatus;
    }

    @Override public String value() { return value; }
    @Override public CaseStatus terminalStatus() { return terminalStatus; }
}

// Usage in a CaseHub definition
GoalBasedCompletion.<AmlGoalKind>builder()
    .goal(AmlGoalKind.FAILURE, GoalExpression.anyOf(fraudDetected))
    .goal(AmlGoalKind.ESCALATED, GoalExpression.allOf(reviewNeeded))
    .goal(AmlGoalKind.REFERRED, GoalExpression.allOf(externalReferral))
    .goal(AmlGoalKind.SUCCESS, GoalExpression.allOf(investigationComplete))
    .build()
```

## What doesn't change

- `GoalExpression`, `AllOfGoalExpression`, `AnyOfGoalExpression` — untouched
- `CaseCompletion` interface — untouched
- `PredicateBasedCompletion` — untouched (separate completion strategy)
- `CaseContextChangedEventHandler.goals()` — goal condition evaluation unchanged
- `GoalReachedEvent` — carries `Goal`, no change
- `CaseOutcomeObserver` / `CaseOutcomeEvent` — metadata carries strings already

## Affected files

| Layer | File | Change |
|-------|------|--------|
| api | `GoalKind.java` | enum → interface (with equals/hashCode contract Javadoc) |
| api | `StandardGoalKind.java` | new — built-in enum |
| api | `DefaultGoalKind.java` | new — package-private record |
| api | `Goal.java` | `kind` field: `GoalKind` → `String`; builder adds `kind(GoalKind)` overload; `equals()` uses `Objects.equals` |
| api | `GoalBasedCompletion.java` | generic class with ordered map + builder (duplicate-key check) |
| api | `CaseDefinition.java` | add `completion(GoalBasedCompletion<?>)` builder method |
| api | `CaseDefinitionYamlMapper.java` | parse open completion block; new `parseGoalExpression(JsonNode, ...)` method; `doneWhen` mutual exclusion; Goal kind parsing simplified (string, no fromValue) |
| common | `CaseStatusChanged.java` | `GoalKind` field → `String` |
| runtime | `GoalReachedEventHandler.java` | iterate map instead of two if-statements; metadata `.put("kind", goal.getKind())` (now string) |
| runtime | `CaseStatusChangedHandler.java` | use string satisfiedGoalKind |
| runtime | `DefaultCaseDefinitionRegistry.java` | generalized goal validation |
| schema | `CaseDefinition.yaml` | Goal.kind: enum → string; CaseCompletion: fixed properties → additionalProperties (removes `unevaluatedProperties: false`) |
| schema | `io.casehub.model.Goal` (generated) | `getKind()` returns `String` instead of `Goal.Kind` enum |
| schema | `io.casehub.model.CaseCompletion` (generated) | fixed `getSuccess()`/`getFailure()` → dynamic additionalProperties map |
| tests | ~40 files | mechanical — constructor calls → builder; `goal.getKind()` assertions change from `GoalKind` to `String` |

## Test strategy

- **Unit:** `GoalKind.of()` returns correct value/status, `StandardGoalKind.fromValue()`
  works for built-ins and throws for unknowns
- **Unit:** `GoalBasedCompletion.builder()` — insertion order preserved, empty builder
  throws, null kind/expression rejected, duplicate kind throws
- **Unit:** `Goal.equals()` — value equality for kind (String), not reference equality
- **Unit:** `GoalReachedEventHandler` — first-match-wins with 3+ kinds, failure-before-success
  when using standard kinds
- **Integration:** YAML with custom kinds + explicit status parsed correctly
- **Integration:** YAML with standard kind + explicit `status` field → validation error
- **Integration:** YAML with `doneWhen` + goal kind entries → mutual exclusion error
- **Integration:** YAML with `doneWhen` only → PredicateBasedCompletion created
- **Integration:** YAML with reserved kind name `doneWhen` as goal kind → validation error
- **Integration:** end-to-end case with custom goal kind reaches correct terminal state
- **Regression:** existing success/failure-only cases behave identically
