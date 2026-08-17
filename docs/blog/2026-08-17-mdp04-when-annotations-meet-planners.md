---
title: "When Annotations Meet Planners"
date: 2026-08-17
series: "Building CaseHub"
entry: mdp04
status: draft
---

CaseHub has three ways to define a case: YAML, fluent builders, and now — annotations. The builder API works. The YAML mapper works. But both require you to know the type system intimately: `CaseDefinition.builder().namespace(...).name(...).capabilities(...).workers(...).bindings(...)`. A hundred lines for a case that does three things.

The annotation model collapses that. A `@Case` interface with `@Worker` methods, `@Bind` triggers, and `@Goal` conditions — the build extension generates the same `CaseDefinition` the builder would produce. Embabel proved this DX pattern works for agent frameworks. The question was whether CaseHub's richer model (reactive dispatch, lifecycle scopes, outcome policies, planning strategies) could fit into annotations without turning them into a configuration DSL with thirty attributes.

The answer turned out to be `@Customize`. A static method on the interface that receives the generated builder after all annotation processing. Common fields live on the annotations — namespace, capability, trigger, cost. Rare fields — authorization, routing signal weights, cognitive demands, channels — go through `@Customize`. The cliff from "annotations can't express this" to "abandon annotations entirely" disappears. You step down one level to the builder for the specific field you need, then step back up.

## The interesting decision: own everything

The original plan was to adopt LangChain4j's annotations as Layer 0 — `@SystemMessage`, `@Agent`, the pattern annotations. I pushed back on this during brainstorming, and the more I looked at it the clearer the tension became.

LC4j's model is imperative orchestration: `@SequenceAgent` says run A then B then C. CaseHub's model is reactive dispatch: bindings fire when context-change triggers match JQ conditions, and a planning strategy controls what fires next. These are fundamentally different philosophies. Using LC4j's `@SystemMessage` on a CaseHub `@Worker` method means the same annotation would carry different execution semantics depending on which framework processes it. That's confusing, not convenient.

So we own all the annotations. CaseHub defines `@Case`, `@Worker`, `@SystemPrompt`, `@Bind`, `@Goal`, `@Milestone`, `@Completion`. No LC4j annotation dependency. LC4j stays as the runtime LLM client library — `ChatModel`, `ChatRequest`, the message types — used internally by the engine's `Agent` class. The annotations module has zero coupling to it.

## GOAP: where type inference gets real

The killer feature isn't the annotation syntax — it's what the build extension infers from it. In GOAP mode, the planner discovers execution order from method signatures:

```java
@Case(namespace = "legal", name = "Review", planning = PlanningMode.GOAP)
public interface DocumentReview {

    @Worker(capability = "analyse", cost = 0.2)
    AnalysisResult analyse(String document);

    @Worker(capability = "extract", cost = 0.3)
    List<Clause> extract(String document, AnalysisResult analysis);

    @Worker(capability = "assess", cost = 0.5)
    RiskAssessment assess(AnalysisResult analysis, List<Clause> clauses);
}
```

The build extension sees that `extract` takes `AnalysisResult` — which `analyse` returns. That's a dependency. `assess` takes both `AnalysisResult` and `List<Clause>` — it depends on both prior workers. No `@Bind` triggers needed. The GOAP planner chains the actions by matching postconditions to preconditions, and cost weights determine which path it takes when alternatives exist.

Embabel does this at runtime via reflection. We do it at both build time (via Jandex, with full validation — cycle detection, ambiguous producers, unreachable workers) and runtime (for dynamically registered workers via A2A or MCP discovery). Same inference logic, different timing. Build-time catches errors early. Runtime catches dynamic workers late. Both use the same `GoapKeyConvention` for deterministic type-to-context-key mapping.

The data flow question was trickier than the planning question. CaseHub's context is typed via `ContextBridge<T>`, not a string-keyed JSON blob. When `analyse()` returns `AnalysisResult`, the build extension generates the bridge wiring so the next worker receives it as a typed parameter. The developer writes `AnalysisResult analysis` and gets an `AnalysisResult`. The plumbing is invisible.

## Making default methods executable

The annotation model looks clean on the surface — `@Worker` methods with typed parameters and return values. But an interface can't be instantiated. You can't invoke `instance.analyse(document)` on an interface type. And `WorkerFunction.Sync` is a final record in the worker-api, not an interface you can implement.

Gizmo solves the first problem. The build extension generates an empty class — `DocumentReview_CaseHubImpl` — that implements the `@Case` interface and inherits all default methods. Zero bytecode beyond a constructor that calls `super()`. The JVM's default method dispatch handles the rest: instantiate the generated class, call the method, get the result.

`AnnotationWorkerFunction` solves the second. It's a static factory that returns a `WorkerFunction.Sync<Map, Map>` — the record the engine's handler pipeline expects. Inside the lambda: load the generated class, instantiate it, resolve parameter types from `WorkerParamDescriptor` metadata, convert input map values via Jackson, invoke the method reflectively, wrap the return value under the inferred effect key. The developer writes `AnalysisResult analyse(String document)`. The runtime calls `method.invoke(instance, args)` and writes `{"analysisResult": {...}}` to the case context.

`@SystemPrompt` workers skip this entirely. If a method carries `@SystemPrompt("You are an analyst")` and a `ChatModelProvider` is available at runtime, the recorder builds an `Agent` and wraps it in `AgentWorkerFunction`. No reflection, no generated subclass — the AI model handles the method's responsibility. When no provider is available (tests, non-AI deployments), it falls back to `noFunction()` silently.

## The RUNTIME_INIT surprise

The build extension originally ran at `STATIC_INIT` — the Quarkus phase where bytecode recording happens before CDI containers exist. This worked for the basic case: Jandex metadata in, `SyntheticBeanBuildItem` out. But `@Completion` broke it. A `@Completion` method returns a `GoalExpression` — a runtime object constructed by invoking the default method on the generated subclass. You can't invoke methods at `STATIC_INIT`. The classes haven't been loaded yet.

Moving to `RUNTIME_INIT` fixed that. But the `SyntheticBeanBuildItem` didn't follow — it still tried to configure the bean at static init, and the error message blamed `SyntheticBeansProcessor#initStatic`, not the build step that created the `RuntimeValue`. One line fixed it: `.setRuntimeInit()` on the bean configuration. Undiscoverable from the error, undocumented in the extension guide.

With `RUNTIME_INIT`, `@Completion` works: instantiate the generated subclass, invoke the `@Completion`-annotated default methods, collect the `GoalExpression` objects, build a `GoalBasedCompletion` and set it on the definition. Same for `@Customize` — invoke the static method with the builder. The Gizmo subclass earns its keep three times: worker function invocation, completion collection, and as the instantiable type that makes all default-method-based wiring possible.

## Where this lands

The annotation module is complete. Nineteen tasks across four batches — from the initial Jandex scan through Gizmo codegen, build-time validation, and the full recorder wiring. Forty-four tests across deployment, runtime, and two example modules. The module has zero LangChain4j dependency, compiles against `casehub-engine-api` and `casehub-worker-api` only, and produces the same `CaseDefinition` that builders and YAML produce.

The surface area question is still open. This is Layer 1 — engine annotations only. The design spec covers five more layers: eidos (agent personality), work (human tasks), blocks (patterns), desiredstate (constraints), and ledger (audit). Each layer follows the same principle: own the annotations, infer what you can, `@Customize` what you can't. Whether those layers materialise depends on whether this layer proves the DX gain is worth the maintenance cost of a parallel definition path.
