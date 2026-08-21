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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.internal.routing.GoalFormationEvaluator;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.MemoryInput;
import io.casehub.neocortex.memory.experience.ExperienceRecorder;
import io.casehub.neocortex.memory.reflection.ReflectionOrchestrator;
import io.casehub.worker.api.WorkerOutcome;
import jakarta.enterprise.inject.Instance;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentExperienceRecorderReasoningTest {

  private CaseMemoryStore store;
  private Instance<CaseMemoryStore> storeInstance;
  private AgentExperienceRecorder recorder;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    store = mock(CaseMemoryStore.class);
    when(store.store(any())).thenReturn("mem-1");

    storeInstance = mock(Instance.class);
    when(storeInstance.isResolvable()).thenReturn(true);
    when(storeInstance.get()).thenReturn(store);

    Instance<ExperienceRecorder> expInstance = mock(Instance.class);
    when(expInstance.isResolvable()).thenReturn(false);

    Instance<ReflectionOrchestrator> reflInstance = mock(Instance.class);
    when(reflInstance.isResolvable()).thenReturn(false);

    recorder =
        new AgentExperienceRecorder(
            expInstance,
            reflInstance,
            mock(CaseDefinitionRegistry.class),
            mock(GoalFormationEvaluator.class),
            storeInstance);
    recorder.reasoningEnabled = true;
  }

  @Test
  void storeReasoningCreatesCorrectMemoryInput() throws Exception {
    CaseInstance caseInstance = mockCaseInstance();
    recorder.storeReasoning(
        caseInstance,
        "agent-1",
        "security-review",
        WorkerOutcome.success(),
        "I approved because no vulnerabilities found",
        "security-check");

    Thread.sleep(300);

    ArgumentCaptor<MemoryInput> captor = ArgumentCaptor.forClass(MemoryInput.class);
    verify(store).store(captor.capture());

    MemoryInput input = captor.getValue();
    assertThat(input.entityId()).isEqualTo("case:" + caseInstance.getUuid());
    assertThat(input.domain().name()).isEqualTo("worker-reasoning");
    assertThat(input.text()).isEqualTo("I approved because no vulnerabilities found");
    assertThat(input.attributes().get("workerName")).isEqualTo("agent-1");
    assertThat(input.attributes().get("capability")).isEqualTo("security-review");
    assertThat(input.attributes().get("outcome")).isEqualTo("SUCCESS");
    assertThat(input.attributes().get("bindingName")).isEqualTo("security-check");
    assertThat(input.attributes()).doesNotContainKey("truncated");
  }

  @Test
  void storeReasoningNoOpWhenNull() {
    recorder.storeReasoning(
        mockCaseInstance(), "agent-1", "cap", WorkerOutcome.success(), null, "binding");
    verify(storeInstance, never()).get();
  }

  @Test
  void storeReasoningNoOpWhenBlank() {
    recorder.storeReasoning(
        mockCaseInstance(), "agent-1", "cap", WorkerOutcome.success(), "  ", "binding");
    verify(storeInstance, never()).get();
  }

  @Test
  void storeReasoningNoOpWhenDisabled() {
    recorder.reasoningEnabled = false;
    recorder.storeReasoning(
        mockCaseInstance(), "agent-1", "cap", WorkerOutcome.success(), "reasoning text", "binding");
    verify(storeInstance, never()).get();
  }

  @SuppressWarnings("unchecked")
  @Test
  void storeReasoningNoOpWhenStoreNotResolvable() {
    Instance<CaseMemoryStore> unresolv = mock(Instance.class);
    when(unresolv.isResolvable()).thenReturn(false);

    var rec =
        new AgentExperienceRecorder(
            mock(Instance.class),
            mock(Instance.class),
            mock(CaseDefinitionRegistry.class),
            mock(GoalFormationEvaluator.class),
            unresolv);
    rec.reasoningEnabled = true;

    rec.storeReasoning(
        mockCaseInstance(), "agent-1", "cap", WorkerOutcome.success(), "reasoning", "binding");
    verify(unresolv, never()).get();
  }

  @Test
  void storeReasoningSwallowsException() throws Exception {
    when(store.store(any())).thenThrow(new RuntimeException("store failed"));

    recorder.storeReasoning(
        mockCaseInstance(), "agent-1", "cap", WorkerOutcome.success(), "reasoning", "binding");
    Thread.sleep(300);
    verify(store).store(any());
  }

  @Test
  void storeReasoningSetsOutcomeForDeclined() throws Exception {
    recorder.storeReasoning(
        mockCaseInstance(),
        "agent-1",
        "cap",
        new WorkerOutcome.Declined<>("scope mismatch"),
        "I declined because...",
        "binding");
    Thread.sleep(300);

    ArgumentCaptor<MemoryInput> captor = ArgumentCaptor.forClass(MemoryInput.class);
    verify(store).store(captor.capture());
    assertThat(captor.getValue().attributes().get("outcome")).isEqualTo("DECLINED");
  }

  @Test
  void truncationMarksAttribute() throws Exception {
    String longReasoning = "A".repeat(5000);
    recorder.storeReasoning(
        mockCaseInstance(), "agent-1", "cap", WorkerOutcome.success(), longReasoning, "binding");
    Thread.sleep(300);

    ArgumentCaptor<MemoryInput> captor = ArgumentCaptor.forClass(MemoryInput.class);
    verify(store).store(captor.capture());
    MemoryInput input = captor.getValue();
    assertThat(input.text().length()).isLessThanOrEqualTo(4096);
    assertThat(input.attributes().get("truncated")).isEqualTo("true");
    assertThat(input.text()).contains("[...truncated...]");
  }

  private CaseInstance mockCaseInstance() {
    CaseInstance ci = mock(CaseInstance.class);
    UUID caseId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    when(ci.getUuid()).thenReturn(caseId);
    ci.tenancyId = "test-tenant";
    return ci;
  }
}
