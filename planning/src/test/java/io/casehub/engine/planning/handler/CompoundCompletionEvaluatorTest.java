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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.casehub.api.model.ExecutorRef;
import io.casehub.api.model.TaskStatus;
import io.casehub.engine.planning.event.BlackboardEventBusAddresses;
import io.casehub.engine.planning.event.CompoundCompletedEvent;
import io.casehub.engine.planning.plan.CompletionSemantics;
import io.casehub.engine.planning.plan.DefaultCasePlanModel;
import io.casehub.engine.planning.plan.DispatchMode;
import io.casehub.engine.planning.plan.PlanItemDefinition;
import io.vertx.mutiny.core.eventbus.EventBus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CompoundCompletionEvaluatorTest {

  private EventBus eventBus;
  private CompoundCompletionEvaluator evaluator;
  private UUID caseId;

  @BeforeEach
  void setUp() {
    eventBus = mock(EventBus.class);
    evaluator = new CompoundCompletionEvaluator(eventBus);
    caseId = UUID.randomUUID();
  }

  private PlanItemDefinition.Primitive primitive(String id) {
    return new PlanItemDefinition.Primitive(
        id, id, ExecutorRef.of("worker"), DispatchMode.ORCHESTRATED, null);
  }

  private PlanItemDefinition.Compound compound(
      String id, List<PlanItemDefinition> children, CompletionSemantics semantics) {
    return new PlanItemDefinition.Compound(
        id, id, children, null, semantics, DispatchMode.ORCHESTRATED, null, null, false);
  }

  // ── ALL semantics ─────────────────────────────────────────────────────────

  @Test
  void all_semantics_not_complete_when_children_pending() {
    var c1 = primitive("c1");
    var c2 = primitive("c2");
    var parent = compound("parent", List.of(c1, c2), CompletionSemantics.all());
    var model = new DefaultCasePlanModel(caseId);
    model.registerDefinition(parent);

    evaluator.evaluate(caseId, "tenant-1", model, "c1");
    verify(eventBus, never()).publish(any(), any());
  }

  @Test
  void all_semantics_completes_when_all_children_terminal() {
    var c1 = primitive("c1");
    var c2 = primitive("c2");
    var parent = compound("parent", List.of(c1, c2), CompletionSemantics.all());
    var model = new DefaultCasePlanModel(caseId);
    model.registerDefinition(parent);

    model.tryDefinitionTransition("c1", TaskStatus.PENDING, TaskStatus.RUNNING);
    model.tryDefinitionTransition("c1", TaskStatus.RUNNING, TaskStatus.COMPLETED);
    model.tryDefinitionTransition("c2", TaskStatus.PENDING, TaskStatus.RUNNING);
    model.tryDefinitionTransition("c2", TaskStatus.RUNNING, TaskStatus.COMPLETED);

    evaluator.evaluate(caseId, "tenant-1", model, "c2");

    var captor = ArgumentCaptor.forClass(CompoundCompletedEvent.class);
    verify(eventBus).publish(eq(BlackboardEventBusAddresses.COMPOUND_COMPLETED), captor.capture());
    assertThat(captor.getValue().compoundId()).isEqualTo("parent");
  }

  @Test
  void all_semantics_faulted_child_counts_as_terminal() {
    var c1 = primitive("c1");
    var c2 = primitive("c2");
    var parent = compound("parent", List.of(c1, c2), CompletionSemantics.all());
    var model = new DefaultCasePlanModel(caseId);
    model.registerDefinition(parent);

    model.tryDefinitionTransition("c1", TaskStatus.PENDING, TaskStatus.RUNNING);
    model.tryDefinitionTransition("c1", TaskStatus.RUNNING, TaskStatus.COMPLETED);
    model.tryDefinitionTransition("c2", TaskStatus.PENDING, TaskStatus.RUNNING);
    model.tryDefinitionTransition("c2", TaskStatus.RUNNING, TaskStatus.FAULTED);

    evaluator.evaluate(caseId, "tenant-1", model, "c2");
    verify(eventBus).publish(eq(BlackboardEventBusAddresses.COMPOUND_COMPLETED), any());
  }

  // ── M_OF_N semantics ──────────────────────────────────────────────────────

  @Test
  void m_of_n_completes_at_threshold() {
    var c1 = primitive("c1");
    var c2 = primitive("c2");
    var c3 = primitive("c3");
    var parent = compound("parent", List.of(c1, c2, c3), CompletionSemantics.mOfN(2));
    var model = new DefaultCasePlanModel(caseId);
    model.registerDefinition(parent);

    model.tryDefinitionTransition("c1", TaskStatus.PENDING, TaskStatus.RUNNING);
    model.tryDefinitionTransition("c1", TaskStatus.RUNNING, TaskStatus.COMPLETED);
    evaluator.evaluate(caseId, "tenant-1", model, "c1");
    verify(eventBus, never()).publish(any(), any());

    model.tryDefinitionTransition("c2", TaskStatus.PENDING, TaskStatus.RUNNING);
    model.tryDefinitionTransition("c2", TaskStatus.RUNNING, TaskStatus.COMPLETED);
    evaluator.evaluate(caseId, "tenant-1", model, "c2");
    verify(eventBus).publish(eq(BlackboardEventBusAddresses.COMPOUND_COMPLETED), any());
  }

  @Test
  void m_of_n_does_not_fire_below_threshold() {
    var c1 = primitive("c1");
    var c2 = primitive("c2");
    var c3 = primitive("c3");
    var parent = compound("parent", List.of(c1, c2, c3), CompletionSemantics.mOfN(2));
    var model = new DefaultCasePlanModel(caseId);
    model.registerDefinition(parent);

    model.tryDefinitionTransition("c1", TaskStatus.PENDING, TaskStatus.RUNNING);
    model.tryDefinitionTransition("c1", TaskStatus.RUNNING, TaskStatus.COMPLETED);
    evaluator.evaluate(caseId, "tenant-1", model, "c1");
    verify(eventBus, never()).publish(any(), any());
  }

  // ── FIRST_WINS semantics ──────────────────────────────────────────────────

  @Test
  void first_wins_completes_on_first_terminal() {
    var c1 = primitive("c1");
    var c2 = primitive("c2");
    var parent = compound("parent", List.of(c1, c2), CompletionSemantics.firstWins());
    var model = new DefaultCasePlanModel(caseId);
    model.registerDefinition(parent);

    model.tryDefinitionTransition("c1", TaskStatus.PENDING, TaskStatus.RUNNING);
    model.tryDefinitionTransition("c1", TaskStatus.RUNNING, TaskStatus.COMPLETED);
    evaluator.evaluate(caseId, "tenant-1", model, "c1");
    verify(eventBus).publish(eq(BlackboardEventBusAddresses.COMPOUND_COMPLETED), any());
  }

  // ── Propagation ───────────────────────────────────────────────────────────

  @Test
  void completion_propagates_to_parent_compound() {
    var leaf1 = primitive("leaf1");
    var leaf2 = primitive("leaf2");
    var inner = compound("inner", List.of(leaf1, leaf2), CompletionSemantics.all());
    var outer = compound("outer", List.of(inner), CompletionSemantics.all());
    var model = new DefaultCasePlanModel(caseId);
    model.registerDefinition(outer);

    model.tryDefinitionTransition("leaf1", TaskStatus.PENDING, TaskStatus.RUNNING);
    model.tryDefinitionTransition("leaf1", TaskStatus.RUNNING, TaskStatus.COMPLETED);
    model.tryDefinitionTransition("leaf2", TaskStatus.PENDING, TaskStatus.RUNNING);
    model.tryDefinitionTransition("leaf2", TaskStatus.RUNNING, TaskStatus.COMPLETED);

    evaluator.evaluate(caseId, "tenant-1", model, "leaf2");

    var captor = ArgumentCaptor.forClass(CompoundCompletedEvent.class);
    verify(eventBus, times(2))
        .publish(eq(BlackboardEventBusAddresses.COMPOUND_COMPLETED), captor.capture());
    var events = captor.getAllValues();
    assertThat(events)
        .extracting(CompoundCompletedEvent::compoundId)
        .containsExactly("inner", "outer");
  }

  @Test
  void propagation_stops_when_parent_not_complete() {
    var leaf1 = primitive("leaf1");
    var inner = compound("inner", List.of(leaf1), CompletionSemantics.all());
    var sibling = primitive("sibling");
    var outer = compound("outer", List.of(inner, sibling), CompletionSemantics.all());
    var model = new DefaultCasePlanModel(caseId);
    model.registerDefinition(outer);

    model.tryDefinitionTransition("leaf1", TaskStatus.PENDING, TaskStatus.RUNNING);
    model.tryDefinitionTransition("leaf1", TaskStatus.RUNNING, TaskStatus.COMPLETED);

    evaluator.evaluate(caseId, "tenant-1", model, "leaf1");

    var captor = ArgumentCaptor.forClass(CompoundCompletedEvent.class);
    verify(eventBus, times(1))
        .publish(eq(BlackboardEventBusAddresses.COMPOUND_COMPLETED), captor.capture());
    assertThat(captor.getValue().compoundId()).isEqualTo("inner");
  }

  // ── Edge cases ────────────────────────────────────────────────────────────

  @Test
  void evaluate_with_changed_item_not_in_any_compound_is_noop() {
    var model = new DefaultCasePlanModel(caseId);
    evaluator.evaluate(caseId, "tenant-1", model, "nonexistent");
    verify(eventBus, never()).publish(any(), any());
  }

  @Test
  void already_completed_compound_does_not_fire_again() {
    var c1 = primitive("c1");
    var parent = compound("parent", List.of(c1), CompletionSemantics.all());
    var model = new DefaultCasePlanModel(caseId);
    model.registerDefinition(parent);

    model.tryDefinitionTransition("c1", TaskStatus.PENDING, TaskStatus.RUNNING);
    model.tryDefinitionTransition("c1", TaskStatus.RUNNING, TaskStatus.COMPLETED);
    model.tryDefinitionTransition("parent", TaskStatus.PENDING, TaskStatus.RUNNING);
    model.tryDefinitionTransition("parent", TaskStatus.RUNNING, TaskStatus.COMPLETED);

    evaluator.evaluate(caseId, "tenant-1", model, "c1");
    verify(eventBus, never()).publish(any(), any());
  }

  @Test
  void runtime_added_child_included_in_evaluation() {
    var c1 = primitive("c1");
    var parent = compound("parent", List.of(c1), CompletionSemantics.all());
    var model = new DefaultCasePlanModel(caseId);
    model.registerDefinition(parent);

    var c2 = primitive("c2");
    model.addChild("parent", c2);

    model.tryDefinitionTransition("c1", TaskStatus.PENDING, TaskStatus.RUNNING);
    model.tryDefinitionTransition("c1", TaskStatus.RUNNING, TaskStatus.COMPLETED);
    evaluator.evaluate(caseId, "tenant-1", model, "c1");
    verify(eventBus, never()).publish(any(), any());

    model.tryDefinitionTransition("c2", TaskStatus.PENDING, TaskStatus.RUNNING);
    model.tryDefinitionTransition("c2", TaskStatus.RUNNING, TaskStatus.COMPLETED);
    evaluator.evaluate(caseId, "tenant-1", model, "c2");
    verify(eventBus).publish(eq(BlackboardEventBusAddresses.COMPOUND_COMPLETED), any());
  }

  @Test
  void three_level_nesting_propagates_fully() {
    var leaf = primitive("leaf");
    var mid = compound("mid", List.of(leaf), CompletionSemantics.all());
    var top = compound("top", List.of(mid), CompletionSemantics.all());
    var model = new DefaultCasePlanModel(caseId);
    model.registerDefinition(top);

    model.tryDefinitionTransition("leaf", TaskStatus.PENDING, TaskStatus.RUNNING);
    model.tryDefinitionTransition("leaf", TaskStatus.RUNNING, TaskStatus.COMPLETED);

    evaluator.evaluate(caseId, "tenant-1", model, "leaf");

    var captor = ArgumentCaptor.forClass(CompoundCompletedEvent.class);
    verify(eventBus, times(2))
        .publish(eq(BlackboardEventBusAddresses.COMPOUND_COMPLETED), captor.capture());
    assertThat(captor.getAllValues())
        .extracting(CompoundCompletedEvent::compoundId)
        .containsExactly("mid", "top");
  }
}
