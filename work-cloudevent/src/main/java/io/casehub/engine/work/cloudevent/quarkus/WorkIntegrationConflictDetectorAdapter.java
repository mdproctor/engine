package io.casehub.engine.work.cloudevent.quarkus;

import io.casehub.engine.work.cloudevent.WorkIntegrationConflictDetector;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

@ApplicationScoped
public class WorkIntegrationConflictDetectorAdapter {

  @Inject WorkIntegrationConflictDetector detector;

  void onStartup(@Observes @Priority(1) StartupEvent event) {
    detector.check();
  }
}
