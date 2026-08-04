# io.casehub.api.model.CaseChannel

**Package:** `io.casehub.api.model`

**Kind:** `record`

Opaque reference to a communication channel for workers on a case.

<p>Backend-agnostic: a Qhorus implementation sets `backendType = "qhorus"` and populates
`properties` with Qhorus-specific metadata (e.g. endpoint URL). The `properties` map
is always immutable.

<p>Channel names follow the convention `"case-{caseId`/{purpose}"} — use String) to construct and `.CASE_CHANNEL_PREFIX` to identify them. Both
the `CaseChannelProvider` implementation and the signal bridge rely on this format;
changing it here propagates to both.

## Fields

### `CASE_CHANNEL_PREFIX` (`java.lang.String`)

Prefix shared by all case-scoped Qhorus channel names.

### `backendType` (`java.lang.String`)

### `id` (`java.lang.String`)

### `name` (`java.lang.String`)

### `properties` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `purpose` (`java.lang.String`)

## Record Components

### `backendType` (`java.lang.String`)

### `id` (`java.lang.String`)

### `name` (`java.lang.String`)

### `properties` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `purpose` (`java.lang.String`)

## Constructors

### `public CaseChannel(java.lang.String id, java.lang.String name, java.lang.String purpose, java.lang.String backendType, java.util.Map<java.lang.String,java.lang.Object> properties)`

#### Parameters

- `id` (`java.lang.String`)
- `name` (`java.lang.String`)
- `purpose` (`java.lang.String`)
- `backendType` (`java.lang.String`)
- `properties` (`java.util.Map<java.lang.String,java.lang.Object>`)

## Methods

### `public java.lang.String backendType()`

### `public static java.lang.String channelName(java.util.UUID caseId, java.lang.String purpose)`

Constructs the canonical channel name for a case and purpose. Format: `"case-{caseId`/{purpose}"}.

#### Parameters

- `caseId` (`java.util.UUID`)
- `purpose` (`java.lang.String`)

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public java.lang.String id()`

### `public java.lang.String name()`

### `public static java.lang.String oversightChannelName(java.util.UUID caseId)`

Constructs the canonical oversight channel name for a case. Equivalent to `channelName(caseId, "oversight")`.

<p>Oversight channels carry human governance decisions. See protocol `qhorus-per-entity-governance-channels.md`.

#### Parameters

- `caseId` (`java.util.UUID`)

### `public static java.util.UUID parseCaseId(java.lang.String channelName)`

Extracts the case UUID from a channel name that follows the `"case-{caseId`/{purpose}"}
convention.

#### Parameters

- `channelName` (`java.lang.String`) — the channel name; may be null

#### Returns

the case UUID, or null if channelName is null, does not start with `.CASE_CHANNEL_PREFIX`, or the UUID segment is malformed

### `public java.util.Map<java.lang.String,java.lang.Object> properties()`

### `public java.lang.String purpose()`

### `public final java.lang.String toString()`
