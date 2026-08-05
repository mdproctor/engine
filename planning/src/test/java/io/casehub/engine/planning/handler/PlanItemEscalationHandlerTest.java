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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.casehub.api.model.ExecutorRef;
import io.casehub.api.model.TaskStatus;
import io.casehub.api.spi.routing.EscalationReason;
import io.casehub.engine.common.internal.event.AgentRoutingEscalationEvent;
import io.casehub.engine.common.spi.event.PlanItemStateChangedEvent;
import io.casehub.engine.planning.plan.DefaultCasePlanModel;
import io.casehub.engine.planning.plan.PlanItem;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PlanItemEscalationHandlerTest {

  private BlackboardRegistry registry;

  @SuppressWarnings("unchecked")
  private final jakarta.enterprise.event.Event<PlanItemStateChangedEvent> stateEvents =
      mock(jakarta.enterprise.event.Event.class);

  private PlanItemEscalationHandler handler;
  private UUID caseId;
  private DefaultCasePlanModel plan;

  @BeforeEach
  void setUp() {
    registry = new BlackboardRegistry();
    handler = new PlanItemEscalationHandler(registry, stateEvents);
    caseId = UUID.randomUUID();
    plan = (DefaultCasePlanModel) registry.getOrCreate(caseId, "test-tenant");
  }

  @Test
  void marks_running_plan_item_escalated() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("worker-a"), 0);
    plan.addPlanItem(item);
    item.markRunning();

    handler.onEscalation(escalationEvent("binding-a"));

    assertThat(item.getStatus()).isEqualTo(TaskStatus.ESCALATED);
  }

  @Test
  void marks_pending_plan_item_escalated() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("worker-a"), 0);
    plan.addPlanItem(item);

    handler.onEscalation(escalationEvent("binding-a"));

    assertThat(item.getStatus()).isEqualTo(TaskStatus.ESCALATED);
  }

  @Test
  void fires_state_changed_event() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("worker-a"), 0);
    plan.addPlanItem(item);
    item.markRunning();

    handler.onEscalation(escalationEvent("binding-a"));

    ArgumentCaptor<PlanItemStateChangedEvent> captor =
        ArgumentCaptor.forClass(PlanItemStateChangedEvent.class);
    verify(stateEvents).fireAsync(captor.capture());

    PlanItemStateChangedEvent fired = captor.getValue();
    assertThat(fired.caseId()).isEqualTo(caseId);
    assertThat(fired.bindingName()).isEqualTo("binding-a");
    assertThat(fired.previousStatus()).isEqualTo(TaskStatus.RUNNING);
    assertThat(fired.newStatus()).isEqualTo(TaskStatus.ESCALATED);
    assertThat(fired.tenancyId()).isEqualTo("test-tenant");
  }

  @Test
  void does_not_escalate_completed_plan_item() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("worker-a"), 0);
    plan.addPlanItem(item);
    item.markRunning();
    item.markCompleted();

    handler.onEscalation(escalationEvent("binding-a"));

    assertThat(item.getStatus()).isEqualTo(TaskStatus.COMPLETED);
    verify(stateEvents, never()).fireAsync(any());
  }

  @Test
  void does_not_escalate_delegated_plan_item() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("worker-a"), 0);
    plan.addPlanItem(item);
    item.tryMarkDispatching();
    item.markDelegated();

    handler.onEscalation(escalationEvent("binding-a"));

    assertThat(item.getStatus()).isEqualTo(TaskStatus.DELEGATED);
    verify(stateEvents, never()).fireAsync(any());
  }

  @Test
  void unknown_case_does_not_throw() {
    handler.onEscalation(
        new AgentRoutingEscalationEvent(
            UUID.randomUUID(),
            "tenant",
            "cap",
            "binding-a",
            EscalationReason.BORDERLINE_STALEMATE));
  }

  @Test
  void unknown_binding_does_not_throw() {
    handler.onEscalation(escalationEvent("nonexistent-binding"));
  }

  @Test
  void direct_orchestration_binding_does_not_throw() {
    handler.onEscalation(escalationEvent("(direct-orchestration)"));
  }

  private AgentRoutingEscalationEvent escalationEvent(String bindingName) {
    return new AgentRoutingEscalationEvent(
        caseId, "test-tenant", "review", bindingName, EscalationReason.BORDERLINE_STALEMATE);
  }
}
