# io.casehub.api.engine.CaseHub

**Package:** `io.casehub.api.engine`

**Kind:** `class`

## Fields

### `runtime` (`io.casehub.api.engine.CaseHubRuntime`)

## Constructors

### `public CaseHub()`

## Methods

### `public void cancelCase(java.util.UUID caseId)`

#### Parameters

- `caseId` (`java.util.UUID`)

### `public abstract io.casehub.api.model.CaseDefinition getDefinition()`

### `public java.lang.Object query(java.util.UUID caseId, java.lang.String path)`

#### Parameters

- `caseId` (`java.util.UUID`)
- `path` (`java.lang.String`)

### `public T query(java.util.UUID caseId, java.lang.String path, java.lang.Class<T> clazz)`

#### Parameters

- `caseId` (`java.util.UUID`)
- `path` (`java.lang.String`)
- `clazz` (`java.lang.Class<T>`)

### `public void resumeCase(java.util.UUID caseId)`

#### Parameters

- `caseId` (`java.util.UUID`)

### `public void signal(java.util.UUID caseId, java.lang.String path, java.lang.Object value)`

#### Parameters

- `caseId` (`java.util.UUID`)
- `path` (`java.lang.String`)
- `value` (`java.lang.Object`)

### `public java.util.UUID startCase()`

### `public java.util.UUID startCase(java.lang.Object inputData)`

Start a case with arbitrary serializable input.

<p>Accepts any Jackson-serializable object (POJO, `Map<String, Object>`, etc.). The input
is converted to the case context via `com.fasterxml.jackson.databind.ObjectMapper`. If a
`Map` is passed, its value types must be JSON-compatible (i.e., not typed collections
with non-Object values) — a raw `Map<String, Object>` passes through as-is.

#### Parameters

- `inputData` (`java.lang.Object`)

### `public void suspendCase(java.util.UUID caseId)`

#### Parameters

- `caseId` (`java.util.UUID`)
