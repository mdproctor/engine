# io.casehub.api.engine.CaseHubRuntime

**Package:** `io.casehub.api.engine`

**Kind:** `interface`

## Methods

### `public abstract void cancelCase(java.util.UUID caseId)`

Cancels a case. Valid from any non-terminal state (RUNNING, SUSPENDED, WAITING).

#### Parameters

- `caseId` (`java.util.UUID`)

#### Throws

- `IllegalArgumentException` — if the case is not found
- `IllegalStateException` — if the case is already in a terminal state

### `public default void cancelCase(java.util.UUID caseId, java.lang.String tenancyId)`

Cancels a case with tenant isolation. Valid from any non-terminal state (RUNNING, SUSPENDED,
WAITING).

#### Parameters

- `caseId` (`java.util.UUID`) — the case identifier
- `tenancyId` (`java.lang.String`) — the tenant identifier

#### Throws

- `IllegalArgumentException` — if the case is not found or the tenancyId does not match
- `IllegalStateException` — if the case is already in a terminal state

### `public abstract java.util.List<io.casehub.api.model.event.CaseEventLogRecord> eventLog(java.util.UUID caseId)`

Retrieves all event log records for a case, ordered by sequence number ascending.

#### Parameters

- `caseId` (`java.util.UUID`) — the case identifier

#### Returns

the list of all event log records for the case

#### Throws

- `IllegalArgumentException` — if the case is not found

### `public abstract java.util.List<io.casehub.api.model.event.CaseEventLogRecord> eventLog(java.util.UUID caseId, java.util.Set<io.casehub.api.model.event.CaseHubEventType> eventTypes)`

Retrieves event log records for a case filtered by event types, ordered by sequence number
ascending.

#### Parameters

- `caseId` (`java.util.UUID`) — the case identifier
- `eventTypes` (`java.util.Set<io.casehub.api.model.event.CaseHubEventType>`) — set of event types to filter by; if null or empty, no filtering is applied

#### Returns

the list of filtered event log records

#### Throws

- `IllegalArgumentException` — if the case is not found

### `public abstract java.util.List<io.casehub.api.model.event.CaseEventLogRecord> eventLog(java.util.UUID caseId, java.util.Set<io.casehub.api.model.event.CaseHubEventType> eventTypes, java.util.Set<io.casehub.api.model.event.EventStreamType> streamTypes)`

Retrieves event log records for a case filtered by event types and stream types, ordered by
sequence number ascending.

#### Parameters

- `caseId` (`java.util.UUID`) — the case identifier
- `eventTypes` (`java.util.Set<io.casehub.api.model.event.CaseHubEventType>`) — set of event types to filter by; if null or empty, no filtering is applied
- `streamTypes` (`java.util.Set<io.casehub.api.model.event.EventStreamType>`) — set of stream types to filter by; if null or empty, no filtering is applied

#### Returns

the list of filtered event log records

#### Throws

- `IllegalArgumentException` — if the case is not found

### `public abstract java.lang.Object query(java.util.UUID caseId, java.lang.String path)`

#### Parameters

- `caseId` (`java.util.UUID`)
- `path` (`java.lang.String`)

### `public abstract T query(java.util.UUID caseId, java.lang.String path, java.lang.Class<T> clazz)`

#### Parameters

- `caseId` (`java.util.UUID`)
- `path` (`java.lang.String`)
- `clazz` (`java.lang.Class<T>`)

### `public abstract void resumeCase(java.util.UUID caseId)`

Resumes a suspended case and re-evaluates context so eligible workers can fire.

#### Parameters

- `caseId` (`java.util.UUID`)

#### Throws

- `IllegalArgumentException` — if the case is not found
- `IllegalStateException` — if the case is not in SUSPENDED state

### `public default void resumeCase(java.util.UUID caseId, java.lang.String tenancyId)`

Resumes a suspended case with tenant isolation and re-evaluates context so eligible workers can
fire.

#### Parameters

- `caseId` (`java.util.UUID`) — the case identifier
- `tenancyId` (`java.lang.String`) — the tenant identifier

#### Throws

- `IllegalArgumentException` — if the case is not found or the tenancyId does not match
- `IllegalStateException` — if the case is not in SUSPENDED state

### `public default void signal(java.util.UUID caseId, io.casehub.api.model.SignalType<T> signalType, T payload)`

#### Parameters

- `caseId` (`java.util.UUID`)
- `signalType` (`io.casehub.api.model.SignalType<T>`)
- `payload` (`T`)

### `public abstract void signal(java.util.UUID caseId, java.lang.String path, java.lang.Object value)`

Signals a case context update. Returns when the signal has been applied to the context, the
event log written, and `CONTEXT_CHANGED` dispatched. It does NOT guarantee that goal
evaluation has completed — callers that need to await case state transitions should use
Awaitility on the case status.

<p>Refs casehubio/engine#493.

#### Parameters

- `caseId` (`java.util.UUID`)
- `path` (`java.lang.String`)
- `value` (`java.lang.Object`)

### `public default void signal(java.util.UUID caseId, java.lang.String path, java.lang.Object value, java.lang.String triggerChannelId, java.lang.String triggerCorrelationId)`

