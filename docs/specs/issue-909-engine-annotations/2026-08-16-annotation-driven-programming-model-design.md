# Annotation-Driven Programming Model for CaseHub

**Date:** 2026-08-16
**Status:** Draft
**Scope:** Holistic design (all layers). Implementation: engine-annotations first, then eidos, work, blocks, desiredstate, ledger.
**Epic:** casehubio/blocks#115
**Engine issue:** casehubio/engine#909

## Motivation

CaseHub is architecturally deeper than competing frameworks across governance, deliberation, planning, and case management. But the entry cost is high — SPIs, builder chains, sealed hierarchies. embabel demonstrates that an annotation-driven model can make agent development accessible without sacrificing composability.

The goal: a developer builds a working CaseHub case in 20 lines. A platform developer adds oversight, trust, and CBR routing with 3 more annotations. Both produce the same underlying types as the existing fluent builders.

## Design Principles

1. **Own the annotations** — CaseHub defines all annotations. No LangChain4j annotation dependency. LC4j remains the runtime LLM client library (ChatModel, ChatRequest, etc.) used by engine-ai internally. (D1)
2. **Progressive disclosure** — simple annotations for the 80% case; `@Customize` escape hatch to the full builder API for the long tail. (D2)
3. **Definition-only** — `@Case` generates a `CaseDefinition` CDI bean. No `CaseHub` subclass, no runtime proxy. (D7)
4. **Three programming models** — annotations, fluent builders, YAML. All produce the same types. Mix freely.
5. **Build-time + runtime inference** — GOAP type inference at build time via Jandex (with validation), and at runtime for dynamically registered workers. Same inference logic, different timing. (D4)
6. **Typed parameters** — developers work with Java types. `ContextBridge` is internal plumbing. (D5)
7. **Holistic design, layered implementation** — design all layers together, implement per-repo. (D3)

---

## Layer 1: Engine Annotations

**Module:** `casehub-engine-annotations` (new, separate from engine-api) (D9)
**Depends on:** `casehub-engine-api`, `casehub-worker-api`
**No dependency on:** `langchain4j`, `langchain4j-agentic`
**Processor:** Quarkus build extension (deployment module)
**Produces:** `CaseDefinition`, `Worker`, `Capability`, `Binding` instances (same types as builders)

**New types introduced by this module:**
- All annotation types (`@Case`, `@Worker`, `@Bind`, etc.) — new
- `PlanningMode` enum — new
- `GoapActionInferrer`, `GoapKeyConvention` utility classes — new (in `casehub-engine-common`)
- `WorkerScopeProducer` CDI producer — new
- `GoapPlanningStrategy` — new `PlanningStrategy` implementation (in `casehub-engine-planning`)
- `AdaptivePlanningStrategy` — new `PlanningStrategy` implementation (in `casehub-engine-planning`)

**Existing types that require modification:**
- `CaseDefinition` — add `List<GoapAction> goapActions` field and `Map<String, Set<String>> goalToEffectKeys` mapping; update Builder, YAML mapper, and REST representation
- `GoapAction` — widen `cost` from `int` to `double`, add `benefit` field, add `Map<String, Boolean> softPreconditions` field
- `GoapPlanner` — accept `benefit` in cost calculation, accept `Set<String>` compound goals, multi-condition heuristic
- `GoapWorldState` — add `satisfiesAll(Set<String>)` method
- `AgentWorkerFunction` — add `List<Class<?>> declaredInputTypes` and `Class<?> declaredOutputType` fields for GOAP type inference

### Annotations

#### `PlanningMode` (enum)

```java
public enum PlanningMode {
    EXPLICIT,  // default — requires @Bind for execution ordering
    GOAP,      // plan upfront from type-inferred dependencies, replan on failure
    ADAPTIVE   // replan after every step (OODA loop) — continuous reassessment
}
```

`GOAP` plans the full action sequence upfront and replans only when a step fails. `ADAPTIVE` replans after every step completion — the planner reassesses the world state and may choose a different path based on intermediate results. Both use the same type inference for dependency discovery; they differ in when the planner runs.

**Runtime integration:** `PlanningMode` maps to `CaseDefinition.planningStrategy`, integrating through the existing `CompoundStrategyDispatcher` pipeline via new `PlanningStrategy` implementations. No separate `planningMode` field or parallel dispatch path — GOAP/ADAPTIVE are regular planning strategies:

| PlanningMode | planningStrategy | PlanningStrategy implementation |
|---|---|---|
| `EXPLICIT` | unchanged — uses case-level setting or `"default"` | Existing `ChoreographyStrategy` / `SequentialPlanningStrategy` |
| `GOAP` | `"goap"` | New `GoapPlanningStrategy` |
| `ADAPTIVE` | `"adaptive"` | New `AdaptivePlanningStrategy` |

**`GoapPlanningStrategy`** implements `PlanningStrategy` and bridges `GoapPlanner` into the dispatch pipeline:

1. Collects `GoapAction` metadata — build-time generated actions (stored on the `CaseDefinition` via a new `goapActions` field) plus runtime-inferred actions for dynamically added workers
2. **Filters actions to eligible bindings:** the `eligible` parameter (bindings that passed trigger and `when` guard evaluation upstream) determines which actions the planner may use. Actions whose corresponding binding is not in `eligible` are excluded from the action set. This ensures the planner never plans an action that can't be dispatched — `when` guard failures gracefully reduce the planner's action space rather than causing stalls.
3. Constructs the initial `GoapWorldState` from the current case context — each key present in context maps to `true`
4. Resolves GOAP goal conditions from `GoalExpression` via the goal-to-effect-key mapping (see §Compound goals)
5. Calls `GoapPlanner.plan()` with the initial state, goal conditions, and the filtered action set
6. Returns the first eligible `Binding` from the planned sequence (one step at a time — `CompoundStrategyDispatcher` calls `select()` again after each completion)
7. On step failure: replans from current world state with the failed action excluded from the action set
8. On empty plan (no valid path to goal with available actions): returns empty list — the case waits for context changes that may unblock `when` guards or provide new workers
9. Effects are applied to the GOAP world state only after successful worker completion. Failed workers leave the world state unchanged.

**GoapAction-to-Binding name identity:** The build extension generates `GoapAction.name` and `Binding.name` from the same source — the `@Worker` method name (or capability name when specified). This guarantees 1:1 correspondence. `GoapPlanningStrategy` uses this name identity to map planned actions to eligible bindings in step 2 and step 6. For dynamically added workers, `GoapActionInferrer` and the `Binding` constructor must independently derive the same name from the `Worker.name()` — this is enforced by sharing the `Worker.name()` value directly rather than re-deriving it.

**GOAP-mode binding triggers:** The build extension generates GOAP worker bindings with `ContextChangeTrigger` using a catch-all expression (`"true"` — always evaluates to truthy). This integrates GOAP into the engine's existing event-driven dispatch loop:

1. Case starts → initial context is set → `ContextChangeTrigger("true")` fires for all GOAP bindings → all become eligible
2. `PlanningStrategyLoopControl.select()` passes eligible bindings to `CompoundStrategyDispatcher` → `GoapPlanningStrategy.select()` → returns one binding (next planned step)
3. Worker executes, completes, writes result to context
4. Context change event fires → `ContextChangeTrigger("true")` fires again for all remaining (non-complete) bindings
5. `GoapPlanningStrategy.select()` receives remaining bindings, returns the next step based on updated world state
6. Repeat until goal is satisfied

The catch-all trigger inverts responsibility: triggers control WHEN the strategy is consulted (on any context change); the strategy controls WHAT fires (the next step in the GOAP plan). This is the same pattern as `ContextChangeTrigger`-based choreography — the only difference is that the strategy (not the trigger expression) determines ordering. Completed bindings are filtered by `PlanningStrategyLoopControl.filterAndIndexForDispatch()` before dispatch.

