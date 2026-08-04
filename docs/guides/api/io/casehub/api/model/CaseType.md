# io.casehub.api.model.CaseType

**Package:** `io.casehub.api.model`

**Kind:** `annotation`

CDI qualifier for case-type-scoped injection.

<p>Enables case definitions to declare type-specific dependencies that are resolved at case start
time. The `value()` matches `CaseDefinition.types()` paths.

<p>Example:

<pre>`@ApplicationScoped
@CaseType("clinical/screening")
public class ScreeningOrchestrator implements CaseOutcomeObserver {
    // ...`
}</pre>

<p>The `value()` is `Nonbinding` — CDI does not use it for bean selection. Runtime
resolution uses `CaseDefinition.types()` to filter discovered beans.

## Methods

### `public abstract java.lang.String value()`
