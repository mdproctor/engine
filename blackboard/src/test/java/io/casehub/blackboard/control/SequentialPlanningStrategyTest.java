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
package io.casehub.blackboard.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.model.Binding;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.ExecutorRef;
import io.casehub.blackboard.plan.CasePlanModel;
import io.casehub.blackboard.plan.DefaultCasePlanModel;
import io.casehub.blackboard.plan.PlanItem;
import io.casehub.worker.api.Capability;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SequentialPlanningStrategyTest {

  private SequentialPlanningStrategy strategy;
  private CasePlanModel plan;

  @BeforeEach
  void setUp() {
    strategy = new SequentialPlanningStrategy();
    plan = new DefaultCasePlanModel(UUID.randomUUID());
  }

  @Test
  void id_isSequential() {
    assertEquals("sequential", strategy.id());
  }

  @Test
  void firstPending_isSelected() {
    Binding a = binding("step-a");
    Binding b = binding("step-b");
    plan.addPlanItem(PlanItem.create("step-a", ExecutorRef.of("workerA"), 0, a.target()));
    plan.addPlanItem(PlanItem.create("step-b", ExecutorRef.of("workerB"), 0, b.target()));

    List<Binding> result = strategy.select(plan, null, List.of(a, b));

    assertEquals(1, result.size());
    assertEquals("step-a", result.get(0).getName());
  }

  @Test
  void completedStep_advancesToNext() {
    Binding a = binding("step-a");
    Binding b = binding("step-b");
    PlanItem itemA = PlanItem.create("step-a", ExecutorRef.of("workerA"), 0, a.target());
    itemA.markRunning();
    itemA.markCompleted();
    plan.addPlanItem(itemA);
    plan.addPlanItem(PlanItem.create("step-b", ExecutorRef.of("workerB"), 0, b.target()));

    List<Binding> result = strategy.select(plan, null, List.of(a, b));

    assertEquals(1, result.size());
    assertEquals("step-b", result.get(0).getName());
  }

  @Test
  void runningStep_returnsEmpty() {
    Binding a = binding("step-a");
    PlanItem itemA = PlanItem.create("step-a", ExecutorRef.of("workerA"), 0, a.target());
    itemA.markRunning();
    plan.addPlanItem(itemA);

    List<Binding> result = strategy.select(plan, null, List.of(a));

    assertTrue(result.isEmpty());
  }

  @Test
  void faultedStep_haltsSequence() {
    Binding a = binding("step-a");
    Binding b = binding("step-b");
    PlanItem itemA = PlanItem.create("step-a", ExecutorRef.of("workerA"), 0, a.target());
    itemA.markRunning();
    itemA.markFaulted();
    plan.addPlanItem(itemA);
    plan.addPlanItem(PlanItem.create("step-b", ExecutorRef.of("workerB"), 0, b.target()));

    List<Binding> result = strategy.select(plan, null, List.of(a, b));

    assertTrue(result.isEmpty());
  }

  @Test
  void allCompleted_returnsEmpty() {
    Binding a = binding("step-a");
    PlanItem itemA = PlanItem.create("step-a", ExecutorRef.of("workerA"), 0, a.target());
    itemA.markRunning();
    itemA.markCompleted();
    plan.addPlanItem(itemA);

    List<Binding> result = strategy.select(plan, null, List.of(a));

    assertTrue(result.isEmpty());
  }

  private Binding binding(String name) {
    Capability cap = Capability.builder().name(name).inputSchema("{}").outputSchema("{}").build();
    return Binding.builder()
        .name(name)
        .capability(cap)
        .on(new ContextChangeTrigger("." + name))
        .build();
  }
}
