# io.casehub.api.spi.WorkerExecutionGuard

**Package:** `io.casehub.api.spi`

**Kind:** `interface`

SPI: determines whether a worker is allowed to execute for a given case.

<p>The engine's default implementation (`AllowAllWorkerExecutionGuard`) always permits
execution. The `casehub-resilience` module provides an `@Alternative` implementation
that blocks quarantined workers (PoisonPill detection).

<p>Implementations are CDI beans. Override via `@Alternative @Priority`.

## Methods

### `public abstract boolean isBlocked(java.lang.String workerId, java.util.UUID caseId)`

Returns `true` if the worker is blocked and must NOT be scheduled.

#### Parameters

- `workerId` (`java.lang.String`) — the worker name
- `caseId` (`java.util.UUID`) — the case instance ID
