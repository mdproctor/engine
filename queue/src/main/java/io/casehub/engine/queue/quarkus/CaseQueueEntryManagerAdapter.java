package io.casehub.engine.queue.quarkus;

import io.casehub.engine.queue.entry.CaseQueueEntryManager;
import io.casehub.engine.queue.event.CaseQueueEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

@ApplicationScoped
public class CaseQueueEntryManagerAdapter {

  @Inject CaseQueueEntryManager manager;

  void onQueueEvent(@Observes CaseQueueEvent event) {
    manager.onQueueEvent(event);
  }
}
