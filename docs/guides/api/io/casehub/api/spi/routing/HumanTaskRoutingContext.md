# io.casehub.api.spi.routing.HumanTaskRoutingContext

**Package:** `io.casehub.api.spi.routing`

**Kind:** `record`

Routing context passed to `HumanTaskRoutingStrategy.select`. Carries everything the
strategy needs for decision-making, excluding the candidates it chooses from.

## Fields

### `bindingName` (`java.lang.String`)

### `caseContext` (`io.casehub.api.context.CaseContext`)

### `caseDefinition` (`io.casehub.api.model.CaseDefinition`)

### `caseId` (`java.util.UUID`)

### `experiences` (`java.util.List<io.casehub.api.spi.routing.RetrievedExperience>`)

### `tenancyId` (`java.lang.String`)

## Record Components

### `bindingName` (`java.lang.String`)

the binding name — matching key for plan trace analysis (equivalent to
    capabilityName for agent routing)

### `caseContext` (`io.casehub.api.context.CaseContext`)

the current case context (strategies needing the JSON form call `caseContext.layer(ContextLayer.WORKING).asJsonNode()`)

### `caseDefinition` (`io.casehub.api.model.CaseDefinition`)

the case definition — gives strategies access to definition-level
    configuration (constraints, routing config) without strategy-specific context fields

### `caseId` (`java.util.UUID`)

the case instance UUID

### `experiences` (`java.util.List<io.casehub.api.spi.routing.RetrievedExperience>`)

retrieved similar cases from CBR (empty list if CBR is not configured)

### `tenancyId` (`java.lang.String`)

the tenant owning the case

## Constructors

### `public HumanTaskRoutingContext(java.util.UUID caseId, java.lang.String bindingName, java.lang.String tenancyId, io.casehub.api.context.CaseContext caseContext, io.casehub.api.model.CaseDefinition caseDefinition, java.util.List<io.casehub.api.spi.routing.RetrievedExperience> experiences)`

#### Parameters

- `caseId` (`java.util.UUID`)
- `bindingName` (`java.lang.String`)
- `tenancyId` (`java.lang.String`)
- `caseContext` (`io.casehub.api.context.CaseContext`)
- `caseDefinition` (`io.casehub.api.model.CaseDefinition`)
- `experiences` (`java.util.List<io.casehub.api.spi.routing.RetrievedExperience>`)

## Methods

### `public java.lang.String bindingName()`

### `public io.casehub.api.context.CaseContext caseContext()`

### `public io.casehub.api.model.CaseDefinition caseDefinition()`

### `public java.util.UUID caseId()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public java.util.List<io.casehub.api.spi.routing.RetrievedExperience> experiences()`

### `public final int hashCode()`

### `public java.lang.String tenancyId()`

### `public final java.lang.String toString()`
