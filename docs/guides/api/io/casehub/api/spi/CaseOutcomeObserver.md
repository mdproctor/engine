# io.casehub.api.spi.CaseOutcomeObserver

**Package:** `io.casehub.api.spi`

**Kind:** `interface`

Lifecycle hook called by the engine when a case closes with a terminal outcome.

<p>The engine discovers all `@ApplicationScoped CaseOutcomeObserver` beans via CDI and
calls `.onOutcome(CaseOutcomeEvent)` for each when a case reaches COMPLETED, FAULTED, or
CANCELLED status. Implementations write a CBR case entry to `CaseMemoryStore`, update trust
scores, or perform other outcome-based learning operations.

<p><strong>Activation:</strong> declare an `@ApplicationScoped` bean implementing this
interface. No module dependency is required beyond `casehub-engine-api`. The engine
discovers all implementations automatically.

<p><strong>Idempotency:</strong> the engine makes no idempotency guarantees. Implementations
should handle duplicate calls gracefully (e.g. using unique case IDs for dedup).

<p><strong>Blocking:</strong> `onOutcome()` is called on a Vert.x worker thread (the
handler uses `blocking = true`). Implementations may perform blocking work directly,
including JPA writes and `@Transactional` operations. The engine catches and logs all
exceptions thrown by observers without propagating them.

<p>Refs casehubio/engine#477 (CBR Retain step — part of casehubio/parent#227).

## Methods

### `public abstract void onOutcome(io.casehub.api.spi.CaseOutcomeEvent event)`

Called when a case closes with a terminal outcome.

#### Parameters

- `event` (`io.casehub.api.spi.CaseOutcomeEvent`) — structured outcome carrying case type, context snapshot, and terminal label
