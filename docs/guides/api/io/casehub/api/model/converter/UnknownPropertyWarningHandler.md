# io.casehub.api.model.converter.UnknownPropertyWarningHandler

**Package:** `io.casehub.api.model.converter`

**Kind:** `class`

Logs a WARNING for unknown YAML properties during case definition deserialization. Does not throw
— `FAIL_ON_UNKNOWN_PROPERTIES` stays disabled for `additionalProperties:true` types.

## Fields

### `INSTANCE` (`io.casehub.api.model.converter.UnknownPropertyWarningHandler`)

### `LOG` (`Logger`)

## Constructors

### `private UnknownPropertyWarningHandler()`

## Methods

### `public boolean handleUnknownProperty(DeserializationContext ctxt, JsonParser p, JsonDeserializer<?> deserializer, java.lang.Object beanOrClass, java.lang.String propertyName)`

#### Parameters

- `ctxt` (`DeserializationContext`)
- `p` (`JsonParser`)
- `deserializer` (`JsonDeserializer<?>`)
- `beanOrClass` (`java.lang.Object`)
- `propertyName` (`java.lang.String`)
