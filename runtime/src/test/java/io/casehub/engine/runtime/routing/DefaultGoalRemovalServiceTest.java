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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.api.spi.routing.GoalRemovalResult;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.eidos.api.GoalPriority;
import io.casehub.eidos.api.Visibility;
import io.casehub.engine.common.spi.EventLogRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultGoalRemovalServiceTest {

  private AgentRegistry agentRegistry;
  private EventLogRepository eventLogRepository;
  private DefaultGoalRemovalService service;

  @BeforeEach
  void setUp() {
    agentRegistry = mock(AgentRegistry.class);
    eventLogRepository = mock(EventLogRepository.class);
    service = new DefaultGoalRemovalService(agentRegistry, eventLogRepository);
  }

  @Test
  void removesNamedGoals() {
    setupAgent("agent-1", "tenant-1", goal("keep"), goal("remove-me"), goal("also-remove"));

    GoalRemovalResult result =
        service.removeGoals(
            "agent-1", "tenant-1", List.of("remove-me", "also-remove"), "drive relevance low");

    assertThat(result.removedGoals()).containsExactlyInAnyOrder("remove-me", "also-remove");
    assertThat(result.remainingGoalCount()).isEqualTo(1);

    var captor = ArgumentCaptor.forClass(AgentDescriptor.class);
    verify(agentRegistry).register(captor.capture());
    assertThat(captor.getValue().goals()).hasSize(1);
    assertThat(captor.getValue().goals().get(0).name()).isEqualTo("keep");
  }

  @Test
  void ignoresNonexistentGoalNames() {
    setupAgent("agent-1", "tenant-1", goal("keep"));

    GoalRemovalResult result =
        service.removeGoals("agent-1", "tenant-1", List.of("nonexistent"), "reason");

    assertThat(result.removedGoals()).isEmpty();
    assertThat(result.remainingGoalCount()).isEqualTo(1);
    verify(agentRegistry, never()).register(any());
  }

  @Test
  void writesAuditLog() {
    setupAgent("agent-1", "tenant-1", goal("remove-me"));

    service.removeGoals("agent-1", "tenant-1", List.of("remove-me"), "stale drive");

    verify(eventLogRepository).append(any(), any());
  }

  @Test
  void returnsEmptyWhenAgentNotFound() {
    when(agentRegistry.findById("unknown", "tenant-1")).thenReturn(Optional.empty());

    GoalRemovalResult result =
        service.removeGoals("unknown", "tenant-1", List.of("goal"), "reason");

    assertThat(result.removedGoals()).isEmpty();
    assertThat(result.remainingGoalCount()).isEqualTo(0);
  }

  @Test
  void removesAllGoals() {
    setupAgent("agent-1", "tenant-1", goal("a"), goal("b"));

    GoalRemovalResult result =
        service.removeGoals("agent-1", "tenant-1", List.of("a", "b"), "cleanup");

    assertThat(result.removedGoals()).containsExactlyInAnyOrder("a", "b");
    assertThat(result.remainingGoalCount()).isEqualTo(0);
  }

  private AgentGoal goal(String name) {
    return new AgentGoal(
        name, "desc-" + name, GoalPriority.SECONDARY, Visibility.PUBLIC, List.of(), null);
  }

  private void setupAgent(String agentId, String tenancyId, AgentGoal... goals) {
    var descriptor =
        AgentDescriptor.builder()
            .agentId(agentId)
            .name("Agent")
            .slot("default")
            .tenancyId(tenancyId)
            .goals(List.of(goals))
            .build();
    when(agentRegistry.findById(agentId, tenancyId)).thenReturn(Optional.of(descriptor));
  }
}
