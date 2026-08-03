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
import static org.mockito.Mockito.when;

import io.casehub.api.model.CaseDefinition;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.BehavioralSignal;
import io.casehub.eidos.api.BehavioralSignalStore;
import io.casehub.eidos.api.GoalPriority;
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

class GoalFailureRecorderTest {

  private BehavioralSignalStore signalStore;
  private CaseDefinitionRegistry registry;
  private GoalFailureRecorder recorder;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    signalStore = mock(BehavioralSignalStore.class);
    Instance<BehavioralSignalStore> storeInstance = mock(Instance.class);
    when(storeInstance.isResolvable()).thenReturn(true);
    when(storeInstance.get()).thenReturn(signalStore);
    registry = mock(CaseDefinitionRegistry.class);
    recorder = new GoalFailureRecorder(storeInstance, registry);
  }

  @Test
  void decline_recordsDeclineForEachGoal() {
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
                    .goals(
                        List.of(
                            new AgentGoal(
                                "goal-a", "First", GoalPriority.PRIMARY, Visibility.PUBLIC),
                            new AgentGoal(
                                "goal-b", "Second", GoalPriority.SECONDARY, Visibility.PUBLIC)))
                    .build()));

    recorder.record(instance, "worker-1", new WorkerOutcome.Declined<>("not possible"));

    verify(signalStore)
        .record(
            "agent-1",
            "tenant-1",
            GoalAbandonmentEvaluator.GOAL_CAPABILITY_SENTINEL,
            "goal-a",
            BehavioralSignal.DECLINE);
    verify(signalStore)
        .record(
            "agent-1",
            "tenant-1",
            GoalAbandonmentEvaluator.GOAL_CAPABILITY_SENTINEL,
            "goal-b",
            BehavioralSignal.DECLINE);
  }

  @Test
  void success_doesNotRecord() {
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
                    .goals(
                        List.of(
                            new AgentGoal(
                                "goal-a", "First", GoalPriority.PRIMARY, Visibility.PUBLIC)))
                    .build()));

    recorder.record(instance, "worker-1", WorkerOutcome.success());

    verifyNoInteractions(signalStore);
  }

  @Test
  void noDescriptor_doesNotRecord() {
    CaseInstance instance = buildCaseInstance("tenant-1");
    CaseDefinition definition = mock(CaseDefinition.class);
    when(registry.getCaseDefinition(instance.getCaseMetaModel())).thenReturn(definition);
    when(definition.agentDescriptorFor("worker-1")).thenReturn(Optional.empty());

    recorder.record(instance, "worker-1", new WorkerOutcome.Declined<>("fail"));

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

    recorder.record(instance, "worker-1", new WorkerOutcome.Declined<>("fail"));

    verifyNoInteractions(signalStore);
  }

  @Test
  void noSignalStore_doesNothing() {
    @SuppressWarnings("unchecked")
    Instance<BehavioralSignalStore> absent = mock(Instance.class);
    when(absent.isResolvable()).thenReturn(false);
    var noStoreRecorder = new GoalFailureRecorder(absent, registry);

    CaseInstance instance = buildCaseInstance("tenant-1");
    noStoreRecorder.record(instance, "worker-1", new WorkerOutcome.Declined<>("fail"));

    verifyNoInteractions(registry);
  }

  private CaseInstance buildCaseInstance(String tenancyId) {
    CaseInstance instance = new CaseInstance();
    instance.tenancyId = tenancyId;
    CaseMetaModel meta = new CaseMetaModel();
    instance.setCaseMetaModel(meta);
    return instance;
  }
}
