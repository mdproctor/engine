# io.casehub.api.spi.WorkerContextProvider

**Package:** `io.casehub.api.spi`

**Kind:** `interface`

Builds startup context for a new worker.

<p>Implementations query `CaseLedgerEntryRepository` (not EventLog) for prior worker
history, constructing `io.casehub.api.model.WorkerSummary` entries with `ledgerEntryId` populated so new workers can set `causedByEntryId` on their own ledger
entries.

<p>Implementations also populate `WorkerContext.channels()` by calling `CaseChannelProvider.listChannels(caseId)`, giving workers access to the channels open for their
case during execution via `io.casehub.api.model.WorkerExecutionContext.current()`.

## Methods

### `public abstract io.casehub.api.model.WorkerContext buildContext(java.lang.String workerId, java.util.UUID caseId, io.casehub.api.model.WorkRequest task)`

Build context for a worker about to start work on a task.

#### Parameters

- `workerId` (`java.lang.String`) — the ID of the worker being started
- `caseId` (`java.util.UUID`) — the ID of the case the worker is executing for; may be `null` for
    provisioning-only flows where no live case exists yet
- `task` (`io.casehub.api.model.WorkRequest`) — the work request describing what the worker should do

#### Returns

startup context including task description, open channels, and lineage

### `public default io.casehub.api.model.WorkerContext buildContext(java.lang.String workerId, java.util.UUID caseId, io.casehub.api.model.WorkRequest task, io.casehub.api.context.PropagationContext parentContext)`

Build context for a worker, inheriting identity and tracing from the parent case's `PropagationContext`. Callers should prefer this overload when a live case instance is available
so that traceId, inherited attributes (userId, roles), and budget/deadline propagate to the
worker via `PropagationContext.createChild()`.

<p>The default delegates to the 3-arg overload for backward compatibility with existing
implementations.

#### Parameters

- `workerId` (`java.lang.String`) — the ID of the worker being started
- `caseId` (`java.util.UUID`) — the ID of the case the worker is executing for; may be `null`
- `task` (`io.casehub.api.model.WorkRequest`) — the work request describing what the worker should do
- `parentContext` (`io.casehub.api.context.PropagationContext`) — the parent case's propagation context carrying identity and tracing

#### Returns

startup context with propagation context inherited from the parent
