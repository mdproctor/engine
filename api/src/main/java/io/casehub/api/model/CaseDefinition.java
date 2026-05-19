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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
