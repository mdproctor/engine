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
package io.casehub.engine.internal.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.api.model.CaseDefinition;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.GoalPriority;
import io.casehub.eidos.api.Visibility;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.internal.context.CaseContextImpl;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentGoalCompletionMarkerTest {

  private CaseDefinitionRegistry registry;
  private AgentGoalCompletionMarker marker;

  @BeforeEach
  void setUp() {
    registry = mock(CaseDefinitionRegistry.class);
    marker = new AgentGoalCompletionMarker(registry);
  }

  @Test
  void marksGoalCompletedForWorkerWithAgentDescriptor() {
    CaseInstance instance = buildCaseInstance("test-tenant");
    CaseDefinition definition =
        CaseDefinition.builder()
            .namespace("ns")
            .name("case")
            .version("1.0")
            .agentDescriptor(
                "worker-a",
                AgentDescriptor.builder()
                    .agentId("agent-1")
                    .name("Agent")
                    .slot("support")
                    .tenancyId("test-tenant")
                    .goals(
                        List.of(
                            new AgentGoal(
                                "resolve-customer-issue",
                                "Resolve customer issues",
                                GoalPriority.PRIMARY,
                                Visibility.PUBLIC,
                                List.of()),
                            new AgentGoal(
                                "maintain-customer-satisfaction",
                                "Maintain satisfaction",
                                GoalPriority.SECONDARY,
                                Visibility.PUBLIC,
                                List.of())))
                    .build())
            .build();

    when(registry.getCaseDefinition(instance.getCaseMetaModel())).thenReturn(definition);

    marker.markGoalsCompleted(instance, "worker-a");

    @SuppressWarnings("unchecked")
    Map<String, Object> agentGoals =
        (Map<String, Object>) instance.getCaseContext().get("_agentGoals");
    assertNotNull(agentGoals);
    assertTrue(agentGoals.containsKey("agent-1"));

    @SuppressWarnings("unchecked")
    Map<String, Object> agentMap = (Map<String, Object>) agentGoals.get("agent-1");
    assertTrue(agentMap.containsKey("resolve-customer-issue"));
    assertTrue(agentMap.containsKey("maintain-customer-satisfaction"));

    @SuppressWarnings("unchecked")
    Map<String, Object> goal1Map = (Map<String, Object>) agentMap.get("resolve-customer-issue");
    assertEquals(true, goal1Map.get("met"));
    assertNotNull(goal1Map.get("timestamp"));

    @SuppressWarnings("unchecked")
    Map<String, Object> goal2Map =
        (Map<String, Object>) agentMap.get("maintain-customer-satisfaction");
    assertEquals(true, goal2Map.get("met"));
    assertNotNull(goal2Map.get("timestamp"));
  }

  @Test
  void doesNotMarkGoalsWhenWorkerHasNoDescriptor() {
    CaseInstance instance = buildCaseInstance("test-tenant");
    CaseDefinition definition =
        CaseDefinition.builder().namespace("ns").name("case").version("1.0").build();

    when(registry.getCaseDefinition(instance.getCaseMetaModel())).thenReturn(definition);

    marker.markGoalsCompleted(instance, "worker-a");

    Object agentGoals = instance.getCaseContext().get("_agentGoals");
    assertNull(agentGoals);
  }

  @Test
  void doesNotMarkGoalsWhenDescriptorHasNoGoals() {
    CaseInstance instance = buildCaseInstance("test-tenant");
    CaseDefinition definition =
        CaseDefinition.builder()
            .namespace("ns")
            .name("case")
            .version("1.0")
            .agentDescriptor(
                "worker-a",
                AgentDescriptor.builder()
                    .agentId("agent-1")
                    .name("basic-worker")
                    .slot("worker")
                    .tenancyId("test-tenant")
                    .build())
            .build();

    when(registry.getCaseDefinition(instance.getCaseMetaModel())).thenReturn(definition);

    marker.markGoalsCompleted(instance, "worker-a");

    Object agentGoals = instance.getCaseContext().get("_agentGoals");
    assertNull(agentGoals);
  }

  @Test
  void preservesExistingAgentGoalsWhenMarkingNew() {
    CaseInstance instance = buildCaseInstance("test-tenant");

    // Pre-populate with existing goals from a different agent
    instance
        .getCaseContext()
        .set(
            "_agentGoals",
            Map.of(
                "agent-1",
                Map.of("existing-goal", Map.of("met", true, "timestamp", "2026-08-01T10:00:00Z"))));

    CaseDefinition definition =
        CaseDefinition.builder()
            .namespace("ns")
            .name("case")
            .version("1.0")
            .agentDescriptor(
                "worker-b",
                AgentDescriptor.builder()
                    .agentId("agent-2")
                    .name("task-agent")
                    .slot("executor")
                    .tenancyId("test-tenant")
                    .goals(
                        List.of(
                            new AgentGoal(
                                "complete-task",
                                "Complete task",
                                GoalPriority.PRIMARY,
                                Visibility.PUBLIC,
                                List.of())))
                    .build())
            .build();

    when(registry.getCaseDefinition(instance.getCaseMetaModel())).thenReturn(definition);

    marker.markGoalsCompleted(instance, "worker-b");

    @SuppressWarnings("unchecked")
    Map<String, Object> agentGoals =
        (Map<String, Object>) instance.getCaseContext().get("_agentGoals");
    assertNotNull(agentGoals);
    assertTrue(agentGoals.containsKey("agent-1"));
    assertTrue(agentGoals.containsKey("agent-2"));

    @SuppressWarnings("unchecked")
    Map<String, Object> agent2Map = (Map<String, Object>) agentGoals.get("agent-2");
    assertTrue(agent2Map.containsKey("complete-task"));
  }

  @Test
  void handlesDefinitionRegistryException() {
    CaseInstance instance = buildCaseInstance("test-tenant");
    when(registry.getCaseDefinition(instance.getCaseMetaModel()))
        .thenThrow(new IllegalArgumentException("Definition not found"));

    marker.markGoalsCompleted(instance, "worker-a");

    Object agentGoals = instance.getCaseContext().get("_agentGoals");
    assertNull(agentGoals);
  }

  private CaseInstance buildCaseInstance(String tenancyId) {
    CaseInstance instance = new CaseInstance();
    instance.tenancyId = tenancyId;
    CaseMetaModel meta = new CaseMetaModel();
    instance.setCaseMetaModel(meta);
    instance.setCaseContext(new CaseContextImpl());
    return instance;
  }
}
