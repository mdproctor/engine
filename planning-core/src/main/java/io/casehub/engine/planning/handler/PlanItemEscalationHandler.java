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
import io.casehub.engine.common.internal.event.AgentRoutingEscalationEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.spi.event.PlanItemStateChangedEvent;
import io.casehub.engine.planning.plan.CasePlanModel;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Marks a {@link io.casehub.engine.planning.plan.PlanItem} ESCALATED when agent routing produces an
 * escalation result.
 *
 * <p>Subscribes to {@code AGENT_ROUTING_ESCALATION} which is published via {@code
 * eventBus.publish()} — fan-out. Coexists with {@link
 * io.casehub.engine.internal.engine.handler.AgentRoutingEscalationHandler} which posts the QUERY to
 * the oversight channel.
 *
 * <p>For CapabilityTarget bindings, the PlanItem is already RUNNING at escalation time (marked by
 * {@code filterAndIndexForDispatch}). {@code tryMarkEscalated()} accepts both PENDING and RUNNING
 * as source states.
 */
@ApplicationScoped
public class PlanItemEscalationHandler {

  private static final Logger LOG = Logger.getLogger(PlanItemEscalationHandler.class);

  private final BlackboardRegistry registry;
  private final Event<PlanItemStateChangedEvent> planItemStateChangedEvents;

  @Inject
  public PlanItemEscalationHandler(
      BlackboardRegistry registry, Event<PlanItemStateChangedEvent> planItemStateChangedEvents) {
    this.registry = registry;
    this.planItemStateChangedEvents = planItemStateChangedEvents;
  }

  @ConsumeEvent(value = EventBusAddresses.AGENT_ROUTING_ESCALATION, blocking = true)
  public void onEscalation(AgentRoutingEscalationEvent event) {
    CasePlanModel plan = registry.get(event.caseId()).orElse(null);
    if (plan == null) {
      return;
    }

    plan.findPlanItemByBindingName(event.bindingName())
        .ifPresent(
            item -> {
              TaskStatus prevStatus = item.getStatus();
              if (!item.tryMarkEscalated()) {
                LOG.debugf(
                    "PlanItem %s for binding '%s' in case %s has status %s — cannot escalate",
                    item.id(), event.bindingName(), event.caseId(), item.getStatus());
                return;
              }
              LOG.infof(
                  "PlanItem %s for binding '%s' in case %s marked ESCALATED (was %s)",
                  item.id(), event.bindingName(), event.caseId(), prevStatus);
              planItemStateChangedEvents.fireAsync(
                  new PlanItemStateChangedEvent(
                      event.caseId(),
                      item.id(),
                      event.bindingName(),
                      prevStatus,
                      TaskStatus.ESCALATED,
                      event.tenancyId()));
            });
  }
}
