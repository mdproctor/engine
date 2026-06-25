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
package io.casehub.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.casehub.model.marshaller.WorkerMarshaller;
import java.util.List;

@JsonSerialize(using = WorkerMarshaller.Serializer.class)
@JsonDeserialize(using = WorkerMarshaller.Deserializer.class)
public class Worker {

  // PENDING → PROCESSING → COMPLETED → FAILED

  private String name;
  private String description;
  private List<String> capabilities;
  private JsonNode inputSchema;
  private JsonNode outputSchema;

  private ExecutionPolicy executionPolicy = new ExecutionPolicy();

  /**
   * Workflow definition: either String (path to external workflow file) OR JsonNode (embedded raw
   * workflow definition). Type: String | JsonNode
   */
  private Object workflow;

  /** AI Agent configuration */
  private Agent agent;

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public List<String> getCapabilities() {
    return capabilities;
  }

  public void setCapabilities(List<String> capabilities) {
    this.capabilities = capabilities;
  }

  public JsonNode getInputSchema() {
    return inputSchema;
  }

  public void setInputSchema(JsonNode inputSchema) {
    this.inputSchema = inputSchema;
  }

  public JsonNode getOutputSchema() {
    return outputSchema;
  }

  public void setOutputSchema(JsonNode outputSchema) {
    this.outputSchema = outputSchema;
  }

  public ExecutionPolicy getExecutionPolicy() {
    return executionPolicy;
  }

  public void setExecutionPolicy(ExecutionPolicy executionPolicy) {
    this.executionPolicy = executionPolicy;
  }

  public Object getWorkflow() {
    return workflow;
  }

  public void setWorkflow(Object workflow) {
    this.workflow = workflow;
  }

  public boolean isWorkflowRef() {
    return workflow instanceof String;
  }

  public boolean hasWorkflowDefinition() {
    return workflow instanceof JsonNode;
  }

  public String getWorkflowAsRef() {
    return (String) workflow;
  }

  public JsonNode getWorkflowDefinition() {
    return (JsonNode) workflow;
  }

  public Agent getAgent() {
    return agent;
  }

  public void setAgent(Agent agent) {
    this.agent = agent;
  }
}
