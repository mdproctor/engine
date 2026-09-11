package io.casehub.engine.queue.quarkus;

import io.casehub.engine.queue.reconcile.CaseLabelReconciler;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

@ApplicationScoped
public class CaseLabelReconcilerAdapter {

  @Inject CaseLabelReconciler reconciler;

  void reconcile(@Observes @Priority(200) StartupEvent event) {
    reconciler.reconcile();
  }
}
