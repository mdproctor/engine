# io.casehub.api.spi.CaseEventRequest

**Package:** `io.casehub.api.spi`

**Kind:** `record`

## Fields

### `caseId` (`java.util.UUID`)

### `metadata` (`JsonNode`)

### `payload` (`JsonNode`)

### `stream` (`io.casehub.api.model.event.EventStreamType`)

### `tenancyId` (`java.lang.String`)

### `type` (`io.casehub.api.model.event.CaseHubEventType`)

### `workerId` (`java.lang.String`)

## Record Components

### `caseId` (`java.util.UUID`)

### `metadata` (`JsonNode`)

### `payload` (`JsonNode`)

### `stream` (`io.casehub.api.model.event.EventStreamType`)

### `tenancyId` (`java.lang.String`)

### `type` (`io.casehub.api.model.event.CaseHubEventType`)

### `workerId` (`java.lang.String`)

## Constructors

### `public CaseEventRequest(java.util.UUID caseId, io.casehub.api.model.event.CaseHubEventType type, io.casehub.api.model.event.EventStreamType stream, java.lang.String workerId, java.lang.String tenancyId, JsonNode payload, JsonNode metadata)`

#### Parameters

- `caseId` (`java.util.UUID`)
- `type` (`io.casehub.api.model.event.CaseHubEventType`)
- `stream` (`io.casehub.api.model.event.EventStreamType`)
- `workerId` (`java.lang.String`)
- `tenancyId` (`java.lang.String`)
- `payload` (`JsonNode`)
- `metadata` (`JsonNode`)

## Methods

### `public java.util.UUID caseId()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public JsonNode metadata()`

### `public JsonNode payload()`

### `public io.casehub.api.model.event.EventStreamType stream()`

### `public java.lang.String tenancyId()`

### `public final java.lang.String toString()`

### `public io.casehub.api.model.event.CaseHubEventType type()`

### `public java.lang.String workerId()`
