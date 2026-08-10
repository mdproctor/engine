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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.casehub.api.model.CaseDefinition;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.GoalOutcome;
import io.casehub.eidos.api.GoalPriority;
import io.casehub.eidos.api.GoalSignalStore;
import io.casehub.eidos.api.Visibility;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.worker.api.WorkerOutcome;
import jakarta.enterprise.inject.Instance;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GoalOutcomeRecorderTest {

  private GoalSignalStore signalStore;
  private CaseDefinitionRegistry registry;
  private GoalOutcomeRecorder recorder;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    signalStore = mock(GoalSignalStore.class);
    Instance<GoalSignalStore> storeInstance = mock(Instance.class);
    when(storeInstance.isResolvable()).thenReturn(true);
    when(storeInstance.get()).thenReturn(signalStore);
    registry = mock(CaseDefinitionRegistry.class);
    recorder = new GoalOutcomeRecorder(storeInstance, registry);
  }

  @Test
  void decline_recordsFailureForEachGoal() {
    CaseInstance instance = buildCaseInstance("tenant-1");
    CaseDefinition definition = mock(CaseDefinition.class);
    when(registry.getCaseDefinition(instance.getCaseMetaModel())).thenReturn(definition);
    when(definition.agentDescriptorFor("worker-1"))
        .thenReturn(
            Optional.of(descriptorWithGoals(goal("goal-a", "cap-x"), goal("goal-b", "cap-x"))));

    recorder.record(instance, "worker-1", "cap-x", new WorkerOutcome.Declined<>("not possible"));

    verify(signalStore).recordOutcome("agent-1", "tenant-1", "goal-a", GoalOutcome.FAILURE);
    verify(signalStore).recordOutcome("agent-1", "tenant-1", "goal-b", GoalOutcome.FAILURE);
  }

  @Test
  void success_recordsSuccessForEachGoal() {
    CaseInstance instance = buildCaseInstance("tenant-1");
    CaseDefinition definition = mock(CaseDefinition.class);
    when(registry.getCaseDefinition(instance.getCaseMetaModel())).thenReturn(definition);
    when(definition.agentDescriptorFor("worker-1"))
        .thenReturn(Optional.of(descriptorWithGoals(goal("goal-a", "cap-x"))));

    recorder.record(instance, "worker-1", "cap-x", WorkerOutcome.success());

    verify(signalStore).recordOutcome("agent-1", "tenant-1", "goal-a", GoalOutcome.SUCCESS);
  }

  @Test
  void completed_recordsSuccessForEachGoal() {
    CaseInstance instance = buildCaseInstance("tenant-1");
    CaseDefinition definition = mock(CaseDefinition.class);
    when(registry.getCaseDefinition(instance.getCaseMetaModel())).thenReturn(definition);
    when(definition.agentDescriptorFor("worker-1"))
        .thenReturn(Optional.of(descriptorWithGoals(goal("goal-a", "cap-x"))));

    recorder.record(instance, "worker-1", "cap-x", WorkerOutcome.completed());

    verify(signalStore).recordOutcome("agent-1", "tenant-1", "goal-a", GoalOutcome.SUCCESS);
  }

  @Test
  void failed_recordsFailure() {
    CaseInstance instance = buildCaseInstance("tenant-1");
    CaseDefinition definition = mock(CaseDefinition.class);
    when(registry.getCaseDefinition(instance.getCaseMetaModel())).thenReturn(definition);
    when(definition.agentDescriptorFor("worker-1"))
        .thenReturn(Optional.of(descriptorWithGoals(goal("goal-a", "cap-x"))));

    recorder.record(instance, "worker-1", "cap-x", new WorkerOutcome.Failed<>("err"));

    verify(signalStore).recordOutcome("agent-1", "tenant-1", "goal-a", GoalOutcome.FAILURE);
  }

  @Test
  void expired_recordsFailure() {
    CaseInstance instance = buildCaseInstance("tenant-1");
    CaseDefinition definition = mock(CaseDefinition.class);
    when(registry.getCaseDefinition(instance.getCaseMetaModel())).thenReturn(definition);
    when(definition.agentDescriptorFor("worker-1"))
        .thenReturn(Optional.of(descriptorWithGoals(goal("goal-a", "cap-x"))));

    recorder.record(instance, "worker-1", "cap-x", new WorkerOutcome.Expired<>("timeout"));

    verify(signalStore).recordOutcome("agent-1", "tenant-1", "goal-a", GoalOutcome.FAILURE);
  }

  @Test
  void nonMatchingCapability_doesNotRecord() {
    CaseInstance instance = buildCaseInstance("tenant-1");
    CaseDefinition definition = mock(CaseDefinition.class);
    when(registry.getCaseDefinition(instance.getCaseMetaModel())).thenReturn(definition);
    when(definition.agentDescriptorFor("worker-1"))
        .thenReturn(Optional.of(descriptorWithGoals(goal("goal-a", "cap-x"))));

    recorder.record(instance, "worker-1", "cap-y", new WorkerOutcome.Declined<>("fail"));

    verifyNoInteractions(signalStore);
  }

  @Test
  void emptyCapabilities_alwaysRecorded() {
    CaseInstance instance = buildCaseInstance("tenant-1");
    CaseDefinition definition = mock(CaseDefinition.class);
    when(registry.getCaseDefinition(instance.getCaseMetaModel())).thenReturn(definition);
    when(definition.agentDescriptorFor("worker-1"))
        .thenReturn(
            Optional.of(
                descriptorWithGoals(
                    new AgentGoal(
                        "goal-a", "First", GoalPriority.PRIMARY, Visibility.PUBLIC, List.of()))));

    recorder.record(instance, "worker-1", "cap-y", new WorkerOutcome.Declined<>("fail"));

    verify(signalStore).recordOutcome("agent-1", "tenant-1", "goal-a", GoalOutcome.FAILURE);
  }

  @Test
  void nullCapabilityName_skipsRecording() {
    CaseInstance instance = buildCaseInstance("tenant-1");

    recorder.record(instance, "worker-1", null, new WorkerOutcome.Declined<>("fail"));

    verifyNoInteractions(signalStore);
    verifyNoInteractions(registry);
  }

  @Test
  void mixedCapabilities_onlyMatchingGoalsRecorded() {
    CaseInstance instance = buildCaseInstance("tenant-1");
    CaseDefinition definition = mock(CaseDefinition.class);
    when(registry.getCaseDefinition(instance.getCaseMetaModel())).thenReturn(definition);
    when(definition.agentDescriptorFor("worker-1"))
        .thenReturn(
            Optional.of(
                descriptorWithGoals(
                    goal("goal-a", "cap-x"),
                    goal("goal-b", "cap-y"),
                    new AgentGoal(
                        "goal-c", "Third", GoalPriority.PRIMARY, Visibility.PUBLIC, List.of()))));

    recorder.record(instance, "worker-1", "cap-x", WorkerOutcome.success());

    verify(signalStore).recordOutcome("agent-1", "tenant-1", "goal-a", GoalOutcome.SUCCESS);
    verify(signalStore).recordOutcome("agent-1", "tenant-1", "goal-c", GoalOutcome.SUCCESS);
    verifyNoMoreInteractions(signalStore);
  }

  @Test
  void noDescriptor_doesNotRecord() {
    CaseInstance instance = buildCaseInstance("tenant-1");
    CaseDefinition definition = mock(CaseDefinition.class);
    when(registry.getCaseDefinition(instance.getCaseMetaModel())).thenReturn(definition);
    when(definition.agentDescriptorFor("worker-1")).thenReturn(Optional.empty());

    recorder.record(instance, "worker-1", "cap-x", new WorkerOutcome.Declined<>("fail"));

    verifyNoInteractions(signalStore);
  }

  @Test
  void noGoals_doesNotRecord() {
    CaseInstance instance = buildCaseInstance("tenant-1");
    CaseDefinition definition = mock(CaseDefinition.class);
    when(registry.getCaseDefinition(instance.getCaseMetaModel())).thenReturn(definition);
    when(definition.agentDescriptorFor("worker-1"))
        .thenReturn(
            Optional.of(
                AgentDescriptor.builder()
                    .agentId("agent-1")
                    .name("Agent")
                    .slot("default")
                    .tenancyId("tenant-1")
                    .build()));

    recorder.record(instance, "worker-1", "cap-x", new WorkerOutcome.Declined<>("fail"));

    verifyNoInteractions(signalStore);
  }

  @Test
  void noSignalStore_doesNothing() {
    @SuppressWarnings("unchecked")
    Instance<GoalSignalStore> absent = mock(Instance.class);
    when(absent.isResolvable()).thenReturn(false);
    var noStoreRecorder = new GoalOutcomeRecorder(absent, registry);

    CaseInstance instance = buildCaseInstance("tenant-1");
    noStoreRecorder.record(instance, "worker-1", "cap-x", new WorkerOutcome.Declined<>("fail"));

    verifyNoInteractions(registry);
  }

  private AgentGoal goal(String name, String capability) {
    return new AgentGoal(
        name, "desc-" + name, GoalPriority.PRIMARY, Visibility.PUBLIC, List.of(capability));
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

  private CaseInstance buildCaseInstance(String tenancyId) {
    CaseInstance instance = new CaseInstance();
    instance.tenancyId = tenancyId;
    CaseMetaModel meta = new CaseMetaModel();
    instance.setCaseMetaModel(meta);
    return instance;
  }
}
