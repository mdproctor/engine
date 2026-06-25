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
package io.casehub.engine.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.spi.WorkerFunctionProvider;
import io.casehub.worker.api.WorkerFunction;
import io.serverlessworkflow.api.WorkflowFormat;
import io.serverlessworkflow.api.WorkflowReader;
import io.serverlessworkflow.api.types.Workflow;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * {@link WorkerFunctionProvider} for Serverless Workflow workers.
 *
 * <p>Handles workers with a {@code do:} block. If the raw schema JSON lacks a {@code document}
 * node, a default document is injected (DSL 1.0.0, generated namespace/name/version) — same pattern
 * as the deleted {@code WorkerMarshaller.Deserializer}.
 */
@ApplicationScoped
public class FlowWorkerFunctionProvider implements WorkerFunctionProvider {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public boolean handles(final JsonNode rawWorkerNode) {
    return rawWorkerNode.has("do");
  }

  @Override
  public WorkerFunction create(final JsonNode rawWorkerNode) {
    try {
      ObjectNode workflowFields = MAPPER.createObjectNode();
      copyIfPresent(rawWorkerNode, workflowFields, "document");
      copyIfPresent(rawWorkerNode, workflowFields, "do");
      copyIfPresent(rawWorkerNode, workflowFields, "input");
      copyIfPresent(rawWorkerNode, workflowFields, "output");
      copyIfPresent(rawWorkerNode, workflowFields, "use");
      copyIfPresent(rawWorkerNode, workflowFields, "schedule");
      copyIfPresent(rawWorkerNode, workflowFields, "timeout");

      if (!workflowFields.has("document")) {
        workflowFields
            .putObject("document")
            .put("dsl", "1.0.0")
            .put("name", "generated")
            .put("namespace", "generated")
            .put("version", "1.0.0");
      }

      final Workflow workflow =
          WorkflowReader.readWorkflowFromString(
              MAPPER.writeValueAsString(workflowFields), WorkflowFormat.YAML);
      return new FlowWorkerFunction(workflow);
    } catch (final Exception e) {
      throw new IllegalArgumentException("Failed to parse workflow definition from YAML", e);
    }
  }

  private static void copyIfPresent(
      final JsonNode source, final ObjectNode target, final String field) {
    if (source.has(field)) {
      target.set(field, source.get(field));
    }
  }
}