Signals a case context update with Qhorus trigger context for causal lineage.

<p>When a Qhorus COMMAND triggers a context update (e.g. Claudony notifying the engine that a
worker has sent a response), the triggering COMMAND's `channelId` and `correlationId` can be threaded through to `ProvisionContext` so the provisioner can
establish causal linkage in the ledger. Both fields are nullable — pass `null` when the
signal is not triggered by a Qhorus COMMAND.

<p>Refs casehubio/engine#231, casehubio/engine#493, claudony#94.

#### Parameters

- `caseId` (`java.util.UUID`)
- `path` (`java.lang.String`)
- `value` (`java.lang.Object`)
- `triggerChannelId` (`java.lang.String`)
- `triggerCorrelationId` (`java.lang.String`)

### `public default void signal(java.util.UUID caseId, java.lang.String path, java.lang.Object value, java.util.Map<java.lang.String,java.lang.Object> signalMetadata)`

Signals a case context update with caller-provided metadata that is stored in the EventLog
entry. Use for cross-case provenance linking — callers pass causedByCaseId and causedByEvent.

#### Parameters

- `caseId` (`java.util.UUID`)
- `path` (`java.lang.String`)
- `value` (`java.lang.Object`)
- `signalMetadata` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `public default void signal(java.util.UUID caseId, java.util.Map<java.lang.String,java.lang.Object> updates)`

Atomically signals multiple context updates. Returns when all updates have been applied to the
context, the event log written, and `CONTEXT_CHANGED` dispatched.

<p>Use this instead of multiple `signal()` calls to avoid intermediate state where only
some keys have been updated.

<p>Refs casehubio/engine#483.

#### Parameters

- `caseId` (`java.util.UUID`)
- `updates` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `public default io.casehub.api.context.CaseContext signalAndAwait(java.util.UUID caseId, java.util.Map<java.lang.String,java.lang.Object> updates, java.time.Duration timeout)`

Signals multiple context updates and waits for all triggered workers to complete. Returns the
final `CaseContext` when:

<ul>
  <li>All updates have been applied
  <li>All capability bindings triggered by the context change have been dispatched
  <li>All dispatched workers have completed (success or failure)
</ul>

<p>If no workers are dispatched (no bindings match), returns immediately with the updated
context.

<p>Throws `SettlementTimeoutException` if settlement does not complete within the
specified duration.

<p>Refs casehubio/engine#483.

#### Parameters

- `caseId` (`java.util.UUID`)
- `updates` (`java.util.Map<java.lang.String,java.lang.Object>`)
- `timeout` (`java.time.Duration`)

### `public abstract java.util.UUID startCase(io.casehub.api.model.CaseDefinition definition)`

#### Parameters

- `definition` (`io.casehub.api.model.CaseDefinition`)

### `public abstract java.util.UUID startCase(io.casehub.api.model.CaseDefinition definition, java.lang.Object inputData)`

#### Parameters

- `definition` (`io.casehub.api.model.CaseDefinition`)
- `inputData` (`java.lang.Object`)

### `public abstract java.util.UUID startCase(io.casehub.api.model.CaseDefinition definition, java.lang.Object inputData, java.util.Map<java.lang.String,java.lang.Object> semanticData)`

#### Parameters

- `definition` (`io.casehub.api.model.CaseDefinition`)
- `inputData` (`java.lang.Object`)
- `semanticData` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `public abstract java.util.UUID startCase(io.casehub.api.model.CaseDefinition definition, java.lang.Object inputData, java.util.Map<java.lang.String,java.lang.Object> semanticData, java.util.UUID parentCaseId, io.casehub.api.context.PropagationContext propagationContext)`

#### Parameters

- `definition` (`io.casehub.api.model.CaseDefinition`)
- `inputData` (`java.lang.Object`)
- `semanticData` (`java.util.Map<java.lang.String,java.lang.Object>`)
- `parentCaseId` (`java.util.UUID`)
- `propagationContext` (`io.casehub.api.context.PropagationContext`)

### `public abstract java.util.UUID startCase(io.casehub.api.model.CaseDefinition definition, java.lang.Object inputData, java.util.UUID parentCaseId, io.casehub.api.context.PropagationContext propagationContext)`

#### Parameters

- `definition` (`io.casehub.api.model.CaseDefinition`)
- `inputData` (`java.lang.Object`)
- `parentCaseId` (`java.util.UUID`)
- `propagationContext` (`io.casehub.api.context.PropagationContext`)

### `public abstract void suspendCase(java.util.UUID caseId)`

Suspends a running case. No new workers will fire while the case is suspended.

#### Parameters

- `caseId` (`java.util.UUID`)

#### Throws

- `IllegalArgumentException` — if the case is not found
- `IllegalStateException` — if the case is not in RUNNING state

### `public default void suspendCase(java.util.UUID caseId, java.lang.String tenancyId)`

Suspends a running case with tenant isolation. No new workers will fire while the case is
suspended.

#### Parameters

- `caseId` (`java.util.UUID`) — the case identifier
- `tenancyId` (`java.lang.String`) — the tenant identifier

#### Throws

- `IllegalArgumentException` — if the case is not found or the tenancyId does not match
- `IllegalStateException` — if the case is not in RUNNING state
