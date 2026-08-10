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
package io.casehub.engine.internal.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ReflectionTriggerConfig;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.internal.routing.GoalFormationEvaluator;
import io.casehub.neocortex.memory.experience.ExperienceEvent;
import io.casehub.neocortex.memory.experience.ExperienceRecorder;
import io.casehub.neocortex.memory.experience.Outcome;
import io.casehub.neocortex.memory.reflection.ReflectionOrchestrator;
import io.casehub.worker.api.WorkerOutcome;
import jakarta.enterprise.inject.Instance;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentExperienceRecorderTest {

  private final List<ExperienceEvent> recorded = new ArrayList<>();
  private final AtomicInteger reflectionCount = new AtomicInteger();

  private ExperienceRecorder expRecorder;
  private ReflectionOrchestrator orchestrator;
  private CaseDefinitionRegistry registry;
  private AgentExperienceRecorder recorder;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    recorded.clear();
    reflectionCount.set(0);

    expRecorder =
        new ExperienceRecorder() {
          @Override
          public String record(ExperienceEvent event) {
            recorded.add(event);
            return "mem-1";
          }

          @Override
          public io.casehub.neocortex.memory.experience.ExperienceStoreResult recordAll(
              java.util.List<ExperienceEvent> events) {
            events.forEach(e -> recorded.add(e));
            return null;
          }
        };
    orchestrator =
        (agentId, tenantId, since, max) -> {
          reflectionCount.incrementAndGet();
          return List.of("insight-1");
        };

    var definition =
        CaseDefinition.builder()
            .namespace("ns")
            .name("test")
            .version("1.0")
            .reflectionTrigger(
                new ReflectionTriggerConfig(
                    true, 3.0, 10, 50, ReflectionTriggerConfig.DEFAULT_IMPORTANCE_WEIGHTS))
            .build();

    registry = mock(CaseDefinitionRegistry.class);
    when(registry.getCaseDefinition(org.mockito.ArgumentMatchers.any(CaseMetaModel.class)))
        .thenReturn(definition);

    Instance<ExperienceRecorder> expInstance = mock(Instance.class);
    when(expInstance.isResolvable()).thenReturn(true);
    when(expInstance.get()).thenReturn(expRecorder);

    Instance<ReflectionOrchestrator> reflInstance = mock(Instance.class);
    when(reflInstance.isResolvable()).thenReturn(true);
    when(reflInstance.get()).thenReturn(orchestrator);

    GoalFormationEvaluator goalFormationEvaluator = mock(GoalFormationEvaluator.class);
    recorder =
        new AgentExperienceRecorder(expInstance, reflInstance, registry, goalFormationEvaluator);
  }

  @Test
  void recordsOutcomeAsExperienceEvent() {
    recorder.record(
        createInstance(), "agent-1", "analysis", new WorkerOutcome.Success<>(null), "b1");
    assertThat(recorded).hasSize(1);
    var event = (Outcome) recorded.get(0);
    assertThat(event.agentId()).isEqualTo("agent-1");
    assertThat(event.capability()).isEqualTo("analysis");
    assertThat(event.result()).isEqualTo("SUCCESS");
  }

  @Test
  void successOutcomeHasCorrectImportance() {
    recorder.record(createInstance(), "agent-1", "cap", new WorkerOutcome.Success<>(null), "b");
    assertThat(((Outcome) recorded.get(0)).importance()).isEqualTo(0.3);
  }

  @Test
  void failedOutcomeHasCorrectImportance() {
    recorder.record(createInstance(), "agent-1", "cap", new WorkerOutcome.Failed<>("error"), "b");
    assertThat(((Outcome) recorded.get(0)).importance()).isEqualTo(0.8);
  }

  @Test
  void declinedOutcomeHasCorrectImportance() {
    recorder.record(
        createInstance(), "agent-1", "cap", new WorkerOutcome.Declined<>("reason"), "b");
    assertThat(((Outcome) recorded.get(0)).importance()).isEqualTo(0.6);
  }

  @Test
  void expiredOutcomeHasCorrectImportance() {
    recorder.record(
        createInstance(), "agent-1", "cap", new WorkerOutcome.Expired<>("timeout"), "b");
    assertThat(((Outcome) recorded.get(0)).importance()).isEqualTo(0.5);
  }

  @Test
  void completedOutcomeHasCorrectImportance() {
    recorder.record(createInstance(), "agent-1", "cap", new WorkerOutcome.Completed<Void>(), "b");
    assertThat(((Outcome) recorded.get(0)).importance()).isEqualTo(0.3);
  }

  @Test
  void reflectionTriggersAtImportanceThreshold() throws Exception {
    var instance = createInstance();
    // 4 FAILED outcomes: 4 * 0.8 = 3.2 > threshold 3.0
    for (int i = 0; i < 4; i++) {
      recorder.record(instance, "agent-1", "cap", new WorkerOutcome.Failed<>("err"), "b");
    }
    Thread.sleep(200);
    assertThat(reflectionCount.get()).isEqualTo(1);
  }

  @Test
  @SuppressWarnings("unchecked")
  void reflectionTriggersAtCountCeiling() throws Exception {
    var definition =
        CaseDefinition.builder()
            .namespace("ns")
            .name("test")
            .version("1.0")
            .reflectionTrigger(
                new ReflectionTriggerConfig(
                    true, 10.0, 3, 50, ReflectionTriggerConfig.DEFAULT_IMPORTANCE_WEIGHTS))
            .build();
    when(registry.getCaseDefinition(org.mockito.ArgumentMatchers.any(CaseMetaModel.class)))
        .thenReturn(definition);

    var instance = createInstance();
    for (int i = 0; i < 3; i++) {
      recorder.record(instance, "agent-1", "cap", new WorkerOutcome.Success<>(null), "b");
    }
    Thread.sleep(200);
    assertThat(reflectionCount.get()).isEqualTo(1);
  }

  @Test
  @SuppressWarnings("unchecked")
  void noReflectionWhenConfigDisabled() {
    var definition = CaseDefinition.builder().namespace("ns").name("test").version("1.0").build();
    when(registry.getCaseDefinition(org.mockito.ArgumentMatchers.any(CaseMetaModel.class)))
        .thenReturn(definition);

    recorder.record(createInstance(), "agent-1", "cap", new WorkerOutcome.Success<>(null), "b");
    assertThat(recorded).hasSize(1);
    assertThat(reflectionCount.get()).isZero();
  }

  @Test
  @SuppressWarnings("unchecked")
  void noopWhenExperienceRecorderUnavailable() {
    Instance<ExperienceRecorder> unavailable = mock(Instance.class);
    when(unavailable.isResolvable()).thenReturn(false);
    Instance<ReflectionOrchestrator> reflInstance = mock(Instance.class);
    when(reflInstance.isResolvable()).thenReturn(false);

    GoalFormationEvaluator goalFormationEvaluator = mock(GoalFormationEvaluator.class);
    var noopRecorder =
        new AgentExperienceRecorder(unavailable, reflInstance, registry, goalFormationEvaluator);
    noopRecorder.record(createInstance(), "agent-1", "cap", new WorkerOutcome.Success<>(null), "b");
    assertThat(recorded).isEmpty();
  }

  private CaseInstance createInstance() {
    var metaModel = new CaseMetaModel();
    metaModel.setNamespace("ns");
    metaModel.setName("test");
    metaModel.setVersion("1.0");

    var instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());
    instance.tenancyId = "tenant-1";
    instance.setCaseMetaModel(metaModel);
    return instance;
  }
}
