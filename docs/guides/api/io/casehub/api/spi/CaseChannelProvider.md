# io.casehub.api.spi.CaseChannelProvider

**Package:** `io.casehub.api.spi`

**Kind:** `interface`

Creates and manages communication channels for workers on a case.

<p>The `CaseChannel` record carries a `backendType` field and extensible `properties` map so implementations can attach backend-specific metadata without coupling the SPI
to any particular channel system.

## Methods

### `public abstract void closeChannel(io.casehub.api.model.CaseChannel channel)`

Close a channel. No-op if the channel is unknown or already closed.

#### Parameters

- `channel` (`io.casehub.api.model.CaseChannel`) — the channel to close

### `public abstract java.util.List<io.casehub.api.model.CaseChannel> listChannels(java.util.UUID caseId)`

List all channels currently open for the given case.

#### Parameters

- `caseId` (`java.util.UUID`) — the case instance ID

#### Returns

list of open channels, empty if none

### `public abstract io.casehub.api.model.CaseChannel openChannel(java.util.UUID caseId, java.lang.String purpose)`

Open or retrieve a channel for the given case and purpose.

<p><strong>Idempotency contract:</strong> calling this method more than once with the same
`caseId` and `purpose` must not throw and must return a usable channel. The engine
calls `openChannel` on every worker dispatch event (`WorkerScheduleEventHandler`)
and also at case start (`CaseStartedEventHandler`). Implementations must treat this as
get-or-create, not unconditional create. See casehubio/engine#323.

#### Parameters

- `caseId` (`java.util.UUID`) — the case instance ID
- `purpose` (`java.lang.String`) — human-readable description of the channel's purpose

#### Returns

the opened channel reference

### `public default void postToChannel(io.casehub.api.model.CaseChannel channel, io.casehub.api.spi.PostRequest request)`

Post a message to a channel.

#### Parameters

- `channel` (`io.casehub.api.model.CaseChannel`) — the channel reference returned by `.openChannel`
- `request` (`io.casehub.api.spi.PostRequest`) — the message content and metadata

### `public default void postToChannel(io.casehub.api.model.CaseChannel channel, java.lang.String from, java.lang.String content)`

Post a message to a channel. Delegates to String, String,
MessageType, String, String, String) with `type`, `correlationId`, `deadline`, and `target` all `null`.

#### Parameters

- `channel` (`io.casehub.api.model.CaseChannel`)
- `from` (`java.lang.String`)
- `content` (`java.lang.String`)

### `public abstract void postToChannel(io.casehub.api.model.CaseChannel channel, java.lang.String from, java.lang.String content, MessageType type, java.lang.String correlationId, java.lang.String deadline, java.lang.String target)`

Post a message to a channel.

#### Parameters

- `channel` (`io.casehub.api.model.CaseChannel`) — the channel reference returned by `.openChannel`
- `from` (`java.lang.String`) — sender identity (worker ID or "human")
- `content` (`java.lang.String`) — message content
- `type` (`MessageType`) — the intent type of the message (e.g. `MessageType.COMMAND`); `null` if
    unspecified
- `correlationId` (`java.lang.String`) — correlation identifier for causal linkage (e.g. eventLogId); `null`
    if unspecified
- `deadline` (`java.lang.String`) — ISO-8601 deadline for temporal obligation tracking; `null` if no deadline
- `target` (`java.lang.String`) — the intended recipient (e.g. worker name / agent ID); `null` if untargeted
