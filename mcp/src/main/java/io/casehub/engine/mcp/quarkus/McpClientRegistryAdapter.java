package io.casehub.engine.mcp.quarkus;

import io.casehub.engine.mcp.McpClientRegistry;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

@ApplicationScoped
public class McpClientRegistryAdapter {

  @Inject McpClientRegistry clientRegistry;

  void onShutdown(@Observes ShutdownEvent event) {
    clientRegistry.shutdown();
  }
}
