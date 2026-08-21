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
package io.casehub.engine.react;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.spi.WorkerFunctionProvider;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.WorkerFunction;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class ReActWorkerFunctionProvider implements WorkerFunctionProvider {

  @Override
  public boolean handles(JsonNode rawWorkerNode) {
    return rawWorkerNode.has("react");
  }

  @Override
  public WorkerFunction<?, ?> create(JsonNode rawWorkerNode) {
    var reactNode = rawWorkerNode.get("react");
    int maxCycles = reactNode.has("maxCycles") ? reactNode.get("maxCycles").asInt() : 20;
    String systemPrompt = resolveSystemPrompt(rawWorkerNode);

    List<ToolSource> tools = resolveTools(reactNode);
    if (tools.isEmpty()) {
      tools =
          List.of(
              new ToolSource.LocalTool(
                  "passthrough",
                  "Passthrough tool (placeholder until capabilities are resolved)",
                  args -> args,
                  java.util.Map.of()));
    }

    return new ReActWorkerFunction(null, systemPrompt, tools, maxCycles);
  }

  private String resolveSystemPrompt(JsonNode rawWorkerNode) {
    if (rawWorkerNode.has("agent") && rawWorkerNode.get("agent").has("systemPrompt")) {
      return rawWorkerNode.get("agent").get("systemPrompt").asText();
    }
    return "";
  }

  private List<ToolSource> resolveTools(JsonNode reactNode) {
    if (!reactNode.has("tools")) {
      return List.of();
    }
    var toolsNode = reactNode.get("tools");
    if (!toolsNode.isArray()) {
      return List.of();
    }
    var tools = new ArrayList<ToolSource>();
    for (var toolName : toolsNode) {
      var name = toolName.asText();
      var cap = new Capability(name, ".", ".", name);
      tools.add(new ToolSource.WorkerTool(cap, name));
    }
    return tools;
  }
}
