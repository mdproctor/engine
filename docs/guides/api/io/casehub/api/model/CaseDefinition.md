# io.casehub.api.model.CaseDefinition

**Package:** `io.casehub.api.model`

**Kind:** `class`

## Fields

### `agentDescriptors` (`java.util.Map<java.lang.String,AgentDescriptor>`)

### `agentRouting` (`java.lang.String`)

### `authorization` (`java.util.Map<AclAction,java.util.List<java.lang.String>>`)

### `bindings` (`java.util.List<io.casehub.api.model.Binding>`)

### `candidateMatching` (`java.lang.String`)

### `capabilities` (`java.util.List<Capability>`)

### `cbrConfig` (`io.casehub.api.model.cbr.CbrConfig`)

### `cognitiveDemands` (`java.util.Map<java.lang.String,io.casehub.api.model.CognitiveDemand>`)

### `completion` (`io.casehub.api.model.CaseCompletion`)

### `contextStoreFactory` (`java.lang.String`)

### `decompositionStrategy` (`java.lang.String`)

### `defaultQuorum` (`io.casehub.api.spi.QuorumConfig`)

### `defaultWorkerBridge` (`io.casehub.api.context.ContextBridge<?>`)

### `dsl` (`java.lang.String`)

### `episodicMemoryConfig` (`io.casehub.api.model.EpisodicMemoryConfig`)

### `goals` (`java.util.List<io.casehub.api.model.Goal>`)

### `humanTaskContextConstraints` (`java.util.List<io.casehub.api.model.routing.ContextConstraint>`)

### `humanTaskRouting` (`java.lang.String`)

### `humanTaskWorkloadConstraint` (`io.casehub.api.model.routing.WorkloadConstraint`)

### `implementationRouting` (`java.lang.String`)

### `inboundMappings` (`java.util.List<io.casehub.api.model.InboundSignalMapping>`)

### `labelRules` (`java.util.List<LabelRule>`)

### `labels` (`java.util.Set<Path>`)

### `layerNames` (`java.util.List<java.lang.String>`)

### `milestones` (`java.util.List<io.casehub.api.model.Milestone>`)

### `name` (`java.lang.String`)

### `namespace` (`java.lang.String`)

### `planningStrategy` (`java.lang.String`)

### `routingSignalWeights` (`java.util.Map<java.lang.String,java.lang.Double>`)

### `semanticData` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `signals` (`java.util.List<io.casehub.api.model.SignalType<?>>`)

### `summary` (`java.lang.String`)

### `title` (`java.lang.String`)

### `types` (`java.util.Set<Path>`)

### `use` (`io.casehub.api.model.Use`)

### `version` (`java.lang.String`)

### `workerServiceAccountIds` (`java.util.Map<java.lang.String,java.lang.String>`)

### `workers` (`java.util.List<Worker>`)

## Constructors

### `public CaseDefinition(java.lang.String namespace, java.lang.String name, java.lang.String version)`

#### Parameters

- `namespace` (`java.lang.String`)
- `name` (`java.lang.String`)
- `version` (`java.lang.String`)

## Methods

### `public java.util.Optional<AgentDescriptor> agentDescriptorFor(java.lang.String workerName)`

#### Parameters

- `workerName` (`java.lang.String`)

### `public static io.casehub.api.model.CaseDefinition.Builder builder()`

### `public boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public java.lang.String getAgentRouting()`

### `public java.util.Map<AclAction,java.util.List<java.lang.String>> getAuthorization()`

### `public java.util.List<io.casehub.api.model.Binding> getBindings()`

### `public java.lang.String getCandidateMatching()`

### `public java.util.List<Capability> getCapabilities()`

### `public io.casehub.api.model.cbr.CbrConfig getCbrConfig()`

### `public io.casehub.api.model.CognitiveDemand getCognitiveDemand(java.lang.String capabilityName)`

#### Parameters

- `capabilityName` (`java.lang.String`)

### `public io.casehub.api.model.CaseCompletion getCompletion()`

### `public java.lang.String getContextStoreFactory()`

### `public java.lang.String getDecompositionStrategy()`

### `public io.casehub.api.spi.QuorumConfig getDefaultQuorum()`

### `public io.casehub.api.context.ContextBridge<?> getDefaultWorkerBridge()`

### `public java.lang.String getDsl()`

### `public io.casehub.api.model.EpisodicMemoryConfig getEpisodicMemoryConfig()`

### `public java.util.List<io.casehub.api.model.Goal> getGoals()`

### `public java.util.List<io.casehub.api.model.routing.ContextConstraint> getHumanTaskContextConstraints()`

### `public java.lang.String getHumanTaskRouting()`

### `public io.casehub.api.model.routing.WorkloadConstraint getHumanTaskWorkloadConstraint()`

### `public java.lang.String getImplementationRouting()`

### `public java.util.List<io.casehub.api.model.InboundSignalMapping> getInboundMappings()`

### `public java.util.List<LabelRule> getLabelRules()`

### `public java.util.Set<Path> getLabels()`

### `public java.util.List<java.lang.String> getLayerNames()`

### `public java.util.List<io.casehub.api.model.Milestone> getMilestones()`

### `public java.lang.String getName()`

### `public java.lang.String getNamespace()`

### `public java.lang.String getPlanningStrategy()`

### `public java.util.Map<java.lang.String,java.lang.Double> getRoutingSignalWeights()`

### `public java.util.Map<java.lang.String,java.lang.Object> getSemanticData()`

### `public java.util.List<io.casehub.api.model.SignalType<?>> getSignals()`

### `public java.lang.String getSummary()`

### `public java.lang.String getTitle()`

### `public java.util.Set<Path> getTypes()`

