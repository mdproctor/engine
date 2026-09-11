package io.casehub.engine.inbound.quarkus;

import io.casehub.connectors.InboundMessage;
import io.casehub.engine.inbound.InboundSignalBridge;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

@ApplicationScoped
public class InboundSignalBridgeAdapter {

  @Inject InboundSignalBridge bridge;

  void onInboundMessage(@ObservesAsync InboundMessage message) {
    bridge.onInboundMessage(message);
  }
}
