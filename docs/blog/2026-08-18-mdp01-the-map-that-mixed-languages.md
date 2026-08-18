---
entry_type: note
subtype: diary
title: The map that mixed languages
date: 2026-08-18
tags: [expression-engine, mvel, jq, yaml, caching]
issues: [925, 926]
---

# The map that mixed languages

ADR-0009 chose per-definition `expressionLang` back in June. One language per case
definition. Clean, CNCF-aligned, simple. Then #238 added `contextType` — typed POJOs
with MVEL inference — and the gap became obvious: a definition that says "I'm a typed
Java object" shouldn't need to lose JQ for the one expression that wants JSON projection.

The syntax we landed on is minimal: `when: { jq: ".amount > 1000" }`. Language as the
map key, expression as the value. Plain strings keep the definition-level default.
One extra nesting level only where you need it.

The interesting part was where the design pressure came from. A review caught that
the JSON Schema change (`oneOf: [string, object]`) would cause jsonschema2pojo to
generate `Object` return types on every expression accessor. `getWhen()` returning
`Object` instead of `String` breaks every call site simultaneously — and the breaks
appear far from the schema change, at each place the mapper calls `registry.create()`.

The fix was to stop reading expression values from the schema model entirely. Thread
the raw `JsonNode` to every expression site and let a shared `resolveExpression()` helper
handle both forms. The schema model's `Object` type becomes a Jackson compatibility shim
— it accepts both strings and maps during deserialization, but the mapper never touches
the return value. This pattern — schema model for Jackson, raw node for logic — was already
in use for bindings. We extended it to milestones, goals, and `doneWhen`.

`doneWhen` was the backward-compatibility trap. It had been hardcoded to JQ since day one
(`new JQExpressionEvaluator(doneWhen)` — no registry, no language parameter). Routing it
through the helper meant deciding its default language. The obvious answer — inherit the
definition-level `expressionLang` — would silently break any MVEL-default definition that
uses `doneWhen`, because the JQ expression would suddenly be interpreted as MVEL. We kept
JQ as `doneWhen`'s default. Override via map syntax if you want something else.

Label rules were the scope boundary. They use `CompiledExpression<Map, Boolean>` — a
different type system from `ExpressionEvaluator`. Making them override-aware would mean
redesigning `LabelRule`'s constructor and evaluation path. That's a separate issue now (#941).

For #926 — the POJO cache — the interesting choice was *not* using ConcurrentHashMap.
When a context change triggers ten MVEL expressions, the same POJO gets deserialized ten
times from the same JsonNode. The natural instinct is a concurrent map keyed by context.
But the primary case is sequential: ten evaluations in a row, same context, same version.
A single `volatile` field holding one `(context, version, class, pojo)` record captures
this exactly. Worst case under concurrency — two threads overwrite each other — is no
worse than having no cache. No eviction, no lifecycle, no GC pressure beyond one record
per miss.

ADR-0009 is now superseded. The definition-level `expressionLang` stays as the default —
per-expression override is additive. CNCF SW 1.0 doesn't have typed-POJO context, so
their per-workflow model doesn't address the use case that drove this. Four follow-on
issues filed: label rule overrides, MVEL sandboxing, data transform overrides, and
consumer guide documentation. The sandboxing one (#942) will be the most interesting —
MVEL3's transpiler generates Java source, and intercepting method access at that level
is genuinely unsolved territory.
