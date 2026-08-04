# io.casehub.api.model.acl.WorkerCredential

**Package:** `io.casehub.api.model.acl`

**Kind:** `record`

## Fields

### `actions` (`java.util.Set<io.casehub.api.model.acl.WorkerAction>`)

### `actorId` (`java.lang.String`)

### `caseId` (`java.util.UUID`)

### `createdAt` (`java.time.Instant`)

### `expiresAt` (`java.time.Instant`)

### `token` (`java.lang.String`)

## Record Components

### `actions` (`java.util.Set<io.casehub.api.model.acl.WorkerAction>`)

### `actorId` (`java.lang.String`)

### `caseId` (`java.util.UUID`)

### `createdAt` (`java.time.Instant`)

### `expiresAt` (`java.time.Instant`)

### `token` (`java.lang.String`)

## Constructors

### `public WorkerCredential(java.lang.String token, java.lang.String actorId, java.util.UUID caseId, java.util.Set<io.casehub.api.model.acl.WorkerAction> actions, java.time.Instant expiresAt, java.time.Instant createdAt)`

#### Parameters

- `token` (`java.lang.String`)
- `actorId` (`java.lang.String`)
- `caseId` (`java.util.UUID`)
- `actions` (`java.util.Set<io.casehub.api.model.acl.WorkerAction>`)
- `expiresAt` (`java.time.Instant`)
- `createdAt` (`java.time.Instant`)

## Methods

### `public java.util.Set<io.casehub.api.model.acl.WorkerAction> actions()`

### `public java.lang.String actorId()`

### `public java.util.UUID caseId()`

### `public java.time.Instant createdAt()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public java.time.Instant expiresAt()`

### `public final int hashCode()`

### `public boolean isExpired()`

### `public final java.lang.String toString()`

### `public java.lang.String token()`
