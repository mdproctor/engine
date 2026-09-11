package io.casehub.engine.a2a.quarkus;

import io.casehub.engine.a2a.A2AClientRegistry;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

@ApplicationScoped
public class A2AClientRegistryAdapter {

  @Inject A2AClientRegistry clientRegistry;

  void onShutdown(@Observes ShutdownEvent event) {
    clientRegistry.shutdown();
  }
}
