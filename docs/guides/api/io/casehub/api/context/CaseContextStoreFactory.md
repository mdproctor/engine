# io.casehub.api.context.CaseContextStoreFactory

**Package:** `io.casehub.api.context`

**Kind:** `interface`

Factory that creates `CaseContextStore` instances per layer per case. Resolved per case
definition via `StrategyResolver` (same `NamedStrategy` pattern as routing
strategies).

## Methods

### `public abstract io.casehub.api.context.CaseContextStore createStore(java.lang.String layerName, java.util.UUID caseId)`

Creates an empty store for a new case.

#### Parameters

- `layerName` (`java.lang.String`)
- `caseId` (`java.util.UUID`)

### `public default boolean isDurable()`

Whether stores produced by this factory survive JVM restarts. When true, recovery uses
loadStore() directly — no EventLog replay. When false (default), recovery replays EventLog to
reconstruct state.

### `public default io.casehub.api.context.CaseContextStore loadStore(java.lang.String layerName, java.util.UUID caseId)`

Loads a store for an existing case. For persistent stores, the returned store is pre-populated
with the persisted state. For volatile stores, returns an empty store (same as createStore).

#### Parameters

- `layerName` (`java.lang.String`)
- `caseId` (`java.util.UUID`)
