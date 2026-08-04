# io.casehub.api.model.SlaStartFrom

**Package:** `io.casehub.api.model`

**Kind:** `enum`

Defines when SLA deadline calculation starts for a milestone.

<ul>
  <li><b>CASE_CREATED</b> — SLA starts from case creation timestamp
  <li><b>MILESTONE_ACTIVATED</b> — SLA starts from PENDING → ACTIVE transition (default)
</ul>

## Enum Constants

### `CASE_CREATED` (`io.casehub.api.model.SlaStartFrom`)

### `MILESTONE_ACTIVATED` (`io.casehub.api.model.SlaStartFrom`)

## Constructors

### `private SlaStartFrom()`

## Methods

### `public static io.casehub.api.model.SlaStartFrom valueOf(java.lang.String name)`

#### Parameters

- `name` (`java.lang.String`)

### `public static io.casehub.api.model.SlaStartFrom[] values()`
