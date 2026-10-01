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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.api.spi.routing.GoalFormationProposal;
import io.casehub.api.spi.routing.GoalFormationResult;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.eidos.api.GoalPriority;
import io.casehub.eidos.api.Visibility;
import io.casehub.engine.common.spi.EventLogRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultGoalFormationServiceTest {

  private AgentRegistry agentRegistry;
  private EventLogRepository eventLogRepository;
  private DefaultGoalFormationService service;

  @BeforeEach
  void setUp() {
    agentRegistry = mock(AgentRegistry.class);
    eventLogRepository = mock(EventLogRepository.class);
    service = new DefaultGoalFormationService(agentRegistry, eventLogRepository);
  }

  @Test
  void registersValidGoals() {
    setupAgent("agent-1", "tenant-1", goal("existing-goal"));

    var proposed =
        new GoalFormationProposal.ProposedGoal(
            "new-goal", "A new goal", GoalPriority.SECONDARY, "from insight", null);
    var proposal = new GoalFormationProposal(List.of(proposed), "rationale");

    GoalFormationResult result = service.propose("agent-1", "tenant-1", proposal);

    assertThat(result.registered()).hasSize(1);
    assertThat(result.registered().get(0).name()).isEqualTo("new-goal");
    assertThat(result.rejected()).isEmpty();
    assertThat(result.totalGoalCount()).isEqualTo(2);

    var captor = ArgumentCaptor.forClass(AgentDescriptor.class);
    verify(agentRegistry).register(captor.capture());
    assertThat(captor.getValue().goals()).hasSize(2);
  }

  @Test
  void rejectsDuplicateNames() {
    setupAgent("agent-1", "tenant-1", goal("existing-goal"));

    var proposed =
        new GoalFormationProposal.ProposedGoal(
            "existing-goal", "duplicate", GoalPriority.SECONDARY, "reason", null);
    var proposal = new GoalFormationProposal(List.of(proposed), "rationale");

    GoalFormationResult result = service.propose("agent-1", "tenant-1", proposal);

    assertThat(result.registered()).isEmpty();
    assertThat(result.rejected()).hasSize(1);
    assertThat(result.rejected().get(0).reason()).contains("duplicate");
  }

  @Test
  void rejectsWhenCapacityExceeded() {
    List<AgentGoal> tenGoals = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      tenGoals.add(goal("goal-" + i));
    }
    setupAgent("agent-1", "tenant-1", tenGoals.toArray(new AgentGoal[0]));

    var proposed =
        new GoalFormationProposal.ProposedGoal(
            "one-more", "desc", GoalPriority.SECONDARY, "reason", null);
    var proposal = new GoalFormationProposal(List.of(proposed), "rationale");

    GoalFormationResult result = service.propose("agent-1", "tenant-1", proposal);

    assertThat(result.registered()).isEmpty();
    assertThat(result.rejected()).hasSize(1);
    assertThat(result.rejected().get(0).reason()).contains("capacity");
  }

  @Test
  void rejectsNameTooLong() {
    setupAgent("agent-1", "tenant-1");

    var proposed =
        new GoalFormationProposal.ProposedGoal(
            "x".repeat(101), "desc", GoalPriority.SECONDARY, "reason", null);
    var proposal = new GoalFormationProposal(List.of(proposed), "rationale");

    GoalFormationResult result = service.propose("agent-1", "tenant-1", proposal);

    assertThat(result.registered()).isEmpty();
    assertThat(result.rejected()).hasSize(1);
    assertThat(result.rejected().get(0).reason()).contains("name");
  }

  @Test
  void rejectsDescriptionTooLong() {
    setupAgent("agent-1", "tenant-1");

    var proposed =
        new GoalFormationProposal.ProposedGoal(
            "goal", "x".repeat(501), GoalPriority.SECONDARY, "reason", null);
    var proposal = new GoalFormationProposal(List.of(proposed), "rationale");

    GoalFormationResult result = service.propose("agent-1", "tenant-1", proposal);

    assertThat(result.registered()).isEmpty();
    assertThat(result.rejected()).hasSize(1);
    assertThat(result.rejected().get(0).reason()).contains("description");
  }

  @Test
  void propagatesAttributes() {
    setupAgent("agent-1", "tenant-1");

    var attrs = Map.of("source", "drive", "driveAxis", "CURIOSITY");
    var proposed =
        new GoalFormationProposal.ProposedGoal(
            "explore-gaps", "Explore knowledge gaps", GoalPriority.SECONDARY, "curiosity", attrs);
    var proposal = new GoalFormationProposal(List.of(proposed), "rationale");

    GoalFormationResult result = service.propose("agent-1", "tenant-1", proposal);

    assertThat(result.registered()).hasSize(1);
    assertThat(result.registered().get(0).attributes()).isEqualTo(attrs);
  }

  @Test
  void defaultsPriorityToSecondaryWhenNull() {
    setupAgent("agent-1", "tenant-1");

    var proposed = new GoalFormationProposal.ProposedGoal("goal", "desc", null, "reason", null);
    var proposal = new GoalFormationProposal(List.of(proposed), "rationale");

    GoalFormationResult result = service.propose("agent-1", "tenant-1", proposal);

    assertThat(result.registered()).hasSize(1);
    assertThat(result.registered().get(0).priority()).isEqualTo(GoalPriority.SECONDARY);
  }

  @Test
  void mixedValidAndInvalid() {
    setupAgent("agent-1", "tenant-1", goal("existing"));

    var valid =
        new GoalFormationProposal.ProposedGoal(
            "new-valid", "A valid goal", GoalPriority.SECONDARY, "reason", null);
    var duplicate =
        new GoalFormationProposal.ProposedGoal(
            "existing", "dup", GoalPriority.SECONDARY, "reason", null);
    var tooLong =
        new GoalFormationProposal.ProposedGoal(
            "x".repeat(101), "desc", GoalPriority.SECONDARY, "reason", null);
    var proposal = new GoalFormationProposal(List.of(valid, duplicate, tooLong), "rationale");

    GoalFormationResult result = service.propose("agent-1", "tenant-1", proposal);

    assertThat(result.registered()).hasSize(1);
    assertThat(result.registered().get(0).name()).isEqualTo("new-valid");
    assertThat(result.rejected()).hasSize(2);
    assertThat(result.totalGoalCount()).isEqualTo(2);
  }

  @Test
  void writesAuditLog() {
    setupAgent("agent-1", "tenant-1");

    var proposed =
        new GoalFormationProposal.ProposedGoal(
            "goal", "desc", GoalPriority.SECONDARY, "reason", null);
    var proposal = new GoalFormationProposal(List.of(proposed), "rationale");

    service.propose("agent-1", "tenant-1", proposal);

    verify(eventLogRepository).append(any(), any());
  }

  @Test
  void returnsEmptyWhenAgentNotFound() {
    when(agentRegistry.findById("unknown", "tenant-1")).thenReturn(Optional.empty());

    var proposed =
        new GoalFormationProposal.ProposedGoal(
            "goal", "desc", GoalPriority.SECONDARY, "reason", null);
    var proposal = new GoalFormationProposal(List.of(proposed), "rationale");

    GoalFormationResult result = service.propose("unknown", "tenant-1", proposal);

    assertThat(result.registered()).isEmpty();
    assertThat(result.rejected()).hasSize(1);
    assertThat(result.rejected().get(0).reason()).contains("not found");
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
