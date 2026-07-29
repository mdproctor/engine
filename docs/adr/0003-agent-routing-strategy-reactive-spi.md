# 0003 — AgentRoutingStrategy Returns Uni<AgentAssignment>

Date: 2026-05-29
Status: Superseded

Superseded by: Composable routing signal architecture (#790). Virtual threads removed
the reactive requirement — all routing strategies now execute synchronously on virtual
threads. `AgentRoutingStrategy.select()` returns `RoutingResult` directly.

## Context and Problem Statement

`AgentRoutingStrategy.select()` was initially synchronous, returning `AgentAssignment`
directly. When `SemanticAgentRoutingStrategy` was introduced (engine#376), its
implementations were expected to call external embedding services — a blocking I/O
operation. Calling blocking code from the Vert.x IO thread (where `@ConsumeEvent`
handlers run) is a hard violation, not a performance concern.

## Decision Drivers

* `SemanticAgentRoutingStrategy` needs to call a blocking embedding provider over HTTP
* `CaseContextChangedEventHandler` is a `@ConsumeEvent` handler running on the Vert.x IO thread
* Retrofitting a synchronous SPI to reactive later is a breaking multi-repo change
  requiring all call sites (handlers, tests, mocks) to be updated simultaneously
* At point of change there were exactly two implementations — the smallest possible
  migration cost

## Considered Options

* **Option A** — Return `Uni<AgentAssignment>` from the SPI (reactive from day one)
* **Option B** — Keep the SPI synchronous; mark `CaseContextChangedEventHandler` as `blocking = true`
* **Option C** — Keep the SPI synchronous; wrap `select()` calls in `Uni.createFrom().blocking()`
  at each call site

## Decision Outcome

Chosen option: **Option A**, because it places the threading contract in the SPI
itself — the only location that knows which implementations may block. Options B and C
scatter the threading decision to callers, all of whom would need updating individually
if the strategy ever moved to async I/O.

### Positive Consequences

* Embedding providers, external trust services, and other blocking implementations
  are safe to write without caller changes
* Call sites chain naturally into the existing `Uni<Void>` reactive pipeline in
  `CaseContextChangedEventHandler`
* In-memory strategies (`LeastLoadedAgentStrategy`, `TrustWeightedAgentStrategy`)
  use `Uni.createFrom().item(result)` — zero overhead

### Negative Consequences / Tradeoffs

* `@FunctionalInterface` annotation removed — lambda implementations must be typed
  explicitly (not inferred)
* `WorkOrchestrator.doSubmit()` uses `.await().indefinitely()` to bridge to its
  `CompletableFuture<WorkResult>` return — acceptable because it runs off the IO thread

## Pros and Cons of the Options

### Option A — Reactive SPI (`Uni<AgentAssignment>`)

* ✅ Blocking I/O is safe from any call site
* ✅ Reactive chain in handlers stays clean
* ✅ Correct threading contract at the abstraction boundary
* ❌ Removes `@FunctionalInterface`; lambda implementations need explicit typing

### Option B — Synchronous SPI, blocking handler

* ✅ SPI stays simple
* ❌ Entire handler dispatches to a worker thread, including all in-memory work before routing
* ❌ Threading decision buried in handler annotation, not the SPI

### Option C — Synchronous SPI, `Uni.createFrom().blocking()` at call sites

* ✅ SPI stays simple
* ❌ Every call site must be updated if blocking strategies arrive after a
  synchronous retrofit
* ❌ Two implementations updated → four call sites updated → higher migration cost

## Links

* engine#376 — SemanticAgentRoutingStrategy (trigger for this decision)
* PP-20260529-9f9627 — protocol: strategy SPIs that may block must return Uni<T>
* GE-20260529-ff186e — garden: correct Mutiny pattern for blocking I/O in reactive SPI