**`AdaptivePlanningStrategy`** extends `GoapPlanningStrategy` with per-step replanning:

1. Same setup as `GoapPlanningStrategy` (steps 1–3)
2. After each step completes: reconstructs world state from updated case context, filters out already-executed actions, replans from scratch
3. Returns only the next single step (never a full sequence)

**ADAPTIVE convergence safeguards:**
- **Execution tracking:** Maintains a `Set<String>` of already-executed action names. Executed actions are filtered from the action set before each replan, preventing re-execution.
- **Max replan limit:** Configurable via `@Customize`, default `2 × N` where N is the number of workers. Exceeding the limit faults the case with `PlanningExhaustionException`.
- **Stagnation detection:** If a step completes but produces no new `true` conditions in the world state and no previously unsatisfied preconditions become satisfiable, the strategy detects stagnation and faults the case.

#### `@Case`

```java
@Retention(RUNTIME)
@Target(TYPE)
public @interface Case {
    String namespace();
    String name();
    String version() default "1.0.0";
    String title() default "";
    String summary() default "";               // maps to CaseDefinition.summary (not description — CaseDefinition has no description field)
    PlanningMode planning() default PlanningMode.EXPLICIT;
}
```

Marks an interface as a case definition. The build extension generates a `CaseDefinition` CDI bean from the annotated interface's methods. `summary` maps to `CaseDefinition.setSummary()`.

#### `@Worker`

```java
@Retention(RUNTIME)
@Target(METHOD)
public @interface Worker {
    String value() default "";               // capability name (convenience — same as capability)
    String capability() default "";          // single capability name
    String[] capabilities() default {};      // multiple capability names
    String description() default "";
    double cost() default 0.0;               // GOAP planner cost (0.0–1.0); 0.0 = unweighted
    double benefit() default 0.0;            // GOAP planner benefit (0.0–1.0); 0.0 = unweighted
    int timeoutMs() default 0;               // 0 = use system default
    int maxRetries() default -1;             // -1 = use platform default (3 attempts); 0 = no retries; N = N retries
    LifecycleScope scope() default LifecycleScope.BINDING;
    Participation participation() default Participation.PARTICIPANT;
    ExecutionMode executionMode() default ExecutionMode.TRANSIENT;
}
```

Declares a method as a worker implementation. The method signature defines the worker function — parameter types are inputs, return type is output. Lifecycle attributes (`scope`, `participation`, `executionMode`) configure how the worker executes — always respected regardless of planning mode, even in GOAP/ADAPTIVE mode where `@Bind` dispatch triggers are ignored.

**Capability name resolution:** `value()` and `capability()` are aliases (mutually exclusive with `capabilities()`). If none set, defaults to the method name.

**Parameter naming:** Uses Java parameter names via `-parameters` compiler flag (D8). The build extension validates that parameter names are not synthetic (`arg0`, `arg1`) and emits a clear error if the flag is missing. `@Param("name")` overrides when the parameter name doesn't match the desired context key.

#### `@Worker` type mapping

| Annotation concept | Builder equivalent | How |
|---|---|---|
| Method name | `Worker.name` | Derived from method name |
| `capability`/`capabilities` | `Worker.capabilityNames` | Direct mapping |
| Method return type | `WorkerFunction.outputType` | Via Jandex |
| Method parameter types | Input schema / GOAP preconditions | Injected from case context by type/name |
| `cost`, `benefit` | GOAP planner weights | Feed into `GoapPlanner`. **Implementation note:** `GoapAction.cost` is currently `int` — must be widened to `double`. `benefit` field must be added to `GoapAction`. These are engine-api changes required before the annotation module. |
| `timeoutMs`, `maxRetries` | `ExecutionPolicy` | Build extension generates the record. `maxRetries = -1` → platform default `RetryPolicy()` (3 attempts). `maxRetries = 0` → `RetryPolicy(1, ...)`. `maxRetries > 0` → `RetryPolicy(maxRetries + 1, ...)`. |
| `scope` | `Binding.lifecycleScope` | Copied to generated `Binding.Builder.lifecycleScope()` |
| `participation` | `Binding.participation` | Copied to generated `Binding.Builder.participation()` |
| `executionMode` | `Binding.executionMode` | Copied to generated `Binding.Builder.executionMode()` |
| `@SystemPrompt` on same method | `AgentWorkerFunction` | Build extension creates `Agent.builder().systemPrompt(...)` |

#### `@Capability`

```java
@Retention(RUNTIME)
@Target(METHOD)
public @interface Capability {
    String name() default "";
    String description() default "";
}
```

When applied alongside `@Worker`, the capability is auto-registered with the worker's capability name. When applied alone, declares a capability that must be satisfied by an external worker (YAML, builder, A2A, MCP discovery).

**Schema generation:** The `Capability` record requires non-null `inputProjection` and `outputProjection`. The build extension generates these from the method signature — parameter types produce a JSON Schema-like input specification (using `ContextBridge` internally), return type produces the output specification. This applies to both `@Worker` methods and standalone `@Capability` methods — standalone capabilities derive schemas from the abstract method's parameter and return types. For marker capabilities with no typed signature (e.g., `void ping()`), schemas default to `"."` — the JQ identity expression, meaning "any input, any output." Explicit schemas can be set via `@Customize`. Note: within the engine, capabilities are matched by name, not by schema. Schemas are metadata used for external tool description generation (MCP tool schemas, A2A agent cards).

#### `@Bind`

```java
@Retention(RUNTIME)
@Target(METHOD)
@Repeatable(Bindings.class)
public @interface Bind {
    String capability() default "";
    String contextChange() default "";
    String event() default "";
    String cron() default "";
    boolean scopeActivated() default false;
    String listenLayer() default "";
    String when() default "";
    String conflictStrategy() default "";
    String[] producedKeys() default {};
}
```

Binds a capability to a trigger. Exactly one of `contextChange`, `event`, `cron`, or `scopeActivated` must be non-default. `@Repeatable` allows multiple bindings per method.

**Expression resolution:** The build extension resolves `contextChange` and `when` expression strings at runtime init via `ExpressionEngineRegistry.create(expression, "jq")`, then uses `ContextChangeTrigger(ExpressionEvaluator, String)` and `Binding.Builder.when(ExpressionEvaluator)`. It never uses the `ContextChangeTrigger(String)` convenience constructor (which hardcodes `JQExpressionEvaluator`), ensuring the expression flows through the registry's engine lookup.

JQ is the default expression language for annotations — `@Bind` does not expose a language selector attribute. For per-binding alternative evaluators, use `@Customize("methodName", Binding.Builder)` to set a custom `ExpressionEvaluator` on the trigger.

**Collapsible onto `@Worker` (D6):** When `@Bind` is on the same method as `@Worker`, the build extension generates both the `Worker` and the `Binding`. The `capability` attribute defaults to the `@Worker`'s capability name. Separate `@Bind` default methods remain available for complex dispatch logic.

**Trigger mapping:**
- `contextChange` -> `ContextChangeTrigger(filter, listenLayer)`
- `event` -> reserved for future `CloudEventTrigger` (not yet implemented — build extension emits error if used)
- `cron` -> `ScheduleTrigger(expression)`
- `scopeActivated` -> `ScopeActivatedTrigger()`

**Naming mapping:** `@Bind.conflictStrategy` maps to `Binding.Builder.conflictResolverStrategy()`. The annotation uses the shorter name for developer ergonomics. Engine-api issue to be filed to rename `Binding.conflictResolverStrategy` → `conflictStrategy` for alignment.

#### `@Goal`

