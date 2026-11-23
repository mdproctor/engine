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
package io.casehub.engine.agent;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.model.AgentWorkerFunction;
import io.casehub.api.model.ai.DeferredAgentConfig;
import io.casehub.api.spi.WorkerFunctionProvider;
import io.casehub.worker.api.WorkerFunction;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class AgentWorkerFunctionProvider implements WorkerFunctionProvider {

  @Override
  public boolean handles(JsonNode rawWorkerNode) {
    return rawWorkerNode.has("agent") && rawWorkerNode.get("agent").has("slot");
  }

  @Override
  public WorkerFunction<?, ?> create(JsonNode rawWorkerNode) {
    JsonNode agentNode = rawWorkerNode.get("agent");
    String slot = agentNode.get("slot").asText();
    String model = textOrNull(agentNode, "model");
    String userMessage = textOrNull(agentNode, "userMessage");
    String inputProjection = textOrNull(agentNode, "inputProjection");
    String outputProjection = textOrNull(agentNode, "outputProjection");
    return AgentWorkerFunction.ofSlot(
        new DeferredAgentConfig.SlotConfig(
            slot, model, userMessage, inputProjection, outputProjection));
  }

  private static String textOrNull(JsonNode node, String field) {
    return node.has(field) ? node.get(field).asText() : null;
  }
}
