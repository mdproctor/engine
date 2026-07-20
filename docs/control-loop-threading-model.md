# Control Loop Architecture and Threading Model

**Date:** 2026-07-20
**Context:** ADR-0005 (parent) — virtual threads replace reactive tier

---

## The Two Design Axes

The original unified design (April 2026) identified that "synchronous vs async"
conflates two independent architectural decisions:

1. **Who decides what fires next?** Central PlanningStrategy (deliberative) vs
   decentralised choreography (reactive). This is the control model.
2. **Does execution block threads?** This is the threading model.

CaseHub chose choreography-by-default for axis 1 (with a deliberative overlay via
the blackboard module), and non-blocking reactive for axis 2 (Mutiny Uni on the
Vert.x event bus).

ADR-0005 changes axis 2 — from Mutiny Uni to virtual threads — without touching
axis 1. This document explains why that separation holds.

---

## The Control Loop

The engine's execution cycle is event-driven:

```
Context change (any source)
  → CaseContextChangedEvent on Vert.x EventBus
    → evaluate binding trigger conditions (JQ/Lambda)
      → loopControl.select(eligible bindings)
        → dispatch workers / human tasks / sub-cases
          → worker completes, writes result to context
            → CaseContextChangedEvent → cycle repeats
```

This is the architectural improvement over the synchronous blackboard (casehub-poc),
which used a blocking `while(RUNNING)` loop that processed one plan item at a time
on a pooled thread. The event-driven model:

- **Handles external stimuli naturally.** CloudEvents, signals, sub-case completions,
  and context changes from any source all enter through the same event bus. The
  synchronous loop had no clean way to receive external input mid-cycle.
- **Dispatches concurrently.** Multiple eligible bindings fire in parallel — the
  fan-out happens within a single evaluation cycle, not one-at-a-time.
- **Re-evaluates on every change.** Each context change triggers a fresh evaluation
  cycle. The loop doesn't need to be told why it was invoked — it sees the current
  state and eligible bindings.
- **Composes with deliberative control.** The `LoopControl` SPI lets the blackboard
  module intercept the eligible set and apply PlanningStrategy reasoning (stage
  gating, priority selection, resource budgets) without changing the event-driven
  substrate.

None of these properties depend on Mutiny Uni. They depend on the Vert.x event bus
and the handler/dispatch/event architecture.

---

## What Uni Was Doing

`CaseContextChangedEventHandler` (the core loop) is annotated
`@ConsumeEvent(blocking = true)` — it runs on a Vert.x **worker thread**, not the
IO thread. Despite this, every method returns `Uni<Void>` and uses Uni composition:

| Uni pattern in the handler | Purpose | Virtual thread equivalent |
|---------------------------|---------|--------------------------|
| `.chain(() -> goals(...))` | Sequential composition | Plain method call |
| `Uni.combine().all().unis(list).discardItems()` | Fan-out (parallel dispatch) | `StructuredTaskScope` |
| `.onFailure().recoverWithUni()` | Typed error recovery | try-catch |
| `.onFailure().invoke(t -> LOG.error(...))` | Error logging | catch block |
| `Uni.createFrom().voidItem()` | Early return | `return` |
| `reactiveWorkerContextProvider.buildContext(...)` | Reactive SPI call | Blocking SPI call |

Because the handler is `blocking = true`, the Uni chain is **structural convenience,
not IO-thread safety**. The handler blocks a worker thread regardless. The reactive
programming model adds complexity without preventing thread starvation — virtual
threads prevent thread starvation by making blocked threads cheap.

---

## What Changes

### Threading model (axis 2)

