# io.casehub.api.model.InboundSignalMapping

**Package:** `io.casehub.api.model`

**Kind:** `record`

Declares a mapping from an inbound connector message to a typed case signal.

<p>Declared on `CaseDefinition`. At runtime, `InboundSignalBridge` matches incoming
`InboundMessage` events by `connectorType`, evaluates the `correlation`
expression to find the case, evaluates the `payload` expression to extract typed data, and
delivers a typed signal via `CaseHubRuntime.signal()`.

## Fields

### `connectorType` (`java.lang.String`)

### `correlation` (`ExpressionEvaluator`)

### `correlationResolver` (`java.lang.String`)

### `payload` (`ExpressionEvaluator`)

### `signalName` (`java.lang.String`)

## Record Components

### `connectorType` (`java.lang.String`)

### `correlation` (`ExpressionEvaluator`)

### `correlationResolver` (`java.lang.String`)

### `payload` (`ExpressionEvaluator`)

### `signalName` (`java.lang.String`)

## Constructors

### `public InboundSignalMapping(java.lang.String signalName, java.lang.String connectorType, ExpressionEvaluator correlation, ExpressionEvaluator payload, java.lang.String correlationResolver)`

#### Parameters

- `signalName` (`java.lang.String`)
- `connectorType` (`java.lang.String`)
- `correlation` (`ExpressionEvaluator`)
- `payload` (`ExpressionEvaluator`)
- `correlationResolver` (`java.lang.String`)

## Methods

### `public static io.casehub.api.model.InboundSignalMapping.Builder builder()`

### `public java.lang.String connectorType()`

### `public ExpressionEvaluator correlation()`

### `public java.lang.String correlationResolver()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public ExpressionEvaluator payload()`

### `public java.lang.String signalName()`

### `public final java.lang.String toString()`
