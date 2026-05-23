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

import io.casehub.api.context.CaseContext;
import io.casehub.api.model.ai.Agent;
import io.casehub.api.model.holder.WorkerFunctionHolder;
import io.casehub.api.plan.PlanElement;
import io.casehub.eidos.api.AgentDescriptor;
import io.serverlessworkflow.api.types.Workflow;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public class Worker implements PlanElement {

  private final String name;
  private final List<Capability> capabilities;
  private final WorkerFunctionHolder<?> functionHolder;
  private ExecutionPolicy executionPolicy;
  private String description;
  private AgentDescriptor agentDescriptor;

  public Worker(
      String name,
      List<Capability> capabilities,
      Function<CaseContext, Map<String, Object>> function) {
    this(name, capabilities, new WorkerFunctionHolder<>(function));
  }

  public Worker(String name, List<Capability> capabilities, Workflow workflow) {
    this(name, capabilities, new WorkerFunctionHolder<>(workflow));
  }

  public Worker(String name, List<Capability> capabilities, File file) {
    this(name, capabilities, new WorkerFunctionHolder<>(file));
  }

  public Worker(String name, List<Capability> capabilities, Agent agent) {
    this(name, capabilities, new WorkerFunctionHolder<>(agent));
  }

  private Worker(
      String name, List<Capability> capabilities, WorkerFunctionHolder<?> functionHolder) {
    this(name, capabilities, functionHolder, new ExecutionPolicy());
  }

  private Worker(
      String name,
      List<Capability> capabilities,
      WorkerFunctionHolder<?> functionHolder,
      ExecutionPolicy executionPolicy) {
    this.name = name;
    this.capabilities = capabilities;
    this.functionHolder = functionHolder;
    this.executionPolicy = executionPolicy;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public void setExecutionPolicy(ExecutionPolicy executionPolicy) {
    this.executionPolicy = executionPolicy;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public List<Capability> getCapabilities() {
    return capabilities;
  }

  public ExecutionPolicy getExecutionPolicy() {
    return executionPolicy;
  }

  public WorkerFunctionHolder<?> getFunction() {
    return functionHolder;
  }

  public AgentDescriptor agentDescriptor() {
    return agentDescriptor;
  }

  public boolean hasDescriptor() {
    return agentDescriptor != null;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {

    private String name;
    private List<Capability> capabilities;
    private WorkerFunctionHolder<?> functionHolder;
    private ExecutionPolicy executionPolicy;
    private String description;
    private AgentDescriptor agentDescriptor;

    private Builder() {}

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder capabilities(Capability... capabilities) {
      this.capabilities = Arrays.asList(capabilities);
      return this;
    }

    public Builder capabilities(List<Capability> capabilities) {
      this.capabilities = capabilities;
      return this;
    }

    public Builder function(Function<Map<String, Object>, Map<String, Object>> function) {
      this.functionHolder = new WorkerFunctionHolder<>(function);
      return this;
    }

    public Builder function(Workflow workflow) {
      this.functionHolder = new WorkerFunctionHolder<>(workflow);
      return this;
    }

    public Builder function(File file) {
      this.functionHolder = new WorkerFunctionHolder<>(file);
      return this;
    }

    public Builder function(Agent agent) {
      this.functionHolder = new WorkerFunctionHolder<>(agent);
      return this;
    }

    public Builder executionPolicy(ExecutionPolicy executionPolicy) {
      this.executionPolicy = executionPolicy;
      return this;
    }

    public Builder description(String description) {
      this.description = description;
      return this;
    }

    public Builder agentDescriptor(AgentDescriptor agentDescriptor) {
      this.agentDescriptor = agentDescriptor;
      return this;
    }

    public Worker build() {
      Worker worker =
          new Worker(
              Objects.requireNonNull(name),
              Objects.requireNonNull(capabilities),
              Objects.requireNonNull(functionHolder));
      if (executionPolicy != null) {
        worker.setExecutionPolicy(executionPolicy);
      }
      worker.setDescription(description);
      worker.agentDescriptor = agentDescriptor;
      return worker;
    }
  }
}
