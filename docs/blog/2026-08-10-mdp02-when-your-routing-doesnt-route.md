---
layout: post
title: "When Your Routing Doesn't Route"
date: 2026-08-10
entry_type: note
subtype: diary
projects: [casehub-engine]
tags: [htn, re-planning, stateful-routing, agentic]
series: issue-881-agentic-planning
---

The plan for engine#882 was straightforward: when an HTN step fails, feed the failure context back to the decomposition strategy and let it produce a revised plan. Three moving parts — `ReplanContext` to carry the failure data, `ReplanPolicy` to configure the guard rails, and `HtnExecutor` to run the loop. The interesting part wasn't any of those.

The design spec placed `ReplanContext` in `casehub-engine-agentic`, the execution module. That's wrong — `DecompositionStrategy.replan()` needs it as a parameter, and `DecompositionStrategy` lives in `engine-api`. Placing the context type in a downstream module would create a circular dependency. It belongs alongside `DecompositionContext` in `engine-api`, where it's a plan-definition type that any strategy implementation can consume.

The spec also didn't address how re-planning works through the engine-hosted path. `PatternWorkerFunctionHandler` runs an `OrchestratedDriver` with a pre-built `ExecutionModel`. But HTN decomposes at execution time — the root task and decomposition strategy need to be available at the handler, not baked into the model at build time. The fix was `HtnExecutor`, a shared class in blocks that both `HtnBuilder.execute()` and the engine handler delegate to. The builder passes the default invoker; the handler passes `EngineAgentInvoker`. Same replan loop, different dispatch path.

The real discovery came during testing. The replan loop would decompose, run the driver, detect the failure, call `replan()`, get a revised plan — and the revised plan would immediately fail with "Sequence complete". No agents dispatched. The driver was returning `Unresolvable` from the routing strategy before a single agent ran.

`SequentialRouting` uses an `AtomicInteger` index that increments on each `route()` call. After the first plan execution with N agents, the index sits at N. The replanned execution creates a new driver, builds a new model, constructs new candidates — but reuses the same `SequentialRouting` instance from the base model. The index is already past the new candidate list. Every `route()` call immediately returns "Sequence complete".

The class looks immutable. It's a small object with no setters, no visible mutation surface. The state is buried inside `AtomicInteger` — a concurrency primitive that reads as "thread-safe" rather than "mutates on read." And the first execution always works, so tests that don't exercise reuse never catch it. The failure manifests as a routing decision, not a driver error — the stack trace points to the wrong layer entirely.

The fix is one line: create a fresh `SequentialRouting` in `HtnExecutor.buildLocalModel()` instead of reusing `base.routing()`. HTN always dispatches sequentially by topological order — the routing strategy is structural, not configurable. A fresh instance per execution makes the contract explicit: routing state is per-execution, not per-model.

The broader pattern is worth noting. Any stateful strategy object shared via an immutable-looking record can exhibit this. Records make the fields visible; they don't make the field values immutable. An `AtomicInteger` inside a record component is a time bomb for any code that assumes "same model, clean state."
