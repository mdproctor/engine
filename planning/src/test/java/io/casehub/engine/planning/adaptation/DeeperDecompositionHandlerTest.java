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
package io.casehub.engine.planning.adaptation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ExecutorRef;
import io.casehub.api.model.FailureCategory;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.engine.plan.DagNode;
import io.casehub.engine.plan.DagPlan;
import io.casehub.engine.plan.DecompositionStrategy;
import io.casehub.engine.plan.JoinType;
import io.casehub.engine.plan.TaskNode;
import io.casehub.engine.planning.decomposition.GoalStep;
import io.casehub.engine.planning.plan.CompletionSemantics;
import io.casehub.engine.planning.plan.DefaultCasePlanModel;
import io.casehub.engine.planning.plan.PlanItem;
import io.casehub.engine.planning.plan.PlanItemDefinition;
import io.casehub.platform.api.routing.StrategyResolver;
import io.casehub.worker.api.Capability;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DeeperDecompositionHandlerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private DeeperDecompositionHandler handler;
  private StrategyResolver strategyResolver;
  private CaseDefinitionRegistry caseDefinitionRegistry;
  private PlanItemStore planItemStore;
  private EventLogRepository eventLogRepository;
  private CaseDefinition definition;
  private DecompositionStrategy<JsonNode> decompositionStrategy;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    strategyResolver = mock(StrategyResolver.class);
    caseDefinitionRegistry = mock(CaseDefinitionRegistry.class);
    planItemStore = mock(PlanItemStore.class);
    eventLogRepository = mock(EventLogRepository.class);
    definition = mock(CaseDefinition.class);
    decompositionStrategy = mock(DecompositionStrategy.class);

    handler =
        new DeeperDecompositionHandler(
            strategyResolver,
            caseDefinitionRegistry,
            planItemStore,
            eventLogRepository,
            Optional.empty());
  }

  @Test
  @SuppressWarnings("unchecked")
  void tryDecompose_happy_path() {
    var caseId = UUID.randomUUID();
    var instance = buildInstance(caseId);
    var plan = buildPlanWithPrimitive(caseId, "analyse");
    var item = addRunningPlanItem(plan, "analyse");

    when(caseDefinitionRegistry.getCaseDefinition(any())).thenReturn(definition);
    when(definition.getDecompositionStrategy()).thenReturn("goap");
    when(definition.getMaxDecompositionDepth()).thenReturn(3);
    when(definition.getCapabilities())
        .thenReturn(List.of(Capability.of("sub-a", ".", "."), Capability.of("sub-b", ".", ".")));
    when(definition.getPlanningConstraints()).thenReturn(null);

    var bindingA = mock(Binding.class);
    when(bindingA.getName()).thenReturn("binding-sub-a");
    var bindingB = mock(Binding.class);
    when(bindingB.getName()).thenReturn("binding-sub-b");
    when(definition.findBindingsByCapability("sub-a")).thenReturn(List.of(bindingA));
    when(definition.findBindingsByCapability("sub-b")).thenReturn(List.of(bindingB));

    when(strategyResolver.resolve(eq(DecompositionStrategy.class), eq("goap")))
        .thenReturn(decompositionStrategy);

    TaskNode.LeafTask<JsonNode> step1 =
        new GoalStep(UUID.randomUUID(), "Extract metadata", "sub-a", Instant.now());
    TaskNode.LeafTask<JsonNode> step2 =
        new GoalStep(UUID.randomUUID(), "Evaluate risk", "sub-b", Instant.now());
    DagPlan<TaskNode.LeafTask<JsonNode>> dagPlan =
        DagPlan.fromNodes(
            List.of(
                new DagNode<>("n1", step1, Set.of(), JoinType.ALL_OF),
                new DagNode<>("n2", step2, Set.of("n1"), JoinType.ALL_OF)));
    when(decompositionStrategy.decompose(any(), any())).thenReturn(dagPlan);

    var category = new FailureCategory.Knowledge("task too coarse", "missing sub-steps");

    boolean result = handler.tryDecompose(instance, plan, item, category);

    assertThat(result).isTrue();
    assertThat(plan.getDefinition("analyse")).isInstanceOf(PlanItemDefinition.Compound.class);
    assertThat(plan.getChildrenOf("analyse")).hasSize(2);

    var logCaptor = ArgumentCaptor.forClass(EventLog.class);
    verify(eventLogRepository).append(logCaptor.capture(), eq("tenant-1"));
    assertThat(logCaptor.getValue().getEventType()).isEqualTo(CaseHubEventType.PLAN_DEEPENED);
  }

  @Test
  void tryDecompose_returns_false_when_not_in_compound() {
    var caseId = UUID.randomUUID();
    var instance = buildInstance(caseId);
    var plan = new DefaultCasePlanModel(caseId);
    var item = PlanItem.create("orphan", ExecutorRef.of("cap", null), 0);
    item.tryMarkRunning();
    plan.addPlanItem(item);

    var category = new FailureCategory.Knowledge("reason", null);
    assertThat(handler.tryDecompose(instance, plan, item, category)).isFalse();
  }

  @Test
  void tryDecompose_returns_false_when_no_decomposition_strategy() {
    var caseId = UUID.randomUUID();
    var instance = buildInstance(caseId);
    var plan = buildPlanWithPrimitive(caseId, "analyse");
    var item = addRunningPlanItem(plan, "analyse");

    when(caseDefinitionRegistry.getCaseDefinition(any())).thenReturn(definition);
    when(definition.getDecompositionStrategy()).thenReturn(null);

    var category = new FailureCategory.Knowledge("reason", null);
    assertThat(handler.tryDecompose(instance, plan, item, category)).isFalse();
  }

  @Test
  void tryDecompose_returns_false_when_max_depth_reached() {
    var caseId = UUID.randomUUID();
    var instance = buildInstance(caseId);

    // Build nested structure: parent > child-compound > leaf
    var plan = new DefaultCasePlanModel(caseId);
    var leaf = new PlanItemDefinition.Primitive("leaf", "Leaf", ExecutorRef.of("cap", null), null);
    var childCompound =
        PlanItemDefinition.Compound.builder("child")
            .id("child")
            .child(leaf)
            .binding("leaf")
            .completion(CompletionSemantics.all())
            .build();
    var parentCompound =
        PlanItemDefinition.Compound.builder("parent")
            .id("parent")
            .child(childCompound)
            .binding("child")
            .completion(CompletionSemantics.all())
            .build();
    plan.registerDefinition(parentCompound);

    var item = PlanItem.create("leaf", ExecutorRef.of("cap", null), 0);
    item.tryMarkRunning();
    plan.addPlanItem(item);

    when(caseDefinitionRegistry.getCaseDefinition(any())).thenReturn(definition);
    when(definition.getDecompositionStrategy()).thenReturn("goap");
    when(definition.getMaxDecompositionDepth()).thenReturn(2);

    var category = new FailureCategory.Knowledge("reason", null);
    // depth of "leaf" = 2 (child + parent), maxDepth = 2 → should return false
    assertThat(handler.tryDecompose(instance, plan, item, category)).isFalse();
  }

  @Test
  @SuppressWarnings("unchecked")
  void tryDecompose_returns_false_when_strategy_throws() {
    var caseId = UUID.randomUUID();
    var instance = buildInstance(caseId);
    var plan = buildPlanWithPrimitive(caseId, "analyse");
    var item = addRunningPlanItem(plan, "analyse");

    when(caseDefinitionRegistry.getCaseDefinition(any())).thenReturn(definition);
    when(definition.getDecompositionStrategy()).thenReturn("goap");
    when(definition.getMaxDecompositionDepth()).thenReturn(3);
    when(definition.getCapabilities()).thenReturn(List.of());
    when(definition.getPlanningConstraints()).thenReturn(null);
    when(strategyResolver.resolve(eq(DecompositionStrategy.class), eq("goap")))
        .thenReturn(decompositionStrategy);
    when(decompositionStrategy.decompose(any(), any()))
        .thenThrow(new RuntimeException("strategy failed"));

    var category = new FailureCategory.Knowledge("reason", null);
    assertThat(handler.tryDecompose(instance, plan, item, category)).isFalse();
    verify(eventLogRepository, never()).append(any(), any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void tryDecompose_returns_false_when_single_step_result() {
    var caseId = UUID.randomUUID();
    var instance = buildInstance(caseId);
    var plan = buildPlanWithPrimitive(caseId, "analyse");
    var item = addRunningPlanItem(plan, "analyse");

    when(caseDefinitionRegistry.getCaseDefinition(any())).thenReturn(definition);
    when(definition.getDecompositionStrategy()).thenReturn("goap");
    when(definition.getMaxDecompositionDepth()).thenReturn(3);
    when(definition.getCapabilities()).thenReturn(List.of(Capability.of("sub-a", ".", ".")));
    when(definition.getPlanningConstraints()).thenReturn(null);
    when(strategyResolver.resolve(eq(DecompositionStrategy.class), eq("goap")))
        .thenReturn(decompositionStrategy);

    TaskNode.LeafTask<JsonNode> step =
        new GoalStep(UUID.randomUUID(), "Single step", "sub-a", Instant.now());
    DagPlan<TaskNode.LeafTask<JsonNode>> dagPlan = DagPlan.singleton(step);
    when(decompositionStrategy.decompose(any(), any())).thenReturn(dagPlan);

    var category = new FailureCategory.Knowledge("reason", null);
    assertThat(handler.tryDecompose(instance, plan, item, category)).isFalse();
  }

  // --- helpers ---

  private CaseInstance buildInstance(UUID caseId) {
    var instance = new CaseInstance();
    instance.setUuid(caseId);
    instance.tenancyId = "tenant-1";
    instance.setCaseMetaModel(new CaseMetaModel());
    return instance;
  }

  private DefaultCasePlanModel buildPlanWithPrimitive(UUID caseId, String bindingName) {
    var plan = new DefaultCasePlanModel(caseId);
    var primitive =
        new PlanItemDefinition.Primitive(
            bindingName, "Step " + bindingName, ExecutorRef.of(bindingName, null), null);
    var compound =
        PlanItemDefinition.Compound.builder("root")
            .id("root")
            .child(primitive)
            .binding(bindingName)
            .completion(CompletionSemantics.all())
            .build();
    plan.registerDefinition(compound);
    return plan;
  }

  private PlanItem addRunningPlanItem(DefaultCasePlanModel plan, String bindingName) {
    var item = PlanItem.create(bindingName, ExecutorRef.of(bindingName, null), 0);
    item.tryMarkRunning();
    plan.addPlanItem(item);
    return item;
  }
}