```java
@Retention(RUNTIME)
@Target(METHOD)
public @interface Goal {
    String value();                                    // goal description
    String condition() default "";                     // JQ/expression condition (via ExpressionEvaluator)
    String kind() default "SUCCESS";                   // GoalKind: SUCCESS, FAILURE, or custom
}
```

The annotated method's name becomes the goal name. `condition` is the expression that determines when this goal is reached — maps to `Goal.Builder.condition()`. `kind` maps to `GoalKind`:

- `"SUCCESS"` → `Goal.Builder.kind(GoalKind.SUCCESS)` → terminal status COMPLETED
- `"FAILURE"` → `Goal.Builder.kind(GoalKind.FAILURE)` → terminal status FAULTED
- Custom strings (e.g., `"TIMEOUT"`) → `Goal.Builder.kind("timeout")` — the build extension stores the kind as a lowercase string on the `Goal`. The `Goal` model stores kind as a plain `String`, so any value is valid. However, for the custom kind to drive case completion, a matching `GoalKind` entry must exist in the `GoalBasedCompletion`. This requires a `@Customize(CaseDefinition.Builder)` method that builds the `GoalBasedCompletion` directly with `GoalKind.of("timeout", CaseStatus.FAULTED)`. Without `@Customize`, the custom-kind goal is tracked but has no effect on case completion.

**Case normalization:** The build extension normalizes `kind` values to lowercase before calling `StandardGoalKind.fromValue()` — annotation values `"SUCCESS"`, `"Success"`, and `"success"` all resolve to `StandardGoalKind.SUCCESS`. This aligns with `StandardGoalKind`'s internal convention of lowercase `value` fields (`"success"`, `"failure"`).

#### `@Milestone`

```java
@Retention(RUNTIME)
@Target(METHOD)
public @interface Milestone {
    String name();
    String entryCriteria() default "";
    String completionCriteria() default "";
    String slaDuration() default "";         // ISO-8601 duration (e.g., "PT4H")
    SlaStartFrom slaStartFrom() default SlaStartFrom.MILESTONE_ACTIVATED;
}
```

#### `@Completion`

```java
@Retention(RUNTIME)
@Target(METHOD)
@Repeatable(Completions.class)
public @interface Completion {
    String kind() default "SUCCESS";  // GoalKind: SUCCESS, FAILURE, or custom
}
```

Marks a `default` method that returns a completion expression for a specific `GoalKind`. The method must be `default` (it has a body that computes the expression) and must return `GoalExpression`. Multiple `@Completion` methods are allowed — one per kind. The build extension collects all `@Completion` methods and builds a `GoalBasedCompletion`:

```java
GoalBasedCompletion.builder()
    .goal(StandardGoalKind.SUCCESS, successExpression)   // from @Completion or @Completion(kind = "SUCCESS")
    .goal(StandardGoalKind.FAILURE, failureExpression)   // from @Completion(kind = "FAILURE")
    .build()
```

**Kind resolution:** `"SUCCESS"` → `StandardGoalKind.SUCCESS`, `"FAILURE"` → `StandardGoalKind.FAILURE`. Custom kind strings require a `@Customize(CaseDefinition.Builder)` method to build the `GoalBasedCompletion` directly with `GoalKind.of(name, CaseStatus)` — the build extension cannot resolve custom `CaseStatus` mappings from annotation strings alone.

**Single vs multiple `@Completion`:** If only one `@Completion` method exists (the common case), its expression is used as the SUCCESS completion. If multiple exist, each must have a distinct `kind`. Duplicate kinds are a build-time error.

#### `@Customize`

```java
@Retention(RUNTIME)
@Target(METHOD)
@Repeatable(Customizers.class)
public @interface Customize {
    String value() default "";  // for Binding.Builder: the @Bind method name to target
}
```

Escape hatch to the builder API. The method must be `static` and accept exactly one parameter:
- `CaseDefinition.Builder` — customizes the case definition. `value()` is ignored.
- `Binding.Builder` — customizes a specific binding. `value()` is required and must name the `@Bind` method.
- Pattern builders (blocks Layer 2) — customizes the execution model.

Called after all annotations are processed — annotation-set values are already on the builder.

**Convention boundary:** Common fields (namespace, name, capability, trigger, cost, timeout) live as annotation attributes. Rare fields (authorization, routing signal weights, cognitive demands, channels, adaptation config) are accessed via `@Customize`. If an annotation attribute would be used by fewer than 20% of cases, it belongs in `@Customize`.

#### `@SystemPrompt`

```java
@Retention(RUNTIME)
@Target(METHOD)
public @interface SystemPrompt {
    String value();
}
```

Marks a `@Worker` method as LLM-backed. The build extension generates an `Agent.builder().systemPrompt(value)` backed `AgentWorkerFunction`. The `Agent` class (in engine-api) wraps LC4j's `ChatModel` internally — the annotation has no LC4j dependency.

**Model resolution:** `AgentBuilder.build()` requires a `ChatModel`. The build extension resolves this via CDI:

1. **CDI lookup** (default): the build extension generates code that injects a `ChatModelProvider` CDI bean at runtime. Quarkus applications register one via the `casehub-platform-agent-*` modules (e.g., `agent-langchain4j`, `agent-claude`). If no `ChatModelProvider` bean is available at runtime, startup fails with a clear error.
2. **`@Customize`**: for model selection (`ModelType`), temperature, response schema, or any other `AgentBuilder` configuration, use `@Customize` with the `AgentBuilder` parameter. This follows the 20% convention — model selection is rare and belongs in `@Customize`, not on the annotation.

Build-time validation: if the application's dependency graph includes no `ChatModelProvider` implementations (no `casehub-platform-agent-*` module on the classpath), the build extension emits a warning: "No ChatModelProvider found on classpath — @SystemPrompt workers will fail at runtime unless a provider is registered."

#### `@Param`

```java
@Retention(RUNTIME)
@Target(PARAMETER)
public @interface Param {
    String value();  // context key override
}
```

Overrides the parameter name when the Java parameter name doesn't match the desired context key. Optional — the common case uses Java parameter names directly.

#### `@Effect`

```java
@Retention(RUNTIME)
@Target(METHOD)
public @interface Effect {
    String value();  // explicit context key for this worker's output
}
```

Overrides the default type-to-key mapping in GOAP mode. Used when the inferred key is ambiguous (two workers return the same type) or unclear.

#### `@SoftDependency` (GOAP parameter)

```java
@Retention(RUNTIME)
@Target(PARAMETER)
public @interface SoftDependency {}
```

Marks a GOAP-inferred dependency as non-blocking. The worker can execute with or without this input. Named `@SoftDependency` rather than `@Optional` to avoid import confusion with `java.util.Optional`.

**GOAP model mapping:** `@SoftDependency` parameters are placed in `GoapAction.softPreconditions` (a new `Map<String, Boolean>` field), not in the hard `preconditions` map. `GoapAction.isApplicable()` checks only hard preconditions — soft preconditions are used by the planner's heuristic as tie-breakers. The planner adds a cost penalty when a soft precondition is unsatisfied:

```
softPenalty = unsatisfiedSoftDeps > 0 ? max(0.5 × action.cost(), 0.1) : 0.0
```

The `max(..., 0.1)` floor ensures soft dependencies always influence planning, even when `cost = 0.0`. Without this floor, `@SoftDependency` with default cost values (0.0) would have zero penalty — a silent no-op. The floor value `0.1` provides a meaningful tie-breaker: paths that satisfy soft dependencies are preferred, but the penalty is small enough not to dominate non-zero explicit costs.

At runtime, if the soft dependency value is present in the case context, it is extracted and passed to the method; if absent, `null` is passed.

### GOAP Auto-Inference

When `planning = PlanningMode.GOAP`, the build extension infers worker dependencies from method signatures:

