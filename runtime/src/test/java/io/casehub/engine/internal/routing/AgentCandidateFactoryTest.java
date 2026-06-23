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

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentHealth;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.CapabilityHealth;
import io.casehub.eidos.api.CapabilityHealth.CapabilityStatus;
import io.casehub.eidos.api.CapabilityHealth.ProbeContext;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentCandidateFactoryTest {

  private CapabilityHealth capabilityHealth;
  private WorkerExecutionManager executionManager;
  private CaseInstance caseInstance;
  private Capability capability;

  @BeforeEach
  void setUp() {
    capabilityHealth = mock(CapabilityHealth.class);
    executionManager = mock(WorkerExecutionManager.class);
    caseInstance = mock(CaseInstance.class);
    capability = Capability.of("research", "{}", "{}");

    when(caseInstance.getUuid()).thenReturn(UUID.randomUUID());
    when(executionManager.getActiveWorkCount("agent-1")).thenReturn(2);
  }

  @Test
  void workerWithMatchingCapability_isIncluded() {
    final Worker worker = workerWithCapability("agent-1", "research");
    final CaseDefinition def = definitionFor(worker);
    when(executionManager.getActiveWorkCount("agent-1")).thenReturn(2);

    final List<AgentCandidate> result =
        AgentCandidateFactory.buildCandidates(
            caseInstance, def, List.of(worker), capability, executionManager, capabilityHealth);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).workerId()).isEqualTo("agent-1");
    assertThat(result.get(0).runningJobs()).isEqualTo(2);
    assertThat(result.get(0).health()).isEqualTo(AgentHealth.READY);
    assertThat(result.get(0).agentDescriptor()).isNull();
  }

  @Test
  void workerWithDifferentCapability_isExcluded() {
    final Worker worker = workerWithCapability("agent-1", "other-capability");
    final CaseDefinition def = definitionFor(worker);

    final List<AgentCandidate> result =
        AgentCandidateFactory.buildCandidates(
            caseInstance, def, List.of(worker), capability, executionManager, capabilityHealth);

    assertThat(result).isEmpty();
  }

  @Test
  void unavailableWorker_isExcluded() {
    final AgentDescriptor descriptor = mock(AgentDescriptor.class);
    final Worker worker = workerWithCapability("agent-1", "research");
    final CaseDefinition def = definitionFor(worker, descriptor);
    when(capabilityHealth.probe(
            descriptor, "research", ProbeContext.of(caseInstance.getUuid().toString())))
        .thenReturn(new CapabilityStatus.Unavailable("down"));

    final List<AgentCandidate> result =
        AgentCandidateFactory.buildCandidates(
            caseInstance, def, List.of(worker), capability, executionManager, capabilityHealth);

    assertThat(result).isEmpty();
  }

  @Test
  void workerWithDescriptor_descriptorPassedThrough() {
    final AgentDescriptor descriptor = mock(AgentDescriptor.class);
    final Worker worker = workerWithCapability("agent-1", "research");
    final CaseDefinition def = definitionFor(worker, descriptor);
    when(capabilityHealth.probe(
            descriptor, "research", ProbeContext.of(caseInstance.getUuid().toString())))
        .thenReturn(new CapabilityStatus.Ready());

    final List<AgentCandidate> result =
        AgentCandidateFactory.buildCandidates(
            caseInstance, def, List.of(worker), capability, executionManager, capabilityHealth);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).agentDescriptor()).isSameAs(descriptor);
  }

  @Test
  void epistemicallyWeakWorker_includedWithCorrectHealth() {
    final AgentDescriptor descriptor = mock(AgentDescriptor.class);
    final Worker worker = workerWithCapability("agent-1", "research");
    final CaseDefinition def = definitionFor(worker, descriptor);
    when(capabilityHealth.probe(
            descriptor, "research", ProbeContext.of(caseInstance.getUuid().toString())))
        .thenReturn(new CapabilityStatus.EpistemicallyWeak("domain", 0.3));

    final List<AgentCandidate> result =
        AgentCandidateFactory.buildCandidates(
            caseInstance, def, List.of(worker), capability, executionManager, capabilityHealth);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).health()).isEqualTo(AgentHealth.EPISTEMICALLY_WEAK);
  }

  @Test
  void nullWorkers_returnsEmpty() {
    final CaseDefinition def =
        CaseDefinition.builder().namespace("t").name("t").version("1").build();

    final List<AgentCandidate> result =
        AgentCandidateFactory.buildCandidates(
            caseInstance, def, null, capability, executionManager, capabilityHealth);

    assertThat(result).isEmpty();
  }

  private Worker workerWithCapability(final String name, final String capabilityName) {
    final Capability cap = Capability.of(capabilityName, "{}", "{}");
    return Worker.builder()
        .name(name)
        .capabilities(cap)
        .function(new WorkerFunction.Sync(input -> WorkerResult.of(Map.of())))
        .build();
  }

  private CaseDefinition definitionFor(final Worker worker) {
    return definitionFor(worker, null);
  }

  private CaseDefinition definitionFor(final Worker worker, final AgentDescriptor descriptor) {
    final CaseDefinition.Builder b =
        CaseDefinition.builder().namespace("t").name("t").version("1").workers(worker);
    if (descriptor != null) {
      b.agentDescriptor(worker.name(), descriptor);
    }
    return b.build();
  }
}
