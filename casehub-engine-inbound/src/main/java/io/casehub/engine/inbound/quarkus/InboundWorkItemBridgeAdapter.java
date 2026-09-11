package io.casehub.engine.inbound.quarkus;

import io.casehub.engine.inbound.InboundWorkItemBridge;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

@ApplicationScoped
public class InboundWorkItemBridgeAdapter {

  @Inject Instance<InboundWorkItemBridge> bridge;

  void onStartup(@Observes StartupEvent event) {
    if (bridge.isResolvable()) {
      InboundWorkItemBridge b = bridge.get();
      // Ambiguity check moved to Quarkus wiring — CDI validates this at build time
    }
  }
}
