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

import io.casehub.api.model.TaskStatus;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkerRetriesExhaustedEvent;
import io.casehub.engine.common.spi.PlanAdaptationEvaluator;
import io.casehub.engine.common.spi.event.PlanItemStateChangedEvent;
import io.casehub.engine.planning.plan.CasePlanModel;
import io.casehub.engine.planning.plan.PlanItem;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Marks a CapabilityTarget PlanItem FAULTED when its Quartz worker exhausts all retries or is
 * blocked by the execution guard.
 *
 * <p>Listens to {@code WORKER_RETRIES_EXHAUSTED} — the same event published by both {@link
 * io.casehub.engine.internal.engine.handler.WorkerScheduleEventHandler} (guard-blocked path) and
 * {@link io.casehub.engine.scheduler.quartz.QuartzWorkerExecutionJobListener} (retry-exhausted
 * path). Both fire-sites use {@code worker.getName()} as the {@code workerId} field, which equals
 * the tracking key stored by {@link BlackboardRegistry#indexForCompletion}.
 *
 * <p>Fires {@link PlanItemFaultedEvent} via CDI {@code fireAsync()} and calls {@link
 * CompoundCompletionEvaluator#evaluate} after marking the PlanItem FAULTED. Consolidates the
 * responsibilities of the former {@code PlanItemFaultHandler} (engine#666).
 *
 * <p>Without this handler, a RUNNING PlanItem stays active indefinitely after exhaustion, blocking
 * re-triggering, stage autocomplete, and {@code PlanItemCompletedEvent} delivery.
 *
 * <p>Refs engine#331, engine#369, engine#666.
 */
@ApplicationScoped
public class WorkerRetryExhaustionHandler {

  private static final Logger LOG = Logger.getLogger(WorkerRetryExhaustionHandler.class);

  private final BlackboardRegistry registry;
  private final CompoundCompletionEvaluator compoundCompletionEvaluator;
  private final Event<PlanItemStateChangedEvent> planItemStateChangedEvents;
  private final Instance<PlanAdaptationEvaluator> planAdaptationEvaluator;

  @Inject
  public WorkerRetryExhaustionHandler(
      final BlackboardRegistry registry,
      final CompoundCompletionEvaluator compoundCompletionEvaluator,
      final Event<PlanItemStateChangedEvent> planItemStateChangedEvents,
      final Instance<PlanAdaptationEvaluator> planAdaptationEvaluator) {
    this.registry = registry;
    this.compoundCompletionEvaluator = compoundCompletionEvaluator;
    this.planItemStateChangedEvents = planItemStateChangedEvents;
    this.planAdaptationEvaluator = planAdaptationEvaluator;
  }

  @ConsumeEvent(value = EventBusAddresses.WORKER_RETRIES_EXHAUSTED, blocking = true)
  public void onWorkerRetriesExhausted(final WorkerRetriesExhaustedEvent event) {
    final CasePlanModel plan = registry.get(event.caseId()).orElse(null);
    if (plan == null) {
      return;
    }

    final String planItemId;
    if (event.bindingName() != null) {
      planItemId =
          plan.getPlanItemByBindingName(event.bindingName())
              .map(PlanItem::getPlanItemId)
              .orElse(null);
    } else {
      planItemId = registry.getPlanItemId(event.caseId(), event.workerId()).orElse(null);
    }
    if (planItemId == null) {
      LOG.debugf(
          "No PlanItem found for binding='%s' worker='%s' in case %s — guard-blocked or already evicted",
          event.bindingName(), event.workerId(), event.caseId());
      return;
    }

    plan.getPlanItem(planItemId)
        .ifPresent(
            item -> {
              if (item.getStatus() != TaskStatus.RUNNING) {
                LOG.debugf(
                    "PlanItem %s for worker '%s' in case %s has status %s — not RUNNING, skipping",
                    planItemId, event.workerId(), event.caseId(), item.getStatus());
                return;
              }
              TaskStatus prevStatus = item.getStatus();
              try {
                item.markFaulted();
              } catch (IllegalStateException e) {
                LOG.debugf(
                    "PlanItem %s already terminal (status=%s) — concurrent transition won",
                    planItemId, item.getStatus());
                return;
              }

              planItemStateChangedEvents.fireAsync(
                  new PlanItemStateChangedEvent(
                      event.caseId(),
                      planItemId,
                      item.getBindingName(),
                      prevStatus,
                      TaskStatus.FAULTED,
                      event.tenancyId()));

              if (planAdaptationEvaluator.isResolvable()) {
                planAdaptationEvaluator
                    .get()
                    .evaluateAdaptation(
                        event.caseId(),
                        event.tenancyId(),
                        item.getBindingName(),
                        TaskStatus.FAULTED);
              }

              compoundCompletionEvaluator.evaluate(
                  event.caseId(), event.tenancyId(), plan, item.getBindingName());
              LOG.warnf(
                  "PlanItem %s marked FAULTED — worker '%s' retries exhausted in case %s",
                  planItemId, event.workerId(), event.caseId());
            });
  }
}
