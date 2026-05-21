# Design: JQ Evaluator Consolidation

**Issue:** casehubio/engine#314  
**Date:** 2026-05-22  
**Status:** Approved

---

## Problem

`evalObjectTemplate` — a hand-rolled `{ key: .path }` template parser — exists in three places:
- `CaseContext` interface (declared as a public API method)
- `CaseContextImpl` (implementation)
- `ContextUtils` (duplicate static implementation)

It does not support nested object templates (`{ outer: { inner: .path } }`), which is the
immediate bug in engine#314. The deeper problem is that it shouldn't exist: the engine already
has `JQEvaluator`, a canonical CDI-injectable jq evaluator. All `evalObjectTemplate` expressions
are valid jq — the hand-rolled parser is a partial, buggy reimplementation of a subset of jq.

A third independent jq implementation also exists: `JqTransformer` in `api/model/ai/`,
which reconstructs a `Scope` and reloads all builtins on every `apply()` call.

---

## Root Cause

`evalObjectTemplate` was placed on the `CaseContext` interface — a data holder — giving call
sites a convenient method to reach for rather than injecting the real evaluator. This violated
the principle that expression evaluation is not a data responsibility.

---

## Design

### 1. Remove `evalObjectTemplate` entirely

Remove from:
- `io.casehub.api.context.CaseContext` (interface method)
- `io.casehub.engine.internal.context.CaseContextImpl` (implementation)
- `io.casehub.engine.internal.context.ContextUtils` (static duplicate)

### 2. Replace all call sites with `JQEvaluator`

Nine production call sites, all in CDI beans. Each switches to:

```java
private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

@Inject JQEvaluator jqEvaluator;

// Before:
Map<String, Object> result = context.evalObjectTemplate(expression);

// After:
ValidationResult vr = jqEvaluator.eval(expression, context.asJsonNode());
Map<String, Object> result = vr.ok() && vr.output() != null && !vr.output().isEmpty()
    ? MAPPER.convertValue(vr.output().get(0), MAP_TYPE)
    : Map.of();
```

Call sites:
| Class | Module | Expression source |
|---|---|---|
| `CaseContextChangedEventHandler` (×3) | runtime | `jq.expression()`, `capability.getInputSchema()`, `subCase.inputMapping()` |
| `WorkerScheduleEventHandler` (×2) | runtime | `capability.getInputSchema()` |
| `WorkItemLifecycleAdapter` | work-adapter | `jq.expression()` |
| `SubCaseCompletionService` | blackboard | `outputMapping` |
| `WorkOrchestrator` | runtime | `capability.getInputSchema()` |
| `QuartzWorkerExecutionJob` | scheduler-quartz | `capability.getOutputSchema()` |

### 3. Fix `JqTransformer` scope reconstruction bug

`JqTransformer.apply()` currently calls `Scope.newEmptyScope()` and
`BuiltinFunctionLoader.getInstance().loadFunctions()` on every invocation — rebuilding the
entire jq function registry per call. Build the scope once in the constructor alongside
the `JsonQuery` compilation.

```java
// Before: scope built per apply() call
public JqTransformer(String jqExpression) {
    Scope initScope = Scope.newEmptyScope();
    BuiltinFunctionLoader.getInstance().loadFunctions(Versions.JQ_1_6, initScope);
    this.query = JsonQuery.compile(jqExpression, Versions.JQ_1_6);
}
public JsonNode apply(JsonNode input) {
    Scope callScope = Scope.newEmptyScope();
    BuiltinFunctionLoader.getInstance().loadFunctions(Versions.JQ_1_6, callScope); // ← bug
    query.apply(callScope, input, results::add);
}

// After: scope built once at construction
public JqTransformer(String jqExpression) {
    this.scope = Scope.newEmptyScope();
    BuiltinFunctionLoader.getInstance().loadFunctions(Versions.JQ_1_6, this.scope);
    this.query = JsonQuery.compile(jqExpression, Versions.JQ_1_6);
}
public JsonNode apply(JsonNode input) {
    query.apply(this.scope, input, results::add); // reuses pre-built scope
}
```

`JqTransformer` remains in the API layer for non-CDI use (Agent/AgentBuilder). CDI beans
must not use it — they inject `JQEvaluator` directly.

### 4. Write evaluator protocol

New protocol `jq-evaluation-canonical.md` in `casehub/` protocols:
- `JQEvaluator` is the canonical jq evaluation point for all CDI beans
- Never instantiate `JsonQuery` directly in CDI beans
- Never hand-roll `{...}` template parsers — use jq
- `JqTransformer` is the non-CDI fallback only (api-layer objects without injection)
- Expression evaluation is not a data responsibility — context objects carry state, not evaluators

---

## What Does Not Change

- `JQEvaluator` itself — no changes
- `JQExpressionEngine` — no changes
- The jq expression language and syntax used in YAML case definitions
- `ValidationResult` — no changes; callers extract `.results().get(0)` and convert

---

## Testing

### Unit tests (pure Java, no Quarkus)
- `CaseContextImplTest` — remove all `evalObjectTemplate` tests (method deleted)
- `ContextUtilsTest` — remove if it exists, or the relevant methods
- `JqTransformerTest` — existing tests pass unchanged; add test verifying scope is not rebuilt per call (check with a counter or timing)

### Integration tests
- `WorkItemLifecycleAdapterTest` — update to reflect `JQEvaluator` injection; nested object outputMapping test added here (the original engine#314 bug)
- One nested object test added to cover the motivating case:
  ```yaml
  outputMapping: "{ humanApproval: { status: .decision } }"
  ```
  Expected: `humanApproval = Map.of("status", "approved")` not `"{ status: .decision }"`

---

## Out of Scope (filed separately)

- Replacing `JqTransformer` entirely with `JQEvaluator` injection into `Agent`/`AgentBuilder` — requires structural change to the api model layer; tracked as a follow-on issue
- `ExpressionEvaluatorFactory` in `CaseDefinitionYamlMapper` — tracked in engine#280
