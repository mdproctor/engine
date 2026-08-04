# io.casehub.api.spi.WorkerStatusListener

**Package:** `io.casehub.api.spi`

**Kind:** `interface`

Receives lifecycle callbacks from worker runtimes.

<p>Allows external provisioners to notify CaseHub when a worker starts, completes, or stalls.
Implementations must be idempotent — callbacks may arrive more than once due to retries or
at-least-once delivery.

## Methods

### `public abstract void onWorkerCompleted(java.lang.String workerId, io.casehub.api.model.WorkResult result)`

Called when a worker has completed its assigned work.

#### Parameters

- `workerId` (`java.lang.String`) — the worker name/ID
- `result` (`io.casehub.api.model.WorkResult`) — the work result including status, output, and correlation key; must not be null

### `public abstract void onWorkerStalled(java.lang.String workerId)`

Called when a worker has stalled. CaseEngine may reassign, retry, or escalate.

#### Parameters

- `workerId` (`java.lang.String`) — the worker name/ID

### `public abstract void onWorkerStarted(java.lang.String workerId, java.util.Map<java.lang.String,java.lang.String> sessionMeta)`

Called when a provisioned worker has started and is ready for work.

#### Parameters

- `workerId` (`java.lang.String`) — the worker name/ID
- `sessionMeta` (`java.util.Map<java.lang.String,java.lang.String>`) — implementation-specific metadata (e.g. tmux session ID). May be null.
