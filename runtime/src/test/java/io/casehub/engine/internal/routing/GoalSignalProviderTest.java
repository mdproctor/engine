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
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.NullNode;
import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentHealth;
import io.casehub.api.spi.routing.AgentRoutingContext;
import io.casehub.api.spi.routing.RoutingSignal;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.GoalPriority;
import io.casehub.eidos.api.Visibility;
import jakarta.enterprise.inject.Instance;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GoalSignalProviderTest {

  private GoalSignalProvider provider;
  private GoalAbandonmentEvaluator mockEvaluator;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    mockEvaluator = mock(GoalAbandonmentEvaluator.class);
    Instance<GoalAbandonmentEvaluator> instance = mock(Instance.class);
    when(instance.isResolvable()).thenReturn(true);
    when(instance.get()).thenReturn(mockEvaluator);
    provider = new GoalSignalProvider(instance);
  }

  @Test
  void id_isGoal() {
    assertThat(provider.id()).isEqualTo("goal");
  }

  @Test
  void noDescriptor_skipped() {
    var candidate = candidateWithoutDescriptor("a");
    var result = provider.evaluate(ctx(), List.of(candidate));
    assertThat(result).isNull();
  }

  @Test
  void noGoals_skipped() {
    var descriptor = descriptorWithGoals("agent1", List.of());
    var candidate = candidateWithDescriptor("a", descriptor);
    var result = provider.evaluate(ctx(), List.of(candidate));
    assertThat(result).isNull();
  }

  @Test
  void allGoalsAbandoned_excluded() {
    var goal1 = goal("goal1");
    var goal2 = goal("goal2");
    var descriptor = descriptorWithGoals("agent1", List.of(goal1, goal2));
    when(mockEvaluator.activeGoals(descriptor)).thenReturn(List.of());
    var candidate = candidateWithDescriptor("a", descriptor);

    var result = provider.evaluate(ctx(), List.of(candidate));

    assertThat(result).isNotNull();
    var signal = result.candidates().get("a");
    assertThat(signal).isInstanceOf(RoutingSignal.CandidateSignal.Exclude.class);
    var exclude = (RoutingSignal.CandidateSignal.Exclude) signal;
    assertThat(exclude.reason()).isEqualTo("all goals abandoned");
  }

  @Test
  void allGoalsActive_scoreOne() {
    var goal1 = goal("goal1");
    var goal2 = goal("goal2");
    var descriptor = descriptorWithGoals("agent1", List.of(goal1, goal2));
    when(mockEvaluator.activeGoals(descriptor)).thenReturn(List.of(goal1, goal2));
    var candidate = candidateWithDescriptor("a", descriptor);

    var result = provider.evaluate(ctx(), List.of(candidate));

    assertThat(result).isNotNull();
    var signal = (RoutingSignal.CandidateSignal.Score) result.candidates().get("a");
    assertThat(signal.value()).isEqualTo(1.0);
    assertThat(signal.rationale()).isEqualTo("2/2 active goals");
  }

  @Test
  void someGoalsAbandoned_fractionalScore() {
    var goal1 = goal("goal1");
    var goal2 = goal("goal2");
    var goal3 = goal("goal3");
    var descriptor = descriptorWithGoals("agent1", List.of(goal1, goal2, goal3));
    when(mockEvaluator.activeGoals(descriptor)).thenReturn(List.of(goal1, goal2));
    var candidate = candidateWithDescriptor("a", descriptor);

    var result = provider.evaluate(ctx(), List.of(candidate));

    assertThat(result).isNotNull();
    var signal = (RoutingSignal.CandidateSignal.Score) result.candidates().get("a");
    assertThat(signal.value()).isCloseTo(0.667, within(0.001));
    assertThat(signal.rationale()).isEqualTo("2/3 active goals");
  }

  @Test
  void noEvaluator_allGoalsConsideredActive() {
    var goal1 = goal("goal1");
    var goal2 = goal("goal2");
    var descriptor = descriptorWithGoals("agent1", List.of(goal1, goal2));
    var candidate = candidateWithDescriptor("a", descriptor);
    @SuppressWarnings("unchecked")
    Instance<GoalAbandonmentEvaluator> emptyInstance = mock(Instance.class);
    when(emptyInstance.isResolvable()).thenReturn(false);
    var providerWithoutEvaluator = new GoalSignalProvider(emptyInstance);

    var result = providerWithoutEvaluator.evaluate(ctx(), List.of(candidate));

    assertThat(result).isNotNull();
    var signal = (RoutingSignal.CandidateSignal.Score) result.candidates().get("a");
    assertThat(signal.value()).isEqualTo(1.0);
  }

  @Test
  void multipleCandidates_individuallyScored() {
    var goal1 = goal("goal1");
    var goal2 = goal("goal2");
    var goal3 = goal("goal3");

    var descriptor1 = descriptorWithGoals("agent1", List.of(goal1, goal2));
    var descriptor2 = descriptorWithGoals("agent2", List.of(goal1, goal2, goal3));

    when(mockEvaluator.activeGoals(descriptor1)).thenReturn(List.of(goal1, goal2));
    when(mockEvaluator.activeGoals(descriptor2)).thenReturn(List.of(goal1, goal2));

    var candidate1 = candidateWithDescriptor("a", descriptor1);
    var candidate2 = candidateWithDescriptor("b", descriptor2);

    var result = provider.evaluate(ctx(), List.of(candidate1, candidate2));

    assertThat(result).isNotNull();
    assertThat(result.candidates()).containsOnlyKeys("a", "b");

    var scoreA = ((RoutingSignal.CandidateSignal.Score) result.candidates().get("a")).value();
    var scoreB = ((RoutingSignal.CandidateSignal.Score) result.candidates().get("b")).value();

    assertThat(scoreA).isEqualTo(1.0);
    assertThat(scoreB).isCloseTo(0.667, within(0.001));
  }

  @Test
  void mixedCandidates_onlyDescriptorsWithGoalsScored() {
    var goal1 = goal("goal1");
    var descriptor = descriptorWithGoals("agent1", List.of(goal1));
    when(mockEvaluator.activeGoals(descriptor)).thenReturn(List.of(goal1));

    var withDescriptor = candidateWithDescriptor("a", descriptor);
    var withoutDescriptor = candidateWithoutDescriptor("b");
    var withoutGoals = candidateWithDescriptor("c", descriptorWithGoals("agent3", List.of()));

    var result = provider.evaluate(ctx(), List.of(withDescriptor, withoutDescriptor, withoutGoals));

    assertThat(result).isNotNull();
    assertThat(result.candidates()).containsOnlyKeys("a");
  }

  private static AgentCandidate candidateWithDescriptor(String id, AgentDescriptor descriptor) {
    return new AgentCandidate(id, Set.of(), 0, AgentHealth.READY, descriptor, null);
  }

  private static AgentCandidate candidateWithoutDescriptor(String id) {
    return new AgentCandidate(id, Set.of(), 0, AgentHealth.READY, null, null);
  }

  private static AgentDescriptor descriptorWithGoals(String agentId, List<AgentGoal> goals) {
    return AgentDescriptor.builder()
        .agentId(agentId)
        .name("Test Agent")
        .slot("test")
        .tenancyId("t1")
        .goals(goals)
        .build();
  }

  private static AgentGoal goal(String name) {
    return new AgentGoal(name, "Test goal", GoalPriority.PRIMARY, Visibility.PUBLIC, List.of());
  }

  private static AgentRoutingContext ctx() {
    return new AgentRoutingContext(
        UUID.randomUUID(), "cap", NullNode.getInstance(), "t1", List.of(), null, null);
  }
}
