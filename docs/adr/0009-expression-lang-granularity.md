# 0009 — expressionLang granularity: per-definition vs per-expression

Date: 2026-06-09
Status: Superseded (per-expression override added by #925)

## Context and Problem Statement

`CaseDefinitionYamlMapper` was extended to support pluggable expression languages
via `ExpressionEvaluatorFactory` (engine#289). A design decision was needed on the
granularity at which `expressionLang` is declared: at the case definition level
(all expressions share one language) or at the individual expression level
(each binding/milestone/goal condition can use a different language).

## Decision Drivers

* CNCF Serverless Workflow 1.0 declares `expressionLang` at the workflow level — one language per workflow definition
* The runtime model already supports per-evaluator language via `ExpressionEvaluator.type()` — per-expression is structurally available at the model layer
* YAML authoring complexity: per-expression syntax requires schema changes to every condition site (Trigger, Goal, Milestone, Binding)
* Validation simplicity: per-definition allows fail-fast at registration time with a single `assertLanguageSupported()` call

## Considered Options

* **Option A** — Per-definition (one `expressionLang` at the top-level YAML field)
* **Option B** — Per-expression (each condition site carries an optional `{lang, expr}` object)
* **Option C** — No field (JQ hardcoded; pluggability deferred entirely)

## Decision Outcome

Chosen option: **Option A — per-definition**, because it aligns with CNCF SW 1.0
precedent, simplifies YAML authoring, enables fail-fast validation at definition
registration, and defers schema complexity until a concrete use case for mixing
languages within one definition exists. Per-expression mixing is already supported
in the Java DSL; the YAML syntax concern is a separate future schema design.

### Positive Consequences

* CNCF SW 1.0 interoperability — same field name and semantics
* Simple YAML authoring — one declaration at the top
* Fail-fast validation: `assertLanguageSupported(expressionLang)` runs once at parse time before creating any evaluators
* The model layer (`ExpressionEvaluator.type()`) already carries per-evaluator language — per-expression YAML is addable later without breaking per-definition

### Negative Consequences / Tradeoffs

* Cannot mix languages within a single YAML definition (e.g. JQ for simple guards, Drools for scoring) — requires Java DSL or separate definitions
* A case definition must choose one language for all its conditions

## Pros and Cons of the Options

### Option A — Per-definition

* ✅ CNCF SW 1.0 alignment
* ✅ Simple — one field, one validation call
* ✅ Consistent expression runtime per definition (easier to reason about)
* ❌ Cannot mix languages in YAML (workaround: Java DSL)

### Option B — Per-expression

* ✅ Maximum flexibility — each condition can use the best language
* ❌ Schema changes required at every condition site (when, filter, condition, entryCriteria)
* ❌ More complex authoring and validation
* ❌ Diverges from CNCF SW 1.0

### Option C — No field

* ✅ Simplest implementation — no schema change
* ❌ Blocks future expression language extensibility at YAML level
* ❌ Misses CNCF alignment opportunity

## Links

* engine#289 — ExpressionEvaluatorFactory SPI implementation
* Protocol PP-20260609-3c86d1 — expressionLang three-way invariant
* CNCF Serverless Workflow 1.0 spec — `expressionLang` field

## Superseded by #925

The original decision chose per-definition granularity. Engine#925 adds per-expression
override via YAML map syntax (`when: { jq: ".expr" }`), implementing Option B from this
ADR. The definition-level `expressionLang` remains the default — per-expression is additive.

The CNCF SW 1.0 divergence concern is accepted: CNCF SW 1.0 defines `expressionLang` at
the workflow level, but casehub's typed-POJO context model (`contextType` + MVEL inference)
creates a concrete need for mixed-language definitions that CNCF SW 1.0 does not address.
