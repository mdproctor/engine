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
package io.casehub.engine.internal.quarkus;

import io.casehub.api.spi.event.CaseCompletedEvent;
import io.casehub.api.spi.event.CaseFaultedEvent;
import io.casehub.api.spi.event.EventDispatcher;
import io.casehub.engine.common.internal.event.ActionGateCancelledEvent;
import io.casehub.engine.common.internal.event.ActionGateWorkerFaultedEvent;
import io.casehub.engine.common.internal.event.AgentRoutingEscalationEvent;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.CaseStatusChanged;
import io.casehub.engine.common.internal.event.CompoundCompletedEvent;
import io.casehub.engine.common.internal.event.ContextSignalEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.ExpectationViolationEvent;
import io.casehub.engine.common.internal.event.GoalReachedEvent;
import io.casehub.engine.common.internal.event.JudgmentFaultEvent;
import io.casehub.engine.common.internal.event.JudgmentReDispatchEvent;
import io.casehub.engine.common.internal.event.MilestoneActivatedEvent;
import io.casehub.engine.common.internal.event.MilestoneCompletedEvent;
import io.casehub.engine.common.internal.event.MilestoneSLAViolatedEvent;
import io.casehub.engine.common.internal.event.SubCaseScheduleEvent;
import io.casehub.engine.common.internal.event.WorkerOutcomeResolvedEvent;
import io.casehub.engine.common.internal.event.WorkerScheduleEvent;
import io.casehub.engine.common.internal.event.WorkflowExecutionCompleted;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;

@ApplicationScoped
public class VertxEventDispatcher implements EventDispatcher {

  private static final Map<Class<?>, String> TYPE_TO_ADDRESS =
      Map.ofEntries(
          Map.entry(CaseStatusChanged.class, EventBusAddresses.CASE_STATUS_CHANGED),
          Map.entry(CaseContextChangedEvent.class, EventBusAddresses.CONTEXT_CHANGED),
          Map.entry(GoalReachedEvent.class, EventBusAddresses.GOAL_REACHED),
          Map.entry(WorkerScheduleEvent.class, EventBusAddresses.WORKER_SCHEDULE),
          Map.entry(WorkflowExecutionCompleted.class, EventBusAddresses.WORKER_EXECUTION_FINISHED),
          Map.entry(SubCaseScheduleEvent.class, EventBusAddresses.SUBCASE_SCHEDULE),
          Map.entry(AgentRoutingEscalationEvent.class, EventBusAddresses.AGENT_ROUTING_ESCALATION),
          Map.entry(MilestoneSLAViolatedEvent.class, EventBusAddresses.MILESTONE_SLA_VIOLATED),
          Map.entry(MilestoneActivatedEvent.class, EventBusAddresses.MILESTONE_ACTIVATED),
          Map.entry(MilestoneCompletedEvent.class, EventBusAddresses.MILESTONE_COMPLETED),
          Map.entry(ContextSignalEvent.class, EventBusAddresses.CONTEXT_SIGNAL),
          Map.entry(WorkerOutcomeResolvedEvent.class, EventBusAddresses.WORKER_OUTCOME_RESOLVED),
          Map.entry(CompoundCompletedEvent.class, EventBusAddresses.COMPOUND_COMPLETED),
          Map.entry(ActionGateCancelledEvent.class, EventBusAddresses.ACTION_GATE_CANCELLED),
          Map.entry(
              ActionGateWorkerFaultedEvent.class, EventBusAddresses.ACTION_GATE_WORKER_FAULTED),
          Map.entry(ExpectationViolationEvent.class, EventBusAddresses.EXPECTATION_VIOLATED),
          Map.entry(JudgmentReDispatchEvent.class, EventBusAddresses.JUDGMENT_RE_DISPATCH),
          Map.entry(JudgmentFaultEvent.class, EventBusAddresses.JUDGMENT_FAULT),
          Map.entry(CaseCompletedEvent.class, EventBusAddresses.CASE_COMPLETED),
          Map.entry(CaseFaultedEvent.class, EventBusAddresses.CASE_FAULTED));

  @Inject EventBus eventBus;

  @Override
  public void dispatch(Object event) {
    String address = TYPE_TO_ADDRESS.get(event.getClass());
    if (address == null) {
      throw new IllegalArgumentException(
          "No EventBus address registered for: " + event.getClass().getName());
    }
    eventBus.publish(address, event);
  }
}
