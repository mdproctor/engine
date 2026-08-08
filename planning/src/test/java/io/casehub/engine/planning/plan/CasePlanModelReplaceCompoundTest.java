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
package io.casehub.engine.planning.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.model.TaskStatus;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CasePlanModelReplaceCompoundTest {

  private DefaultCasePlanModel plan;
  private UUID caseId;

  @BeforeEach
  void setUp() {
    caseId = UUID.randomUUID();
    plan = new DefaultCasePlanModel(caseId);
  }

  private PlanItemDefinition.Compound buildCompound(String name, String... childBindingNames) {
    var builder =
        PlanItemDefinition.Compound.builder(name)
            .id(name)
            .completion(CompletionSemantics.all())
            .dispatchMode(DispatchMode.CHOREOGRAPHED);
    for (String bindingName : childBindingNames) {
      builder.child(
          new PlanItemDefinition.Primitive(
              name + "-" + bindingName,
              "Step " + bindingName,
              io.casehub.api.model.ExecutorRef.of("worker-" + bindingName, null),
              null));
      builder.binding(bindingName);
    }
    return builder.build();
  }

  private PlanItem createPlanItem(String id, String bindingName) {
    return PlanItem.create(
        bindingName, io.casehub.api.model.ExecutorRef.of("w", null), 0, null, "Step desc");
  }

  @Test
  void replaceCompoundUnregistersOldChildren() {
    var compound = buildCompound("goal", "cap-a", "cap-b", "cap-c");
    plan.registerDefinition(compound);

    var newCompound = buildCompound("goal", "cap-d", "cap-e");
    plan.replaceCompound("goal", newCompound, 1);

    assertFalse(plan.getChildrenOf("goal").contains("goal-cap-a"));
    assertFalse(plan.getChildrenOf("goal").contains("goal-cap-b"));
    assertFalse(plan.getChildrenOf("goal").contains("goal-cap-c"));
  }

  @Test
  void replaceCompoundRegistersNewChildren() {
    var compound = buildCompound("goal", "cap-a", "cap-b");
    plan.registerDefinition(compound);

    var newCompound = buildCompound("goal", "cap-d", "cap-e");
    plan.replaceCompound("goal", newCompound, 1);

    var children = plan.getChildrenOf("goal");
    assertTrue(children.contains("goal-cap-d"));
    assertTrue(children.contains("goal-cap-e"));
    assertEquals(2, children.size());
    assertEquals(TaskStatus.PENDING, plan.getDefinitionStatus("goal-cap-d"));
  }

  @Test
  void replaceCompoundUpdatesParentIndex() {
    var compound = buildCompound("goal", "cap-a");
    plan.registerDefinition(compound);

    var newCompound = buildCompound("goal", "cap-d");
    plan.replaceCompound("goal", newCompound, 1);

    assertTrue(plan.getParentOf("cap-d").isPresent());
    assertEquals("goal", plan.getParentOf("cap-d").get());
    assertFalse(plan.getParentOf("cap-a").isPresent());
  }

  @Test
  void replaceCompoundUpdatesScopedBindings() {
    var compound = buildCompound("goal", "cap-a", "cap-b");
    plan.registerDefinition(compound);

    var newCompound = buildCompound("goal", "cap-d", "cap-e");
    plan.replaceCompound("goal", newCompound, 1);

    var compounds = plan.getAllCompounds();
    assertEquals(1, compounds.size());
    assertTrue(compounds.get(0).scopedBindings().containsKey("cap-d"));
    assertTrue(compounds.get(0).scopedBindings().containsKey("cap-e"));
    assertFalse(compounds.get(0).scopedBindings().containsKey("cap-a"));
  }

  @Test
  void replaceCompoundRemovesPendingPlanItems() {
    var compound = buildCompound("goal", "cap-a", "cap-b");
    plan.registerDefinition(compound);
    plan.addPlanItem(createPlanItem("goal-step-0", "cap-a"));
    plan.addPlanItem(createPlanItem("goal-step-1", "cap-b"));

    var newCompound = buildCompound("goal", "cap-d");
    plan.replaceCompound("goal", newCompound, 1);

    assertFalse(plan.findPlanItemByBindingName("cap-a").isPresent());
    assertFalse(plan.findPlanItemByBindingName("cap-b").isPresent());
  }

  @Test
  void replaceCompoundPreservesCompletedPlanItems() {
    var compound = buildCompound("goal", "cap-a", "cap-b");
    plan.registerDefinition(compound);
    var itemA = createPlanItem("goal-step-0", "cap-a");
    itemA.tryMarkRunning();
    itemA.markCompleted();
    plan.addPlanItem(itemA);
    plan.addPlanItem(createPlanItem("goal-step-1", "cap-b"));

    var newCompound = buildCompound("goal", "cap-d");
    plan.replaceCompound("goal", newCompound, 1);

    assertTrue(plan.findPlanItemByBindingName("cap-a").isPresent());
    assertEquals(TaskStatus.COMPLETED, plan.findPlanItemByBindingName("cap-a").get().getStatus());
  }

  @Test
  void replaceCompoundIncrementsGeneration() {
    var compound = buildCompound("goal", "cap-a");
    plan.registerDefinition(compound);
    assertEquals(0, plan.getAdaptationGeneration("goal"));

    plan.replaceCompound("goal", buildCompound("goal", "cap-d"), 1);
    assertEquals(1, plan.getAdaptationGeneration("goal"));

    plan.replaceCompound("goal", buildCompound("goal", "cap-e"), 2);
    assertEquals(2, plan.getAdaptationGeneration("goal"));
  }

  @Test
  void replaceCompoundThrowsOnUnknownCompoundId() {
    assertThrows(
        IllegalArgumentException.class,
        () -> plan.replaceCompound("nonexistent", buildCompound("nonexistent", "cap-a"), 1));
  }

  @Test
  void replaceCompoundThrowsOnPrimitiveId() {
    var compound = buildCompound("goal", "cap-a");
    plan.registerDefinition(compound);
    assertThrows(
        IllegalArgumentException.class,
        () -> plan.replaceCompound("goal-step-0", buildCompound("goal-step-0", "cap-a"), 1));
  }

  @Test
  void evaluateCompletionWorksAfterReplacement() {
    var compound = buildCompound("goal", "cap-a", "cap-b");
    plan.registerDefinition(compound);

    var newCompound = buildCompound("goal", "cap-d");
    plan.replaceCompound("goal", newCompound, 1);

    // Transition both structural child and scoped binding PlanItem to terminal
    plan.tryDefinitionTransition("goal-cap-d", TaskStatus.PENDING, TaskStatus.COMPLETED);
    var item =
        PlanItem.create("cap-d", io.casehub.api.model.ExecutorRef.of("w", null), 0, null, "Step D");
    item.tryMarkRunning();
    item.markCompleted();
    plan.addPlanItem(item);

    assertTrue(plan.evaluateCompletion("goal"));
  }

  @Test
  void getAdaptationGenerationDefaultsToZero() {
    assertEquals(0, plan.getAdaptationGeneration("nonexistent"));
  }
}
