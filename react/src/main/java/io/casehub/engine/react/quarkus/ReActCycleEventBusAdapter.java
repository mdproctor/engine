package io.casehub.engine.react.quarkus;

import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.react.ReActCycleEventHandler;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ReActCycleEventBusAdapter {

  @Inject ReActCycleEventHandler handler;

  @ConsumeEvent(EventBusAddresses.REACT_CYCLE)
  @RunOnVirtualThread
  public void onReactCycle(JsonObject message) {
    handler.onReactCycle(message);
  }
}
