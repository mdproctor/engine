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
import io.casehub.api.spi.routing.GoalRemovalResult;
import io.casehub.api.spi.routing.GoalRemovalService;
import io.casehub.api.spi.routing.GoalRevisionAction;
import io.casehub.api.spi.routing.GoalRevisionProposal;
import io.casehub.api.spi.routing.GoalRevisionStrategy;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.eidos.api.DefaultGoalEvolution;
import io.casehub.eidos.api.GoalEvolution;
import io.casehub.eidos.api.GoalOutcome;
import io.casehub.eidos.api.GoalPriority;
import io.casehub.eidos.api.GoalSignalStore;
import io.casehub.eidos.api.InMemoryGoalSignalStore;
import io.casehub.eidos.api.Visibility;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.worker.api.WorkerOutcome;
import jakarta.enterprise.inject.Instance;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GoalRevisionEvaluatorTest {

  private InMemoryGoalSignalStore goalSignalStore;
  private GoalEvolution goalEvolution;
  private AgentRegistry agentRegistry;
  private GoalRemovalService goalRemovalService;
  private CaseDefinitionRegistry caseDefinitionRegistry;
  private EngineStrategyResolver strategyResolver;
  private EventLogRepository eventLogRepository;
  private GoalRevisionEvaluator evaluator;

  private final List<AgentDescriptor> registeredDescriptors = new ArrayList<>();
  private final List<EventLog> writtenLogs = new ArrayList<>();

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    goalSignalStore = new InMemoryGoalSignalStore();
    goalEvolution = new DefaultGoalEvolution();
    agentRegistry = mock(AgentRegistry.class);
    goalRemovalService = mock(GoalRemovalService.class);
    when(goalRemovalService.removeGoals(any(), any(), any(), any()))
        .thenReturn(new GoalRemovalResult(List.of(), 0));
    caseDefinitionRegistry = mock(CaseDefinitionRegistry.class);
    strategyResolver = mock(EngineStrategyResolver.class);
    eventLogRepository = mock(EventLogRepository.class);

    registeredDescriptors.clear();
    writtenLogs.clear();

    Instance<GoalSignalStore> signalStoreInstance = mock(Instance.class);
    when(signalStoreInstance.isResolvable()).thenReturn(true);
    when(signalStoreInstance.get()).thenReturn(goalSignalStore);

    Instance<GoalEvolution> evolutionInstance = mock(Instance.class);
    when(evolutionInstance.isResolvable()).thenReturn(true);
    when(evolutionInstance.get()).thenReturn(goalEvolution);

    Instance<AgentRegistry> registryInstance = mock(Instance.class);
    when(registryInstance.isResolvable()).thenReturn(true);
    when(registryInstance.get()).thenReturn(agentRegistry);

    evaluator =
        new GoalRevisionEvaluator(
            signalStoreInstance,
            evolutionInstance,
            registryInstance,
            goalRemovalService,
            caseDefinitionRegistry,
            strategyResolver,
            eventLogRepository,
            true,
            "llm",
            3,
            5.0);
  }

  @Test
  void skipsWhenNotEnabled() {
    @SuppressWarnings("unchecked")
    Instance<GoalSignalStore> si = mock(Instance.class);
    @SuppressWarnings("unchecked")
    Instance<GoalEvolution> ei = mock(Instance.class);
    @SuppressWarnings("unchecked")
    Instance<AgentRegistry> ri = mock(Instance.class);
    var disabled =
        new GoalRevisionEvaluator(
            si,
            ei,
            ri,
            goalRemovalService,
            caseDefinitionRegistry,
            strategyResolver,
            eventLogRepository,
            false,
            "llm",
            3,
            5.0);

    disabled.record(buildCaseInstance("tenant-1"), "worker-1", "cap-x", WorkerOutcome.success());

    verify(si, never()).isResolvable();
  }

  @Test
  void skipsWhenGoalSignalStoreNotResolvable() {
    @SuppressWarnings("unchecked")
    Instance<GoalSignalStore> absent = mock(Instance.class);
    when(absent.isResolvable()).thenReturn(false);
    @SuppressWarnings("unchecked")
    Instance<GoalEvolution> ei = mock(Instance.class);
    @SuppressWarnings("unchecked")
    Instance<AgentRegistry> ri = mock(Instance.class);
    var eval =
        new GoalRevisionEvaluator(
            absent,
            ei,
            ri,
            goalRemovalService,
            caseDefinitionRegistry,
            strategyResolver,
            eventLogRepository,
            true,
            "llm",
            3,
            5.0);

    eval.record(buildCaseInstance("tenant-1"), "worker-1", "cap-x", WorkerOutcome.success());

    verify(ei, never()).isResolvable();
  }

  @Test
  void skipsWhenNoDescriptor() {
    CaseInstance instance = buildCaseInstance("tenant-1");
    CaseDefinition definition = mock(CaseDefinition.class);
    when(caseDefinitionRegistry.getCaseDefinition(instance.getCaseMetaModel()))
        .thenReturn(definition);
    when(definition.agentDescriptorFor("worker-1")).thenReturn(Optional.empty());

    evaluator.record(instance, "worker-1", "cap-x", WorkerOutcome.success());

    verify(agentRegistry, never()).findById(any(), any());
  }

  @Test
  void skipsWhenNoGoals() {
    CaseInstance instance = buildCaseInstance("tenant-1");
    CaseDefinition definition = mock(CaseDefinition.class);
    when(caseDefinitionRegistry.getCaseDefinition(instance.getCaseMetaModel()))
        .thenReturn(definition);
    when(definition.agentDescriptorFor("worker-1")).thenReturn(Optional.of(descriptorWithGoals()));

    evaluator.record(instance, "worker-1", "cap-x", WorkerOutcome.success());

    verify(agentRegistry, never()).findById(any(), any());
  }

  @Test
  void doesNotTriggerBelowThreshold() {
    CaseInstance instance = buildCaseInstance("tenant-1");
    setupDefinition(instance, "worker-1", goal("g1", GoalPriority.SECONDARY));

    evaluator.record(instance, "worker-1", "cap-x", WorkerOutcome.success());
    evaluator.record(instance, "worker-1", "cap-x", WorkerOutcome.success());

    verify(agentRegistry, never()).findById(any(), any());
  }

  @Test
  void triggersAtMinOutcomes() throws Exception {
    CaseInstance instance = buildCaseInstance("tenant-1");
    setupDefinition(instance, "worker-1", goal("g1", GoalPriority.SECONDARY));

    AgentDescriptor current = descriptorWithGoals(goal("g1", GoalPriority.SECONDARY));
    when(agentRegistry.findById("agent-1", "tenant-1")).thenReturn(Optional.of(current));

    for (int i = 0; i < 5; i++) {
      goalSignalStore.recordOutcome("agent-1", "tenant-1", "g1", GoalOutcome.SUCCESS);
    }

    CountDownLatch latch = new CountDownLatch(1);
    doAnswer(
            inv -> {
              registeredDescriptors.add(inv.getArgument(0));
              latch.countDown();
              return null;
            })
        .when(agentRegistry)
        .register(any());

    for (int i = 0; i < 3; i++) {
      evaluator.record(instance, "worker-1", "cap-x", WorkerOutcome.success());
    }

    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(registeredDescriptors).hasSize(1);
  }

  @Test
  void promotesSecondaryGoalOnHighSuccessRate() throws Exception {
    CaseInstance instance = buildCaseInstance("tenant-1");
    setupDefinition(instance, "worker-1", goal("g1", GoalPriority.SECONDARY));

    for (int i = 0; i < 9; i++) {
      goalSignalStore.recordOutcome("agent-1", "tenant-1", "g1", GoalOutcome.SUCCESS);
    }
    goalSignalStore.recordOutcome("agent-1", "tenant-1", "g1", GoalOutcome.FAILURE);

    AgentDescriptor current = descriptorWithGoals(goal("g1", GoalPriority.SECONDARY));
    when(agentRegistry.findById("agent-1", "tenant-1")).thenReturn(Optional.of(current));

    CountDownLatch latch = new CountDownLatch(1);
    doAnswer(
            inv -> {
              registeredDescriptors.add(inv.getArgument(0));
              latch.countDown();
              return null;
            })
        .when(agentRegistry)
        .register(any());

    for (int i = 0; i < 3; i++) {
      evaluator.record(instance, "worker-1", "cap-x", WorkerOutcome.success());
    }

    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    AgentDescriptor registered = registeredDescriptors.get(0);
    assertThat(
            registered.goals().stream()
                .filter(g -> g.name().equals("g1"))
                .findFirst()
                .orElseThrow()
                .priority())
        .isEqualTo(GoalPriority.PRIMARY);
  }

  @Test
  void noChangeWhenUnchangedResult() throws Exception {
    CaseInstance instance = buildCaseInstance("tenant-1");
    setupDefinition(instance, "worker-1", goal("g1", GoalPriority.SECONDARY));

    goalSignalStore.recordOutcome("agent-1", "tenant-1", "g1", GoalOutcome.SUCCESS);
    goalSignalStore.recordOutcome("agent-1", "tenant-1", "g1", GoalOutcome.FAILURE);

    AgentDescriptor current = descriptorWithGoals(goal("g1", GoalPriority.SECONDARY));
    when(agentRegistry.findById("agent-1", "tenant-1")).thenReturn(Optional.of(current));

    for (int i = 0; i < 3; i++) {
      evaluator.record(instance, "worker-1", "cap-x", WorkerOutcome.success());
    }

    Thread.sleep(500);
    verify(agentRegistry, never()).register(any());
  }

  @Test
  void dampenedResultDecaysSignals() throws Exception {
    CaseInstance instance = buildCaseInstance("tenant-1");
    setupDefinition(instance, "worker-1", goal("g1", GoalPriority.SECONDARY));

    for (int i = 0; i < 3; i++) {
      goalSignalStore.recordOutcome("agent-1", "tenant-1", "g1", GoalOutcome.SUCCESS);
    }

    AgentDescriptor current = descriptorWithGoals(goal("g1", GoalPriority.SECONDARY));
    when(agentRegistry.findById("agent-1", "tenant-1")).thenReturn(Optional.of(current));

    for (int i = 0; i < 3; i++) {
      evaluator.record(instance, "worker-1", "cap-x", WorkerOutcome.success());
    }

    Thread.sleep(500);
    verify(agentRegistry, never()).register(any());
    assertThat(goalSignalStore.outcomeCounts("agent-1", "tenant-1").get("g1").successCount())
        .isLessThan(3);
  }

  @Test
  void exceptionIsolation_neverBlocksCaseProgression() {
    CaseInstance instance = buildCaseInstance("tenant-1");
    when(caseDefinitionRegistry.getCaseDefinition(any())).thenThrow(new RuntimeException("boom"));

    evaluator.record(instance, "worker-1", "cap-x", WorkerOutcome.success());
  }

  private AgentGoal goal(String name, GoalPriority priority) {
    return new AgentGoal(name, "desc-" + name, priority, Visibility.PUBLIC, List.of(), null);
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

  private void setupStrategyReturning(GoalRevisionProposal proposal) {
    GoalRevisionStrategy strategy = mock(GoalRevisionStrategy.class);
    when(strategy.revise(any())).thenReturn(proposal);
    when(strategyResolver.resolve(GoalRevisionStrategy.class, "llm")).thenReturn(strategy);
  }

  @Test
  void abandonActionRemovesGoalFromDescriptor() throws Exception {
    CaseInstance instance = buildCaseInstance("tenant-1");
    AgentGoal g1 = goal("g1", GoalPriority.SECONDARY);
    AgentGoal g2 = goal("g2", GoalPriority.PRIMARY);
    setupDefinition(instance, "worker-1", g1, g2);

    for (int i = 0; i < 9; i++) {
      goalSignalStore.recordOutcome("agent-1", "tenant-1", "g1", GoalOutcome.SUCCESS);
    }
    goalSignalStore.recordOutcome("agent-1", "tenant-1", "g1", GoalOutcome.FAILURE);
    goalSignalStore.recordOutcome("agent-1", "tenant-1", "g2", GoalOutcome.SUCCESS);

    AgentDescriptor current = descriptorWithGoals(g1, g2);
    when(agentRegistry.findById("agent-1", "tenant-1")).thenReturn(Optional.of(current));

    GoalRevisionProposal proposal =
        new GoalRevisionProposal(
            List.of(
                new GoalRevisionProposal.RevisedGoal(
                    "g2", GoalRevisionAction.ABANDON, null, "no longer relevant")),
            "dropping g2");
    setupStrategyReturning(proposal);

    CountDownLatch latch = new CountDownLatch(1);
    doAnswer(
            inv -> {
              registeredDescriptors.add(inv.getArgument(0));
              latch.countDown();
              return null;
            })
        .when(agentRegistry)
        .register(any());

    for (int i = 0; i < 3; i++) {
      evaluator.record(instance, "worker-1", "cap-x", WorkerOutcome.success());
    }

    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    Thread.sleep(200);
    verify(goalRemovalService).removeGoals("agent-1", "tenant-1", List.of("g2"), "goal revised");
  }

  @Test
  void completeActionRemovesGoalFromDescriptor() throws Exception {
    CaseInstance instance = buildCaseInstance("tenant-1");
    AgentGoal g1 = goal("g1", GoalPriority.SECONDARY);
    AgentGoal g2 = goal("g2", GoalPriority.PRIMARY);
    setupDefinition(instance, "worker-1", g1, g2);

    for (int i = 0; i < 9; i++) {
      goalSignalStore.recordOutcome("agent-1", "tenant-1", "g1", GoalOutcome.SUCCESS);
    }
    goalSignalStore.recordOutcome("agent-1", "tenant-1", "g1", GoalOutcome.FAILURE);
    goalSignalStore.recordOutcome("agent-1", "tenant-1", "g2", GoalOutcome.SUCCESS);

    AgentDescriptor current = descriptorWithGoals(g1, g2);
    when(agentRegistry.findById("agent-1", "tenant-1")).thenReturn(Optional.of(current));

    GoalRevisionProposal proposal =
        new GoalRevisionProposal(
            List.of(
                new GoalRevisionProposal.RevisedGoal(
                    "g2", GoalRevisionAction.COMPLETE, null, "goal achieved")),
            "completing g2");
    setupStrategyReturning(proposal);

    CountDownLatch latch = new CountDownLatch(1);
    doAnswer(
            inv -> {
              registeredDescriptors.add(inv.getArgument(0));
              latch.countDown();
              return null;
            })
        .when(agentRegistry)
        .register(any());

    for (int i = 0; i < 3; i++) {
      evaluator.record(instance, "worker-1", "cap-x", WorkerOutcome.success());
    }

    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    Thread.sleep(200);
    verify(goalRemovalService).removeGoals("agent-1", "tenant-1", List.of("g2"), "goal revised");
  }

  @Test
  void mixedActionsAppliedCorrectly() throws Exception {
    CaseInstance instance = buildCaseInstance("tenant-1");
    AgentGoal g1 = goal("g1", GoalPriority.SECONDARY);
    AgentGoal g2 = goal("g2", GoalPriority.PRIMARY);
    AgentGoal g3 = goal("g3", GoalPriority.SECONDARY);
    setupDefinition(instance, "worker-1", g1, g2, g3);

    for (int i = 0; i < 9; i++) {
      goalSignalStore.recordOutcome("agent-1", "tenant-1", "g1", GoalOutcome.SUCCESS);
    }
    goalSignalStore.recordOutcome("agent-1", "tenant-1", "g1", GoalOutcome.FAILURE);

    AgentDescriptor current = descriptorWithGoals(g1, g2, g3);
    when(agentRegistry.findById("agent-1", "tenant-1")).thenReturn(Optional.of(current));

    GoalRevisionProposal proposal =
        new GoalRevisionProposal(
            List.of(
                new GoalRevisionProposal.RevisedGoal(
                    "g1", GoalRevisionAction.REVISE, "updated desc", "refined"),
                new GoalRevisionProposal.RevisedGoal(
                    "g2", GoalRevisionAction.ABANDON, null, "unachievable"),
                new GoalRevisionProposal.RevisedGoal(
                    "g3", GoalRevisionAction.COMPLETE, null, "achieved")),
            "mixed actions");
    setupStrategyReturning(proposal);

    CountDownLatch latch = new CountDownLatch(1);
    doAnswer(
            inv -> {
              registeredDescriptors.add(inv.getArgument(0));
              latch.countDown();
              return null;
            })
        .when(agentRegistry)
        .register(any());

    for (int i = 0; i < 3; i++) {
      evaluator.record(instance, "worker-1", "cap-x", WorkerOutcome.success());
    }

    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    Thread.sleep(200);
    AgentDescriptor registered = registeredDescriptors.get(0);
    assertThat(
            registered.goals().stream()
                .filter(g -> g.name().equals("g1"))
                .findFirst()
                .orElseThrow()
                .description())
        .isEqualTo("updated desc");
    org.mockito.ArgumentCaptor<List<String>> removalCaptor =
        org.mockito.ArgumentCaptor.forClass(List.class);
    verify(goalRemovalService)
        .removeGoals(
            org.mockito.ArgumentMatchers.eq("agent-1"),
            org.mockito.ArgumentMatchers.eq("tenant-1"),
            removalCaptor.capture(),
            org.mockito.ArgumentMatchers.eq("goal revised"));
    assertThat(removalCaptor.getValue()).containsExactlyInAnyOrder("g2", "g3");
  }

  @Test
  void auditLogContainsAbandonedAndCompletedGoals() throws Exception {
    CaseInstance instance = buildCaseInstance("tenant-1");
    AgentGoal g1 = goal("g1", GoalPriority.SECONDARY);
    AgentGoal g2 = goal("g2", GoalPriority.PRIMARY);
    setupDefinition(instance, "worker-1", g1, g2);

    for (int i = 0; i < 9; i++) {
      goalSignalStore.recordOutcome("agent-1", "tenant-1", "g1", GoalOutcome.SUCCESS);
    }
    goalSignalStore.recordOutcome("agent-1", "tenant-1", "g1", GoalOutcome.FAILURE);

    AgentDescriptor current = descriptorWithGoals(g1, g2);
    when(agentRegistry.findById("agent-1", "tenant-1")).thenReturn(Optional.of(current));

    GoalRevisionProposal proposal =
        new GoalRevisionProposal(
            List.of(
                new GoalRevisionProposal.RevisedGoal(
                    "g1", GoalRevisionAction.REVISE, "updated", "refined"),
                new GoalRevisionProposal.RevisedGoal(
                    "g2", GoalRevisionAction.ABANDON, null, "unachievable")),
            "test");
    setupStrategyReturning(proposal);

    CountDownLatch latch = new CountDownLatch(1);
    doAnswer(
            inv -> {
              registeredDescriptors.add(inv.getArgument(0));
              latch.countDown();
              return null;
            })
        .when(agentRegistry)
        .register(any());

    org.mockito.ArgumentCaptor<EventLog> logCaptor =
        org.mockito.ArgumentCaptor.forClass(EventLog.class);

    for (int i = 0; i < 3; i++) {
      evaluator.record(instance, "worker-1", "cap-x", WorkerOutcome.success());
    }

    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    Thread.sleep(200);
    verify(eventLogRepository).append(logCaptor.capture(), any());

    com.fasterxml.jackson.databind.JsonNode payload = logCaptor.getValue().getPayload();
    assertThat(payload.get("abandonedGoals").toString()).contains("g2");
    assertThat(payload.get("completedGoals").size()).isZero();
    assertThat(payload.get("descriptionRevisions").size()).isEqualTo(1);
    assertThat(payload.get("totalGoalsAffected").asInt()).isGreaterThan(0);
  }
}
