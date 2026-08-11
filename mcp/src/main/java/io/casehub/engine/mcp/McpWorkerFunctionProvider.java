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
package io.casehub.engine.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.spi.DiscoveredWorker;
import io.casehub.api.spi.WorkerFunctionProvider;
import io.casehub.engine.common.internal.auth.AuthConfig;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.WorkerFunction;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jboss.logging.Logger;

@ApplicationScoped
public class McpWorkerFunctionProvider implements WorkerFunctionProvider {

  private static final Logger LOG = Logger.getLogger(McpWorkerFunctionProvider.class);

  @jakarta.inject.Inject McpEndpointRegistry endpointRegistry;

  @Override
  public boolean handles(final JsonNode rawWorkerNode) {
    return rawWorkerNode.has("mcp");
  }

  @Override
  public WorkerFunction<?, ?> create(final JsonNode rawWorkerNode) {
    throw new UnsupportedOperationException("MCP provider uses discoverWorkers() — not create()");
  }

  @Override
  public List<DiscoveredWorker> discoverWorkers(final JsonNode rawWorkerNode) {
    final JsonNode mcpNode = rawWorkerNode.get("mcp");
    final McpTransport transport = parseTransport(mcpNode);
    final String baseName = rawWorkerNode.get("name").asText();
    final Set<String> explicitCapabilities = parseExplicitCapabilities(rawWorkerNode);

    final McpSyncClient client = createDiscoveryClient(transport);
    try {
      client.initialize();
      final List<Tool> allTools = client.listTools().tools();
      final List<Tool> tools = filterTools(allTools, explicitCapabilities);

      if (tools.isEmpty()) {
        LOG.warnf(
            "MCP server at %s discovered no matching tools (total: %d, filter: %s)",
            transport, allTools.size(), explicitCapabilities);
        return List.of();
      }

      final boolean singleTool = tools.size() == 1;
      final List<DiscoveredWorker> discovered = new ArrayList<>();
      for (final Tool tool : tools) {
        final String workerName = singleTool ? baseName : baseName + "--" + tool.name();
        final Capability capability =
            Capability.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(".")
                .outputSchema(".")
                .build();
        final McpWorkerFunction function = new McpWorkerFunction(transport, tool.name());
        endpointRegistry.register(workerName, transport);
        discovered.add(new DiscoveredWorker(workerName, capability, function));
      }
      return discovered;
    } finally {
      try {
        client.closeGracefully();
      } catch (Exception e) {
        LOG.debugf("Error closing MCP discovery client: %s", e.getMessage());
      }
    }
  }

  McpSyncClient createDiscoveryClient(final McpTransport transport) {
    final var sdkTransport = McpTransportFactory.create(transport);
    return McpClient.sync(sdkTransport).capabilities(ClientCapabilities.builder().build()).build();
  }

  private McpTransport parseTransport(final JsonNode mcpNode) {
    if (mcpNode.has("command")) {
      final List<String> command = new ArrayList<>();
      mcpNode.get("command").forEach(n -> command.add(n.asText()));
      final java.util.Map<String, String> env = new java.util.LinkedHashMap<>();
      if (mcpNode.has("env")) {
        mcpNode
            .get("env")
            .fields()
            .forEachRemaining(e -> env.put(e.getKey(), e.getValue().asText()));
      }
      return new McpTransport.Stdio(command, env);
    }
    if (mcpNode.has("url")) {
      final AuthConfig auth = parseAuth(mcpNode);
      return new McpTransport.Http(mcpNode.get("url").asText(), auth);
    }
    throw new IllegalArgumentException(
        "MCP worker must declare either 'command' (stdio) or 'url' (http)");
  }

  private AuthConfig parseAuth(final JsonNode mcpNode) {
    if (!mcpNode.has("auth")) {
      return AuthConfig.NONE;
    }
    final JsonNode authNode = mcpNode.get("auth");
    final String typeStr = authNode.has("type") ? authNode.get("type").asText("none") : "none";
    final AuthConfig.AuthType type =
        switch (typeStr.toLowerCase()) {
          case "bearer" -> AuthConfig.AuthType.BEARER;
          case "api-key", "api_key" -> AuthConfig.AuthType.API_KEY;
          default -> AuthConfig.AuthType.NONE;
        };
    final String tokenConfigKey =
        authNode.has("tokenConfigKey") ? authNode.get("tokenConfigKey").asText() : null;
    return new AuthConfig(type, tokenConfigKey);
  }

  private Set<String> parseExplicitCapabilities(final JsonNode rawWorkerNode) {
    if (!rawWorkerNode.has("capabilities") || rawWorkerNode.get("capabilities").isEmpty()) {
      return Set.of();
    }
    final Set<String> caps = new java.util.LinkedHashSet<>();
    rawWorkerNode.get("capabilities").forEach(n -> caps.add(n.asText()));
    return caps;
  }

  private List<Tool> filterTools(final List<Tool> tools, final Set<String> filter) {
    if (filter.isEmpty()) {
      return tools;
    }
    return tools.stream().filter(t -> filter.contains(t.name())).toList();
  }
}
