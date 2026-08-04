# io.casehub.api.model.Binding

**Package:** `io.casehub.api.model`

**Kind:** `class`

## Fields

### `conflictResolverStrategy` (`java.lang.String`)

### `contextWrite` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `executionMode` (`io.casehub.api.model.ExecutionMode`)

### `inputProjectionOverride` (`java.lang.String`)

### `lifecycleScope` (`io.casehub.api.model.LifecycleScope`)

### `name` (`java.lang.String`)

### `on` (`io.casehub.api.model.Trigger`)

### `outcomePolicy` (`io.casehub.api.model.OutcomePolicy`)

### `participation` (`io.casehub.api.model.Participation`)

### `permissionIntent` (`java.util.List<io.casehub.api.model.acl.WorkerAction>`)

### `producedKeys` (`java.util.Set<java.lang.String>`)

### `target` (`io.casehub.api.model.BindingTarget`)

### `when` (`ExpressionEvaluator`)

## Constructors

### `private Binding(java.lang.String name, io.casehub.api.model.BindingTarget target, io.casehub.api.model.Trigger on)`

#### Parameters

- `name` (`java.lang.String`)
- `target` (`io.casehub.api.model.BindingTarget`)
- `on` (`io.casehub.api.model.Trigger`)

## Methods

### `public static io.casehub.api.model.Binding.Builder builder()`

### `public java.lang.String effectiveInputProjection(Capability capability)`

#### Parameters

- `capability` (`Capability`)

### `public io.casehub.api.model.ExecutionMode executionMode()`

### `public java.lang.String getConflictResolverStrategy()`

Strategy name for resolving concurrent writes to the same CaseContext key. Values:
"LAST_WRITER_WINS" (default), "FIRST_WRITER_WINS", "FAIL". Null means use the default
(LAST_WRITER_WINS). See casehubio/engine#45, #51.

### `public java.util.Map<java.lang.String,java.lang.Object> getContextWrite()`

### `public java.lang.String getInputProjectionOverride()`

### `public java.lang.String getName()`

### `public io.casehub.api.model.Trigger getOn()`

### `public io.casehub.api.model.OutcomePolicy getOutcomePolicy()`

### `public java.util.List<io.casehub.api.model.acl.WorkerAction> getPermissionIntent()`

### `public java.util.Set<java.lang.String> getProducedKeys()`

Keys this binding declares it will produce. Used for static analysis and audit trail. Empty by
default. Overlaps within the same stage trigger a validation warning.

### `public ExpressionEvaluator getWhen()`

### `public io.casehub.api.model.LifecycleScope lifecycleScope()`

### `public io.casehub.api.model.Participation participation()`

### `public void setConflictResolverStrategy(java.lang.String conflictResolverStrategy)`

#### Parameters

- `conflictResolverStrategy` (`java.lang.String`)

### `public void setContextWrite(java.util.Map<java.lang.String,java.lang.Object> contextWrite)`

#### Parameters

- `contextWrite` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `public void setExecutionMode(io.casehub.api.model.ExecutionMode executionMode)`

#### Parameters

- `executionMode` (`io.casehub.api.model.ExecutionMode`)

### `public void setInputProjectionOverride(java.lang.String inputProjectionOverride)`

#### Parameters

- `inputProjectionOverride` (`java.lang.String`)

### `public void setLifecycleScope(io.casehub.api.model.LifecycleScope lifecycleScope)`

#### Parameters

- `lifecycleScope` (`io.casehub.api.model.LifecycleScope`)

### `public void setOutcomePolicy(io.casehub.api.model.OutcomePolicy outcomePolicy)`

#### Parameters

- `outcomePolicy` (`io.casehub.api.model.OutcomePolicy`)

### `public void setParticipation(io.casehub.api.model.Participation participation)`

#### Parameters

- `participation` (`io.casehub.api.model.Participation`)

### `public void setPermissionIntent(java.util.List<io.casehub.api.model.acl.WorkerAction> permissionIntent)`

#### Parameters

- `permissionIntent` (`java.util.List<io.casehub.api.model.acl.WorkerAction>`)

### `public void setProducedKeys(java.util.Set<java.lang.String> producedKeys)`

#### Parameters

- `producedKeys` (`java.util.Set<java.lang.String>`)

### `public void setWhen(ExpressionEvaluator when)`

#### Parameters

- `when` (`ExpressionEvaluator`)

### `public io.casehub.api.model.BindingTarget target()`
