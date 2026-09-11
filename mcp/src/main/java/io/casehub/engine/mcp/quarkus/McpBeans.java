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
package io.casehub.engine.mcp.quarkus;

import io.casehub.engine.mcp.McpCapabilityHealth;
import io.casehub.engine.mcp.McpClientRegistry;
import io.casehub.engine.mcp.McpEndpointRegistry;
import io.casehub.engine.mcp.McpWorkerFunctionHandler;
import io.casehub.engine.mcp.McpWorkerFunctionProvider;
import io.quarkus.virtual.threads.VirtualThreads;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.util.concurrent.ExecutorService;

@ApplicationScoped
public class McpBeans {

  @Produces
  @ApplicationScoped
  McpEndpointRegistry mcpEndpointRegistry() {
    return new McpEndpointRegistry();
  }

  @Produces
  @ApplicationScoped
  McpClientRegistry mcpClientRegistry() {
    return new McpClientRegistry();
  }

  @Produces
  @ApplicationScoped
  McpWorkerFunctionProvider mcpWorkerFunctionProvider(McpEndpointRegistry endpointRegistry) {
    return new McpWorkerFunctionProvider(endpointRegistry);
  }

  @Produces
  @ApplicationScoped
  McpCapabilityHealth mcpCapabilityHealth(
      McpEndpointRegistry endpointRegistry, McpClientRegistry clientRegistry) {
    return new McpCapabilityHealth(endpointRegistry, clientRegistry);
  }

  @Produces
  @ApplicationScoped
  McpWorkerFunctionHandler mcpWorkerFunctionHandler(
      McpClientRegistry clientRegistry, @VirtualThreads ExecutorService virtualThreads) {
    return new McpWorkerFunctionHandler(clientRegistry, virtualThreads);
  }
}
