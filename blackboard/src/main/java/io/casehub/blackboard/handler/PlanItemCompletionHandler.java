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

import io.casehub.blackboard.event.BlackboardEventBusAddresses;
import io.casehub.blackboard.event.PlanItemCompletedEvent;
import io.casehub.blackboard.event.StageCompletedEvent;
import io.casehub.blackboard.plan.CasePlanModel;
import io.casehub.blackboard.plan.PlanItem;
import io.casehub.blackboard.registry.BlackboardRegistry;
import io.casehub.blackboard.stage.Stage;
import io.casehub.engine.internal.event.EventBusAddresses;
import io.casehub.engine.internal.event.WorkflowExecutionCompleted;
import io.casehub.engine.internal.model.PlanItemStatus;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Marks {@link PlanItem}s COMPLETED when a worker finishes, then evaluates Stage autocomplete for
 * any active Stage containing the completed item.
 *
 * <p>Subscribes to {@code WORKER_EXECUTION_FINISHED} which is published via {@code
 * eventBus.publish()} — fan-out. Coexists with {@link
 * io.casehub.engine.internal.engine.handler.WorkflowExecutionCompletedHandler}.
 *
 * <p><strong>Internal Event Use:</strong> {@link WorkflowExecutionCompleted} is in the {@code
 * engine.internal.event} package. For event consumption via {@code @ConsumeEvent}, the handler must
 * use the same event type that was published by the engine — there is no public API alternative.
 * Using internal event types in {@code @ConsumeEvent} handlers is the accepted pattern in this
 * codebase.
 *
 * <p>See casehubio/engine#76. Stage future alignment: casehubio/engine#84.
 */
@ApplicationScoped
public class PlanItemCompletionHandler {

  private static final Logger LOG = Logger.getLogger(PlanItemCompletionHandler.class);

  private final BlackboardRegistry registry;
  private final EventBus eventBus;
  private final Event<PlanItemCompletedEvent> planItemCompletedEvents;

  @Inject
  public PlanItemCompletionHandler(
      BlackboardRegistry registry,
      EventBus eventBus,
      Event<PlanItemCompletedEvent> planItemCompletedEvents) {
    this.registry = registry;
    this.eventBus = eventBus;
    this.planItemCompletedEvents = planItemCompletedEvents;
  }

  @ConsumeEvent(EventBusAddresses.WORKER_EXECUTION_FINISHED)
  public Uni<Void> onWorkerFinished(WorkflowExecutionCompleted event) {
    UUID caseId = event.caseInstance().getUuid();
    String workerName = event.worker().getName();

    CasePlanModel plan = registry.get(caseId).orElse(null);
    if (plan == null) return Uni.createFrom().voidItem();

    String planItemId = registry.getPlanItemId(caseId, workerName).orElse(null);
    if (planItemId == null) {
      LOG.debugf(
          "No PlanItem indexed for worker '%s' in case %s — pure choreography or already evicted",
          workerName, caseId);
      return Uni.createFrom().voidItem();
    }

    plan.getPlanItem(planItemId)
        .ifPresent(
            item -> {
              item.markCompleted();
              // activeByBinding self-cleans lazily in hasActivePlanItem() when it encounters a
              // terminal item — so hasActivePlanItem("binding-x") returns false immediately after
              // markCompleted(). itemsById retains completed items for post-completion
              // observability (e.g. integration-test assertions on PlanItem status).
              evaluateStageAutocomplete(caseId, plan, planItemId);
              // Fire after markCompleted() — observers see the exact planItemId that completed,
              // not the current completionIndex which may have been overwritten by a re-trigger.
              planItemCompletedEvents.fireAsync(
                  new PlanItemCompletedEvent(caseId, planItemId, workerName));
            });

    return Uni.createFrom().voidItem();
  }

  private void evaluateStageAutocomplete(UUID caseId, CasePlanModel plan, String completedItemId) {
    for (Stage stage : plan.getActiveStages()) {
      if (!stage.isAutocomplete()) continue;
      if (!stage.getRequiredItemIds().contains(completedItemId)) continue;

      boolean allDone =
          stage.getRequiredItemIds().stream()
              .allMatch(
                  itemId ->
                      plan.getPlanItem(itemId)
                          .map(pi -> pi.getStatus() == PlanItemStatus.COMPLETED)
                          .orElse(
                              false)); // unregistered item — treat as not done, blocks autocomplete

      if (allDone) {
        stage.complete();
        eventBus.publish(
            BlackboardEventBusAddresses.STAGE_COMPLETED, new StageCompletedEvent(caseId, stage));
      }
    }
  }
}
