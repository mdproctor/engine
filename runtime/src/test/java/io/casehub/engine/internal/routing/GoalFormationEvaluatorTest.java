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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.spi.routing.GoalFormationProposal;
import io.casehub.api.spi.routing.GoalFormationStrategy;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.eidos.api.GoalPriority;
import io.casehub.eidos.api.Visibility;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.neocortex.memory.CaseMemoryStore;
import jakarta.enterprise.inject.Instance;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GoalFormationEvaluatorTest {

  private AgentRegistry agentRegistry;
  private CaseDefinitionRegistry caseDefinitionRegistry;
  private EngineStrategyResolver strategyResolver;
  private EventLogRepository eventLogRepository;
  private GoalFormationEvaluator evaluator;
  private GoalFormationStrategy strategy;

  private final List<AgentDescriptor> registeredDescriptors = new ArrayList<>();

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    agentRegistry = mock(AgentRegistry.class);
    caseDefinitionRegistry = mock(CaseDefinitionRegistry.class);
    strategyResolver = mock(EngineStrategyResolver.class);
    eventLogRepository = mock(EventLogRepository.class);
    strategy = mock(GoalFormationStrategy.class);

    registeredDescriptors.clear();

    Instance<AgentRegistry> registryInstance = mock(Instance.class);
    when(registryInstance.isResolvable()).thenReturn(true);
    when(registryInstance.get()).thenReturn(agentRegistry);

    Instance<CaseMemoryStore> memoryInstance = mock(Instance.class);
    when(memoryInstance.isResolvable()).thenReturn(false);

    try {
      when(strategyResolver.resolve(GoalFormationStrategy.class, "llm")).thenReturn(strategy);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    evaluator =
        new GoalFormationEvaluator(
            registryInstance,
            memoryInstance,
            caseDefinitionRegistry,
            strategyResolver,
            eventLogRepository,
            true,
            true,
            "llm",
            2,
            0,
            20);
  }

  @Test
  void skipsWhenNotEnabled() {
    @SuppressWarnings("unchecked")
    Instance<AgentRegistry> ri = mock(Instance.class);
    @SuppressWarnings("unchecked")
    Instance<CaseMemoryStore> mi = mock(Instance.class);
    var disabled =
        new GoalFormationEvaluator(
            ri,
            mi,
            caseDefinitionRegistry,
            strategyResolver,
            eventLogRepository,
            false,
            true,
            "llm",
            2,
            60,
            20);

    disabled.evaluate("worker-1", buildCaseInstance("tenant-1"), List.of("insight"));
    verify(ri, never()).isResolvable();
  }

  @SuppressWarnings("unchecked")
  @Test
  void skipsWhenAgentRegistryNotResolvable() {
    Instance<AgentRegistry> absent = mock(Instance.class);
    when(absent.isResolvable()).thenReturn(false);
    Instance<CaseMemoryStore> mi = mock(Instance.class);
    var eval =
        new GoalFormationEvaluator(
            absent,
            mi,
            caseDefinitionRegistry,
            strategyResolver,
            eventLogRepository,
            true,
            true,
            "llm",
            2,
            0,
            20);

    eval.evaluate("worker-1", buildCaseInstance("tenant-1"), List.of("insight"));
    verify(caseDefinitionRegistry, never()).getCaseDefinition(any());
  }

  @Test
  void skipsWhenNoDescriptor() {
    CaseInstance instance = buildCaseInstance("tenant-1");
    CaseDefinition definition = mock(CaseDefinition.class);
    when(caseDefinitionRegistry.getCaseDefinition(instance.getCaseMetaModel()))
        .thenReturn(definition);
    when(definition.agentDescriptorFor("worker-1")).thenReturn(Optional.empty());

    evaluator.evaluate("worker-1", instance, List.of("insight"));
    verify(agentRegistry, never()).findById(any(), any());
  }

  @Test
  void skipsWhenInsightsEmpty() {
    evaluator.evaluate("worker-1", buildCaseInstance("tenant-1"), List.of());
    verify(caseDefinitionRegistry, never()).getCaseDefinition(any());
  }

  @Test
  void skipsWhenCapacityFull() {
    CaseInstance instance = buildCaseInstance("tenant-1");
    List<AgentGoal> tenGoals = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      tenGoals.add(goal("goal-" + i));
    }
    setupDefinition(instance, "worker-1", tenGoals.toArray(new AgentGoal[0]));

    evaluator.evaluate("worker-1", instance, List.of("insight"));
    verify(agentRegistry, never()).findById(any(), any());
  }

  @Test
  void autoApproveRegistersNewGoals() throws Exception {
    CaseInstance instance = buildCaseInstance("tenant-1");
    setupDefinition(instance, "worker-1", goal("existing-goal"));

    var proposed =
        new GoalFormationProposal.ProposedGoal(
            "new-goal", "A new goal", GoalPriority.SECONDARY, "from insight");
    when(strategy.propose(any()))
        .thenReturn(new GoalFormationProposal(List.of(proposed), "rationale"));

    CountDownLatch latch = new CountDownLatch(1);
    doAnswer(
            inv -> {
              registeredDescriptors.add(inv.getArgument(0));
              latch.countDown();
              return null;
            })
        .when(agentRegistry)
        .register(any());

    evaluator.evaluate("worker-1", instance, List.of("insight"));
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

    AgentDescriptor registered = registeredDescriptors.get(0);
    assertThat(registered.goals()).hasSize(2);
    assertThat(registered.goals().stream().map(AgentGoal::name).toList())
        .containsExactlyInAnyOrder("existing-goal", "new-goal");
  }

  @Test
  void autoApproveDisabledWritesProposedButDoesNotRegister() throws Exception {
    @SuppressWarnings("unchecked")
    Instance<AgentRegistry> ri = mock(Instance.class);
    when(ri.isResolvable()).thenReturn(true);
    when(ri.get()).thenReturn(agentRegistry);
    @SuppressWarnings("unchecked")
    Instance<CaseMemoryStore> mi = mock(Instance.class);
    when(mi.isResolvable()).thenReturn(false);

    var noApprove =
        new GoalFormationEvaluator(
            ri,
            mi,
            caseDefinitionRegistry,
            strategyResolver,
            eventLogRepository,
            true,
            false,
            "llm",
            2,
            0,
            20);

    CaseInstance instance = buildCaseInstance("tenant-1");
    setupDefinition(instance, "worker-1", goal("existing-goal"));

    var proposed =
        new GoalFormationProposal.ProposedGoal(
            "new-goal", "A new goal", GoalPriority.SECONDARY, "from insight");
    when(strategy.propose(any()))
        .thenReturn(new GoalFormationProposal(List.of(proposed), "rationale"));

    CountDownLatch latch = new CountDownLatch(1);
    doAnswer(
            inv -> {
              latch.countDown();
              return null;
            })
        .when(eventLogRepository)
        .append(any(), any());

    noApprove.evaluate("worker-1", instance, List.of("insight"));
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

    verify(agentRegistry, never()).register(any());
  }

  @Test
  void defaultsPriorityToSecondaryWhenNull() throws Exception {
    CaseInstance instance = buildCaseInstance("tenant-1");
    setupDefinition(instance, "worker-1", goal("existing-goal"));

    var proposed =
        new GoalFormationProposal.ProposedGoal("new-goal", "A new goal", null, "from insight");
    when(strategy.propose(any()))
        .thenReturn(new GoalFormationProposal(List.of(proposed), "rationale"));

    CountDownLatch latch = new CountDownLatch(1);
    doAnswer(
            inv -> {
              registeredDescriptors.add(inv.getArgument(0));
              latch.countDown();
              return null;
            })
        .when(agentRegistry)
        .register(any());

    evaluator.evaluate("worker-1", instance, List.of("insight"));
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

    AgentGoal newGoal =
        registeredDescriptors.get(0).goals().stream()
            .filter(g -> g.name().equals("new-goal"))
            .findFirst()
            .orElseThrow();
    assertThat(newGoal.priority()).isEqualTo(GoalPriority.SECONDARY);
  }

  @Test
  void rejectsGoalWithNameTooLong() throws Exception {
    CaseInstance instance = buildCaseInstance("tenant-1");
    setupDefinition(instance, "worker-1", goal("existing-goal"));

    String longName = "x".repeat(101);
    var proposed =
        new GoalFormationProposal.ProposedGoal(longName, "desc", GoalPriority.SECONDARY, "reason");
    when(strategy.propose(any()))
        .thenReturn(new GoalFormationProposal(List.of(proposed), "rationale"));

    evaluator.evaluate("worker-1", instance, List.of("insight"));
    Thread.sleep(300);
    verify(agentRegistry, never()).register(any());
  }

  @Test
  void rejectsDuplicateGoalName() throws Exception {
    CaseInstance instance = buildCaseInstance("tenant-1");
    setupDefinition(instance, "worker-1", goal("existing-goal"));

    var proposed =
        new GoalFormationProposal.ProposedGoal(
            "existing-goal", "duplicate", GoalPriority.SECONDARY, "reason");
    when(strategy.propose(any()))
        .thenReturn(new GoalFormationProposal(List.of(proposed), "rationale"));

    evaluator.evaluate("worker-1", instance, List.of("insight"));
    Thread.sleep(300);
    verify(agentRegistry, never()).register(any());
  }

  @Test
  void capsAtMaxNewPerReflection() throws Exception {
    CaseInstance instance = buildCaseInstance("tenant-1");
    setupDefinition(instance, "worker-1", goal("existing-goal"));

    List<GoalFormationProposal.ProposedGoal> proposed = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      proposed.add(
          new GoalFormationProposal.ProposedGoal(
              "goal-" + i, "desc " + i, GoalPriority.SECONDARY, "reason"));
    }
    when(strategy.propose(any())).thenReturn(new GoalFormationProposal(proposed, "rationale"));

    CountDownLatch latch = new CountDownLatch(1);
    doAnswer(
            inv -> {
              registeredDescriptors.add(inv.getArgument(0));
              latch.countDown();
              return null;
            })
        .when(agentRegistry)
        .register(any());

    evaluator.evaluate("worker-1", instance, List.of("insight"));
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

    AgentDescriptor registered = registeredDescriptors.get(0);
    assertThat(registered.goals()).hasSize(3); // 1 existing + 2 new (max)
  }

  @Test
  void perGoalErrorIsolation() throws Exception {
    CaseInstance instance = buildCaseInstance("tenant-1");
    setupDefinition(instance, "worker-1", goal("existing-goal"));

    var invalid =
        new GoalFormationProposal.ProposedGoal(
            "x".repeat(101), "desc", GoalPriority.SECONDARY, "reason");
    var valid =
        new GoalFormationProposal.ProposedGoal(
            "valid-goal", "A valid goal", GoalPriority.SECONDARY, "reason");
    when(strategy.propose(any()))
        .thenReturn(new GoalFormationProposal(List.of(invalid, valid), "rationale"));

    CountDownLatch latch = new CountDownLatch(1);
    doAnswer(
            inv -> {
              registeredDescriptors.add(inv.getArgument(0));
              latch.countDown();
              return null;
            })
        .when(agentRegistry)
        .register(any());

    evaluator.evaluate("worker-1", instance, List.of("insight"));
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

    AgentDescriptor registered = registeredDescriptors.get(0);
    assertThat(registered.goals()).hasSize(2);
    assertThat(registered.goals().stream().map(AgentGoal::name).toList())
        .containsExactlyInAnyOrder("existing-goal", "valid-goal");
  }

  @Test
  void exceptionIsolation_neverBlocksCaseProgression() {
    CaseInstance instance = buildCaseInstance("tenant-1");
    when(caseDefinitionRegistry.getCaseDefinition(any())).thenThrow(new RuntimeException("boom"));

    evaluator.evaluate("worker-1", instance, List.of("insight"));
  }

  @Test
  void cooldownPreventsImmediateReformation() throws Exception {
    @SuppressWarnings("unchecked")
    Instance<AgentRegistry> ri = mock(Instance.class);
    when(ri.isResolvable()).thenReturn(true);
    when(ri.get()).thenReturn(agentRegistry);
    @SuppressWarnings("unchecked")
    Instance<CaseMemoryStore> mi = mock(Instance.class);
    when(mi.isResolvable()).thenReturn(false);

    var withCooldown =
        new GoalFormationEvaluator(
            ri,
            mi,
            caseDefinitionRegistry,
            strategyResolver,
            eventLogRepository,
            true,
            true,
            "llm",
            2,
            60,
            20);

    CaseInstance instance = buildCaseInstance("tenant-1");
    setupDefinition(instance, "worker-1", goal("existing-goal"));

    var proposed =
        new GoalFormationProposal.ProposedGoal(
            "new-goal", "desc", GoalPriority.SECONDARY, "reason");
    when(strategy.propose(any()))
        .thenReturn(new GoalFormationProposal(List.of(proposed), "rationale"));

    CountDownLatch latch = new CountDownLatch(1);
    doAnswer(
            inv -> {
              registeredDescriptors.add(inv.getArgument(0));
              latch.countDown();
              return null;
            })
        .when(agentRegistry)
        .register(any());

    withCooldown.evaluate("worker-1", instance, List.of("insight-1"));
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

    withCooldown.evaluate("worker-1", instance, List.of("insight-2"));
    Thread.sleep(300);

    assertThat(registeredDescriptors).hasSize(1);
  }

  private AgentGoal goal(String name) {
    return new AgentGoal(
        name, "desc-" + name, GoalPriority.SECONDARY, Visibility.PUBLIC, List.of());
  }

  private AgentDescriptor descriptorWithGoals(AgentGoal... goals) {
    return AgentDescriptor.builder()
        .agentId("agent-1")
        .name("Agent")
        .slot("default")
        .tenancyId("tenant-1")
        .goals(List.of(goals))
        .build();
  }

  private void setupDefinition(CaseInstance instance, String workerName, AgentGoal... goals) {
    CaseDefinition definition = mock(CaseDefinition.class);
    when(caseDefinitionRegistry.getCaseDefinition(instance.getCaseMetaModel()))
        .thenReturn(definition);
    when(definition.agentDescriptorFor(workerName))
        .thenReturn(Optional.of(descriptorWithGoals(goals)));
  }

  private CaseInstance buildCaseInstance(String tenancyId) {
    CaseInstance instance = new CaseInstance();
    instance.tenancyId = tenancyId;
    CaseMetaModel meta = new CaseMetaModel();
    instance.setCaseMetaModel(meta);
    return instance;
  }
}
