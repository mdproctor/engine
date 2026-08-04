# io.casehub.api.model.DefaultSubCaseCompletionStrategy

**Package:** `io.casehub.api.model`

**Kind:** `class`

Standard mapping: COMPLETED→COMPLETED, FAULTED→FAULTED, all others→TERMINATED. See
casehubio/engine#195.

## Constructors

### `public DefaultSubCaseCompletionStrategy()`

## Methods

### `public io.casehub.api.model.SubCaseCompletionStrategy.ItemStatus mapToStageItemStatus(io.casehub.api.model.CaseStatus childCaseStatus)`

#### Parameters

- `childCaseStatus` (`io.casehub.api.model.CaseStatus`)
