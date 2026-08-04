# io.casehub.api.engine.LoopControl

**Package:** `io.casehub.api.engine`

**Kind:** `interface`

SPI for controlling which eligible bindings are selected for execution.

<p>Returns `List<Binding>` to allow synchronous selection. See casehubio/engine#76.

<p>The default implementation (`io.casehub.engine.internal.engine.ChoreographyLoopControl`)
wraps with a simple pass-through — no behaviour change.

## Methods

### `public abstract java.util.List<io.casehub.api.model.Binding> select(io.casehub.api.engine.PlanExecutionContext context, java.util.List<io.casehub.api.model.Binding> eligible)`

Select the bindings to fire from the set of bindings whose trigger conditions have already been
evaluated and matched. Implementations may augment the eligible set with bindings that become
eligible through planning evaluation (e.g., scope-activated bindings triggered by compound
activation or case start).

#### Parameters

- `context` (`io.casehub.api.engine.PlanExecutionContext`) — case identity, definition, and current case state — enables implementations to
    look up plan models without requiring access to internal engine structures
- `eligible` (`java.util.List<io.casehub.api.model.Binding>`) — bindings whose trigger conditions matched — may be empty, never null

#### Returns

the bindings to fire — may include scope-activated bindings not in the eligible input;
    may be empty, must not be null