```java
@Case(namespace = "legal", name = "Review", planning = PlanningMode.GOAP)
public interface DocumentReview {

    @Worker(capability = "analyse", cost = 2)
    @SystemPrompt("You are a document analyst. Extract key findings.")
    AnalysisResult analyse(String document);

    @Worker(capability = "extractClauses", cost = 3)
    List<Clause> extract(String document, AnalysisResult analysis);

    @Worker(capability = "assessRisk", cost = 5)
    RiskAssessment assess(AnalysisResult analysis, List<Clause> clauses);

    @Goal("Document fully reviewed")
    @Completion
    default GoalExpression done() {
        return GoalExpression.goal("riskAssessed");
    }
}
```

**Inference rules:**

| Rule | Example | Result |
|---|---|---|
| Non-matching parameter type | `AnalysisResult analysis` on `extract()` | Precondition: `analysisResult` must be in context |
| Return type | `AnalysisResult` from `analyse()` | Effect: `analysisResult` added to context |
| Primitive/String parameters | `String document` on `analyse()` | Input parameter (from initial context), not a dependency |
| `@Param`-annotated parameters | `@Param("doc") String document` | Input parameter with explicit key, not a dependency |
| Parameterized types | `List<Clause>` from `extract()` | Effect key: `clauseList` |
| Subtype matching | `DetailedAnalysis extends AnalysisResult` | Satisfies `AnalysisResult` precondition (Jandex hierarchy walk) |
| `@SoftDependency` parameter | `@SoftDependency AnalysisResult analysis` | Soft precondition (preferred but not required) |
| `@Effect("name")` override | `@Effect("tags") List<String> extract()` | Key: `tags` instead of `stringList` |

**Key derivation convention:** camelCase of simple type name. `AnalysisResult` -> `"analysisResult"`. `List<Clause>` -> `"clauseList"`. Collision detection at build time.

**Bridge to boolean world state:** The existing `GoapAction` uses `Map<String, Boolean>` for preconditions and effects, and `GoapWorldState` is a `Map<String, Boolean>`. The type-based inference bridges to this model as follows:

- **Precondition:** if worker `extract()` requires `AnalysisResult analysis`, the inferred precondition is `{"analysisResult": true}` — the key must be present (true) in the world state
- **Effect:** if worker `analyse()` returns `AnalysisResult`, the inferred effect is `{"analysisResult": true}` — executing this action sets the key to true
- **Initial world state:** built from the case's initial context — each key present in the context at case start maps to `true`
- **`@SoftDependency`:** generates a **soft** precondition (stored in `GoapAction.softPreconditions`, not `preconditions`). `isApplicable()` ignores soft preconditions — the action can execute without them. The planner's heuristic adds a cost penalty when soft preconditions are unsatisfied, preferring paths that satisfy them without requiring them

The boolean model is a presence/absence check: "has this type been produced?" This is the correct abstraction for GOAP dependency planning — the planner doesn't need the actual values, only whether the dependency has been satisfied. The actual typed values flow through the case context at runtime.

**What counts as a dependency vs an input parameter:**
- If the parameter type matches another `@Worker`'s return type on the same `@Case` interface -> dependency (GOAP precondition)
- If the parameter type is `String`, a primitive, or `Map<String, Object>` -> input parameter (from initial context or `@Param` key)
- If no producer exists for the type -> build-time error (unreachable worker) unless `@SoftDependency`

**Boolean world state mapping:** GOAP operates on `Map<String, Boolean>`. Type-presence semantics map to boolean state as follows:

| Concept | Boolean mapping | Example |
|---|---|---|
| Precondition (input type) | `"keyName" → true` required in world state | `AnalysisResult analysis` → precondition `analysisResult = true` |
| Effect (return type) | `"keyName" → true` set after execution | `AnalysisResult analyse(...)` → effect `analysisResult = true` |
| Initial context key present | `"keyName" → true` in initial world state | `document` in initial context → `document = true` |
| Initial context key absent | key absent from initial world state (defaults to `false`) | |

Workers produce effects — they do not set negative effects (`false`). A worker that transforms `AnalysisResult` into `RefinedAnalysis` has precondition `{analysisResult: true}` and effect `{refinedAnalysis: true}`. The `analysisResult` key remains `true` — the boolean state tracks availability for planning purposes, not mutation. Runtime context management is separate from the planner's boolean abstraction.

**Compound goals:** `GoapPlanner.plan()` accepts `Set<String> goalConditions` (all must be satisfied). GoalExpression goal names (which reference `@Goal` method names) are NOT used directly as GOAP goal conditions — the build extension translates them to GOAP effect keys at build time.

**Goal-to-effect-key resolution:** GoalExpression references goals by name (for engine-level completion via `GoalBasedCompletion`). GOAP planning operates on effect keys (type-derived context keys). These are different naming schemes — goal names are semantic labels chosen by the developer (e.g., `"riskAssessed"`), while effect keys are structural identifiers derived from return types (e.g., `"riskAssessment"` from `RiskAssessment`). The build extension bridges them:

1. For each `@Goal` method, the build extension analyzes the `condition` expression to extract referenced context keys. For the common pattern `.keyName != null`, the extracted key is `keyName`.
2. Each extracted key is validated against the set of known worker effect keys. If a key doesn't match any effect, the build extension emits a build-time error.
3. The mapping `goalName → Set<String> effectKeys` is stored on the `CaseDefinition` alongside `goapActions`.
4. `GoapPlanningStrategy` uses this mapping when resolving `GoalExpression` for planning — goal names are replaced with their corresponding effect keys.

Example resolution:
- `@Goal(condition = ".riskAssessment != null") void riskAssessed()` → mapping: `"riskAssessed" → Set.of("riskAssessment")`
- `GoalExpression.goal("riskAssessed")` → via mapping → GOAP goal conditions: `Set.of("riskAssessment")`
- `GoalExpression.allOf(goal("analysisComplete"), goal("riskAssessed"))` → union of both mappings → `Set.of("analysisResult", "riskAssessment")` (assuming `@Goal analysisComplete` has condition `.analysisResult != null`)

**Condition parsing:** The build extension supports the following @Goal condition patterns for automatic effect key extraction:
- `.keyName != null` → key: `keyName`
- `.keyName == true` → key: `keyName`
- Compound conditions joined by `&&` → multiple keys (each clause parsed independently)

Conditions that cannot be parsed (nested paths like `.result.score`, value comparisons like `.status == 'approved'`, or complex JQ expressions) produce a build-time error in GOAP/ADAPTIVE mode: `"Cannot derive GOAP goal conditions from @Goal 'X' condition — use a simple presence condition (.keyName != null) for GOAP compatibility, or use @Customize with a custom GoalBasedCompletion."` This ensures that every @Goal in GOAP mode has a well-defined mapping to GOAP world state keys.

**`anyOf` restriction:** `GoalExpression.anyOf(...)` → **build-time error** in GOAP/ADAPTIVE mode: "`anyOf` goal expressions are not supported in GOAP mode — GOAP plans toward a single goal set. Use `allOf`, restructure as a single goal, or use `@Customize` with a custom `GoalBasedCompletion` for disjunctive completion semantics."

`anyOf` is semantically "first to reach" — it doesn't map to upfront GOAP planning where the planner commits to a single goal set. Supporting `anyOf` would require planning for each alternative and selecting the cheapest, which adds complexity with limited practical benefit (GOAP cases typically use `allOf` — "all workers must complete"). This restriction applies to the GOAP goal resolution only; `anyOf` remains fully supported in EXPLICIT mode.

The A* heuristic counts unsatisfied conditions: `goalConditions.stream().filter(c -> !state.satisfies(c)).count()`. This is admissible (never overestimates) and consistent, preserving A* optimality.

