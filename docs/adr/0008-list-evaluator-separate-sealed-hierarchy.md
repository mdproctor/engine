# 0008 — ListEvaluator as a separate sealed hierarchy from ExpressionEvaluator

Date: 2026-06-07
Status: Accepted

## Context and Problem Statement

`HumanTaskTarget.candidateGroups` and `candidateUsers` needed to support both static lists and JQ expressions evaluated at runtime. `ExpressionEvaluator` already exists in the codebase as a marker interface for boolean predicates dispatched by `ExpressionEngine.evaluate()`. The question was whether to reuse that hierarchy for list-producing expressions or introduce a separate type.

## Decision Drivers

* `ExpressionEvaluator` is the input type for `ExpressionEngine.evaluate(ExpressionEvaluator, CaseContext): boolean` — every existing implementation returns a boolean predicate
* `ExpressionEngineRegistry` dispatches on `ExpressionEvaluator.type()` to route to the correct engine — a non-predicate type in the hierarchy could cause a misfire or uncaught dispatch error
* Compiler-enforced exhaustiveness on sealed types is valuable for the handler switch

## Considered Options

* **Option A** — Introduce a new `LiteralListEvaluator` implementing `ExpressionEvaluator`
* **Option B** — Introduce a separate `ListEvaluator` sealed interface with `StaticList` and `JQList` inner records
* **Option C** — Use a plain `String` field for the JQ expression alongside the existing `Set<String>` (two fields, XOR)

## Decision Outcome

Chosen option: **Option B**, because `ListEvaluator` produces `Set<String>`, not `boolean`. Placing it in the `ExpressionEvaluator` hierarchy would be type pollution: `ExpressionEngineRegistry` would receive a type it cannot dispatch correctly, and readers of the `ExpressionEvaluator` interface javadoc would be misled about the return semantics.

### Positive Consequences

* Compiler enforces exhaustiveness on the `ListEvaluator` switch — no runtime uncaught case
* `ExpressionEvaluator` hierarchy stays semantically pure (boolean predicates only)
* When engine#439 adds dynamic `title`/`scope`, a `StringEvaluator` sealed type follows the same pattern without touching either existing hierarchy

### Negative Consequences / Tradeoffs

* One additional interface to understand; the name `ListEvaluator` vs `ExpressionEvaluator` requires a short explanation for new contributors

## Pros and Cons of the Options

### Option A — `LiteralListEvaluator` extends `ExpressionEvaluator`

* ✅ Fewer types to introduce
* ❌ `ExpressionEngineRegistry` receives a type it cannot dispatch — potential misfire
* ❌ `ExpressionEvaluator.type()` constant (`"literal-list"`) has no registered engine — semantically undefined
* ❌ Interface javadoc promises boolean predicate semantics; `LiteralListEvaluator` violates that contract

### Option B — Separate `ListEvaluator` sealed interface (chosen)

* ✅ `ExpressionEvaluator` hierarchy stays boolean-only
* ✅ Sealed type gives compiler-enforced exhaustive switch on `StaticList` / `JQList`
* ✅ No `type()` constant needed — `ListExpressionResolver` switches directly on concrete type
* ❌ One extra interface

### Option C — Two separate fields (expression String + static Set)

* ✅ No new types
* ❌ XOR constraint must be enforced at runtime, not compile time
* ❌ Builder has two entry points for what is logically one field — confusing API
* ❌ Does not compose with the existing `ExpressionEvaluator` pattern

## Links

* engine#387 — implementation issue
* engine#439 — follow-on: dynamic `title`/`scope`/`expiresIn` via `StringEvaluator`
* `api/src/main/java/io/casehub/api/model/evaluator/ListEvaluator.java`
