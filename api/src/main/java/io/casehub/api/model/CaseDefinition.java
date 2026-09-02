/*
 * Copyright 2026-Present The Case Hub Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.casehub.api.model;

import io.casehub.api.model.cbr.CbrConfig;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.platform.api.acl.AclAction;
import io.casehub.platform.api.label.LabelRule;
import io.casehub.platform.api.path.Path;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public class CaseDefinition {

  @com.fasterxml.jackson.annotation.JsonPropertyDescription("The CaseHub's namespace.")
  private final String namespace;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription("The CaseHub's name.")
  private final String name;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "The version of the DSL used by the CaseHub.")
  private String dsl;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription("The CaseHub's semantic version.")
  private final String version;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription("The CaseHub's title.")
  private String title;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription("The CaseHub's Markdown summary.")
  private String summary;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Declares external dependencies (secrets, config maps) required by this Case definition.")
  private Use use;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Static domain knowledge injected into the semantic layer at case start.")
  private Map<String, Object> semanticData;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Episodic memory configuration for this case definition.")
  private EpisodicMemoryConfig episodicMemoryConfig;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "User-defined layer names for this case definition.")
  private List<String> layerNames;

  private Map<String, AgentDescriptor> agentDescriptors = Map.of();

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Inline definition namespace for definitionRef '#name' references. Opaque content for UI consumption.")
  private java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> definitions =
      java.util.Map.of();

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Capabilities define what Workers can do — declared competences with input/output contracts.")
  private final List<Capability> capabilities;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Autonomous participants that observe CaseContext, make local decisions, and perform work.")
  private final List<Worker> workers;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Bindings connect trigger conditions to worker capabilities.")
  private final List<Binding> bindings;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Observable progress markers derived from CaseContext.")
  private final List<Milestone> milestones;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Desired end-states expressed as predicates over the CaseContext.")
  private final List<Goal> goals;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Defines when a Case is terminally completed or failed based on Goal satisfaction.")
  private CaseCompletion completion;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Planning strategy ID. Built-in: \"default\" (choreography), \"sequential\".")
  private String planningStrategy;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Agent routing strategy ID. Selects which worker instance handles a task.")
  private String agentRouting;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Implementation routing strategy ID. Selects which binding(s) handle a capability.")
  private String implementationRouting;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "HumanTask routing strategy ID. Enriches candidate sets with historical data.")
  private String humanTaskRouting;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Declarative rules for humanTask candidate filtering and scoring.")
  private List<io.casehub.api.model.routing.ContextConstraint> humanTaskContextConstraints =
      List.of();

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Workload-based humanTask candidate constraint.")
  private io.casehub.api.model.routing.WorkloadConstraint humanTaskWorkloadConstraint;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Candidate matching strategy ID. Built-in: \"exact\", \"subsumption\".")
  private String candidateMatching;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "HTN decomposition strategy ID. Default: \"identity\".")
  private String decompositionStrategy;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Maximum nesting depth for dynamic decomposition. Default: 3.")
  private Integer maxDecompositionDepth;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Maximum adaptation count per compound before Concede. Default: 5.")
  private Integer maxAdaptations;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Maximum escalation count per judgment yield before Fault. Default: 3.")
  private Integer maxEscalations;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Case-Based Reasoning retrieval configuration.")
  private io.casehub.api.model.cbr.CbrConfig cbrConfig;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Per-signal-provider weight configuration for composable agent routing.")
  private Map<String, Double> routingSignalWeights;

  private Map<String, CognitiveDemand> cognitiveDemands = Map.of();

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "ACL grants created when a case of this type is started.")
  private Map<io.casehub.platform.api.acl.AclAction, List<String>> authorization;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Map of worker name to service account ID for tenant-specific endpoint resolution.")
  private Map<String, String> workerServiceAccountIds;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Default M-of-N multi-party approval configuration for action gates.")
  private io.casehub.api.spi.QuorumConfig defaultQuorum;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Per-case reflection trigger configuration.")
  private ReflectionTriggerConfig reflectionTrigger;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Per-case memory retrieval configuration.")
  private MemoryRetrievalConfig memoryRetrieval;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Per-case plan adaptation configuration.")
  private AdaptationConfig adaptationConfig;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Per-case resource constraints for decomposition and pattern execution.")
  private io.casehub.engine.plan.PlanningConstraints planningConstraints;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Per-case expectation tracking configuration.")
  private io.casehub.engine.plan.monitoring.MonitoringConfig monitoringConfig;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Cascading decomposition strategy configuration.")
  private io.casehub.engine.plan.PortfolioConfig portfolioConfig;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Typed streaming channels for worker-to-worker data flow.")
  private List<ChannelDeclaration> channels = List.of();

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "GOAP action declarations for planning.")
  private List<io.casehub.engine.plan.goap.GoapAction> goapActions;

  private Map<String, Set<String>> goalToEffectKeys;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Per-case multi-level recovery configuration.")
  private RecoveryPolicy recoveryPolicy;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Behavioral type classifications — hierarchical path strings via Path.parse().")
  private Set<Path> types = Set.of();

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Operational classification labels — hierarchical path strings via Path.parse().")
  private Set<Path> labels = Set.of();

  private io.casehub.api.context.ContextBridge<?> defaultWorkerBridge;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription("Context store factory strategy ID.")
  private String contextStoreFactory;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Fully qualified class name for the typed context bridge. Enables typed POJO context instead of Map<String, Object>.")
  private String contextType;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Default expression language for this definition. Default: \"jq\".")
  private String expressionLang;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Typed signal declarations for this case definition.")
  private List<SignalType<?>> signals = List.of();

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Label evaluation rules for case queue management.")
  private List<LabelRule> labelRules = List.of();

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Inbound connector message to typed case signal mappings.")
  private List<InboundSignalMapping> inboundMappings = List.of();

  private List<CompoundDeclaration> compounds;

  private io.casehub.engine.plan.TaskNode.CompoundTask<com.fasterxml.jackson.databind.JsonNode>
      decompositionTree;

  public CaseDefinition(String namespace, String name, String version) {
    this.namespace = namespace;
    this.name = name;
    this.version = version;
    this.capabilities = new java.util.ArrayList<>();
    this.workers = new java.util.ArrayList<>();
    this.bindings = new java.util.ArrayList<>();
    this.milestones = new java.util.ArrayList<>();
    this.goals = new java.util.ArrayList<>();
  }

  public String getVersion() {
    return version;
  }

  public String getDsl() {
    return dsl;
  }

  public void setDsl(String dsl) {
    this.dsl = dsl;
  }

  public String getNamespace() {
    return namespace;
  }

  public String getName() {
    return name;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getSummary() {
    return summary;
  }

  public void setSummary(String summary) {
    this.summary = summary;
  }

  public Use getUse() {
    return use;
  }

  public void setUse(Use use) {
    this.use = use;
  }

  public List<Capability> getCapabilities() {
    return capabilities;
  }

  public List<Worker> getWorkers() {
    return workers;
  }

  public List<Binding> getBindings() {
    return bindings;
  }

  public java.util.List<Binding> findBindingsByCapability(String capabilityName) {
    return bindings.stream()
        .filter(
            b ->
                b.target() instanceof CapabilityTarget ct
                    && ct.capability().name().equals(capabilityName))
        .toList();
  }

  public List<Milestone> getMilestones() {
    return milestones;
  }

  public List<Goal> getGoals() {
    return goals;
  }

  public CaseCompletion getCompletion() {
    return completion;
  }

  public void setCompletion(CaseCompletion completion) {
    this.completion = completion;
  }

  public Map<String, Object> getSemanticData() {
    return semanticData;
  }

  public void setSemanticData(Map<String, Object> semanticData) {
    this.semanticData = semanticData;
  }

  public java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> getDefinitions() {
    return definitions;
  }

  public void setDefinitions(
      java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> definitions) {
    this.definitions = definitions != null ? java.util.Map.copyOf(definitions) : java.util.Map.of();
  }

  public EpisodicMemoryConfig getEpisodicMemoryConfig() {
    return episodicMemoryConfig;
  }

  public void setEpisodicMemoryConfig(EpisodicMemoryConfig config) {
    this.episodicMemoryConfig = config;
  }

  public List<String> getLayerNames() {
    return layerNames;
  }

  public void setLayerNames(List<String> layerNames) {
    this.layerNames = layerNames;
  }

  public Optional<AgentDescriptor> agentDescriptorFor(String workerName) {
    return Optional.ofNullable(agentDescriptors.get(workerName));
  }

  public void setAgentDescriptors(Map<String, AgentDescriptor> agentDescriptors) {
    this.agentDescriptors = agentDescriptors != null ? Map.copyOf(agentDescriptors) : Map.of();
  }

  public String getPlanningStrategy() {
    return planningStrategy;
  }

  public void setPlanningStrategy(String planningStrategy) {
    this.planningStrategy = planningStrategy;
  }

  public String getAgentRouting() {
    return agentRouting;
  }

  public void setAgentRouting(String agentRouting) {
    this.agentRouting = agentRouting;
  }

  public String getImplementationRouting() {
    return implementationRouting;
  }

  public void setImplementationRouting(String implementationRouting) {
    this.implementationRouting = implementationRouting;
  }

  public String getHumanTaskRouting() {
    return humanTaskRouting;
  }

  public void setHumanTaskRouting(String humanTaskRouting) {
    this.humanTaskRouting = humanTaskRouting;
  }

  public List<io.casehub.api.model.routing.ContextConstraint> getHumanTaskContextConstraints() {
    return humanTaskContextConstraints;
  }

  public void setHumanTaskContextConstraints(
      List<io.casehub.api.model.routing.ContextConstraint> constraints) {
    this.humanTaskContextConstraints = constraints != null ? List.copyOf(constraints) : List.of();
  }

  public io.casehub.api.model.routing.WorkloadConstraint getHumanTaskWorkloadConstraint() {
    return humanTaskWorkloadConstraint;
  }

  public void setHumanTaskWorkloadConstraint(
      io.casehub.api.model.routing.WorkloadConstraint constraint) {
    this.humanTaskWorkloadConstraint = constraint;
  }

  public String getCandidateMatching() {
    return candidateMatching;
  }

  public void setCandidateMatching(String candidateMatching) {
    this.candidateMatching = candidateMatching;
  }

  public String getDecompositionStrategy() {
    return decompositionStrategy;
  }

  public void setDecompositionStrategy(String decompositionStrategy) {
    this.decompositionStrategy = decompositionStrategy;
  }

  public Integer getMaxDecompositionDepth() {
    return maxDecompositionDepth;
  }

  public void setMaxDecompositionDepth(Integer maxDecompositionDepth) {
    this.maxDecompositionDepth = maxDecompositionDepth;
  }

  public Integer getMaxAdaptations() {
    return maxAdaptations;
  }

  public void setMaxAdaptations(Integer maxAdaptations) {
    this.maxAdaptations = maxAdaptations;
  }

  public Integer getMaxEscalations() {
    return maxEscalations;
  }

  public void setMaxEscalations(Integer maxEscalations) {
    this.maxEscalations = maxEscalations;
  }

  public Set<Path> getTypes() {
    return types;
  }

  public void setTypes(Set<Path> types) {
    this.types = types != null ? Set.copyOf(types) : Set.of();
  }

  public Set<Path> getLabels() {
    return labels;
  }

  public void setLabels(Set<Path> labels) {
    this.labels = labels != null ? Set.copyOf(labels) : Set.of();
  }

  public CbrConfig getCbrConfig() {
    return cbrConfig;
  }

  public void setCbrConfig(CbrConfig cbrConfig) {
    this.cbrConfig = cbrConfig;
  }

  public io.casehub.api.context.ContextBridge<?> getDefaultWorkerBridge() {
    return defaultWorkerBridge;
  }

  public void setDefaultWorkerBridge(io.casehub.api.context.ContextBridge<?> defaultWorkerBridge) {
    this.defaultWorkerBridge = defaultWorkerBridge;
  }

  public String getContextStoreFactory() {
    return contextStoreFactory;
  }

  public void setContextStoreFactory(String contextStoreFactory) {
    this.contextStoreFactory = contextStoreFactory;
  }

  public String getContextType() {
    return contextType;
  }

  public void setContextType(String contextType) {
    this.contextType = contextType;
  }

  public String getExpressionLang() {
    return expressionLang;
  }

  public void setExpressionLang(String expressionLang) {
    this.expressionLang = expressionLang;
  }

  public List<SignalType<?>> getSignals() {
    return signals;
  }

  public void setSignals(List<SignalType<?>> signals) {
    this.signals = signals != null ? List.copyOf(signals) : List.of();
  }

  public List<LabelRule> getLabelRules() {
    return labelRules;
  }

  public void setLabelRules(List<LabelRule> labelRules) {
    this.labelRules = List.copyOf(labelRules);
  }

  public List<InboundSignalMapping> getInboundMappings() {
    return inboundMappings;
  }

  public void setInboundMappings(List<InboundSignalMapping> inboundMappings) {
    this.inboundMappings = inboundMappings;
  }

  public Map<String, Double> getRoutingSignalWeights() {
    return routingSignalWeights;
  }

  public void setRoutingSignalWeights(Map<String, Double> routingSignalWeights) {
    this.routingSignalWeights = routingSignalWeights;
  }

  public CognitiveDemand getCognitiveDemand(String capabilityName) {
    return cognitiveDemands.get(capabilityName);
  }

  public void setCognitiveDemands(Map<String, CognitiveDemand> cognitiveDemands) {
    this.cognitiveDemands = cognitiveDemands != null ? cognitiveDemands : Map.of();
  }

  public Map<AclAction, List<String>> getAuthorization() {
    return authorization;
  }

  public void setAuthorization(Map<AclAction, List<String>> authorization) {
    this.authorization = authorization;
  }

  public String getWorkerServiceAccountId(String workerName) {
    return workerServiceAccountIds != null ? workerServiceAccountIds.get(workerName) : null;
  }

  public Map<String, String> getWorkerServiceAccountIds() {
    return workerServiceAccountIds;
  }

  public void setWorkerServiceAccountIds(Map<String, String> workerServiceAccountIds) {
    this.workerServiceAccountIds = workerServiceAccountIds;
  }

  public io.casehub.api.spi.QuorumConfig getDefaultQuorum() {
    return defaultQuorum;
  }

  public void setDefaultQuorum(io.casehub.api.spi.QuorumConfig defaultQuorum) {
    this.defaultQuorum = defaultQuorum;
  }

  public ReflectionTriggerConfig getReflectionTrigger() {
    return reflectionTrigger;
  }

  public void setReflectionTrigger(ReflectionTriggerConfig reflectionTrigger) {
    this.reflectionTrigger = reflectionTrigger;
  }

  public MemoryRetrievalConfig getMemoryRetrieval() {
    return memoryRetrieval;
  }

  public void setMemoryRetrieval(MemoryRetrievalConfig memoryRetrieval) {
    this.memoryRetrieval = memoryRetrieval;
  }

  public AdaptationConfig getAdaptationConfig() {
    return adaptationConfig;
  }

  public void setAdaptationConfig(AdaptationConfig adaptationConfig) {
    this.adaptationConfig = adaptationConfig;
  }

  public io.casehub.engine.plan.PlanningConstraints getPlanningConstraints() {
    return planningConstraints;
  }

  public void setPlanningConstraints(
      io.casehub.engine.plan.PlanningConstraints planningConstraints) {
    this.planningConstraints = planningConstraints;
  }

  public io.casehub.engine.plan.monitoring.MonitoringConfig getMonitoringConfig() {
    return monitoringConfig;
  }

  public void setMonitoringConfig(
      io.casehub.engine.plan.monitoring.MonitoringConfig monitoringConfig) {
    this.monitoringConfig = monitoringConfig;
  }

  public io.casehub.engine.plan.PortfolioConfig getPortfolioConfig() {
    return portfolioConfig;
  }

  public void setPortfolioConfig(io.casehub.engine.plan.PortfolioConfig portfolioConfig) {
    this.portfolioConfig = portfolioConfig;
  }

  public List<ChannelDeclaration> getChannels() {
    return channels;
  }

  public void setChannels(List<ChannelDeclaration> channels) {
    this.channels = channels != null ? channels : List.of();
  }

  public List<io.casehub.engine.plan.goap.GoapAction> getGoapActions() {
    return goapActions;
  }

  public void setGoapActions(List<io.casehub.engine.plan.goap.GoapAction> goapActions) {
    this.goapActions = goapActions;
  }

  public List<CompoundDeclaration> getCompounds() {
    return compounds != null ? compounds : List.of();
  }

  public void setCompounds(List<CompoundDeclaration> compounds) {
    this.compounds = compounds != null ? List.copyOf(compounds) : null;
  }

  public Map<String, Set<String>> getGoalToEffectKeys() {
    return goalToEffectKeys;
  }

  public void setGoalToEffectKeys(Map<String, Set<String>> goalToEffectKeys) {
    this.goalToEffectKeys = goalToEffectKeys;
  }

  public RecoveryPolicy getRecoveryPolicy() {
    return recoveryPolicy;
  }

  public void setRecoveryPolicy(RecoveryPolicy recoveryPolicy) {
    this.recoveryPolicy = recoveryPolicy;
  }

  public io.casehub.engine.plan.TaskNode.CompoundTask<com.fasterxml.jackson.databind.JsonNode>
      getDecompositionTree() {
    return decompositionTree;
  }

  public void setDecompositionTree(
      io.casehub.engine.plan.TaskNode.CompoundTask<com.fasterxml.jackson.databind.JsonNode>
          decompositionTree) {
    this.decompositionTree = decompositionTree;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {

    private String namespace;
    private String name;
    private String version;
    private String title;
    private String summary;
    private List<Capability> capabilities;
    private List<Worker> workers;
    private List<Binding> bindings;
    private List<Milestone> milestones;
    private List<Goal> goals;
    private CaseCompletion completion;
    private Map<String, Object> semanticData;
    private EpisodicMemoryConfig episodicMemoryConfig;
    private List<String> layerNames;
    private Map<String, AgentDescriptor> agentDescriptors = new HashMap<>();
    private String planningStrategy;
    private String agentRouting;
    private String implementationRouting;
    private String humanTaskRouting;
    private List<io.casehub.api.model.routing.ContextConstraint> humanTaskContextConstraints =
        new ArrayList<>();
    private io.casehub.api.model.routing.WorkloadConstraint humanTaskWorkloadConstraint;

    private String candidateMatching;
    private String decompositionStrategy;
    private Integer maxDecompositionDepth;
    private Integer maxAdaptations;
    private Integer maxEscalations;

    private Set<Path> types = new LinkedHashSet<>();
    private Set<Path> labels = new LinkedHashSet<>();
    private CbrConfig cbrConfig;
    private io.casehub.api.context.ContextBridge<?> defaultWorkerBridge;
    private String contextStoreFactory;
    private String contextType;
    private String expressionLang;

    private List<SignalType<?>> signals = new java.util.ArrayList<>();
    private List<LabelRule> labelRules = new ArrayList<>();
    private List<InboundSignalMapping> inboundMappings;
    private Map<String, Double> routingSignalWeights;
    private Map<String, CognitiveDemand> cognitiveDemands;
    private Map<AclAction, List<String>> authorization;
    private Map<String, String> workerServiceAccountIds;
    private io.casehub.api.spi.QuorumConfig defaultQuorum;
    private ReflectionTriggerConfig reflectionTrigger;
    private MemoryRetrievalConfig memoryRetrieval;
    private AdaptationConfig adaptationConfig;
    private io.casehub.engine.plan.PlanningConstraints planningConstraints;
    private io.casehub.engine.plan.monitoring.MonitoringConfig monitoringConfig;
    private io.casehub.engine.plan.PortfolioConfig portfolioConfig;

    private List<ChannelDeclaration> channels = new java.util.ArrayList<>();
    private List<io.casehub.engine.plan.goap.GoapAction> goapActions;
    private List<CompoundDeclaration> compounds;
    private Map<String, Set<String>> goalToEffectKeys = new java.util.HashMap<>();
    private RecoveryPolicy recoveryPolicy;
    private io.casehub.engine.plan.TaskNode.CompoundTask<com.fasterxml.jackson.databind.JsonNode>
        decompositionTree;

    private Builder() {}

    public Builder namespace(String namespace) {
      this.namespace = namespace;
      return this;
    }

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder version(String version) {
      this.version = version;
      return this;
    }

    public Builder title(String title) {
      this.title = title;
      return this;
    }

    public Builder summary(String summary) {
      this.summary = summary;
      return this;
    }

    public Builder capabilities(List<Capability> capabilities) {
      this.capabilities = capabilities;
      return this;
    }

    public Builder capabilities(Capability... capabilities) {
      this.capabilities = List.of(capabilities);
      return this;
    }

    public Builder workers(List<Worker> workers) {
      this.workers = workers;
      return this;
    }

    public Builder workers(Worker... workers) {
      this.workers = List.of(workers);
      return this;
    }

    public Builder bindings(List<Binding> bindings) {
      this.bindings = bindings;
      return this;
    }

    public Builder bindings(Binding... bindings) {
      this.bindings = List.of(bindings);
      return this;
    }

    public Builder milestones(List<Milestone> milestones) {
      this.milestones = milestones;
      return this;
    }

    public Builder milestones(Milestone... milestones) {
      this.milestones = List.of(milestones);
      return this;
    }

    public Builder goals(List<Goal> goals) {
      this.goals = goals;
      return this;
    }

    public Builder goals(Goal... goals) {
      this.goals = List.of(goals);
      return this;
    }

    public Builder completion(GoalExpression success) {
      return completion(success, null);
    }

    public Builder completion(GoalExpression success, GoalExpression failure) {
      var gbc = GoalBasedCompletion.<StandardGoalKind>builder();
      if (failure != null) gbc.goal(StandardGoalKind.FAILURE, failure);
      if (success != null) gbc.goal(StandardGoalKind.SUCCESS, success);
      this.completion = gbc.build();
      return this;
    }

    public Builder completion(GoalBasedCompletion<?> completion) {
      this.completion = completion;
      return this;
    }

    public Builder completion(String when) {
      this.completion = new PredicateBasedCompletion(new JQExpressionEvaluator(when));
      return this;
    }

    public Builder semanticData(Map<String, Object> semanticData) {
      this.semanticData = semanticData;
      return this;
    }

    public Builder episodicMemory(String domain, String entityId) {
      this.episodicMemoryConfig = EpisodicMemoryConfig.of(domain, entityId);
      return this;
    }

    public Builder episodicMemory(String domain, String entityId, int recent) {
      this.episodicMemoryConfig = EpisodicMemoryConfig.of(domain, entityId, recent);
      return this;
    }

    public Builder agentDescriptor(String workerName, AgentDescriptor descriptor) {
      this.agentDescriptors.put(workerName, descriptor);
      return this;
    }

    public Builder layerNames(List<String> layerNames) {
      this.layerNames = layerNames;
      return this;
    }

    public Builder layer(String name) {
      if (this.layerNames == null) {
        this.layerNames = new java.util.ArrayList<>();
      }
      this.layerNames.add(name);
      return this;
    }

    public Builder layers(String... names) {
      if (this.layerNames == null) {
        this.layerNames = new java.util.ArrayList<>();
      }
      java.util.Collections.addAll(this.layerNames, names);
      return this;
    }

    public Builder planningStrategy(String planningStrategy) {
      this.planningStrategy = planningStrategy;
      return this;
    }

    public Builder agentRouting(String agentRouting) {
      this.agentRouting = agentRouting;
      return this;
    }

    public Builder implementationRouting(String implementationRouting) {
      this.implementationRouting = implementationRouting;
      return this;
    }

    public Builder humanTaskRouting(String humanTaskRouting) {
      this.humanTaskRouting = humanTaskRouting;
      return this;
    }

    public Builder humanTaskContextConstraint(
        io.casehub.api.model.routing.ContextConstraint constraint) {
      this.humanTaskContextConstraints.add(constraint);
      return this;
    }

    public Builder humanTaskWorkloadConstraint(
        io.casehub.api.model.routing.WorkloadConstraint constraint) {
      this.humanTaskWorkloadConstraint = constraint;
      return this;
    }

    public Builder candidateMatching(String candidateMatching) {
      this.candidateMatching = candidateMatching;
      return this;
    }

    public Builder decompositionStrategy(String decompositionStrategy) {
      this.decompositionStrategy = decompositionStrategy;
      return this;
    }

    public Builder maxDecompositionDepth(Integer maxDecompositionDepth) {
      this.maxDecompositionDepth = maxDecompositionDepth;
      return this;
    }

    public Builder maxAdaptations(Integer maxAdaptations) {
      this.maxAdaptations = maxAdaptations;
      return this;
    }

    public Builder maxEscalations(Integer maxEscalations) {
      this.maxEscalations = maxEscalations;
      return this;
    }

    public Builder type(Path type) {
      this.types.add(type);
      return this;
    }

    public Builder types(Set<Path> types) {
      this.types = new LinkedHashSet<>(types);
      return this;
    }

    public Builder label(Path label) {
      this.labels.add(label);
      return this;
    }

    public Builder labels(Set<Path> labels) {
      this.labels = new LinkedHashSet<>(labels);
      return this;
    }

    public Builder cbrConfig(CbrConfig cbrConfig) {
      this.cbrConfig = cbrConfig;
      return this;
    }

    public Builder defaultWorkerBridge(io.casehub.api.context.ContextBridge<?> bridge) {
      this.defaultWorkerBridge = bridge;
      return this;
    }

    public Builder contextStoreFactory(String contextStoreFactory) {
      this.contextStoreFactory = contextStoreFactory;
      return this;
    }

    public Builder contextType(String contextType) {
      this.contextType = contextType;
      return this;
    }

    public Builder expressionLang(String expressionLang) {
      this.expressionLang = expressionLang;
      return this;
    }

    public Builder signal(SignalType<?> signal) {
      this.signals.add(signal);
      return this;
    }

    public Builder labelRule(LabelRule rule) {
      this.labelRules.add(Objects.requireNonNull(rule));
      return this;
    }

    public Builder labelRules(List<LabelRule> rules) {
      this.labelRules = new ArrayList<>(rules);
      return this;
    }

    public Builder inboundMapping(InboundSignalMapping mapping) {
      if (this.inboundMappings == null) {
        this.inboundMappings = new java.util.ArrayList<>();
      }
      this.inboundMappings.add(mapping);
      return this;
    }

    public Builder routingSignalWeights(Map<String, Double> weights) {
      this.routingSignalWeights = weights;
      return this;
    }

    public Builder cognitiveDemand(String capabilityName, CognitiveDemand demand) {
      if (this.cognitiveDemands == null) {
        this.cognitiveDemands = new java.util.LinkedHashMap<>();
      }
      this.cognitiveDemands.put(capabilityName, demand);
      return this;
    }

    public Builder authorization(AclAction action, List<String> groups) {
      if (this.authorization == null) {
        this.authorization = new java.util.EnumMap<>(AclAction.class);
      }
      this.authorization.put(action, List.copyOf(groups));
      return this;
    }

    public Builder authorization(Map<AclAction, List<String>> authorization) {
      this.authorization = authorization != null ? new java.util.EnumMap<>(authorization) : null;
      return this;
    }

    public Builder workerServiceAccountId(String workerName, String serviceAccountId) {
      if (this.workerServiceAccountIds == null) {
        this.workerServiceAccountIds = new java.util.HashMap<>();
      }
      this.workerServiceAccountIds.put(workerName, serviceAccountId);
      return this;
    }

    public Builder defaultQuorum(io.casehub.api.spi.QuorumConfig defaultQuorum) {
      this.defaultQuorum = defaultQuorum;
      return this;
    }

    public Builder reflectionTrigger(ReflectionTriggerConfig reflectionTrigger) {
      this.reflectionTrigger = reflectionTrigger;
      return this;
    }

    public Builder memoryRetrieval(MemoryRetrievalConfig memoryRetrieval) {
      this.memoryRetrieval = memoryRetrieval;
      return this;
    }

    public Builder adaptationConfig(AdaptationConfig adaptationConfig) {
      this.adaptationConfig = adaptationConfig;
      return this;
    }

    public Builder planningConstraints(
        io.casehub.engine.plan.PlanningConstraints planningConstraints) {
      this.planningConstraints = planningConstraints;
      return this;
    }

    public Builder monitoring(io.casehub.engine.plan.monitoring.MonitoringConfig monitoringConfig) {
      this.monitoringConfig = monitoringConfig;
      return this;
    }

    public Builder portfolioConfig(io.casehub.engine.plan.PortfolioConfig portfolioConfig) {
      this.portfolioConfig = portfolioConfig;
      return this;
    }

    public Builder channel(String name, Class<?> recordType) {
      this.channels.add(new ChannelDeclaration(name, recordType, null, null));
      return this;
    }

    public Builder channel(String name, Class<?> recordType, String transport) {
      this.channels.add(new ChannelDeclaration(name, recordType, transport, null));
      return this;
    }

    public Builder channel(ChannelDeclaration channel) {
      this.channels.add(channel);
      return this;
    }

    public Builder goapActions(List<io.casehub.engine.plan.goap.GoapAction> goapActions) {
      this.goapActions = goapActions;
      return this;
    }

    public Builder compounds(List<CompoundDeclaration> compounds) {
      this.compounds = compounds;
      return this;
    }

    public Builder goalToEffectKey(String goalName, Set<String> effectKeys) {
      this.goalToEffectKeys.put(goalName, Set.copyOf(effectKeys));
      return this;
    }

    public Builder recoveryPolicy(RecoveryPolicy recoveryPolicy) {
      this.recoveryPolicy = recoveryPolicy;
      return this;
    }

    public Builder decompositionTree(
        io.casehub.engine.plan.TaskNode.CompoundTask<com.fasterxml.jackson.databind.JsonNode>
            decompositionTree) {
      this.decompositionTree = decompositionTree;
      return this;
    }

    public CaseDefinition build() {
      CaseDefinition caseHubDefinition =
          new CaseDefinition(
              Objects.requireNonNull(namespace),
              Objects.requireNonNull(name),
              Objects.requireNonNull(version));
      caseHubDefinition.setTitle(title);
      caseHubDefinition.setSummary(summary);
      if (capabilities != null) {
        caseHubDefinition.getCapabilities().addAll(capabilities);
      }
      if (workers != null) {
        caseHubDefinition.getWorkers().addAll(workers);
      }
      if (bindings != null) {
        caseHubDefinition.getBindings().addAll(bindings);
      }
      if (milestones != null) {
        caseHubDefinition.getMilestones().addAll(milestones);
      }
      if (goals != null) {
        caseHubDefinition.getGoals().addAll(goals);
      }
      caseHubDefinition.setCompletion(completion);
      caseHubDefinition.setSemanticData(semanticData);
      caseHubDefinition.setEpisodicMemoryConfig(episodicMemoryConfig);
      caseHubDefinition.setLayerNames(layerNames);
      caseHubDefinition.setAgentDescriptors(agentDescriptors);
      caseHubDefinition.setPlanningStrategy(planningStrategy);
      caseHubDefinition.setAgentRouting(agentRouting);
      caseHubDefinition.setImplementationRouting(implementationRouting);
      caseHubDefinition.setHumanTaskRouting(humanTaskRouting);
      caseHubDefinition.setHumanTaskContextConstraints(humanTaskContextConstraints);
      caseHubDefinition.setHumanTaskWorkloadConstraint(humanTaskWorkloadConstraint);
      caseHubDefinition.setCandidateMatching(candidateMatching);
      caseHubDefinition.setDecompositionStrategy(decompositionStrategy);
      caseHubDefinition.setMaxDecompositionDepth(maxDecompositionDepth);
      caseHubDefinition.setMaxAdaptations(maxAdaptations);
      caseHubDefinition.setMaxEscalations(maxEscalations);
      caseHubDefinition.setTypes(types);
      caseHubDefinition.setLabels(labels);
      caseHubDefinition.setCbrConfig(cbrConfig);
      caseHubDefinition.setDefaultWorkerBridge(defaultWorkerBridge);
      caseHubDefinition.setContextStoreFactory(contextStoreFactory);
      caseHubDefinition.setContextType(contextType);
      caseHubDefinition.setExpressionLang(expressionLang);
      caseHubDefinition.setAdaptationConfig(adaptationConfig);
      caseHubDefinition.setChannels(channels);
      caseHubDefinition.setGoapActions(this.goapActions);
      caseHubDefinition.setCompounds(this.compounds);
      if (!this.goalToEffectKeys.isEmpty()) {
        caseHubDefinition.setGoalToEffectKeys(Map.copyOf(this.goalToEffectKeys));
      }

      Set<String> signalNames = new HashSet<>();
      for (SignalType<?> s : signals) {
        if (!signalNames.add(s.name())) {
          throw new IllegalArgumentException("Duplicate signal name: " + s.name());
        }
      }
      caseHubDefinition.setSignals(signals);
      caseHubDefinition.setLabelRules(labelRules);

      if (inboundMappings != null && !inboundMappings.isEmpty()) {
        Set<String> declaredSignalNames =
            signals.stream().map(SignalType::name).collect(java.util.stream.Collectors.toSet());
        for (InboundSignalMapping m : inboundMappings) {
          if (!declaredSignalNames.contains(m.signalName())) {
            throw new IllegalStateException(
                "InboundSignalMapping references undeclared signal '"
                    + m.signalName()
                    + "'. Declared signals: "
                    + declaredSignalNames);
          }
        }
        caseHubDefinition.setInboundMappings(List.copyOf(inboundMappings));
      }
      caseHubDefinition.setRoutingSignalWeights(routingSignalWeights);
      if (cognitiveDemands != null) {
        caseHubDefinition.setCognitiveDemands(cognitiveDemands);
      }
      if (authorization != null && !authorization.isEmpty()) {
        caseHubDefinition.setAuthorization(Map.copyOf(authorization));
      }
      if (workerServiceAccountIds != null && !workerServiceAccountIds.isEmpty()) {
        for (var entry : workerServiceAccountIds.entrySet()) {
          var actorType =
              io.casehub.platform.api.identity.ActorTypeResolver.resolve(entry.getValue());
          if (actorType != io.casehub.platform.api.identity.ActorType.AGENT) {
            throw new IllegalArgumentException(
                "serviceAccountId for worker '"
                    + entry.getKey()
                    + "' must resolve to AGENT, got "
                    + actorType
                    + " for '"
                    + entry.getValue()
                    + "'");
          }
        }
        caseHubDefinition.setWorkerServiceAccountIds(Map.copyOf(workerServiceAccountIds));
      }
      caseHubDefinition.setDefaultQuorum(defaultQuorum);
      caseHubDefinition.setReflectionTrigger(reflectionTrigger);
      caseHubDefinition.setMemoryRetrieval(memoryRetrieval);
      caseHubDefinition.setPlanningConstraints(planningConstraints);
      caseHubDefinition.setRecoveryPolicy(recoveryPolicy);
      caseHubDefinition.setMonitoringConfig(monitoringConfig);
      caseHubDefinition.setPortfolioConfig(portfolioConfig);
      caseHubDefinition.setDecompositionTree(decompositionTree);

      return caseHubDefinition;
    }
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof CaseDefinition that)) return false;
    return Objects.equals(namespace, that.namespace)
        && Objects.equals(name, that.name)
        && Objects.equals(version, that.version);
  }

  @Override
  public int hashCode() {
    return Objects.hash(namespace, name, version);
  }
}
