# io.casehub.api.spi.PostRequest

**Package:** `io.casehub.api.spi`

**Kind:** `record`

## Fields

### `content` (`java.lang.String`)

### `correlationId` (`java.lang.String`)

### `deadline` (`java.lang.String`)

### `from` (`java.lang.String`)

### `target` (`java.lang.String`)

### `topic` (`java.lang.String`)

### `type` (`MessageType`)

## Record Components

### `content` (`java.lang.String`)

### `correlationId` (`java.lang.String`)

### `deadline` (`java.lang.String`)

### `from` (`java.lang.String`)

### `target` (`java.lang.String`)

### `topic` (`java.lang.String`)

### `type` (`MessageType`)

## Constructors

### `public PostRequest(java.lang.String from, java.lang.String content, MessageType type, java.lang.String correlationId, java.lang.String deadline, java.lang.String target, java.lang.String topic)`

#### Parameters

- `from` (`java.lang.String`)
- `content` (`java.lang.String`)
- `type` (`MessageType`)
- `correlationId` (`java.lang.String`)
- `deadline` (`java.lang.String`)
- `target` (`java.lang.String`)
- `topic` (`java.lang.String`)

## Methods

### `public static io.casehub.api.spi.PostRequest.Builder builder(java.lang.String content, MessageType type)`

#### Parameters

- `content` (`java.lang.String`)
- `type` (`MessageType`)

### `public java.lang.String content()`

### `public java.lang.String correlationId()`

### `public java.lang.String deadline()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public java.lang.String from()`

### `public final int hashCode()`

### `public java.lang.String target()`

### `public final java.lang.String toString()`

### `public java.lang.String topic()`

### `public MessageType type()`
