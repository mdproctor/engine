# io.casehub.api.spi.mesh.CaseChannelLayout.ChannelSpec

**Package:** `io.casehub.api.spi.mesh`

**Kind:** `record`

Specification for a single Qhorus channel in the agent mesh.

## Fields

### `allowedTypes` (`java.util.Set<MessageType>`)

### `deniedTypes` (`java.util.Set<MessageType>`)

### `description` (`java.lang.String`)

### `purpose` (`java.lang.String`)

### `semantic` (`ChannelSemantic`)

## Record Components

### `allowedTypes` (`java.util.Set<MessageType>`)

message types permitted; `null` = all types allowed. Callers must
    pass an unmodifiable set (e.g. `Set.of`) — not defensively copied; all standard
    implementations use `Set.of`.

### `deniedTypes` (`java.util.Set<MessageType>`)

message types explicitly denied; `null` = no denial. Denial wins when
    a type appears in both sets. If a new `MessageType` is added with no commitment
    effect (like EVENT), add it here for governance channels — this comment is the mechanical
    anchor for that obligation. Same unmodifiable-set contract as `allowedTypes`.

### `description` (`java.lang.String`)

human-readable channel description

### `purpose` (`java.lang.String`)

channel name suffix; e.g. `"work"`, `"observe"`, `"oversight"`

### `semantic` (`ChannelSemantic`)

channel semantic; always `ChannelSemantic.APPEND` for mesh channels

## Constructors

### `public ChannelSpec(java.lang.String purpose, ChannelSemantic semantic, java.util.Set<MessageType> allowedTypes, java.util.Set<MessageType> deniedTypes, java.lang.String description)`

#### Parameters

- `purpose` (`java.lang.String`)
- `semantic` (`ChannelSemantic`)
- `allowedTypes` (`java.util.Set<MessageType>`)
- `deniedTypes` (`java.util.Set<MessageType>`)
- `description` (`java.lang.String`)

## Methods

### `public java.util.Set<MessageType> allowedTypes()`

### `public java.util.Set<MessageType> deniedTypes()`

### `public java.lang.String description()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public java.lang.String purpose()`

### `public ChannelSemantic semantic()`

### `public final java.lang.String toString()`