**Dual completion subsystems:** In GOAP mode, two completion subsystems operate independently:
1. **GOAP planning:** controls execution ordering — plans toward making effect keys `true` in the boolean world state
2. **Engine completion:** controls case termination — `GoalBasedCompletion` evaluates `@Goal.condition` JQ expressions against actual case context, fires `GoalReachedEvent`, and transitions the case to COMPLETED or FAULTED

These converge by design: the GOAP plan executes workers → workers produce typed results → results appear in context under effect keys → `@Goal.condition` (which references the same keys) becomes true → case completes. The goal-to-effect-key mapping ensures the planner targets the same keys that the @Goal conditions check. Build-time validation (condition parsing + effect key matching) prevents divergence.

**Build-time validation for GOAP goal coherence:** In GOAP/ADAPTIVE mode, the build extension validates that every `@Goal.condition` references only keys that are producible by the declared workers' effects. If a condition references a key that no worker produces, the build extension emits: `"@Goal 'X' condition references key 'Y' which is not produced by any worker effect — GOAP plan cannot satisfy this goal."`

`GoapWorldState` gains `satisfiesAll(Set<String>)` — the goal test checks all conditions simultaneously.

**Benefit cost formula:** The `benefit` field on `@Worker` and `GoapAction` adjusts planning cost:

```
effectiveCost = cost × (1.0 - benefit)
```

Where `cost ∈ [0.0, 1.0]` and `benefit ∈ [0.0, 1.0]`, guaranteeing `effectiveCost ∈ [0.0, 1.0]` — always non-negative, preserving A* admissibility and termination. The planner accumulates `effectiveCost` along paths: `current.cost() + action.effectiveCost()`.

| cost | benefit | effectiveCost | Semantics |
|---|---|---|---|
| 0.5 | 0.0 | 0.50 | No benefit — full cost |
| 0.5 | 0.3 | 0.35 | Moderate benefit reduces cost |
| 0.8 | 1.0 | 0.00 | Maximum benefit — effectively free |
| 0.0 | 0.0 | 0.00 | Both defaults — unweighted (all actions equal cost) |

When both `cost` and `benefit` are `0.0` (the annotation defaults), all actions have zero effective cost and the planner finds any valid ordering. This is the expected behavior for cases that don't need cost-weighted planning.

**Build-time validation:**
- Cycle detection (A depends on B depends on A)
- Unreachable workers (precondition never satisfied)
- Ambiguous producers (two workers return the same type without `@Effect`)
- Empty plan (no workers can start — all have unsatisfied preconditions)

**Runtime inference (D4):** The same inference logic is available at runtime for dynamically registered workers. `GoapActionInferrer` (shared utility in `casehub-engine-common`) accepts `WorkerFunction` instances and generates `GoapAction` preconditions/effects from `inputType()`/`outputType()`. Build extension uses Jandex; runtime uses reflection. Same key convention.

### Worker Method Bodies

`@Case` is an interface — methods have no bodies by default. Worker implementations are provided in three ways:

1. **`@SystemPrompt` workers** — LLM-backed. The build extension generates an `AgentWorkerFunction`. No method body needed (the method is abstract).

2. **`default` method workers** — plain Java function. The method body IS the worker function:
   ```java
   @Worker(capability = "validate")
   @Bind(contextChange = ".documentReceived == true")
   default ValidationResult validate(Document document) {
       return new ValidationResult(document.isValid(), document.errors());
   }
   ```

3. **External workers** — `@Capability` without `@Worker` declares a capability satisfied by an external worker (YAML-defined MCP, A2A, builder-produced). The method is abstract with no body.

The build extension detects which case applies: `@SystemPrompt` present → LLM, `default` method → wrap body as `WorkerFunction.Sync`, abstract + `@Capability` only → external (no function generated).

**Default method invocation mechanism:** `@Case` is an interface — `default` methods cannot be called without an implementing instance. The build extension uses Quarkus Gizmo to generate a concrete synthetic subclass that implements the `@Case` interface. Default methods are inherited by the subclass. The build extension then generates a `WorkerFunction.Sync` that:
1. Creates an instance of the generated subclass (stateless — no constructor args)
2. Extracts input parameters from the case context by name/type
3. Invokes the default method on the subclass instance
4. Wraps the return value in `WorkerResult.success(result)`

The synthetic subclass is generated at build time (Quarkus deployment phase) — zero runtime reflection, GraalVM-compatible. The `@Case` interface itself is never proxied — the synthetic subclass is an implementation detail of the build extension, invisible to the developer.

**Multi-parameter bridging:** `WorkerFunction.Sync<T, R>` takes a single input type `T`. When a `@Worker` method has multiple parameters, the build extension generates a `WorkerFunction.Sync<Map<String, Object>, R>` where the input map is the case context. The generated function:
1. Receives the input `Map<String, Object>` from the case context
2. Extracts each parameter by its **context key** and casts to the declared type
3. Invokes the default method on the synthetic subclass instance with the extracted parameters
4. Wraps the return value as `WorkerResult`

**Parameter extraction key convention** — unified with GOAP effect/precondition keys:

| Parameter type | Key derivation | Example |
|---|---|---|
| Domain type (non-primitive, non-String) | camelCase of simple type name | `AnalysisResult analysis` → extracts by key `"analysisResult"` |
| Parameterized domain type | camelCase of `ElementTypeContainer` | `List<Clause> clauses` → extracts by key `"clauseList"` |
| `String`, primitives, `Map<String, Object>` | Java parameter name (or `@Param` override) | `String document` → extracts by key `"document"` |
| `WorkerScope` | Special — injected from BiFunction, not from context | N/A |
| `@Param("key")` annotated | Explicit key override (any type) | `@Param("doc") String x` → `"doc"` |

This convention is shared between GOAP effect/precondition keys and parameter extraction, ensuring consistency: a worker that produces effect key `"analysisResult"` is consumed by a parameter that extracts key `"analysisResult"`. The Java parameter name is informational for domain types — `AnalysisResult analysis` and `AnalysisResult result` both extract from key `"analysisResult"`.

**Error handling:**
- Required parameter missing from context → `WorkerResult.failed("Worker 'assess' parameter 'analysis' (key: analysisResult) not found in case context")`
- `@SoftDependency` parameter missing → `null` passed to the method
- Type mismatch at runtime → `WorkerResult.failed(...)` with a clear cast-error message

**Generic type handling:** Due to type erasure, `List<Clause>` extracts as `List` at runtime. Build-time validation (GOAP cycle/ambiguity checks) ensures that if the key is present, the element type is correct. The generated wrapper uses `@SuppressWarnings("unchecked")` for the cast.

This is `ContextBridge`'s role — it bridges between the untyped `Map<String, Object>` context and the typed method parameters. The developer works with Java types; the plumbing is invisible.

### GOAP + @Bind Interaction

When `planning = PlanningMode.GOAP` or `ADAPTIVE`:
- `@Bind` **trigger attributes** (`contextChange`, `event`, `cron`, `scopeActivated`) are **ignored** — the planner controls dispatch ordering via inferred dependencies
- Build extension emits a **warning** (not error) if `@Bind` trigger attributes are set on a `@Worker` method in GOAP/ADAPTIVE mode: "GOAP mode ignores @Bind triggers — dependencies are inferred from types. Remove @Bind triggers or switch to PlanningMode.EXPLICIT."
- No partial interpretation of `@Bind`: lifecycle attributes (`scope`, `participation`, `executionMode`) live on `@Worker`, not `@Bind`, so they are always respected regardless of planning mode
- `@Bind` guard and metadata attributes ARE respected in GOAP/ADAPTIVE mode

**`@Bind` attribute classification in GOAP/ADAPTIVE mode:**

