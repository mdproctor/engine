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

  private final String namespace;
  private final String name;
  private String dsl;
  private final String version;
  private String title;
  private String summary;
  private Use use;
  private final List<Capability> capabilities;
  private final List<Worker> workers;
  private final List<Binding> bindings;
  private final List<Milestone> milestones;
  private final List<Goal> goals;
  private CaseCompletion completion;
  private Map<String, Object> semanticData;
  private EpisodicMemoryConfig episodicMemoryConfig;
  private List<String> layerNames;
  private Map<String, AgentDescriptor> agentDescriptors = Map.of();
  private String planningStrategy;
  private String agentRouting;
  private String implementationRouting;
  private String humanTaskRouting;
  private List<io.casehub.api.model.routing.ContextConstraint> humanTaskContextConstraints =
      List.of();
  private io.casehub.api.model.routing.WorkloadConstraint humanTaskWorkloadConstraint;

  private String candidateMatching;
  private String decompositionStrategy;

  private Set<Path> types = Set.of();
  private Set<Path> labels = Set.of();
  private CbrConfig cbrConfig;
  private io.casehub.api.context.ContextBridge<?> defaultWorkerBridge;
  private String contextStoreFactory;
  private List<SignalType<?>> signals = List.of();
  private List<LabelRule> labelRules = List.of();
  private List<InboundSignalMapping> inboundMappings = List.of();
  private Map<String, Double> routingSignalWeights;
  private Map<String, CognitiveDemand> cognitiveDemands = Map.of();

  public CaseDefinition(String namespace, String name, String version) {
    this.namespace = namespace;
    this.name = name;
    this.version = version;
    this.capabilities = new ArrayList<>();
    this.bindings = new ArrayList<>();
    this.milestones = new ArrayList<>();
    this.goals = new ArrayList<>();
    this.workers = new ArrayList<>();
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
    this.cognitiveDemands = cognitiveDemands != null ? Map.copyOf(cognitiveDemands) : Map.of();
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

    private Set<Path> types = new LinkedHashSet<>();
    private Set<Path> labels = new LinkedHashSet<>();
    private CbrConfig cbrConfig;
    private io.casehub.api.context.ContextBridge<?> defaultWorkerBridge;
    private String contextStoreFactory;
    private List<SignalType<?>> signals = new java.util.ArrayList<>();
    private List<LabelRule> labelRules = new ArrayList<>();
    private List<InboundSignalMapping> inboundMappings;
    private Map<String, Double> routingSignalWeights;
    private Map<String, CognitiveDemand> cognitiveDemands;

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

    public CaseDefinition build() {
      CaseDefinition caseHubDefinition =
          new CaseDefinition(
              Objects.requireNonNull(namespace),
              Objects.requireNonNull(name),
              Objects.requireNonNull(version));
      caseHubDefinition.setTitle(title);
      caseHubDefinition.setSummary(summary);
      if (capabilities != null) {
        caseHubDefinition.capabilities.addAll(capabilities);
      }
      if (workers != null) {
        caseHubDefinition.workers.addAll(workers);
      }
      if (bindings != null) {
        caseHubDefinition.bindings.addAll(bindings);
      }
      if (milestones != null) {
        caseHubDefinition.milestones.addAll(milestones);
      }
      if (goals != null) {
        caseHubDefinition.goals.addAll(goals);
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
      caseHubDefinition.setTypes(types);
      caseHubDefinition.setLabels(labels);
      caseHubDefinition.setCbrConfig(cbrConfig);
      caseHubDefinition.setDefaultWorkerBridge(defaultWorkerBridge);
      caseHubDefinition.setContextStoreFactory(contextStoreFactory);

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
