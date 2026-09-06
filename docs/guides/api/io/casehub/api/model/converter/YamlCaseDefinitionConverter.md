# io.casehub.api.model.converter.YamlCaseDefinitionConverter

**Package:** `io.casehub.api.model.converter`

**Kind:** `class`

Converts YAML records to the `CaseDefinition` domain model. Replaces the previous
hand-coded deserializers and post-processor.

## Fields

### `LOG` (`Logger`)

### `MAPPER` (`ObjectMapper`)

## Constructors

### `private YamlCaseDefinitionConverter()`

## Methods

### `private static void applyAgentDescriptors(java.util.List<YamlWorker> yamlWorkers, io.casehub.api.model.CaseDefinition def)`

#### Parameters

- `yamlWorkers` (`java.util.List<YamlWorker>`)
- `def` (`io.casehub.api.model.CaseDefinition`)

### `private static void applyContextTypeBridge(io.casehub.api.model.CaseDefinition def)`

#### Parameters

- `def` (`io.casehub.api.model.CaseDefinition`)

### `private static void applyGoapShorthand(java.util.List<YamlWorker> yamlWorkers, io.casehub.api.model.CaseDefinition def)`

#### Parameters

- `yamlWorkers` (`java.util.List<YamlWorker>`)
- `def` (`io.casehub.api.model.CaseDefinition`)

### `private static WorkerFunction<?,?> buildAgentFunction(YamlWorker yw)`

#### Parameters

- `yw` (`YamlWorker`)

### `private static WorkerFunction<?,?> buildTypedSyncFunction(YamlWorker yw)`

#### Parameters

- `yw` (`YamlWorker`)

### `public static io.casehub.api.model.CaseDefinition convert(YamlCaseDefinition yaml, io.casehub.api.engine.ExpressionEngineRegistry registry, io.casehub.api.spi.WorkerFunctionProviderRegistry providers)`

#### Parameters

- `yaml` (`YamlCaseDefinition`)
- `registry` (`io.casehub.api.engine.ExpressionEngineRegistry`)
- `providers` (`io.casehub.api.spi.WorkerFunctionProviderRegistry`)

### `static AgentDescriptor convertAgentDescriptor(io.casehub.api.model.converter.yaml.YamlAgentDescriptor yad, java.lang.String workerName)`

#### Parameters

- `yad` (`io.casehub.api.model.converter.yaml.YamlAgentDescriptor`)
- `workerName` (`java.lang.String`)

### `private static void convertBindings(java.util.List<YamlBinding> yamlBindings, io.casehub.api.model.CaseDefinition def, java.util.Map<java.lang.String,io.casehub.api.model.CapabilityTarget> capTargetMap)`

#### Parameters

- `yamlBindings` (`java.util.List<YamlBinding>`)
- `def` (`io.casehub.api.model.CaseDefinition`)
- `capTargetMap` (`java.util.Map<java.lang.String,io.casehub.api.model.CapabilityTarget>`)

### `private static java.util.Map<java.lang.String,io.casehub.api.model.CapabilityTarget> convertCapabilities(java.util.List<YamlCapability> caps, io.casehub.api.model.CaseDefinition def)`

#### Parameters

- `caps` (`java.util.List<YamlCapability>`)
- `def` (`io.casehub.api.model.CaseDefinition`)

### `private static io.casehub.api.model.ChannelDeclaration convertChannel(YamlChannel ych)`

#### Parameters

- `ych` (`YamlChannel`)

### `private static io.casehub.api.model.CompoundDeclaration convertCompound(YamlCompound yc)`

#### Parameters

- `yc` (`YamlCompound`)

### `private static io.casehub.engine.plan.TaskNode.CompoundTask<com.fasterxml.jackson.databind.JsonNode> convertCompoundNode(io.casehub.api.model.converter.yaml.YamlHtnNode node, io.casehub.api.engine.ExpressionEngineRegistry registry)`

#### Parameters

- `node` (`io.casehub.api.model.converter.yaml.YamlHtnNode`)
- `registry` (`io.casehub.api.engine.ExpressionEngineRegistry`)

### `private static io.casehub.api.model.routing.ContextConstraint convertContextConstraint(YamlContextConstraint ycc)`

#### Parameters

- `ycc` (`YamlContextConstraint`)

### `private static void convertDecomposition(io.casehub.api.model.converter.yaml.YamlDecomposition decomp, io.casehub.api.model.CaseDefinition def, io.casehub.api.engine.ExpressionEngineRegistry registry)`

#### Parameters

- `decomp` (`io.casehub.api.model.converter.yaml.YamlDecomposition`)
- `def` (`io.casehub.api.model.CaseDefinition`)
- `registry` (`io.casehub.api.engine.ExpressionEngineRegistry`)

### `private static ExecutionPolicy convertExecutionPolicy(YamlExecutionPolicy yep)`

#### Parameters

- `yep` (`YamlExecutionPolicy`)

### `private static io.casehub.engine.plan.goap.GoapAction convertGoapAction(YamlGoapAction ya)`

#### Parameters

- `ya` (`YamlGoapAction`)

### `private static io.casehub.engine.plan.DecompositionMethod<com.fasterxml.jackson.databind.JsonNode> convertHtnMethod(io.casehub.api.model.converter.yaml.YamlHtnMethod method, io.casehub.api.engine.ExpressionEngineRegistry registry)`

#### Parameters

- `method` (`io.casehub.api.model.converter.yaml.YamlHtnMethod`)
- `registry` (`io.casehub.api.engine.ExpressionEngineRegistry`)

### `private static io.casehub.api.model.JudgmentTarget convertHumanTaskToJudgment(YamlHumanTaskTarget ht, java.lang.String bindingName)`

