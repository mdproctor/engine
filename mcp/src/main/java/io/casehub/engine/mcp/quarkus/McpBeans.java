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
