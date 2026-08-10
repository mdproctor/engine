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
package io.casehub.engine.planning.decomposition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.context.ContextLayer;
import io.casehub.api.context.MutableCaseContext;
import io.casehub.api.context.WritableLayer;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.engine.internal.routing.EngineStrategyResolver;
import io.casehub.engine.internal.routing.GoalAbandonmentEvaluator;
import io.casehub.engine.plan.DagPlan;
import io.casehub.engine.plan.DecompositionStrategy;
import io.casehub.engine.plan.PlanningConstraints;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultGoalDecomposerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private DefaultGoalDecomposer decomposer;
  private EngineStrategyResolver strategyResolver;
  private GoalAbandonmentEvaluator abandonmentEvaluator;
  private BlackboardRegistry blackboardRegistry;
  private PlanItemStore planItemStore;
  private EventLogRepository eventLogRepository;

  @BeforeEach
  void setUp() {
    decomposer = new DefaultGoalDecomposer();
    strategyResolver = mock(EngineStrategyResolver.class);
    abandonmentEvaluator = mock(GoalAbandonmentEvaluator.class);
    blackboardRegistry = mock(BlackboardRegistry.class);
    planItemStore = mock(PlanItemStore.class);
    eventLogRepository = mock(EventLogRepository.class);

    setField(decomposer, "strategyResolver", strategyResolver);
    setField(decomposer, "abandonmentEvaluator", abandonmentEvaluator);
    setField(decomposer, "blackboardRegistry", blackboardRegistry);
    setField(decomposer, "planItemStore", planItemStore);
    setField(decomposer, "eventLogRepository", eventLogRepository);
    setField(decomposer, "timeoutMs", 30000L);
  }

  @Test
  void emitsConstraintsInfeasibleWhenEmptyPlanWithHardConstraints() {
    var caseId = UUID.randomUUID();
    var instance = new CaseInstance();
    instance.setUuid(caseId);
    instance.tenancyId = "tenant-1";

    var goal =
        new io.casehub.eidos.api.AgentGoal(
            "analyse",
            "Analyse data",
            io.casehub.eidos.api.GoalPriority.PRIMARY,
            io.casehub.eidos.api.Visibility.PUBLIC,
            List.of());

    var descriptor =
        io.casehub.eidos.api.AgentDescriptor.builder()
            .agentId("agent-1")
            .name("agent-1")
            .slot("default")
            .tenancyId("tenant-1")
            .goals(List.of(goal))
            .build();

    var constraints = PlanningConstraints.of(java.time.Duration.ofMinutes(30), 3);
    var definition =
        CaseDefinition.builder()
            .namespace("io.test")
            .name("test")
            .version("1.0")
            .capabilities(new Capability("unknown-cap", "", "", null))
            .workers(Worker.builder().name("w1").capabilityName("unknown-cap").noFunction().build())
            .planningConstraints(constraints)
            .decompositionStrategy("llm")
            .build();
    setAgentDescriptors(definition, Map.of("w1", descriptor));

    var stepWithUnknownCap =
        new GoalStep(
            UUID.randomUUID(), "do something", "nonexistent-capability", java.time.Instant.now());
    @SuppressWarnings("unchecked")
    DecompositionStrategy<JsonNode> strategy = mock(DecompositionStrategy.class);
    when(strategy.decompose(any(), any()))
        .thenReturn(Uni.createFrom().item(DagPlan.singleton(stepWithUnknownCap)));
    when(strategyResolver.resolve(any(), anyString())).thenReturn(strategy);

    when(abandonmentEvaluator.activeGoals(any())).thenReturn(List.of(goal));
    when(planItemStore.findByCaseId(caseId, "tenant-1")).thenReturn(List.of());
    when(blackboardRegistry.getOrCreate(any(), anyString()))
        .thenReturn(mock(io.casehub.engine.planning.plan.CasePlanModel.class));

    var context = mock(MutableCaseContext.class);
    var layer = mock(WritableLayer.class);
    when(context.layer(ContextLayer.WORKING)).thenReturn(layer);
    when(layer.asJsonNode()).thenReturn(MAPPER.createObjectNode());

    decomposer.decompose(instance, definition, context);

    var captor = ArgumentCaptor.forClass(EventLog.class);
    verify(eventLogRepository).append(captor.capture(), any());

    var log = captor.getValue();
    assertThat(log.getEventType()).isEqualTo(CaseHubEventType.CONSTRAINTS_INFEASIBLE);
    assertThat(log.getMetadata().get("goalName").asText()).isEqualTo("analyse");
  }

  @Test
  void noInfeasibleEventWhenEmptyPlanWithoutConstraints() {
    var caseId = UUID.randomUUID();
    var instance = new CaseInstance();
    instance.setUuid(caseId);
    instance.tenancyId = "tenant-1";

    var goal =
        new io.casehub.eidos.api.AgentGoal(
            "analyse",
            "Analyse data",
            io.casehub.eidos.api.GoalPriority.PRIMARY,
            io.casehub.eidos.api.Visibility.PUBLIC,
            List.of());

    var descriptor =
        io.casehub.eidos.api.AgentDescriptor.builder()
            .agentId("agent-1")
            .name("agent-1")
            .slot("default")
            .tenancyId("tenant-1")
            .goals(List.of(goal))
            .build();

    var definition =
        CaseDefinition.builder()
            .namespace("io.test")
            .name("test")
            .version("1.0")
            .capabilities(new Capability("unknown-cap", "", "", null))
            .workers(Worker.builder().name("w1").capabilityName("unknown-cap").noFunction().build())
            .decompositionStrategy("llm")
            .build();
    setAgentDescriptors(definition, Map.of("w1", descriptor));

    var stepWithUnknownCap =
        new GoalStep(
            UUID.randomUUID(), "do something", "nonexistent-capability", java.time.Instant.now());
    @SuppressWarnings("unchecked")
    DecompositionStrategy<JsonNode> strategy = mock(DecompositionStrategy.class);
    when(strategy.decompose(any(), any()))
        .thenReturn(Uni.createFrom().item(DagPlan.singleton(stepWithUnknownCap)));
    when(strategyResolver.resolve(any(), anyString())).thenReturn(strategy);

    when(abandonmentEvaluator.activeGoals(any())).thenReturn(List.of(goal));
    when(planItemStore.findByCaseId(caseId, "tenant-1")).thenReturn(List.of());
    when(blackboardRegistry.getOrCreate(any(), anyString()))
        .thenReturn(mock(io.casehub.engine.planning.plan.CasePlanModel.class));

    var context = mock(MutableCaseContext.class);
    var layer = mock(WritableLayer.class);
    when(context.layer(ContextLayer.WORKING)).thenReturn(layer);
    when(layer.asJsonNode()).thenReturn(MAPPER.createObjectNode());

    decomposer.decompose(instance, definition, context);

    verify(eventLogRepository, never()).append(any(), any());
  }

  private void setAgentDescriptors(
      CaseDefinition definition, Map<String, AgentDescriptor> descriptors) {
    try {
      var field = CaseDefinition.class.getDeclaredField("agentDescriptors");
      field.setAccessible(true);
      field.set(definition, descriptors);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static void setField(Object target, String fieldName, Object value) {
    try {
      var field = target.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(target, value);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
