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
  private final CaseDefinitionSpec spec;

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

  public CaseDefinition(String namespace, String name, String version) {
    this.namespace = namespace;
    this.name = name;
    this.version = version;
    this.spec = new CaseDefinitionSpec();
  }

  public String getVersion() {
    return version;
  }

  public CaseDefinitionSpec getSpec() {
    return spec;
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
    return spec.getCapabilities();
  }

  public List<Worker> getWorkers() {
    return spec.getWorkers();
  }

  public List<Binding> getBindings() {
    return spec.getBindings();
  }

  public java.util.List<Binding> findBindingsByCapability(String capabilityName) {
    return spec.getBindings().stream()
        .filter(
            b ->
                b.target() instanceof CapabilityTarget ct
                    && ct.capability().name().equals(capabilityName))
        .toList();
  }

  public List<Milestone> getMilestones() {
    return spec.getMilestones();
  }

  public List<Goal> getGoals() {
    return spec.getGoals();
  }

  public CaseCompletion getCompletion() {
    return spec.getCompletion();
  }

  public void setCompletion(CaseCompletion completion) {
    spec.setCompletion(completion);
  }

  public Map<String, Object> getSemanticData() {
    return semanticData;
  }

  public void setSemanticData(Map<String, Object> semanticData) {
    this.semanticData = semanticData;
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
    return spec.getPlanningStrategy();
  }

  public void setPlanningStrategy(String planningStrategy) {
    spec.setPlanningStrategy(planningStrategy);
  }

  public String getAgentRouting() {
    return spec.getAgentRouting();
  }

  public void setAgentRouting(String agentRouting) {
    spec.setAgentRouting(agentRouting);
  }

  public String getImplementationRouting() {
    return spec.getImplementationRouting();
  }

  public void setImplementationRouting(String implementationRouting) {
    spec.setImplementationRouting(implementationRouting);
  }

  public String getHumanTaskRouting() {
    return spec.getHumanTaskRouting();
  }

  public void setHumanTaskRouting(String humanTaskRouting) {
    spec.setHumanTaskRouting(humanTaskRouting);
  }

  public List<io.casehub.api.model.routing.ContextConstraint> getHumanTaskContextConstraints() {
    return spec.getHumanTaskContextConstraints();
  }

  public void setHumanTaskContextConstraints(
      List<io.casehub.api.model.routing.ContextConstraint> constraints) {
    spec.setHumanTaskContextConstraints(constraints);
  }

  public io.casehub.api.model.routing.WorkloadConstraint getHumanTaskWorkloadConstraint() {
    return spec.getHumanTaskWorkloadConstraint();
  }

  public void setHumanTaskWorkloadConstraint(
      io.casehub.api.model.routing.WorkloadConstraint constraint) {
    spec.setHumanTaskWorkloadConstraint(constraint);
  }

  public String getCandidateMatching() {
    return spec.getCandidateMatching();
  }

  public void setCandidateMatching(String candidateMatching) {
    spec.setCandidateMatching(candidateMatching);
  }

  public String getDecompositionStrategy() {
    return spec.getDecompositionStrategy();
  }

  public void setDecompositionStrategy(String decompositionStrategy) {
    spec.setDecompositionStrategy(decompositionStrategy);
  }

  public Integer getMaxDecompositionDepth() {
    return spec.getMaxDecompositionDepth();
  }

  public void setMaxDecompositionDepth(Integer maxDecompositionDepth) {
    spec.setMaxDecompositionDepth(maxDecompositionDepth);
  }

  public Integer getMaxAdaptations() {
    return spec.getMaxAdaptations();
  }

  public void setMaxAdaptations(Integer maxAdaptations) {
    spec.setMaxAdaptations(maxAdaptations);
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
    return spec.getCbrConfig();
  }

  public void setCbrConfig(CbrConfig cbrConfig) {
    spec.setCbrConfig(cbrConfig);
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
    return spec.getRoutingSignalWeights();
  }

  public void setRoutingSignalWeights(Map<String, Double> routingSignalWeights) {
    spec.setRoutingSignalWeights(routingSignalWeights);
  }

  public CognitiveDemand getCognitiveDemand(String capabilityName) {
    return spec.getCognitiveDemand(capabilityName);
  }

  public void setCognitiveDemands(Map<String, CognitiveDemand> cognitiveDemands) {
    spec.setCognitiveDemands(cognitiveDemands);
  }

  public Map<AclAction, List<String>> getAuthorization() {
    return spec.getAuthorization();
  }

  public void setAuthorization(Map<AclAction, List<String>> authorization) {
    spec.setAuthorization(authorization);
  }

  public String getWorkerServiceAccountId(String workerName) {
    return spec.getWorkerServiceAccountId(workerName);
  }

  public Map<String, String> getWorkerServiceAccountIds() {
    return spec.getWorkerServiceAccountIds();
  }

  public void setWorkerServiceAccountIds(Map<String, String> workerServiceAccountIds) {
    spec.setWorkerServiceAccountIds(workerServiceAccountIds);
  }

  public io.casehub.api.spi.QuorumConfig getDefaultQuorum() {
    return spec.getDefaultQuorum();
  }

  public void setDefaultQuorum(io.casehub.api.spi.QuorumConfig defaultQuorum) {
    spec.setDefaultQuorum(defaultQuorum);
  }

  public ReflectionTriggerConfig getReflectionTrigger() {
    return spec.getReflectionTrigger();
  }

  public void setReflectionTrigger(ReflectionTriggerConfig reflectionTrigger) {
    spec.setReflectionTrigger(reflectionTrigger);
  }

  public MemoryRetrievalConfig getMemoryRetrieval() {
    return spec.getMemoryRetrieval();
  }

  public void setMemoryRetrieval(MemoryRetrievalConfig memoryRetrieval) {
    spec.setMemoryRetrieval(memoryRetrieval);
  }

  public AdaptationConfig getAdaptationConfig() {
    return spec.getAdaptationConfig();
  }

  public void setAdaptationConfig(AdaptationConfig adaptationConfig) {
    spec.setAdaptationConfig(adaptationConfig);
  }

  public io.casehub.engine.plan.PlanningConstraints getPlanningConstraints() {
    return spec.getPlanningConstraints();
  }

  public void setPlanningConstraints(
      io.casehub.engine.plan.PlanningConstraints planningConstraints) {
    spec.setPlanningConstraints(planningConstraints);
  }

  public io.casehub.engine.plan.monitoring.MonitoringConfig getMonitoringConfig() {
    return spec.getMonitoringConfig();
  }

  public void setMonitoringConfig(
      io.casehub.engine.plan.monitoring.MonitoringConfig monitoringConfig) {
    spec.setMonitoringConfig(monitoringConfig);
  }

  public io.casehub.engine.plan.PortfolioConfig getPortfolioConfig() {
    return spec.getPortfolioConfig();
  }

  public void setPortfolioConfig(io.casehub.engine.plan.PortfolioConfig portfolioConfig) {
    spec.setPortfolioConfig(portfolioConfig);
  }

  public List<ChannelDeclaration> getChannels() {
    return spec.getChannels();
  }

  public void setChannels(List<ChannelDeclaration> channels) {
    spec.setChannels(channels);
  }

  public List<io.casehub.engine.plan.goap.GoapAction> getGoapActions() {
    return spec.getGoapActions();
  }

  public void setGoapActions(List<io.casehub.engine.plan.goap.GoapAction> goapActions) {
    spec.setGoapActions(goapActions);
  }

  public Map<String, Set<String>> getGoalToEffectKeys() {
    return spec.getGoalToEffectKeys();
  }

  public void setGoalToEffectKeys(Map<String, Set<String>> goalToEffectKeys) {
    spec.setGoalToEffectKeys(goalToEffectKeys);
  }

  public RecoveryPolicy getRecoveryPolicy() {
    return spec.getRecoveryPolicy();
  }

  public void setRecoveryPolicy(RecoveryPolicy recoveryPolicy) {
    spec.setRecoveryPolicy(recoveryPolicy);
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
    private Map<String, Set<String>> goalToEffectKeys = new java.util.HashMap<>();
    private RecoveryPolicy recoveryPolicy;

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

    public Builder goalToEffectKey(String goalName, Set<String> effectKeys) {
      this.goalToEffectKeys.put(goalName, Set.copyOf(effectKeys));
      return this;
    }

    public Builder recoveryPolicy(RecoveryPolicy recoveryPolicy) {
      this.recoveryPolicy = recoveryPolicy;
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