### `public io.casehub.api.model.Use getUse()`

### `public java.lang.String getVersion()`

### `public java.lang.String getWorkerServiceAccountId(java.lang.String workerName)`

#### Parameters

- `workerName` (`java.lang.String`)

### `public java.util.Map<java.lang.String,java.lang.String> getWorkerServiceAccountIds()`

### `public java.util.List<Worker> getWorkers()`

### `public int hashCode()`

### `public void setAgentDescriptors(java.util.Map<java.lang.String,AgentDescriptor> agentDescriptors)`

#### Parameters

- `agentDescriptors` (`java.util.Map<java.lang.String,AgentDescriptor>`)

### `public void setAgentRouting(java.lang.String agentRouting)`

#### Parameters

- `agentRouting` (`java.lang.String`)

### `public void setAuthorization(java.util.Map<AclAction,java.util.List<java.lang.String>> authorization)`

#### Parameters

- `authorization` (`java.util.Map<AclAction,java.util.List<java.lang.String>>`)

### `public void setCandidateMatching(java.lang.String candidateMatching)`

#### Parameters

- `candidateMatching` (`java.lang.String`)

### `public void setCbrConfig(io.casehub.api.model.cbr.CbrConfig cbrConfig)`

#### Parameters

- `cbrConfig` (`io.casehub.api.model.cbr.CbrConfig`)

### `public void setCognitiveDemands(java.util.Map<java.lang.String,io.casehub.api.model.CognitiveDemand> cognitiveDemands)`

#### Parameters

- `cognitiveDemands` (`java.util.Map<java.lang.String,io.casehub.api.model.CognitiveDemand>`)

### `public void setCompletion(io.casehub.api.model.CaseCompletion completion)`

#### Parameters

- `completion` (`io.casehub.api.model.CaseCompletion`)

### `public void setContextStoreFactory(java.lang.String contextStoreFactory)`

#### Parameters

- `contextStoreFactory` (`java.lang.String`)

### `public void setDecompositionStrategy(java.lang.String decompositionStrategy)`

#### Parameters

- `decompositionStrategy` (`java.lang.String`)

### `public void setDefaultQuorum(io.casehub.api.spi.QuorumConfig defaultQuorum)`

#### Parameters

- `defaultQuorum` (`io.casehub.api.spi.QuorumConfig`)

### `public void setDefaultWorkerBridge(io.casehub.api.context.ContextBridge<?> defaultWorkerBridge)`

#### Parameters

- `defaultWorkerBridge` (`io.casehub.api.context.ContextBridge<?>`)

### `public void setDsl(java.lang.String dsl)`

#### Parameters

- `dsl` (`java.lang.String`)

### `public void setEpisodicMemoryConfig(io.casehub.api.model.EpisodicMemoryConfig config)`

#### Parameters

- `config` (`io.casehub.api.model.EpisodicMemoryConfig`)

### `public void setHumanTaskContextConstraints(java.util.List<io.casehub.api.model.routing.ContextConstraint> constraints)`

#### Parameters

- `constraints` (`java.util.List<io.casehub.api.model.routing.ContextConstraint>`)

### `public void setHumanTaskRouting(java.lang.String humanTaskRouting)`

#### Parameters

- `humanTaskRouting` (`java.lang.String`)

### `public void setHumanTaskWorkloadConstraint(io.casehub.api.model.routing.WorkloadConstraint constraint)`

#### Parameters

- `constraint` (`io.casehub.api.model.routing.WorkloadConstraint`)

### `public void setImplementationRouting(java.lang.String implementationRouting)`

#### Parameters

- `implementationRouting` (`java.lang.String`)

### `public void setInboundMappings(java.util.List<io.casehub.api.model.InboundSignalMapping> inboundMappings)`

#### Parameters

- `inboundMappings` (`java.util.List<io.casehub.api.model.InboundSignalMapping>`)

### `public void setLabelRules(java.util.List<LabelRule> labelRules)`

#### Parameters

- `labelRules` (`java.util.List<LabelRule>`)

### `public void setLabels(java.util.Set<Path> labels)`

#### Parameters

- `labels` (`java.util.Set<Path>`)

### `public void setLayerNames(java.util.List<java.lang.String> layerNames)`

#### Parameters

- `layerNames` (`java.util.List<java.lang.String>`)

### `public void setPlanningStrategy(java.lang.String planningStrategy)`

#### Parameters

- `planningStrategy` (`java.lang.String`)

### `public void setRoutingSignalWeights(java.util.Map<java.lang.String,java.lang.Double> routingSignalWeights)`

#### Parameters

- `routingSignalWeights` (`java.util.Map<java.lang.String,java.lang.Double>`)

### `public void setSemanticData(java.util.Map<java.lang.String,java.lang.Object> semanticData)`

#### Parameters

- `semanticData` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `public void setSignals(java.util.List<io.casehub.api.model.SignalType<?>> signals)`

#### Parameters

- `signals` (`java.util.List<io.casehub.api.model.SignalType<?>>`)

### `public void setSummary(java.lang.String summary)`

#### Parameters

- `summary` (`java.lang.String`)

### `public void setTitle(java.lang.String title)`

#### Parameters

- `title` (`java.lang.String`)

### `public void setTypes(java.util.Set<Path> types)`

#### Parameters

- `types` (`java.util.Set<Path>`)

### `public void setUse(io.casehub.api.model.Use use)`

#### Parameters

- `use` (`io.casehub.api.model.Use`)

### `public void setWorkerServiceAccountIds(java.util.Map<java.lang.String,java.lang.String> workerServiceAccountIds)`

#### Parameters

- `workerServiceAccountIds` (`java.util.Map<java.lang.String,java.lang.String>`)