#### Parameters

- `ht` (`YamlHumanTaskTarget`)
- `bindingName` (`java.lang.String`)

### `private static void convertInboundMappings(java.util.List<YamlInboundMapping> yamlMappings, io.casehub.api.model.CaseDefinition def)`

#### Parameters

- `yamlMappings` (`java.util.List<YamlInboundMapping>`)
- `def` (`io.casehub.api.model.CaseDefinition`)

### `private static io.casehub.api.model.JudgmentTarget convertJudgment(YamlJudgmentTarget yj, java.lang.String bindingName)`

#### Parameters

- `yj` (`YamlJudgmentTarget`)
- `bindingName` (`java.lang.String`)

### `private static void convertLabelRules(java.util.List<YamlLabelRule> yamlRules, io.casehub.api.model.CaseDefinition def)`

#### Parameters

- `yamlRules` (`java.util.List<YamlLabelRule>`)
- `def` (`io.casehub.api.model.CaseDefinition`)

### `private static io.casehub.api.model.MemoryRetrievalConfig convertMemoryRetrieval(YamlMemoryRetrievalConfig ym)`

#### Parameters

- `ym` (`YamlMemoryRetrievalConfig`)

### `private static io.casehub.api.model.Milestone convertMilestone(YamlMilestone ym)`

#### Parameters

- `ym` (`YamlMilestone`)

### `private static io.casehub.engine.plan.monitoring.MonitoringConfig convertMonitoring(YamlMonitoringConfig ym)`

#### Parameters

- `ym` (`YamlMonitoringConfig`)

### `private static io.casehub.api.model.OutcomePolicy convertOutcomePolicy(JsonNode node)`

#### Parameters

- `node` (`JsonNode`)

### `private static io.casehub.engine.plan.PlanningConstraints convertPlanningConstraints(YamlPlanningConstraints ypc)`

#### Parameters

- `ypc` (`YamlPlanningConstraints`)

### `private static io.casehub.api.spi.QuorumConfig convertQuorum(YamlQuorumConfig yq)`

#### Parameters

- `yq` (`YamlQuorumConfig`)

### `private static io.casehub.api.model.RecoveryOverride convertRecoveryOverride(YamlRecoveryOverride yro)`

#### Parameters

- `yro` (`YamlRecoveryOverride`)

### `private static io.casehub.api.model.RecoveryPolicy convertRecoveryPolicy(YamlRecoveryPolicy yrp)`

#### Parameters

- `yrp` (`YamlRecoveryPolicy`)

### `private static io.casehub.api.model.ReflectionTriggerConfig convertReflection(YamlReflectionTriggerConfig yr)`

#### Parameters

- `yr` (`YamlReflectionTriggerConfig`)

### `private static java.util.Map<java.lang.String,io.casehub.api.model.CapabilityTarget> convertSpec(YamlCaseSpec spec, io.casehub.api.model.CaseDefinition def)`

#### Parameters

- `spec` (`YamlCaseSpec`)
- `def` (`io.casehub.api.model.CaseDefinition`)

### `private static io.casehub.api.model.StallRecoveryPolicy convertStallRecoveryPolicy(com.fasterxml.jackson.databind.JsonNode node)`

#### Parameters

- `node` (`com.fasterxml.jackson.databind.JsonNode`)

### `private static io.casehub.api.model.SubCase convertSubCase(YamlSubCaseTarget ys)`

#### Parameters

- `ys` (`YamlSubCaseTarget`)

### `private static void convertTopLevel(YamlCaseDefinition yaml, io.casehub.api.model.CaseDefinition def)`

#### Parameters

- `yaml` (`YamlCaseDefinition`)
- `def` (`io.casehub.api.model.CaseDefinition`)

### `private static void convertWorkers(java.util.List<YamlWorker> yamlWorkers, io.casehub.api.model.CaseDefinition def, io.casehub.api.spi.WorkerFunctionProviderRegistry providers)`

#### Parameters

- `yamlWorkers` (`java.util.List<YamlWorker>`)
- `def` (`io.casehub.api.model.CaseDefinition`)
- `providers` (`io.casehub.api.spi.WorkerFunctionProviderRegistry`)

### `private static io.casehub.api.model.routing.WorkloadConstraint convertWorkloadConstraint(YamlWorkloadConstraint ywc)`

#### Parameters

- `ywc` (`YamlWorkloadConstraint`)

### `private static void linkGoalKinds(io.casehub.api.model.CaseDefinition def)`

#### Parameters

- `def` (`io.casehub.api.model.CaseDefinition`)

### `private static io.casehub.api.spi.routing.CandidateSetSpec resolveCandidateSet(JsonNode node)`

#### Parameters

- `node` (`JsonNode`)

### `private static void resolveTarget(YamlBinding yb, io.casehub.api.model.Binding.Builder builder, java.util.Map<java.lang.String,io.casehub.api.model.CapabilityTarget> capTargetMap)`

#### Parameters

- `yb` (`YamlBinding`)
- `builder` (`io.casehub.api.model.Binding.Builder`)
- `capTargetMap` (`java.util.Map<java.lang.String,io.casehub.api.model.CapabilityTarget>`)

### `private static java.util.Map<java.lang.String,java.lang.Boolean> toTrueBooleanMap(java.util.List<java.lang.String> keys)`

#### Parameters

- `keys` (`java.util.List<java.lang.String>`)

### `private static void validateExpression(ExpressionEvaluator evaluator, java.lang.String fieldName, java.lang.String bindingName)`

#### Parameters

- `evaluator` (`ExpressionEvaluator`)
- `fieldName` (`java.lang.String`)
- `bindingName` (`java.lang.String`)
