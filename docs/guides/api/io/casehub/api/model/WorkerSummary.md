# io.casehub.api.model.WorkerSummary

**Package:** `io.casehub.api.model`

**Kind:** `record`

Lightweight view of a prior worker's execution, extracted from a CaseLedgerEntry.

<p>`ledgerEntryId` is the UUID of the `WORKER_EXECUTION_COMPLETED` ledger entry — new
workers set `causedByEntryId` on their own ledger entries to this value to establish causal
lineage. Both `ledgerEntryId` and `outputSummary` are nullable.

## Fields

### `completedAt` (`java.time.Instant`)

### `ledgerEntryId` (`java.util.UUID`)

### `outputSummary` (`java.lang.String`)

### `startedAt` (`java.time.Instant`)

### `workerId` (`java.lang.String`)

### `workerName` (`java.lang.String`)

## Record Components

### `completedAt` (`java.time.Instant`)

### `ledgerEntryId` (`java.util.UUID`)

### `outputSummary` (`java.lang.String`)

### `startedAt` (`java.time.Instant`)

### `workerId` (`java.lang.String`)

### `workerName` (`java.lang.String`)

## Constructors

### `public WorkerSummary(java.lang.String workerId, java.lang.String workerName, java.time.Instant startedAt, java.time.Instant completedAt, java.lang.String outputSummary, java.util.UUID ledgerEntryId)`

#### Parameters

- `workerId` (`java.lang.String`)
- `workerName` (`java.lang.String`)
- `startedAt` (`java.time.Instant`)
- `completedAt` (`java.time.Instant`)
- `outputSummary` (`java.lang.String`)
- `ledgerEntryId` (`java.util.UUID`)

## Methods

### `public java.time.Instant completedAt()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public java.util.UUID ledgerEntryId()`

### `public java.lang.String outputSummary()`

### `public java.time.Instant startedAt()`

### `public final java.lang.String toString()`

### `public java.lang.String workerId()`

### `public java.lang.String workerName()`
