# io.casehub.api.model.SubCaseCompletionStrategy

**Package:** `io.casehub.api.model`

**Kind:** `interface`

Maps a child case terminal state to a stage item completion status. See casehubio/engine#195.

## Methods

### `public abstract io.casehub.api.model.SubCaseCompletionStrategy.ItemStatus mapToStageItemStatus(io.casehub.api.model.CaseStatus childCaseStatus)`

#### Parameters

- `childCaseStatus` (`io.casehub.api.model.CaseStatus`)
