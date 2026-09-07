# io.casehub.api.spi.StepOutcomeObserver

**Package:** `io.casehub.api.spi`

**Kind:** `interface`

Lifecycle hook called by the engine after each worker execution step completes.

<p>The engine discovers all `@ApplicationScoped StepOutcomeObserver` beans via CDI and
calls `.onStepOutcome(StepOutcomeEvent)` for each when a worker execution finishes — on
both success and failure paths. Implementations record per-step CBR cases, update step-level
metrics, or perform other step-outcome-based learning operations.

<p><strong>Activation:</strong> declare an `@ApplicationScoped` bean implementing this
interface. No module dependency is required beyond `casehub-engine-api`. The engine
discovers all implementations automatically.

<p><strong>Blocking:</strong> `onStepOutcome()` is called on a virtual thread (the handler
uses `@RunOnVirtualThread`). Implementations may perform blocking work directly, including
JPA writes and `@Transactional` operations. The engine catches and logs all exceptions
thrown by observers without propagating them.

<p>Refs casehubio/engine#1050.

## Methods

### `public abstract void onStepOutcome(io.casehub.api.spi.StepOutcomeEvent event)`

Called after a worker execution step completes.

#### Parameters

- `event` (`io.casehub.api.spi.StepOutcomeEvent`) — structured outcome carrying step identity, context snapshot, and outcome