| `@Bind` attribute | Category | GOAP/ADAPTIVE behavior |
|---|---|---|
| `contextChange` | Dispatch trigger | **Ignored** — planner infers ordering from types |
| `event` | Dispatch trigger | **Ignored** |
| `cron` | Dispatch trigger | **Ignored** |
| `scopeActivated` | Dispatch trigger | **Ignored** |
| `listenLayer` | Dispatch trigger qualifier | **Ignored** — no trigger in GOAP mode |
| `conflictStrategy` | Conflict resolution | **Respected** — independent of dispatch ordering |
| `when` | Runtime guard | **Respected** — evaluated as part of trigger eligibility, BEFORE `LoopControl.select()` (same as EXPLICIT mode). A binding that fails its `when` guard is excluded from the `eligible` set, so `GoapPlanningStrategy` never receives it. The strategy filters its `GoapAction` set to match eligible bindings (see §PlanningMode) — if a `when` guard blocks a binding, the corresponding action is excluded from the planner's action set, and the planner finds an alternative path or returns an empty plan (case waits for context changes to unblock the guard). |
| `producedKeys` | GOAP metadata | **Respected as override** — when set explicitly, overrides type-inferred effect keys. Build extension emits a warning if `producedKeys` diverges from inferred keys. |
| `capability` | Metadata | **Respected** — names the bound capability |

**`@Worker` lifecycle attributes** (`scope`, `participation`, `executionMode`) are always respected — they configure how the worker executes, not when. The build extension copies them to the generated `Binding.Builder` in all planning modes.

This separation is clean: `@Bind` is purely dispatch triggers and metadata — entirely ignorable in GOAP mode. `@Worker` carries lifecycle configuration that applies regardless of how dispatch is determined.

### Scope Access

Workers access `WorkerScope` via parameter declaration — the build extension recognizes `WorkerScope` as a special parameter type and injects it from the execution context (not from case context):

```java
@Worker(capability = "validate")
@Bind(contextChange = ".documentReceived == true")
default ValidationResult validate(Document document, WorkerScope scope) {
    String caseId = scope.caseId();
    return new ValidationResult(document.isValid(), caseId);
}
```

The build extension generates a `WorkerFunction.Sync` that receives `WorkerScope` in its `BiFunction<T, WorkerScope, WorkerResult<R>>` and passes it to the method. `WorkerScope` parameters are excluded from GOAP dependency inference — they are not context keys.

For `@SystemPrompt` workers (abstract methods), `WorkerScope` is not available as a direct parameter — the `AgentWorkerFunction` receives `WorkerScope` internally via the `BiFunction` contract and makes it available to the agent's execution context.

**CDI bridge (non-annotation path):** For workers defined via builders or called from CDI beans, `WorkerScopeProducer` provides CDI access. The engine runtime stores the current `WorkerScope` in a `ThreadLocal` before invoking the worker function. The `@RequestScoped` CDI producer reads from that `ThreadLocal`. This bridge is transparent to annotation-defined workers (they use parameter injection) but enables CDI beans called from within worker execution to access the scope.

### Examples

**Example 1: `simple-case-annotated` (EXPLICIT mode)**

```java
@Case(namespace = "test", name = "Document Processing", version = "1.0.0")
public interface DocumentProcessingCase {

    @Worker(capability = "processDocument")
    @Bind(contextChange = ".status == 'processing'")
    default ProcessedDocument process(String documentId, String status) {
        return new ProcessedDocument(documentId, "Processed content for " + documentId, "processed");
    }

    @Milestone(name = "documentProcessed",
               completionCriteria = ".status == 'processed'")
    default void documentProcessed() {}

    @Goal(value = "Document processing complete",
          condition = ".status == 'processed'")
    @Completion
    default GoalExpression done() {
        return GoalExpression.goal("documentProcessed");
    }
}
```

**Example 2: `multi-worker-annotated` (EXPLICIT mode)**

```java
@Case(namespace = "legal", name = "Document Review", version = "1.0.0")
public interface DocumentReviewCase {

    @Worker(capability = "analyse")
    @Bind(contextChange = ".status == 'received'")
    @SystemPrompt("You are a document analyst. Extract key findings.")
    AnalysisResult analyse(String document);

    @Worker(capability = "extractClauses")
    @Bind(contextChange = ".analysisComplete == true")
    @SystemPrompt("Extract legally significant clauses.")
    List<Clause> extractClauses(String document);

    @Worker(capability = "assessRisk")
    @Bind(contextChange = ".clausesExtracted == true",
          when = ".priority == 'high'")
    RiskAssessment assessRisk(AnalysisResult analysis, List<Clause> clauses);

    @Milestone(name = "analysisComplete",
               entryCriteria = ".status == 'received'",
               completionCriteria = ".analysisComplete == true")
    default void analysisComplete() {}

    @Goal(value = "Risk assessment completed",
          condition = ".riskAssessment != null")
    void riskAssessed();

    @Completion
    default GoalExpression done() {
        return GoalExpression.allOf(
            GoalExpression.goal("analysisComplete"),
            GoalExpression.goal("riskAssessed"));
    }
}
```

**Example 3: `goap-case-annotated` (GOAP mode with inferred dependencies)**

```java
@Case(namespace = "legal", name = "Document Review",
      version = "1.0.0", planning = PlanningMode.GOAP)
public interface GoapDocumentReviewCase {

    @Worker(capability = "analyse", cost = 2)
    @SystemPrompt("You are a document analyst. Extract key findings.")
    AnalysisResult analyse(String document);

    @Worker(capability = "extractClauses", cost = 3)
    List<Clause> extract(String document, AnalysisResult analysis);

    @Worker(capability = "assessRisk", cost = 5)
    RiskAssessment assess(AnalysisResult analysis, List<Clause> clauses);

    @Goal(value = "Risk assessment completed",
          condition = ".riskAssessment != null")
    void riskAssessed();

    @Completion
    default GoalExpression done() {
        return GoalExpression.goal("riskAssessed");
    }
}
```

### Interop with Existing Builders and YAML

All three programming models produce the same types:

```java
// Annotation-defined case with a builder-defined worker
@Case(namespace = "legal", name = "Mixed Review", planning = PlanningMode.GOAP)
public interface MixedCase {

    @Worker(capability = "analyse")
    AnalysisResult analyse(String document);

    // "assessRisk" capability satisfied by a YAML-defined MCP worker
    // or a builder-defined worker via CDI @Produces

    @Goal("Document reviewed")
    @Completion
    default GoalExpression done() {
        return GoalExpression.goal("riskAssessed");
    }
}

// Builder-defined worker registered via CDI
@ApplicationScoped
public class RiskWorkerProducer {
    @Produces
    Worker riskWorker() {
        return Worker.builder()
            .name("risk-assessor")
            .capabilityName("assessRisk")
            .<RiskInput>fn().returning(RiskAssessment.class)
            .apply((input, scope) -> { /* ... */ })
            .build();
    }
}
```

The engine resolves capabilities by name — it doesn't care how the worker was defined.

---

## Layer 2: Cross-Module Annotations (design for composition)

Layer 2 annotations live in their respective repo modules but compose onto Layer 1's `@Case` interfaces and `@Worker` methods. The engine build extension provides composition points; each repo's build extension processes its own annotations.

### Eidos — Agent Identity (casehub-eidos-annotations)

**Annotations:** `@Identity`, `@Disposition`, `@AgentGoals`, `@AgentConstraints`, `@Discoverable`
**Target:** TYPE (applied to `@Case` interfaces or standalone agent interfaces)
**Produces:** `AgentDescriptor` CDI beans registered with `AgentRegistry`