| Before | After |
|--------|-------|
| `@ConsumeEvent(blocking = true)` + `Uni<Void>` | `@ConsumeEvent` on virtual thread + `void` |
| `Uni.combine().all().unis(list)` | `StructuredTaskScope` for fan-out |
| `.chain()` / `.flatMap()` | Sequential method calls |
| `.onFailure().recoverWithUni()` | try-catch |
| `ReactiveWorkerContextProvider` | `WorkerContextProvider` (blocking, on virtual thread) |
| `ReactiveWorkerProvisioner` | `WorkerProvisioner` (blocking, on virtual thread) |
| `PlanningStrategy.select()` returns `Uni<List<Binding>>` | Returns `List<Binding>` (blocking calls like LLM/DB are fine on virtual threads) |
| Dual-tier SPIs (`FooStore` + `ReactiveFooStore`) | Single-tier (`FooStore` only) |
| `@IfBuildProperty` reactive gating | Removed |
| `BlockingReactiveParityTest` | Removed |

### Control model (axis 1) — unchanged

| Property | Status |
|----------|--------|
| Event-driven evaluation cycle | **Unchanged** — Vert.x event bus still delivers `CaseContextChangedEvent` |
| Concurrent binding dispatch | **Unchanged** — `StructuredTaskScope` forks just like `Uni.combine().all()` |
| Re-evaluation on every context change | **Unchanged** — event bus delivery is independent of threading model |
| External stimuli (signals, CloudEvents) | **Unchanged** — event bus handles all sources |
| `LoopControl` SPI (choreography vs deliberative) | **Unchanged** — the SPI selects bindings regardless of how they execute |
| `PlanningStrategy` deliberative overlay | **Unchanged** — strategies filter/reorder bindings; execution model is irrelevant |
| Stage gating and lifecycle | **Unchanged** — `PlanningStrategyLoopControl` evaluates stage predicates before dispatch |
| Durable execution (Quartz) | **Unchanged** — Quartz job scheduling is independent of Uni |
| Sub-case orchestration | **Unchanged** — `SubCaseCompletionService` and group policies are event-driven |
| Worker provisioning | **Simplified** — one SPI variant instead of two |

---

## Why the Separation Holds

The synchronous blackboard's limitation was not that it used threads — it was that
the **control model was synchronous**: evaluate → pick one → execute → wait → repeat.
External stimuli couldn't enter mid-cycle. Concurrent dispatch wasn't possible.
Re-evaluation required the loop to complete.

The engine's improvement was making the **control model event-driven**: any context
change from any source triggers re-evaluation, multiple bindings dispatch concurrently,
and the cycle repeats on completion — all driven by events, not a while-loop.

Uni was the implementation vehicle for expressing this within a handler method. But
the event-driven architecture is in the **event bus topology** (who publishes what,
who subscribes, what triggers re-evaluation), not in the Uni return types.

With virtual threads:
- The event bus still delivers events asynchronously
- The handler still evaluates bindings and dispatches concurrently
- Workers still complete independently and trigger new events
- The cycle still repeats on every context change

The control loop is event-driven because of the event bus, not because of Uni.

---

## What Stays Reactive

Uni/Multi remain in use where the programming model genuinely fits:

| Pattern | Why it stays |
|---------|-------------|
| Reactive Messaging (Kafka, AMQP) | SmallRye Reactive Messaging API is `Multi<T>` by design |
| `Multi<T>` streaming with backpressure | No virtual thread equivalent for backpressure |
| Postgres LISTEN/NOTIFY broadcasters | Event-driven pub/sub — reactive PG client is correct |
| SSE endpoints | Already `@RunOnVirtualThread` — no change needed |

These are streaming/messaging patterns where the reactive programming model provides
backpressure and stream composition that virtual threads do not replicate.

---

## References

| Document | Location |
|----------|----------|
| ADR-0005 | `parent/docs/adr/0005-virtual-threads-replace-reactive-tier.md` |
| Hybrid sync/async analysis | `engine/docs/hybrid-sync-async-analysis.md` |
| Original unified design | `casehub-poc/docs/superpowers/specs/2026-04-09-casehub-unified-design.md` |
| Key source: control loop | `engine/runtime/.../handler/CaseContextChangedEventHandler.java` |
| Key source: loop control SPI | `engine/api/.../engine/LoopControl.java` |
| Key source: planning strategy | `engine/blackboard/.../PlanningStrategy.java` |
