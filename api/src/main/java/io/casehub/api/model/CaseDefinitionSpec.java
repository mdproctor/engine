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

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.casehub.api.model.cbr.CbrConfig;
import io.casehub.api.model.routing.ContextConstraint;
import io.casehub.api.model.routing.WorkloadConstraint;
import io.casehub.api.spi.QuorumConfig;
import io.casehub.engine.plan.PlanningConstraints;
import io.casehub.engine.plan.PortfolioConfig;
import io.casehub.engine.plan.goap.GoapAction;
import io.casehub.engine.plan.monitoring.MonitoringConfig;
import io.casehub.platform.api.acl.AclAction;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Specification block of a CaseDefinition — what the case DOES.
 *
 * <p>Groups capabilities, workers, bindings, goals, milestones, completion semantics, and strategy
 * configuration. Matches the {@code spec:} block in YAML case definitions. Identity (namespace,
 * name, version) and configuration (signals, layers, labels) stay on {@link CaseDefinition}.
 */
public class CaseDefinitionSpec {

  @JsonPropertyDescription(
      "Capabilities define what Workers can do — declared competences with input/output contracts.")
  private final List<Capability> capabilities;

  @JsonPropertyDescription(
      "Autonomous participants that observe CaseContext, make local decisions, and perform work.")
  private final List<Worker> workers;

  @JsonPropertyDescription("Bindings connect trigger conditions to worker capabilities.")
  private final List<Binding> bindings;

  @JsonPropertyDescription("Observable progress markers derived from CaseContext.")
  private final List<Milestone> milestones;

  @JsonPropertyDescription("Desired end-states expressed as predicates over the CaseContext.")
  private final List<Goal> goals;

  @JsonPropertyDescription(
      "Defines when a Case is terminally completed or failed based on Goal satisfaction.")
  private CaseCompletion completion;

  @JsonPropertyDescription(
      "Planning strategy ID. Built-in: \"default\" (choreography), \"sequential\".")
  private String planningStrategy;

  @JsonPropertyDescription(
      "Agent routing strategy ID. Selects which worker instance handles a task.")
  private String agentRouting;

  @JsonPropertyDescription(
      "Implementation routing strategy ID. Selects which binding(s) handle a capability.")
  private String implementationRouting;

  @JsonPropertyDescription(
      "HumanTask routing strategy ID. Enriches candidate sets with historical data.")
  private String humanTaskRouting;

  @JsonPropertyDescription("Declarative rules for humanTask candidate filtering and scoring.")
  private List<ContextConstraint> humanTaskContextConstraints = List.of();

  @JsonPropertyDescription("Workload-based humanTask candidate constraint.")
  private WorkloadConstraint humanTaskWorkloadConstraint;

  @JsonPropertyDescription("Candidate matching strategy ID. Built-in: \"exact\", \"subsumption\".")
  private String candidateMatching;

  @JsonPropertyDescription("HTN decomposition strategy ID. Default: \"identity\".")
  private String decompositionStrategy;

  @JsonPropertyDescription("Maximum nesting depth for dynamic decomposition. Default: 3.")
  private Integer maxDecompositionDepth;

  @JsonPropertyDescription("Maximum adaptation count per compound before Concede. Default: 5.")
  private Integer maxAdaptations;

  @JsonPropertyDescription("Case-Based Reasoning retrieval configuration.")
  private CbrConfig cbrConfig;

  @JsonPropertyDescription("Per-signal-provider weight configuration for composable agent routing.")
  private Map<String, Double> routingSignalWeights;

  private Map<String, CognitiveDemand> cognitiveDemands = Map.of();

  @JsonPropertyDescription("ACL grants created when a case of this type is started.")
  private Map<AclAction, List<String>> authorization;

  @JsonPropertyDescription(
      "Map of worker name to service account ID for tenant-specific endpoint resolution.")
  private Map<String, String> workerServiceAccountIds;

  @JsonPropertyDescription("Default M-of-N multi-party approval configuration for action gates.")
  private QuorumConfig defaultQuorum;

  @JsonPropertyDescription("Per-case reflection trigger configuration.")
  private ReflectionTriggerConfig reflectionTrigger;

  @JsonPropertyDescription("Per-case memory retrieval configuration.")
  private MemoryRetrievalConfig memoryRetrieval;

  @JsonPropertyDescription("Per-case plan adaptation configuration.")
  private AdaptationConfig adaptationConfig;

  @JsonPropertyDescription("Per-case resource constraints for decomposition and pattern execution.")
  private PlanningConstraints planningConstraints;

  @JsonPropertyDescription("Per-case expectation tracking configuration.")
  private MonitoringConfig monitoringConfig;

  @JsonPropertyDescription("Cascading decomposition strategy configuration.")
  private PortfolioConfig portfolioConfig;

  @JsonPropertyDescription("Typed streaming channels for worker-to-worker data flow.")
  private List<ChannelDeclaration> channels = List.of();

  @JsonPropertyDescription("GOAP action declarations for planning.")
  private List<GoapAction> goapActions;

  private Map<String, Set<String>> goalToEffectKeys;

  @JsonPropertyDescription("Per-case multi-level recovery configuration.")
  private RecoveryPolicy recoveryPolicy;

  public CaseDefinitionSpec() {
    this.capabilities = new ArrayList<>();
    this.workers = new ArrayList<>();
    this.bindings = new ArrayList<>();
    this.milestones = new ArrayList<>();
    this.goals = new ArrayList<>();
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

  public List<ContextConstraint> getHumanTaskContextConstraints() {
    return humanTaskContextConstraints;
  }

  public void setHumanTaskContextConstraints(List<ContextConstraint> constraints) {
    this.humanTaskContextConstraints = constraints != null ? List.copyOf(constraints) : List.of();
  }

  public WorkloadConstraint getHumanTaskWorkloadConstraint() {
    return humanTaskWorkloadConstraint;
  }

  public void setHumanTaskWorkloadConstraint(WorkloadConstraint constraint) {
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

  public CbrConfig getCbrConfig() {
    return cbrConfig;
  }

  public void setCbrConfig(CbrConfig cbrConfig) {
    this.cbrConfig = cbrConfig;
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

  public Map<String, CognitiveDemand> getCognitiveDemands() {
    return cognitiveDemands;
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

  public QuorumConfig getDefaultQuorum() {
    return defaultQuorum;
  }

  public void setDefaultQuorum(QuorumConfig defaultQuorum) {
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

  public PlanningConstraints getPlanningConstraints() {
    return planningConstraints;
  }

  public void setPlanningConstraints(PlanningConstraints planningConstraints) {
    this.planningConstraints = planningConstraints;
  }

  public MonitoringConfig getMonitoringConfig() {
    return monitoringConfig;
  }

  public void setMonitoringConfig(MonitoringConfig monitoringConfig) {
    this.monitoringConfig = monitoringConfig;
  }

  public PortfolioConfig getPortfolioConfig() {
    return portfolioConfig;
  }

  public void setPortfolioConfig(PortfolioConfig portfolioConfig) {
    this.portfolioConfig = portfolioConfig;
  }

  public List<ChannelDeclaration> getChannels() {
    return channels;
  }

  public void setChannels(List<ChannelDeclaration> channels) {
    this.channels = channels != null ? channels : List.of();
  }

  public List<GoapAction> getGoapActions() {
    return goapActions;
  }

  public void setGoapActions(List<GoapAction> goapActions) {
    this.goapActions = goapActions;
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
}
