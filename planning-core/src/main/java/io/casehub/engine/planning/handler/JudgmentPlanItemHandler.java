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

import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.JudgmentTarget;
import io.casehub.api.model.TaskStatus;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.JudgmentFaultEvent;
import io.casehub.engine.common.internal.event.JudgmentReDispatchEvent;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.JudgmentScheduleRequest;
import io.casehub.engine.common.spi.JudgmentScheduler;
import io.casehub.engine.common.spi.event.PlanItemStateChangedEvent;
import io.casehub.engine.planning.plan.CasePlanModel;
import io.casehub.engine.planning.plan.PlanItem;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Manages PlanItem state transitions for judgment escalation decisions.
 *
 * <p>On re-dispatch (ReYield/Escalate): transitions PlanItem DELEGATED → DISPATCHING, then
 * re-schedules via {@link JudgmentScheduler}. On fault: marks PlanItem FAULTED.
 *
 * <p>Refs engine#1000, engine#999.
 */
@ApplicationScoped
public class JudgmentPlanItemHandler {

  private static final Logger LOG = Logger.getLogger(JudgmentPlanItemHandler.class);

  @Inject BlackboardRegistry registry;
  @Inject CaseDefinitionRegistry caseDefinitionRegistry;
  @Inject Instance<JudgmentScheduler> judgmentScheduler;
  @Inject Event<PlanItemStateChangedEvent> planItemStateChangedEvents;

  @ConsumeEvent(value = EventBusAddresses.JUDGMENT_RE_DISPATCH, blocking = true)
  @RunOnVirtualThread
  public void onReDispatch(JudgmentReDispatchEvent event) {
    CasePlanModel plan = registry.get(event.caseId()).orElse(null);
    if (plan == null) {
      LOG.debugf("No CasePlanModel for caseId=%s — ignoring judgment re-dispatch", event.caseId());
      return;
    }

    plan.findPlanItemByBindingName(event.bindingName())
        .ifPresent(
            item -> {
              TaskStatus prevStatus = item.getStatus();
              if (!item.tryMarkReDispatching()) {
                LOG.warnf(
                    "Cannot re-dispatch PlanItem %s for binding '%s' (status=%s) — skipping",
                    item.id(), event.bindingName(), item.getStatus());
                return;
              }
              LOG.infof(
                  "PlanItem %s for binding '%s' transitioned %s → DISPATCHING for re-dispatch",
                  item.id(), event.bindingName(), prevStatus);

              planItemStateChangedEvents.fireAsync(
                  new PlanItemStateChangedEvent(
                      event.caseId(),
                      item.id(),
                      event.bindingName(),
                      prevStatus,
                      TaskStatus.DISPATCHING,
                      event.tenancyId()));

              scheduleJudgment(event, item);
            });
  }

  @ConsumeEvent(value = EventBusAddresses.JUDGMENT_FAULT, blocking = true)
  @RunOnVirtualThread
  public void onFault(JudgmentFaultEvent event) {
    CasePlanModel plan = registry.get(event.caseId()).orElse(null);
    if (plan == null) {
      LOG.debugf("No CasePlanModel for caseId=%s — ignoring judgment fault", event.caseId());
      return;
    }

    plan.findPlanItemByBindingName(event.bindingName())
        .ifPresent(
            item -> {
              TaskStatus prevStatus = item.getStatus();
              try {
                item.markFaulted();
                LOG.infof(
                    "PlanItem %s for binding '%s' marked FAULTED: %s",
                    item.id(), event.bindingName(), event.reason());
                planItemStateChangedEvents.fireAsync(
                    new PlanItemStateChangedEvent(
                        event.caseId(),
                        item.id(),
                        event.bindingName(),
                        prevStatus,
                        TaskStatus.FAULTED,
                        event.tenancyId()));
              } catch (IllegalStateException e) {
                LOG.warnf(
                    "Cannot fault PlanItem %s (status=%s): %s",
                    item.id(), item.getStatus(), e.getMessage());
              }
            });
  }

  private void scheduleJudgment(JudgmentReDispatchEvent event, PlanItem item) {
    if (!judgmentScheduler.isResolvable()) {
      LOG.warnf(
          "No JudgmentScheduler available for re-dispatch — reverting PlanItem %s to DELEGATED",
          item.id());
      item.markDelegated();
      return;
    }

    CaseDefinition def =
        caseDefinitionRegistry.allDefinitions().stream()
            .filter(
                d ->
                    d.getBindings().stream().anyMatch(b -> b.getName().equals(event.bindingName())))
            .findFirst()
            .orElse(null);
    if (def == null) {
      LOG.warnf(
          "CaseDefinition not found for binding '%s' — reverting PlanItem %s to DELEGATED",
          event.bindingName(), item.id());
      item.markDelegated();
      return;
    }

    Binding binding =
        def.getBindings().stream()
            .filter(b -> b.getName().equals(event.bindingName()))
            .findFirst()
            .orElse(null);
    if (binding == null || !(binding.target() instanceof JudgmentTarget target)) {
      LOG.warnf(
          "Binding '%s' is not a JudgmentTarget — reverting PlanItem %s to DELEGATED",
          event.bindingName(), item.id());
      item.markDelegated();
      return;
    }

    Map<String, Object> inputData =
        event.feedback() != null ? Map.of("_feedback", event.feedback()) : Map.of();

    try {
      judgmentScheduler
          .get()
          .schedule(
              new JudgmentScheduleRequest(
                  event.caseId(),
                  event.tenancyId(),
                  event.bindingName(),
                  target,
                  inputData,
                  null,
                  null));

      LOG.infof(
          "Judgment re-dispatched: caseId=%s binding=%s feedback=%s callerConfig=%s",
          event.caseId(), event.bindingName(), event.feedback(), event.newCallerConfig());
    } catch (Exception e) {
      LOG.errorf(
          e,
          "Judgment scheduling failed for binding '%s' — reverting PlanItem %s to DELEGATED",
          event.bindingName(),
          item.id());
      item.markDelegated();
    }
  }
}
