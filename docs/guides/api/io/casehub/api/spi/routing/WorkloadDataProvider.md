# io.casehub.api.spi.routing.WorkloadDataProvider

**Package:** `io.casehub.api.spi.routing`

**Kind:** `interface`

Provides operational workload snapshots for candidate users. Used by `ConstraintHumanTaskRoutingStrategy` for per-candidate workload-based filtering and scoring.

<p>The default implementation (`NoOpWorkloadDataProvider`) returns an empty map, causing
workload constraints to degrade gracefully. Real implementations may be backed by `WorkerExecutionManager.getActiveCaseIds()` (engine-actor-state) or WorkItem query
(work-engine-adapter).

## Methods

### `public abstract java.util.Map<java.lang.String,io.casehub.api.spi.routing.WorkloadSnapshot> getWorkload(java.util.Set<java.lang.String> userIds, java.lang.String tenancyId)`

#### Parameters

- `userIds` (`java.util.Set<java.lang.String>`)
- `tenancyId` (`java.lang.String`)
