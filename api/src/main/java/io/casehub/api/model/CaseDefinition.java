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

import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.platform.api.path.Path;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import java.util.ArrayList;
import java.util.HashMap;
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
  private List<String> panelNames;
  private Map<String, AgentDescriptor> agentDescriptors = Map.of();
  private String planningStrategy;
  private String agentRouting;
  private String implementationRouting;
  private String candidateMatching;
  private Set<Path> types = Set.of();
  private Set<Path> labels = Set.of();

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

  public List<String> getPanelNames() {
    return panelNames;
  }

  public void setPanelNames(List<String> panelNames) {
    this.panelNames = panelNames;
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

  public String getCandidateMatching() {
    return candidateMatching;
  }

  public void setCandidateMatching(String candidateMatching) {
    this.candidateMatching = candidateMatching;
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
    private List<String> panelNames;
    private Map<String, AgentDescriptor> agentDescriptors = new HashMap<>();
    private String planningStrategy;
    private String agentRouting;
    private String implementationRouting;
    private String candidateMatching;
    private Set<Path> types = new LinkedHashSet<>();
    private Set<Path> labels = new LinkedHashSet<>();

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
      this.completion = new GoalBasedCompletion(success, failure);
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

    public Builder panelNames(List<String> panelNames) {
      this.panelNames = panelNames;
      return this;
    }

    public Builder panel(String name) {
      if (this.panelNames == null) {
        this.panelNames = new java.util.ArrayList<>();
      }
      this.panelNames.add(name);
      return this;
    }

    public Builder panels(String... names) {
      if (this.panelNames == null) {
        this.panelNames = new java.util.ArrayList<>();
      }
      java.util.Collections.addAll(this.panelNames, names);
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

    public Builder candidateMatching(String candidateMatching) {
      this.candidateMatching = candidateMatching;
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
      caseHubDefinition.setPanelNames(panelNames);
      caseHubDefinition.setAgentDescriptors(agentDescriptors);
      caseHubDefinition.setPlanningStrategy(planningStrategy);
      caseHubDefinition.setAgentRouting(agentRouting);
      caseHubDefinition.setImplementationRouting(implementationRouting);
      caseHubDefinition.setCandidateMatching(candidateMatching);
      caseHubDefinition.setTypes(types);
      caseHubDefinition.setLabels(labels);

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
