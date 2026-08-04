# io.casehub.api.model.WorkerFunctions

**Package:** `io.casehub.api.model`

**Kind:** `class`

Utilities for composing worker functions into larger flows.

## Constructors

### `private WorkerFunctions()`

## Methods

### `public static java.util.Map<java.lang.String,java.lang.Object> merge(java.util.Map<java.lang.String,java.lang.Object> base, java.util.Map<java.lang.String,java.lang.Object> overlay)`

Merges two maps, with overlay keys overwriting base keys.

#### Parameters

- `base` (`java.util.Map<java.lang.String,java.lang.Object>`) — the base map
- `overlay` (`java.util.Map<java.lang.String,java.lang.Object>`) — the overlay map

#### Returns

a new LinkedHashMap with base entries followed by overlay entries

### `public static WorkerFunction.Sync<java.util.Map<java.lang.String,java.lang.Object>,java.util.Map<java.lang.String,java.lang.Object>> sequence(WorkerFunction<?,?>[] steps)`

#### Parameters

- `steps` (`WorkerFunction<?,?>[]`)
