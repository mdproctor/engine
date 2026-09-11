package io.casehub.engine.queue.quarkus;

import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.queue.entry.CaseQueueEntryManager;
import io.casehub.engine.queue.event.CaseQueueEntryClaimed;
import io.casehub.engine.queue.event.CaseQueueEntryEscalated;
import io.casehub.engine.queue.event.CaseQueueEntryReleased;
import io.casehub.engine.queue.event.CaseQueueEntryRevoked;
import io.casehub.engine.queue.event.CaseQueueEvent;
import io.casehub.engine.queue.label.CaseLabelEvaluator;
import io.casehub.engine.queue.reconcile.CaseLabelReconciler;
import io.casehub.engine.queue.service.CaseQueueService;
import io.casehub.engine.queue.spi.CaseQueueEntryStore;
import io.casehub.engine.queue.store.InMemoryCaseQueueEntryStore;
import io.casehub.engine.queue.view.CaseQueueViewManager;
import io.casehub.platform.api.view.CrossTenantSubjectViewStore;
import io.casehub.platform.api.view.SubjectViewStore;
import io.casehub.platform.view.SubjectViewOrchestrator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class QueueBeans {

  @Produces
  @ApplicationScoped
  CaseQueueViewManager caseQueueViewManager(
      SubjectViewOrchestrator views, SubjectViewStore viewStore) {
    return new CaseQueueViewManager(views, viewStore);
  }

  @Produces
  @ApplicationScoped
  InMemoryCaseQueueEntryStore inMemoryCaseQueueEntryStore() {
    return new InMemoryCaseQueueEntryStore();
  }

  @Produces
  @ApplicationScoped
  CaseQueueService caseQueueService(
      CaseQueueEntryStore store,
      Event<CaseQueueEntryClaimed> claimed,
      Event<CaseQueueEntryReleased> released,
      Event<CaseQueueEntryEscalated> escalated) {
    return new CaseQueueService(
        store,
        e -> claimed.fireAsync(e),
        e -> released.fireAsync(e),
        e -> escalated.fireAsync(e));
  }

  @Produces
  @ApplicationScoped
  CaseQueueEntryManager caseQueueEntryManager(
      CaseQueueEntryStore store, Event<CaseQueueEntryRevoked> revoked) {
    return new CaseQueueEntryManager(store, e -> revoked.fireAsync(e));
  }

  @Produces
  @ApplicationScoped
  CaseLabelEvaluator caseLabelEvaluator(
      CaseDefinitionRegistry definitionRegistry,
      CaseInstanceRepository caseInstanceRepository,
      SubjectViewOrchestrator views,
      Event<CaseQueueEvent> queueEvents) {
    return new CaseLabelEvaluator(
        definitionRegistry, caseInstanceRepository, views, e -> queueEvents.fire(e));
  }

  @Produces
  @ApplicationScoped
  CaseLabelReconciler caseLabelReconciler(
      CaseDefinitionRegistry definitionRegistry,
      CaseInstanceRepository caseInstanceRepository,
      SubjectViewOrchestrator views,
      CrossTenantSubjectViewStore crossTenantViewStore,
      Event<CaseQueueEvent> queueEvents) {
    return new CaseLabelReconciler(
        definitionRegistry,
        caseInstanceRepository,
        views,
        crossTenantViewStore,
        e -> queueEvents.fire(e));
  }
}
