# io.casehub.api.model.Binding

**Package:** `io.casehub.api.model`

**Kind:** `class`

## Fields

### `compensateRef` (`java.lang.String`)

### `compensation` (`boolean`)

### `conflictResolverStrategy` (`java.lang.String`)

### `consumes` (`java.lang.String`)

### `contextWrite` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `contingency` (`java.util.List<java.lang.String>`)

### `exchangeProjectionExpression` (`java.lang.String`)

### `exchangeProjectionStrategy` (`java.lang.String`)

### `executionMode` (`io.casehub.api.model.ExecutionMode`)

### `inputProjectionOverride` (`ExpressionEvaluator`)

### `lifecycleScope` (`io.casehub.api.model.LifecycleScope`)

### `name` (`java.lang.String`)

### `on` (`io.casehub.api.model.Trigger`)

### `outcomePolicy` (`io.casehub.api.model.OutcomePolicy`)

### `participation` (`io.casehub.api.model.Participation`)

### `permissionIntent` (`java.util.List<WorkerAction>`)

### `producedKeys` (`java.util.Set<java.lang.String>`)

### `produces` (`java.lang.String`)

### `recoveryOverride` (`io.casehub.api.model.RecoveryOverride`)

### `replanHint` (`io.casehub.api.model.ReplanHint`)

### `requiredKeys` (`java.util.Set<java.lang.String>`)

### `sideEffectClassification` (`io.casehub.api.model.SideEffectClassification`)

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

### `public ExpressionEvaluator effectiveInputProjection(io.casehub.api.model.CapabilityTarget capTarget)`

#### Parameters

- `capTarget` (`io.casehub.api.model.CapabilityTarget`)

### `public io.casehub.api.model.ExecutionMode executionMode()`

### `public java.lang.String getCompensateRef()`

### `public java.lang.String getConflictResolverStrategy()`

Strategy name for resolving concurrent writes to the same CaseContext key. Values:
"LAST_WRITER_WINS" (default), "FIRST_WRITER_WINS", "FAIL". Null means use the default
(LAST_WRITER_WINS). See casehubio/engine#45, #51.

### `public java.lang.String getConsumes()`

### `public java.util.Map<java.lang.String,java.lang.Object> getContextWrite()`

### `public java.util.List<java.lang.String> getContingency()`

### `public java.lang.String getExchangeProjectionExpression()`

### `public java.lang.String getExchangeProjectionStrategy()`

### `public ExpressionEvaluator getInputProjectionOverride()`

### `public java.lang.String getName()`

### `public io.casehub.api.model.Trigger getOn()`

### `public io.casehub.api.model.OutcomePolicy getOutcomePolicy()`

### `public java.util.List<WorkerAction> getPermissionIntent()`

### `public java.util.Set<java.lang.String> getProducedKeys()`

Keys this binding declares it will produce. Used for static analysis and audit trail. Empty by
default. Overlaps within the same stage trigger a validation warning.

### `public java.lang.String getProduces()`

### `public io.casehub.api.model.RecoveryOverride getRecoveryOverride()`

### `public io.casehub.api.model.ReplanHint getReplanHint()`

### `public java.util.Set<java.lang.String> getRequiredKeys()`

### `public io.casehub.api.model.SideEffectClassification getSideEffectClassification()`

### `public ExpressionEvaluator getWhen()`

### `public boolean isCompensation()`

### `public io.casehub.api.model.LifecycleScope lifecycleScope()`

### `public io.casehub.api.model.Participation participation()`

### `public void setCompensateRef(java.lang.String compensateRef)`

#### Parameters

- `compensateRef` (`java.lang.String`)

### `public void setCompensation(boolean compensation)`

#### Parameters

- `compensation` (`boolean`)

### `public void setConflictResolverStrategy(java.lang.String conflictResolverStrategy)`

#### Parameters

- `conflictResolverStrategy` (`java.lang.String`)

### `public void setConsumes(java.lang.String consumes)`

#### Parameters

- `consumes` (`java.lang.String`)

### `public void setContextWrite(java.util.Map<java.lang.String,java.lang.Object> contextWrite)`

#### Parameters

- `contextWrite` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `public void setContingency(java.util.List<java.lang.String> contingency)`

#### Parameters

- `contingency` (`java.util.List<java.lang.String>`)

### `public void setExchangeProjectionExpression(java.lang.String exchangeProjectionExpression)`

#### Parameters

- `exchangeProjectionExpression` (`java.lang.String`)

### `public void setExchangeProjectionStrategy(java.lang.String exchangeProjectionStrategy)`

#### Parameters

- `exchangeProjectionStrategy` (`java.lang.String`)

### `public void setExecutionMode(io.casehub.api.model.ExecutionMode executionMode)`

#### Parameters

- `executionMode` (`io.casehub.api.model.ExecutionMode`)

### `public void setInputProjectionOverride(ExpressionEvaluator inputProjectionOverride)`

#### Parameters

- `inputProjectionOverride` (`ExpressionEvaluator`)

### `public void setLifecycleScope(io.casehub.api.model.LifecycleScope lifecycleScope)`

#### Parameters

- `lifecycleScope` (`io.casehub.api.model.LifecycleScope`)

### `public void setOutcomePolicy(io.casehub.api.model.OutcomePolicy outcomePolicy)`

#### Parameters

- `outcomePolicy` (`io.casehub.api.model.OutcomePolicy`)

### `public void setParticipation(io.casehub.api.model.Participation participation)`

#### Parameters

- `participation` (`io.casehub.api.model.Participation`)

### `public void setPermissionIntent(java.util.List<WorkerAction> permissionIntent)`

#### Parameters

- `permissionIntent` (`java.util.List<WorkerAction>`)

### `public void setProducedKeys(java.util.Set<java.lang.String> producedKeys)`

#### Parameters

- `producedKeys` (`java.util.Set<java.lang.String>`)

### `public void setProduces(java.lang.String produces)`

#### Parameters

- `produces` (`java.lang.String`)

### `public void setRecoveryOverride(io.casehub.api.model.RecoveryOverride recoveryOverride)`

#### Parameters

- `recoveryOverride` (`io.casehub.api.model.RecoveryOverride`)

### `public void setReplanHint(io.casehub.api.model.ReplanHint replanHint)`

#### Parameters

- `replanHint` (`io.casehub.api.model.ReplanHint`)

### `public void setRequiredKeys(java.util.Set<java.lang.String> requiredKeys)`

#### Parameters

- `requiredKeys` (`java.util.Set<java.lang.String>`)

### `public void setSideEffectClassification(io.casehub.api.model.SideEffectClassification sideEffectClassification)`

#### Parameters

- `sideEffectClassification` (`io.casehub.api.model.SideEffectClassification`)

### `public void setWhen(ExpressionEvaluator when)`

#### Parameters

- `when` (`ExpressionEvaluator`)

### `public io.casehub.api.model.BindingTarget target()`

### `public static java.util.List<java.lang.String> validateCompensationBindings(java.util.List<io.casehub.api.model.Binding> bindings)`

#### Parameters

- `bindings` (`java.util.List<io.casehub.api.model.Binding>`)