```java
@Identity(slot = "legal-analyst",
          provider = "casehub",
          modelFamily = "claude",
          jurisdiction = "EU")
@Disposition(socialOrient = "collaborative",
             ruleFollowing = "strict",
             riskAppetite = "cautious")
@Case(namespace = "legal", name = "Document Review")
public interface DocumentReviewCase { /* ... */ }
```

The eidos build extension generates an `AgentDescriptor` from `@Identity` + `@Disposition` and wires it to the case definition via `CaseDefinition.agentDescriptorFor()`.

### Work — Human-in-the-Loop (casehub-work-annotations)

**Annotations:** `@HumanApproval`, `@RequiresQuorum`, `@Escalate`
**Target:** METHOD (applied to `@Worker` or `@Bind` methods)
**Produces:** `HumanTaskTarget` bindings, `WorkItem` configuration

```java
@Worker(capability = "assessRisk")
@Bind(contextChange = ".clausesExtracted == true")
@HumanApproval(title = "Risk assessment review",
               candidateGroups = "senior-legal",
               claimDeadline = "PT30M")
@Escalate(onExpiry = "legal-director", deadline = "PT4H")
RiskAssessment assessRisk(AnalysisResult analysis, List<Clause> clauses);
```

The work build extension wraps the worker dispatch with `HumanTaskTarget` and configures the `WorkItem` lifecycle.

### Blocks — Governance (casehub-blocks-annotations)

**Annotations:** `@OversightGate`, `@TrustRouted`, `@CbrRouted`, `@Attestation`, `@OnFailure`
**Target:** METHOD (compose onto any `@Worker` method)
**Produces:** Interceptor wiring, routing strategy configuration

```java
@Worker(capability = "assessRisk")
@Bind(contextChange = ".clausesExtracted == true")
@OversightGate(LegalRiskClassifier.class)
@TrustRouted(minimumScore = 0.8)
RiskAssessment assessRisk(AnalysisResult analysis, List<Clause> clauses);
```

The blocks build extension generates `ActionRiskClassifier` chain wiring and `TrustRoutingPolicyResolver` configuration.

### Blocks — Orchestration Patterns (casehub-blocks-annotations)

**Annotations:** `@DebateAgent`, `@VotingAgent`, `@HtnAgent`
**Target:** METHOD (on separate pattern interfaces, not on `@Case` interfaces)
**Produces:** `ExecutionModel`, `PatternWorkerFunction`

```java
public interface ComplianceReview {

    @DebateAgent(maxRounds = 5)
    @OversightGate(ComplianceRiskClassifier.class)
    String review(
        @Debater(role = "critic", systemPrompt = "Challenge every claim...")
        AgentRef critic,
        @Debater(role = "advocate", systemPrompt = "Defend compliance...")
        AgentRef advocate,
        @Judge AgentRef judge,
        String document);
}
```

