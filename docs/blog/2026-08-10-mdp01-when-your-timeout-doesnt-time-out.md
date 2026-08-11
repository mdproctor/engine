---
layout: post
title: "When Your Timeout Doesn't Actually Time Out"
date: 2026-08-10
entry_type: note
subtype: diary
projects: [casehub-engine]
tags: [mutiny, timeout, virtual-threads, quarkus, agentic-planning, constraints]
---

The agentic planning epic has four phases. Phase 1 landed the execution bridge — blocks patterns running inside the engine's worker boundary. Phase 2 is about giving those patterns resource awareness: how long can you run, how many agents can you use at once.

The design looked clean on paper. `PlanningConstraints` carries a time budget and a resource limit. The decomposition strategy gets them for prompt guidance — tell the LLM "you have 30 minutes and 3 agents, plan accordingly." The execution handler enforces them at runtime. Two enforcement points, two different mechanisms.

Resource limiting was straightforward. `ExecutionModel` is an immutable record with ten components — routing strategy, activation rule, aggregation, termination, and so on. To cap the number of agents dispatched per iteration, I wrapped the routing strategy:

```java
var capped = ctx -> original.route(ctx).map(decision -> {
    if (decision instanceof Selected selected
        && selected.agents().size() > limit) {
      return new Selected(selected.agents().subList(0, limit));
    }
    return decision;
});
return new ExecutionModel<>(capped, model.decomposition(),
    model.activation(), /* ... 7 more components */);
```

Records don't have `withRouting()` — you reconstruct the whole thing. Verbose but type-safe, and the compiler catches any mismatch. It's the Java equivalent of Kotlin's `copy()`, just without the syntax sugar.

Time budget is where things got interesting.

The blocks driver wraps its iteration loop in `Uni.createFrom().item(() -> { while (!cancelled) { ... } })`. The handler calls `.await().atMost(Duration.ofMillis(budget))` on the result. Mutiny's `atMost()` is documented as a timeout. The tests should have failed when the agent ran longer than the budget.

They didn't. The pattern completed successfully with a 50ms budget and a 200ms agent.

The issue: `Uni.createFrom().item(Supplier)` evaluates the supplier synchronously on the subscribing thread. When you call `.await()`, the current thread blocks inside the supplier. The `atMost()` timer is scheduled, but it can never fire — the thread that needs to handle the timeout is the same thread running the work. It's a timeout that can't time out.

This is fundamentally different from `Uni.createFrom().completionStage()` or anything using `emitOn()`, where the work runs on a different thread and the subscriber is free to handle the timer. The API gives no hint of this distinction. `atMost()` reads exactly the same regardless of how the Uni was created.

The fix: don't use Mutiny for the timeout. Run the driver on a virtual thread and use `Future.get(timeout, TimeUnit)`:

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    var future = executor.submit(() ->
        driver.execute(model, input).await().indefinitely());
    result = future.get(budgetMs, TimeUnit.MILLISECONDS);
} catch (TimeoutException e) {
    driver.cancel();
    return WorkerResult.expired("Time budget exceeded");
}
```

A second trap: `CompletableFuture.supplyAsync()` was the first attempt, but it uses the ForkJoinPool, which in Quarkus has the wrong classloader. The error was `JtaContextProvider not a subtype` — a classloader boundary issue that reads like a missing dependency. Virtual thread executors inherit the creating thread's classloader, so they work cleanly in Quarkus contexts.

The constraint threading through the decomposition layer was the unexciting part — a default method on `DecompositionContext`, a new component on `GoalDecompositionContext`, the YAML mapper reading `timeBudget` as an ISO-8601 Duration. Clean, backward-compatible, no surprises. The `LlmDecompositionStrategy` appends constraint text to the user prompt: "you have 30 minutes and 3 available agents." Informational, not enforced — the LLM produces a better plan when it knows the constraints, but the real enforcement happens at the driver level.

What I find satisfying about this design is the separation. The decomposition layer treats constraints as information — guidance for planning. The execution layer treats them as hard limits — the driver dies if it exceeds them. Same data, different semantics, at different layers. The `PlanningConstraints` record carries both roles without knowing which one applies.

Re-planning (#882) is next. When a step fails in an HTN pattern, the driver needs to revise the plan using only the remaining capabilities and whatever time budget is left. That remaining budget is going to flow through the same `PlanningConstraints` — but now it's a shrinking resource, not a static declaration. The constraints become live state.
