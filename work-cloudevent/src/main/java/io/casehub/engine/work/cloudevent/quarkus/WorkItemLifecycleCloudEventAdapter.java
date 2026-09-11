package io.casehub.engine.work.cloudevent.quarkus;

import io.casehub.engine.work.cloudevent.WorkItemLifecycleCloudEventConsumer;
import io.cloudevents.CloudEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

@ApplicationScoped
public class WorkItemLifecycleCloudEventAdapter {

  @Inject WorkItemLifecycleCloudEventConsumer consumer;

  void onLifecycleCloudEvent(@ObservesAsync CloudEvent ce) {
    consumer.onLifecycleCloudEvent(ce);
  }
}