**Future deliberation patterns** (blocks#104, #106–#111): `@NegotiationAgent`, `@AuctionAgent`, `@CoalitionAgent`, `@JointIntentionAgent`, `@BeliefRevisionAgent`, `@NormativeConflictAgent`. These follow the same annotation pattern as `@DebateAgent`/`@VotingAgent` — method-level on pattern interfaces, producing `ExecutionModel` via `Patterns.*()`. Not in scope for the first implementation batch but the build extension architecture accommodates them (each is a new `@BuildStep` handler in the blocks build extension).

### Desiredstate — Reconciliation (casehub-desiredstate-annotations)

**Annotations:** `@DesiredState`, `@NodeSpec`, `@DependsOn`, `@FaultPolicy`
**Target:** TYPE and METHOD
**Produces:** `DesiredStateGraph` configuration

### Ledger — Audit (casehub-ledger-annotations)

**Annotations:** `@Audited`, `@ComplianceSupplement`
**Target:** METHOD (compose onto `@Worker`)
**Produces:** Ledger interceptor wiring

### Composition architecture

Each Layer 2 build extension:
1. Runs AFTER the engine build extension (Quarkus build step ordering via `@Consume`)
2. Scans its own annotations on `@Case` interfaces or `@Worker` methods
3. Generates its own CDI beans (AgentDescriptor, WorkItem config, interceptors)
4. Does NOT modify the engine-generated `CaseDefinition` — CDI wires everything at runtime

Engine build extension composition points:
- `@Customize(CaseDefinition.Builder)` — any Layer 2 can emit a `@Customize` method
- `@Customize("methodName", Binding.Builder)` — customize specific bindings
- Annotation targets (TYPE, METHOD) are open — any annotation can go on a `@Case` interface or `@Worker` method

---

## Build Extension Architecture

### Module structure

Quarkus extensions require a deployment/runtime split. The `annotations` directory contains two Maven modules:

```
annotations/
  runtime/
    src/main/java/
      io/casehub/engine/annotations/
        Case.java, Worker.java, Capability.java, Bind.java, Bindings.java,
        Goal.java, Milestone.java, Completion.java, Completions.java,
        Customize.java, Customizers.java,
        SystemPrompt.java, Param.java, Effect.java, SoftDependency.java,
        PlanningMode.java
      io/casehub/engine/annotations/runtime/
        WorkerScopeProducer.java         # request-scoped CDI producer
    pom.xml                              # depends on engine-api, worker-api
  deployment/
    src/main/java/
      io/casehub/engine/annotations/deployment/
        EngineAnnotationsProcessor.java  # Quarkus build extension (@BuildStep methods)
    pom.xml                              # depends on runtime + quarkus-arc-deployment
  pom.xml                                # parent aggregator
```

The `runtime` module contains annotation definitions and CDI producers — on the classpath at both build time and runtime. The `deployment` module contains the Quarkus `@BuildStep` processor — on the classpath at build time only, stripped from the final artifact.

### Build extension pipeline

1. **Jandex scanning** — find all `@Case`-annotated interfaces in the Jandex index
2. **Validation** — before generating anything:
   - `@Bind` references match declared `@Worker`/`@Capability` names
   - `@Bind` has exactly one trigger attribute set
   - `@Goal` completion expressions reference valid goals
   - GOAP mode: parameter type -> return type chains form a valid DAG
   - `@Worker` with `@SystemPrompt` is on a method with compatible signature
   - `-parameters` flag present (parameter names not synthetic)
   - `@Effect` used only when ambiguous types exist
3. **Synthetic bean generation** — Quarkus `SyntheticBeanBuildItem`:
   - `CaseDefinition` per `@Case` interface (via builder calls in a recorded invocation)
   - `Worker` per `@Worker` method (with function reference)
   - `Capability` per `@Worker` or standalone `@Capability`
   - `Binding` per `@Bind` (whether on `@Worker` or separate method)
   - GOAP `GoapAction` instances when `planning = GOAP`
4. **Build-time resolution** — annotations and GOAP inference resolved at build time via Jandex. `@Completion` and `@Customize` method bodies execute during Quarkus runtime init (`@Record(RUNTIME_INIT)`) — standard recorder mechanism, not per-request reflection. GraalVM-compatible. `@Completion` default methods are invoked using the same synthetic subclass generated for `@Worker` default methods (see §Worker Method Bodies) — the build extension creates an instance of the subclass and calls the `@Completion` method on it during runtime init to obtain the `GoalExpression`. Since `@Completion` methods take no parameters and return a static `GoalExpression` structure, the stateless invocation is straightforward.

### Build-time validation messages

| Check | Error |
|---|---|
| Unknown capability in `@Bind` | `@Bind on 'afterAnalysis' references capability 'analyse' not declared on this @Case` |
| No trigger set | `@Bind on 'onReceived' has no trigger — set exactly one of contextChange, event, cron, scopeActivated` |
| Multiple triggers | `@Bind on 'onReceived' has multiple triggers (contextChange, cron) — set exactly one per @Bind` |
| GOAP cycle | `Dependency cycle: analyse -> extract -> analyse` |
| GOAP ambiguous producer | `Workers 'extractTags' and 'extractErrors' both return List<String> — add @Effect to disambiguate` |
| Missing `-parameters` flag | `Parameter names are synthetic (arg0, arg1) — add -parameters to javac options` |
| `@Worker` both capability and capabilities | `@Worker on 'analyse' sets both capability and capabilities — use one or the other` |
| Duplicate `@Completion` kinds | `Duplicate @Completion kind 'SUCCESS' on DocumentReviewCase — each kind may appear at most once` |
| Duplicate `@Goal` names | `Duplicate @Goal name 'documentReviewed' on DocumentReviewCase` |
| Duplicate `@Milestone` names | `Duplicate @Milestone name 'analysisComplete' on DocumentReviewCase` |
| `@Worker` with `default` body and `@SystemPrompt` | `@Worker on 'analyse' has both a default method body and @SystemPrompt — remove one (default body = Java function, @SystemPrompt = LLM-backed)` |
| `@SoftDependency` in EXPLICIT mode | Warning: `@SoftDependency on parameter 'analysis' of 'extract' has no effect in PlanningMode.EXPLICIT — only used in GOAP/ADAPTIVE mode` |
| `@Param` on GOAP-inferred dependency | `@Param on 'analysis' of 'extract' overrides the context key — this parameter is treated as an input parameter, not a GOAP dependency. If this is intentional, the worker has no dependency on AnalysisResult.` |
| `@SoftDependency` with `cost = 0.0` | Warning: `@SoftDependency on parameter 'analysis' of 'extract' — worker cost is 0.0; soft dependency penalty uses minimum floor (0.1). Set explicit cost for finer control.` |
| GOAP `@Goal` condition unparseable | `Cannot derive GOAP goal conditions from @Goal 'riskAssessed' condition '.result.score > 80' — use a simple presence condition (.keyName != null) for GOAP compatibility` |
| GOAP `@Goal` condition references unknown key | `@Goal 'riskAssessed' condition references key 'riskScore' which is not produced by any worker effect — GOAP plan cannot satisfy this goal` |

### Cross-module build extension ordering

```
Quarkus build step ordering:

1. Engine build extension — generates CaseDefinition, Worker, Capability, Binding
2. Eidos build extension — generates AgentDescriptor (reads @Identity, @Disposition)
3. Work build extension — generates WorkItem config (reads @HumanApproval)
4. Blocks build extension — generates ExecutionModel, interceptors (reads @OversightGate, etc.)

Each extension uses @Consume(BeanDiscoveryFinishedBuildItem.class) or
@Produce/@Consume custom build items for ordering.
```

---

## GOAP Runtime Inference

The same inference logic available at build time is also available at runtime for dynamically registered workers. `GoapActionInferrer` is a shared utility in `casehub-engine-common`:

```java
public class GoapActionInferrer {

    public static GoapAction infer(Worker worker, WorkerFunction<?, ?> function) {
        // 1. If function is AgentWorkerFunction with declared types, use those
        // 2. Otherwise, use function.inputType() / function.outputType()
        // 3. Same key convention as build-time inference
    }
}
```

Build extension uses Jandex types. Runtime uses `Class<?>` from `WorkerFunction`. Same key derivation convention (`GoapKeyConvention` utility class).

**AgentWorkerFunction type metadata:** `AgentWorkerFunction` implements `WorkerFunction<Map, Map>`, erasing the original method's type information. To support runtime GOAP inference for agent-backed workers, `AgentWorkerFunction` gains two additional fields:

```java
public record AgentWorkerFunction(
    Agent agent,
    List<Class<?>> declaredInputTypes,  // original method parameter types (empty for untyped)
    Class<?> declaredOutputType         // original method return type (nullable for untyped)
) implements WorkerFunction<Map<String, Object>, Map<String, Object>> {
    // inputType() and outputType() still return Map.class for runtime execution
    // GoapActionInferrer uses declaredInputTypes/declaredOutputType for inference
}
```

`declaredInputTypes` is a list because workers often have multiple typed parameters (e.g., `assess(AnalysisResult analysis, List<Clause> clauses)` → two preconditions). The build extension populates all parameter types from the Jandex method signature. For builder-defined agent workers, consumers set these explicitly: `Agent.builder().systemPrompt("...").declaredInputTypes(List.of(AnalysisResult.class, List.class)).declaredOutputType(RiskAssessment.class).build()`.

**Two-tier GOAP action resolution:** For annotation-defined workers, build-time `GoapAction`s (generated from Jandex with full type information) are authoritative and stored on `CaseDefinition.goapActions`. Runtime inference via `GoapActionInferrer` only runs for dynamically added workers. `GoapPlanningStrategy` combines both sets when planning.

Workers registered via builders, YAML, A2A discovery, or MCP tool discovery automatically participate in GOAP planning when the case uses `PlanningMode.GOAP`. No recompilation needed.

---

## Testing Strategy

### Unit tests
- Annotation presence and attribute validation (reflection tests)
- `GoapActionInferrer` — type inference logic with edge cases (generics, subtypes, collisions)
- `GoapKeyConvention` — key derivation from types

### Integration tests
- `@QuarkusTest` with annotation-defined cases
- Generated `CaseDefinition` equals builder-equivalent `CaseDefinition`
- GOAP-inferred execution order matches expected dependency chain
- `@Customize` builder modifications applied correctly
- Mixed mode: annotation-defined + builder-defined workers in same case

### Build-time validation tests
- Invalid annotation combinations produce compile-time errors
- GOAP cycle detection
- Missing `-parameters` flag detection

### Drift-protection tests
- For each `@Case` attribute, assert `CaseDefinition.builder()` has a matching setter
- For each `@Worker` attribute, assert `Worker.builder()` has a matching setter
- Run as part of the build — fail if either side adds a field without updating the other

---

## Epic Structure

### Epic: Annotation-Driven Agent Programming Model (casehubio/blocks#115)

| Issue | Repo | Module | Layer | Dependencies | Scale |
|---|---|---|---|---|---|
| engine#909 | engine | `casehub-engine-annotations` | 1 | None | L |
| eidos#139 | eidos | `casehub-eidos-annotations` | 2 | engine#909 | M |
| work#356 | work | `casehub-work-annotations` | 2 | engine#909 | M |
| blocks#116 | blocks | `casehub-blocks-annotations` | 2 | engine#909, eidos#139 | L |
| blocks#117 | blocks | `casehub-desiredstate-annotations` | 2 | engine#909 | M |
| ledger#195 | ledger | `casehub-ledger-annotations` | 2 | engine#909 | S |

### Batch planning (wrap windows)

**Batch 1:** engine#909 — full Layer 1 with GOAP. Foundation for everything else.
**Batch 2:** eidos#139 + work#356 — independent of each other, both depend on engine#909.
**Batch 3:** blocks#116 — depends on engine#909 + eidos#139 for full composition.
**Batch 4:** blocks#117 + ledger#195 — independent, lower priority.

---

## What Stays Builder/YAML Only

| Capability | Why |
|---|---|
| Dynamic routing logic | Runtime decisions need code |
| Complex decomposition trees | Nested `CompoundTask` with guards needs builder expressiveness |
| Runtime `ExecutionModel` construction | Programmatic composition for dynamic patterns |
| Event-driven choreography | `EventSource`, `EventConcurrencyPolicy` are runtime abstractions |
| Advanced retry configuration | Backoff delays, complex policies — use `ExecutionPolicy` builder |

The annotation model handles the 80% case. The builders handle the 20% that needs runtime flexibility. `@Customize` bridges the gap — annotation-defined cases access the full builder API for the long tail.
