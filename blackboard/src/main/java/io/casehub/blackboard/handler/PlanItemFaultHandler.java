/*
 * Copyright 2026-Present The Case Hub Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.casehub.blackboard.handler;

import io.casehub.blackboard.event.PlanItemFaultedEvent;
import io.casehub.blackboard.plan.CasePlanModel;
import io.casehub.blackboard.plan.PlanItem;
import io.casehub.blackboard.registry.BlackboardRegistry;
import io.casehub.engine.internal.event.EventBusAddresses;
import io.casehub.engine.internal.event.WorkerRetriesExhaustedEvent;
import io.casehub.engine.internal.model.PlanItemStatus;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.util.EnumSet;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Marks {@link PlanItem}s FAULTED when a worker's retries are exhausted.
 *
 * <p>Subscribes to {@code WORKER_RETRIES_EXHAUSTED} which is published via {@code
 * eventBus.publish()} — fan-out. Coexists with {@link
 * io.casehub.engine.internal.engine.handler.WorkerRetriesExhaustedEventHandler} which handles the
 * CaseInstance-level FAULTED transition.
 */
@ApplicationScoped
public class PlanItemFaultHandler {

  private static final Logger LOG = Logger.getLogger(PlanItemFaultHandler.class);

  private static final Set<PlanItemStatus> FAULTABLE =
      EnumSet.of(PlanItemStatus.PENDING, PlanItemStatus.RUNNING, PlanItemStatus.DELEGATED);

  private final BlackboardRegistry registry;
  private final Event<PlanItemFaultedEvent> planItemFaultedEvents;

  @Inject
  public PlanItemFaultHandler(
      BlackboardRegistry registry, Event<PlanItemFaultedEvent> planItemFaultedEvents) {
    this.registry = registry;
    this.planItemFaultedEvents = planItemFaultedEvents;
  }

  @ConsumeEvent(EventBusAddresses.WORKER_RETRIES_EXHAUSTED)
  public Uni<Void> onWorkerRetriesExhausted(WorkerRetriesExhaustedEvent event) {
    CasePlanModel plan = registry.get(event.caseId()).orElse(null);
    if (plan == null) return Uni.createFrom().voidItem();

    String planItemId = registry.getPlanItemId(event.caseId(), event.workerId()).orElse(null);
    if (planItemId == null) {
      LOG.debugf(
          "No PlanItem indexed for worker '%s' in case %s — pure choreography or already evicted",
          event.workerId(), event.caseId());
      return Uni.createFrom().voidItem();
    }

    plan.getPlanItem(planItemId)
        .ifPresent(
            item -> {
              if (!FAULTABLE.contains(item.getStatus())) {
                LOG.debugf(
                    "PlanItem %s for worker '%s' in case %s has status %s — not faultable, skipping",
                    planItemId, event.workerId(), event.caseId(), item.getStatus());
                return;
              }
              item.markFaulted();
              LOG.infof(
                  "PlanItem %s marked FAULTED for worker '%s' in case %s",
                  planItemId, event.workerId(), event.caseId());
              planItemFaultedEvents.fireAsync(
                  new PlanItemFaultedEvent(event.caseId(), planItemId, event.workerId()));
            });

    return Uni.createFrom().voidItem();
  }
}
