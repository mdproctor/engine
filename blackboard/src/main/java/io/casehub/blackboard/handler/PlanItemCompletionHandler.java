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

import io.casehub.api.model.WorkerOutcome;
import io.casehub.blackboard.event.BlackboardEventBusAddresses;
import io.casehub.blackboard.event.SubCaseExecutionCompleted;
import io.casehub.blackboard.plan.CasePlanModel;
import io.casehub.blackboard.plan.PlanItem;
import io.casehub.blackboard.registry.BlackboardRegistry;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkflowExecutionCompleted;
import io.casehub.engine.common.internal.model.PlanItemStatus;
import io.casehub.engine.common.spi.event.PlanItemCompletedEvent;
import io.quarkus.vertx.ConsumeEvent;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.util.EnumSet;
import java.util.Set;
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

  private static final Set<PlanItemStatus> COMPLETABLE =
      EnumSet.of(PlanItemStatus.RUNNING, PlanItemStatus.DELEGATED);

  private final BlackboardRegistry registry;
  private final EventBus eventBus;
  private final Event<PlanItemCompletedEvent> planItemCompletedEvents;
  private final StageAutocompleteEvaluator stageAutocompleteEvaluator;

  @Inject
  public PlanItemCompletionHandler(
      BlackboardRegistry registry,
      EventBus eventBus,
      Event<PlanItemCompletedEvent> planItemCompletedEvents,
      StageAutocompleteEvaluator stageAutocompleteEvaluator) {
    this.registry = registry;
    this.eventBus = eventBus;
    this.planItemCompletedEvents = planItemCompletedEvents;
    this.stageAutocompleteEvaluator = stageAutocompleteEvaluator;
  }

  @ConsumeEvent(value = EventBusAddresses.WORKER_EXECUTION_FINISHED, blocking = true)
  public void onWorkerFinished(WorkflowExecutionCompleted event) {
    // Non-success outcomes are handled by WorkerOutcomeResolvedHandler — skip PlanItem completion.
    if (!(event.outcome() instanceof WorkerOutcome.Success)) {
      return;
    }
    // bindingName-first lookup: when non-null use direct binding lookup; fallback to completion
    // index
    if (event.bindingName() != null) {
      completePlanItemByBindingName(
          event.caseInstance().getUuid(), event.bindingName(), event.caseInstance().tenancyId);
    } else {
      completePlanItemByKey(
          event.caseInstance().getUuid(), event.worker().getName(), event.caseInstance().tenancyId);
    }
  }

  @ConsumeEvent(value = BlackboardEventBusAddresses.SUBCASE_EXECUTION_COMPLETED, blocking = true)
  public void onSubCaseFinished(SubCaseExecutionCompleted event) {
    completePlanItemByKey(event.parentCaseId(), event.childCaseId().toString(), event.tenancyId());
  }

  private void completePlanItemByBindingName(UUID caseId, String bindingName, String tenancyId) {
    CasePlanModel plan = registry.get(caseId).orElse(null);
    if (plan == null) return;

    plan.getPlanItemByBindingName(bindingName)
        .ifPresentOrElse(
            item -> {
              if (!COMPLETABLE.contains(item.getStatus())) {
                LOG.debugf(
                    "PlanItem %s for binding '%s' in case %s has status %s — not completable, skipping",
                    item.getPlanItemId(), bindingName, caseId, item.getStatus());
                return;
              }
              item.markCompleted();
              stageAutocompleteEvaluator.evaluate(caseId, plan, item.getPlanItemId());
              planItemCompletedEvents.fireAsync(
                  new PlanItemCompletedEvent(caseId, item.getPlanItemId(), bindingName, tenancyId));
            },
            () ->
                LOG.debugf(
                    "No PlanItem found for binding '%s' in case %s — pure choreography or already evicted",
                    bindingName, caseId));
  }

  private void completePlanItemByKey(UUID caseId, String trackingKey, String tenancyId) {
    CasePlanModel plan = registry.get(caseId).orElse(null);
    if (plan == null) return;

    String planItemId = registry.getPlanItemId(caseId, trackingKey).orElse(null);
    if (planItemId == null) {
      LOG.debugf(
          "No PlanItem indexed for key '%s' in case %s — pure choreography or already evicted",
          trackingKey, caseId);
      return;
    }

    plan.getPlanItem(planItemId)
        .ifPresent(
            item -> {
              if (!COMPLETABLE.contains(item.getStatus())) {
                LOG.debugf(
                    "PlanItem %s for key '%s' in case %s has status %s — not completable, skipping",
                    planItemId, trackingKey, caseId, item.getStatus());
                return;
              }
              item.markCompleted();
              // activeByBinding self-cleans lazily in hasActivePlanItem() — completed items remain
              // in itemsById for post-completion observability.
              stageAutocompleteEvaluator.evaluate(caseId, plan, planItemId);
              // Fire after markCompleted() so observers see the exact planItemId that completed.
              planItemCompletedEvents.fireAsync(
                  new PlanItemCompletedEvent(caseId, planItemId, trackingKey, tenancyId));
            });
  }
}
