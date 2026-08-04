# io.casehub.api.spi.routing.HumanTaskCandidates

**Package:** `io.casehub.api.spi.routing`

**Kind:** `record`

## Fields

### `groupMembership` (`java.util.Map<java.lang.String,java.util.Set<java.lang.String>>`)

### `groups` (`java.util.Set<java.lang.String>`)

### `users` (`java.util.Set<java.lang.String>`)

## Record Components

### `groupMembership` (`java.util.Map<java.lang.String,java.util.Set<java.lang.String>>`)

### `groups` (`java.util.Set<java.lang.String>`)

### `users` (`java.util.Set<java.lang.String>`)

## Constructors

### `public HumanTaskCandidates(java.util.Set<java.lang.String> groups, java.util.Set<java.lang.String> users, java.util.Map<java.lang.String,java.util.Set<java.lang.String>> groupMembership)`

#### Parameters

- `groups` (`java.util.Set<java.lang.String>`)
- `users` (`java.util.Set<java.lang.String>`)
- `groupMembership` (`java.util.Map<java.lang.String,java.util.Set<java.lang.String>>`)

## Methods

### `public java.util.Set<java.lang.String> allUsers()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public java.util.Map<java.lang.String,java.util.Set<java.lang.String>> groupMembership()`

### `public java.util.Set<java.lang.String> groups()`

### `public final int hashCode()`

### `public static io.casehub.api.spi.routing.HumanTaskCandidates of(java.util.Set<java.lang.String> groups, java.util.Set<java.lang.String> users)`

#### Parameters

- `groups` (`java.util.Set<java.lang.String>`)
- `users` (`java.util.Set<java.lang.String>`)

### `public final java.lang.String toString()`

### `public java.util.Set<java.lang.String> users()`
