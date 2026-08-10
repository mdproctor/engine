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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.GoalOutcome;
import io.casehub.eidos.api.GoalPriority;
import io.casehub.eidos.api.GoalSignalStore;
import io.casehub.eidos.api.InMemoryGoalSignalStore;
import io.casehub.eidos.api.Visibility;
import jakarta.enterprise.inject.Instance;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GoalAbandonmentEvaluatorTest {

  private InMemoryGoalSignalStore signalStore;
  private GoalAbandonmentEvaluator evaluator;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    signalStore = new InMemoryGoalSignalStore();
    Instance<GoalSignalStore> storeInstance = mock(Instance.class);
    when(storeInstance.isResolvable()).thenReturn(true);
    when(storeInstance.get()).thenReturn(signalStore);
    evaluator = new GoalAbandonmentEvaluator(storeInstance, 5);
  }

  @Test
  void belowThreshold_goalIsActive() {
    for (int i = 0; i < 3; i++) {
      signalStore.recordOutcome("agent-1", "tenant-1", "maximize_roi", GoalOutcome.FAILURE);
    }
    assertThat(evaluator.isAbandoned("agent-1", "tenant-1", "maximize_roi")).isFalse();
  }

  @Test
  void atThreshold_goalIsAbandoned() {
    for (int i = 0; i < 5; i++) {
      signalStore.recordOutcome("agent-1", "tenant-1", "maximize_roi", GoalOutcome.FAILURE);
    }
    assertThat(evaluator.isAbandoned("agent-1", "tenant-1", "maximize_roi")).isTrue();
  }

  @Test
  void aboveThreshold_goalIsAbandoned() {
    for (int i = 0; i < 8; i++) {
      signalStore.recordOutcome("agent-1", "tenant-1", "maximize_roi", GoalOutcome.FAILURE);
    }
    assertThat(evaluator.isAbandoned("agent-1", "tenant-1", "maximize_roi")).isTrue();
  }

  @Test
  void successDoesNotCountTowardAbandonment() {
    for (int i = 0; i < 10; i++) {
      signalStore.recordOutcome("agent-1", "tenant-1", "maximize_roi", GoalOutcome.SUCCESS);
    }
    assertThat(evaluator.isAbandoned("agent-1", "tenant-1", "maximize_roi")).isFalse();
  }

  @Test
  void noSignalStore_neverAbandoned() {
    @SuppressWarnings("unchecked")
    Instance<GoalSignalStore> absent = mock(Instance.class);
    when(absent.isResolvable()).thenReturn(false);
    var noStoreEvaluator = new GoalAbandonmentEvaluator(absent, 5);
    assertThat(noStoreEvaluator.isAbandoned("agent-1", "tenant-1", "any")).isFalse();
  }

  @Test
  void activeGoals_filtersAbandoned() {
    for (int i = 0; i < 2; i++) {
      signalStore.recordOutcome("agent-1", "tenant-1", "goal-a", GoalOutcome.FAILURE);
    }
    for (int i = 0; i < 7; i++) {
      signalStore.recordOutcome("agent-1", "tenant-1", "goal-b", GoalOutcome.FAILURE);
    }

    AgentDescriptor descriptor =
        AgentDescriptor.builder()
            .agentId("agent-1")
            .name("Test Agent")
            .slot("default")
            .tenancyId("tenant-1")
            .goals(
                List.of(
                    new AgentGoal(
                        "goal-a",
                        "Active goal",
                        GoalPriority.PRIMARY,
                        Visibility.PUBLIC,
                        List.of()),
                    new AgentGoal(
                        "goal-b",
                        "Abandoned goal",
                        GoalPriority.SECONDARY,
                        Visibility.PUBLIC,
                        List.of())))
            .build();

    List<AgentGoal> active = evaluator.activeGoals(descriptor);
    assertThat(active).hasSize(1);
    assertThat(active.get(0).name()).isEqualTo("goal-a");
  }

  @Test
  void activeGoals_emptyGoals_returnsEmpty() {
    AgentDescriptor descriptor =
        AgentDescriptor.builder()
            .agentId("agent-1")
            .name("Test Agent")
            .slot("default")
            .tenancyId("tenant-1")
            .build();
    assertThat(evaluator.activeGoals(descriptor)).isEmpty();
  }

  @Test
  void noOutcomes_goalIsActive() {
    assertThat(evaluator.isAbandoned("agent-1", "tenant-1", "unknown-goal")).isFalse();
  }
}
