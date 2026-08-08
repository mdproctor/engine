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
package io.casehub.engine.planning.handler;

import io.casehub.api.context.ContextLayer;
import io.casehub.api.model.TaskStatus;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkflowExecutionCompleted;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.PlanAdaptationEvaluator;
import io.casehub.engine.common.spi.event.PlanItemStateChangedEvent;
import io.casehub.engine.planning.event.BlackboardEventBusAddresses;
import io.casehub.engine.planning.event.SubCaseExecutionCompleted;
import io.casehub.engine.planning.plan.CasePlanModel;
import io.casehub.engine.planning.plan.PlanItem;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import io.casehub.worker.api.WorkerOutcome;
import io.quarkus.vertx.ConsumeEvent;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
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

  private static final Set<TaskStatus> COMPLETABLE =
      EnumSet.of(TaskStatus.RUNNING, TaskStatus.DELEGATED);

  private final BlackboardRegistry registry;
  private final EventBus eventBus;
  private final Event<PlanItemStateChangedEvent> planItemStateChangedEvents;
  private final CompoundCompletionEvaluator compoundCompletionEvaluator;
  private final Instance<PlanAdaptationEvaluator> planAdaptationEvaluator;

  @Inject
  public PlanItemCompletionHandler(
      BlackboardRegistry registry,
      EventBus eventBus,
      Event<PlanItemStateChangedEvent> planItemStateChangedEvents,
      CompoundCompletionEvaluator compoundCompletionEvaluator,
      Instance<PlanAdaptationEvaluator> planAdaptationEvaluator) {
    this.registry = registry;
    this.eventBus = eventBus;
    this.planItemStateChangedEvents = planItemStateChangedEvents;
    this.compoundCompletionEvaluator = compoundCompletionEvaluator;
    this.planAdaptationEvaluator = planAdaptationEvaluator;
  }

  @ConsumeEvent(value = EventBusAddresses.WORKER_EXECUTION_FINISHED, blocking = true)
  public void onWorkerFinished(WorkflowExecutionCompleted event) {
    // Non-success outcomes are handled by WorkerOutcomeResolvedHandler — skip PlanItem completion.
    if (!(event.outcome() instanceof WorkerOutcome.Success)
        && !(event.outcome() instanceof WorkerOutcome.Completed)) {
      return;
    }
    // bindingName-first lookup: when non-null use direct binding lookup; fallback to completion
    // index
    if (event.bindingName() != null) {
      completePlanItemByBindingName(
          event.caseInstance().getUuid(), event.bindingName(), event.caseInstance().tenancyId);
    } else {
      completePlanItemByKey(
          event.caseInstance().getUuid(), event.worker().name(), event.caseInstance().tenancyId);
    }
    // WorkflowExecutionCompletedHandler also publishes CONTEXT_CHANGED (after output application),
    // but handler ordering on WORKER_EXECUTION_FINISHED fan-out is non-deterministic. If that
    // CONTEXT_CHANGED is processed before this handler marks the PlanItem COMPLETED, the planning
    // strategy sees stale state and may skip the next step. This second CONTEXT_CHANGED guarantees
    // re-evaluation with consistent PlanItem state. Refs casehubio/engine#646, #659.
    CaseInstance ci = event.caseInstance();
    if (ci.getCaseContext() != null) {
      eventBus.publish(
          EventBusAddresses.CONTEXT_CHANGED,
          new CaseContextChangedEvent(ci, ci.getCaseContext().snapshot(), ContextLayer.WORKING));
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
              TaskStatus prevStatus = item.getStatus();
              item.markCompleted();
              if (planAdaptationEvaluator.isResolvable()) {
                planAdaptationEvaluator
                    .get()
                    .evaluateAdaptation(caseId, tenancyId, bindingName, TaskStatus.COMPLETED);
              }
              compoundCompletionEvaluator.evaluate(caseId, tenancyId, plan, item.getBindingName());
              planItemStateChangedEvents.fireAsync(
                  new PlanItemStateChangedEvent(
                      caseId,
                      item.getPlanItemId(),
                      bindingName,
                      prevStatus,
                      TaskStatus.COMPLETED,
                      tenancyId));
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
              TaskStatus prevStatus = item.getStatus();
              item.markCompleted();
              if (planAdaptationEvaluator.isResolvable()) {
                planAdaptationEvaluator
                    .get()
                    .evaluateAdaptation(
                        caseId, tenancyId, item.getBindingName(), TaskStatus.COMPLETED);
              }
              compoundCompletionEvaluator.evaluate(caseId, tenancyId, plan, item.getBindingName());
              planItemStateChangedEvents.fireAsync(
                  new PlanItemStateChangedEvent(
                      caseId,
                      planItemId,
                      item.getBindingName(),
                      prevStatus,
                      TaskStatus.COMPLETED,
                      tenancyId));
            });
  }
}
