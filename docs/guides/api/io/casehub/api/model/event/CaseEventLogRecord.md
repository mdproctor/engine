# io.casehub.api.model.event.CaseEventLogRecord

**Package:** `io.casehub.api.model.event`

**Kind:** `record`

## Fields

### `eventType` (`io.casehub.api.model.event.CaseHubEventType`)

### `metadata` (`JsonNode`)

### `payload` (`JsonNode`)

### `streamType` (`io.casehub.api.model.event.EventStreamType`)

### `timestamp` (`java.time.Instant`)

## Record Components

### `eventType` (`io.casehub.api.model.event.CaseHubEventType`)

### `metadata` (`JsonNode`)

### `payload` (`JsonNode`)

### `streamType` (`io.casehub.api.model.event.EventStreamType`)

### `timestamp` (`java.time.Instant`)

## Constructors

### `public CaseEventLogRecord(io.casehub.api.model.event.CaseHubEventType eventType, io.casehub.api.model.event.EventStreamType streamType, java.time.Instant timestamp, JsonNode payload, JsonNode metadata)`

#### Parameters

- `eventType` (`io.casehub.api.model.event.CaseHubEventType`)
- `streamType` (`io.casehub.api.model.event.EventStreamType`)
- `timestamp` (`java.time.Instant`)
- `payload` (`JsonNode`)
- `metadata` (`JsonNode`)

## Methods

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public io.casehub.api.model.event.CaseHubEventType eventType()`

### `public final int hashCode()`

### `public JsonNode metadata()`

### `public JsonNode payload()`

### `public io.casehub.api.model.event.EventStreamType streamType()`

### `public java.time.Instant timestamp()`

### `public final java.lang.String toString()`
