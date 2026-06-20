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

import io.casehub.blackboard.plan.PlanItem;
import io.casehub.blackboard.registry.BlackboardRegistry;
import io.casehub.engine.common.internal.event.ActionGateWorkerFaultedEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.spi.event.PlanItemFaultedEvent;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Marks the associated {@link PlanItem} FAULTED when a gate expires, enabling {@link
 * io.casehub.blackboard.stage.StageAutocompleteEvaluator} to proceed.
 *
 * <p>Consumes {@link ActionGateWorkerFaultedEvent} on {@link
 * EventBusAddresses#ACTION_GATE_WORKER_FAULTED}, published by {@code ActionGateRejectedHandler} and
 * {@code ActionGateExpiredHandler} in the engine runtime. Uses a dedicated event address (not
 * WORKER_RETRIES_EXHAUSTED) because gate faults must NOT cause a CaseInstance state transition to
 * FAULTED — only the PlanItem should be faulted. Refs engine#402.
 */
@ApplicationScoped
public class ActionGateExpiredPlanItemHandler {

  private static final Logger LOG = Logger.getLogger(ActionGateExpiredPlanItemHandler.class);

  @Inject BlackboardRegistry registry;
  @Inject Event<PlanItemFaultedEvent> planItemFaultedEvents;

  @ConsumeEvent(value = EventBusAddresses.ACTION_GATE_WORKER_FAULTED, blocking = true)
  public void onActionGateWorkerFaulted(final ActionGateWorkerFaultedEvent event) {
    final String planItemId = registry.getPlanItemId(event.caseId(), event.workerId()).orElse(null);
    if (planItemId == null) {
      LOG.debugf(
          "No PlanItem indexed for worker '%s' in case %s — blackboard not active or already evicted",
          event.workerId(), event.caseId());
      return;
    }

    registry
        .get(event.caseId())
        .flatMap(plan -> plan.getPlanItem(planItemId))
        .ifPresent(
            item -> {
              if (item.getStatus().isTerminal()) {
                LOG.debugf(
                    "PlanItem %s for worker '%s' in case %s has status %s — already terminal,"
                        + " skipping",
                    planItemId, event.workerId(), event.caseId(), item.getStatus());
                return;
              }
              item.markFaulted();
              planItemFaultedEvents.fireAsync(
                  new PlanItemFaultedEvent(
                      event.caseId(), planItemId, item.getBindingName(), event.tenancyId()));
              LOG.infof(
                  "PlanItem %s faulted (gate worker faulted): caseId=%s workerId=%s",
                  planItemId, event.caseId(), event.workerId());
            });
  }
}
