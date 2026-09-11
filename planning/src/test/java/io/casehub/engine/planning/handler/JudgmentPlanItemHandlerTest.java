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

import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ExecutorRef;
import io.casehub.api.model.JudgmentTarget;
import io.casehub.api.model.TaskStatus;
import io.casehub.engine.common.internal.event.JudgmentFaultEvent;
import io.casehub.engine.common.internal.event.JudgmentReDispatchEvent;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.JudgmentScheduleRequest;
import io.casehub.engine.common.spi.JudgmentScheduler;
import io.casehub.engine.common.spi.event.PlanItemStateChangedEvent;
import io.casehub.engine.planning.plan.DefaultCasePlanModel;
import io.casehub.engine.planning.plan.PlanItem;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import io.casehub.engine.planning.store.NoOpPlanItemStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JudgmentPlanItemHandlerTest {

  private BlackboardRegistry registry;
  private RecordingScheduler scheduler;
  private JudgmentPlanItemHandler handler;
  private UUID caseId;
  private DefaultCasePlanModel plan;
  private final List<PlanItemStateChangedEvent> capturedStateEvents = new ArrayList<>();
  private final Consumer<PlanItemStateChangedEvent> stateEvents = capturedStateEvents::add;

  @BeforeEach
  void setUp() {
    registry = new BlackboardRegistry(new NoOpPlanItemStore());
    scheduler = new RecordingScheduler();
    capturedStateEvents.clear();
    handler =
        new JudgmentPlanItemHandler(
            registry, stubDefinitionRegistry(), Optional.of(scheduler), stateEvents);
    caseId = UUID.randomUUID();
    plan = (DefaultCasePlanModel) registry.getOrCreate(caseId, "test-tenant");
  }

  // ── Re-dispatch tests ──

  @Test
  void reDispatch_transitionsDelegatedToDispatching() {
    PlanItem item = createDelegatedItem("review-tx");

    handler.onReDispatch(reDispatchEvent("review-tx", "please provide more evidence"));

    assertThat(item.getStatus()).isEqualTo(TaskStatus.DISPATCHING);
  }

  @Test
  void reDispatch_schedulesJudgmentWithFeedback() {
    createDelegatedItem("review-tx");

    handler.onReDispatch(reDispatchEvent("review-tx", "more evidence needed"));

    assertThat(scheduler.lastRequest).isNotNull();
    assertThat(scheduler.lastRequest.bindingName()).isEqualTo("review-tx");
    assertThat(scheduler.lastRequest.inputData())
        .containsEntry("_feedback", "more evidence needed");
  }

  @Test
  void reDispatch_firesStateChangedEvent() {
    createDelegatedItem("review-tx");

    handler.onReDispatch(reDispatchEvent("review-tx", "feedback"));

    assertThat(capturedStateEvents).hasSize(1);
    PlanItemStateChangedEvent fired = capturedStateEvents.get(0);
    assertThat(fired.previousStatus()).isEqualTo(TaskStatus.DELEGATED);
    assertThat(fired.newStatus()).isEqualTo(TaskStatus.DISPATCHING);
  }

  @Test
  void reDispatch_skipsWhenPlanItemNotDelegated() {
    PlanItem item = PlanItem.create("review-tx", ExecutorRef.of("worker"), 0);
    plan.addPlanItem(item);

    handler.onReDispatch(reDispatchEvent("review-tx", "feedback"));

    assertThat(item.getStatus()).isEqualTo(TaskStatus.PENDING);
    assertThat(capturedStateEvents).isEmpty();
    assertThat(scheduler.lastRequest).isNull();
  }

  @Test
  void reDispatch_unknownCaseDoesNotThrow() {
    handler.onReDispatch(
        new JudgmentReDispatchEvent(UUID.randomUUID(), "t", "binding", "fb", null));
  }

  // ── Fault tests ──

  @Test
  void fault_marksDelegatedItemFaulted() {
    PlanItem item = createDelegatedItem("review-tx");

    handler.onFault(faultEvent("review-tx", "max escalations reached"));

    assertThat(item.getStatus()).isEqualTo(TaskStatus.FAULTED);
  }

  @Test
  void fault_firesStateChangedEvent() {
    createDelegatedItem("review-tx");

    handler.onFault(faultEvent("review-tx", "reason"));

    assertThat(capturedStateEvents).hasSize(1);
    PlanItemStateChangedEvent fired = capturedStateEvents.get(0);
    assertThat(fired.previousStatus()).isEqualTo(TaskStatus.DELEGATED);
    assertThat(fired.newStatus()).isEqualTo(TaskStatus.FAULTED);
  }

  @Test
  void fault_doesNotThrowOnTerminalItem() {
    PlanItem item = createDelegatedItem("review-tx");
    item.markCompleted();

    handler.onFault(faultEvent("review-tx", "reason"));

    assertThat(item.getStatus()).isEqualTo(TaskStatus.COMPLETED);
    assertThat(capturedStateEvents).isEmpty();
  }

  @Test
  void fault_unknownCaseDoesNotThrow() {
    handler.onFault(new JudgmentFaultEvent(UUID.randomUUID(), "t", "b", "reason"));
  }

  // ── Helpers ──

  private PlanItem createDelegatedItem(String bindingName) {
    PlanItem item = PlanItem.create(bindingName, ExecutorRef.of("worker"), 0);
    plan.addPlanItem(item);
    item.tryMarkDispatching();
    item.markDelegated();
    return item;
  }

  private JudgmentReDispatchEvent reDispatchEvent(String bindingName, String feedback) {
    return new JudgmentReDispatchEvent(caseId, "test-tenant", bindingName, feedback, null);
  }

  private JudgmentFaultEvent faultEvent(String bindingName, String reason) {
    return new JudgmentFaultEvent(caseId, "test-tenant", bindingName, reason);
  }

  private CaseDefinitionRegistry stubDefinitionRegistry() {
    JudgmentTarget target = JudgmentTarget.builder().prompt("Review this").build();
    Binding binding =
        Binding.builder()
            .name("review-tx")
            .judgment(target)
            .on(new io.casehub.api.model.ContextChangeTrigger(".always"))
            .build();
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test-case")
            .version("1.0")
            .bindings(binding)
            .build();
    return new CaseDefinitionRegistry() {
      @Override
      public CaseDefinition getCaseDefinition(
          io.casehub.engine.common.internal.model.CaseMetaModel meta) {
        return def;
      }

      @Override
      public List<CaseDefinition> allDefinitions() {
        return List.of(def);
      }

      @Override
      public io.casehub.engine.common.internal.model.CaseMetaModel registerCaseDefinition(
          CaseDefinition model) {
        return null;
      }

      @Override
      public io.casehub.engine.common.internal.model.CaseMetaModel getCaseMetaModel(
          CaseDefinition caseDefinition) {
        return null;
      }
    };
  }

  private static class RecordingScheduler implements JudgmentScheduler {
    JudgmentScheduleRequest lastRequest;

    @Override
    public void schedule(JudgmentScheduleRequest request) {
      lastRequest = request;
    }
  }
}
