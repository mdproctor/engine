# io.casehub.api.model.cbr.CbrCaseTypeRegistration

**Package:** `io.casehub.api.model.cbr`

**Kind:** `interface`

CDI marker interface for registering custom CBR case subtypes with `CbrRetrievalService`.
Implementations are discovered via `@Inject @All Instance<CbrCaseTypeRegistration>` and
merged into the built-in type map at construction time.

<p>A single registration may override a built-in mapping (e.g., "plan" →
CustomPlanCbrCase.class), but two registrations claiming the same `cbrType()` key throw
`IllegalStateException` at construction — fail-fast, no silent override.

## Methods

### `public abstract java.lang.Class<?> caseClass()`

The Java class to use for deserialization when retrieving cases of this type.

### `public abstract java.lang.String cbrType()`

The CBR type discriminator — matches `CbrCase.cbrType()`.
