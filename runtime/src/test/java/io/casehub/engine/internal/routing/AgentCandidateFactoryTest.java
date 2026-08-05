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
import static org.mockito.Mockito.when;

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentHealth;
import io.casehub.api.spi.routing.CandidateMatchingStrategy;
import io.casehub.eidos.api.AgentCapability;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.CapabilityHealth;
import io.casehub.eidos.api.CapabilityHealth.CapabilityStatus;
import io.casehub.eidos.api.CapabilityHealth.ProbeContext;
import io.casehub.eidos.api.MatchDegree;
import io.casehub.eidos.api.VocabularyRegistry;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.engine.internal.worker.NoOpVocabularyRegistry;
import io.casehub.platform.api.routing.NamedStrategy;
import io.casehub.platform.api.routing.StrategyResolver;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentCandidateFactoryTest {

  private static final String VOCAB_URI = "urn:casehub:vocab:capability";

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
    when(capabilityHealth.probe(any(), any(), any())).thenReturn(new CapabilityStatus.Ready());
  }

  // --- Existing behavior: exact string matching ---

  @Test
  void workerWithMatchingCapability_isIncluded() {
    final AgentCandidateFactory factory =
        new AgentCandidateFactory(resolverFor(new NoOpVocabularyRegistry()));
    final Worker worker = workerWithCapability("agent-1", "research");
    final CaseDefinition def = definitionFor(worker);
    when(executionManager.getActiveWorkCount("agent-1")).thenReturn(2);

    final List<AgentCandidate> result =
        factory.buildCandidates(
            caseInstance, def, List.of(worker), capability, executionManager, capabilityHealth);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).workerId()).isEqualTo("agent-1");
    assertThat(result.get(0).runningJobs()).isEqualTo(2);
    assertThat(result.get(0).health()).isEqualTo(AgentHealth.READY);
    assertThat(result.get(0).agentDescriptor()).isNull();
    assertThat(result.get(0).matchDegree()).isInstanceOf(MatchDegree.Exact.class);
  }

  @Test
  void workerWithDifferentCapability_isExcluded() {
    final AgentCandidateFactory factory =
        new AgentCandidateFactory(resolverFor(new NoOpVocabularyRegistry()));
    final Worker worker = workerWithCapability("agent-1", "other-capability");
    final CaseDefinition def = definitionFor(worker);

    final List<AgentCandidate> result =
        factory.buildCandidates(
            caseInstance, def, List.of(worker), capability, executionManager, capabilityHealth);

    assertThat(result).isEmpty();
  }

  @Test
  void unavailableWorker_isExcluded() {
    final AgentCandidateFactory factory =
        new AgentCandidateFactory(resolverFor(new NoOpVocabularyRegistry()));
    final AgentDescriptor descriptor = mock(AgentDescriptor.class);
    final Worker worker = workerWithCapability("agent-1", "research");
    final CaseDefinition def = definitionFor(worker, descriptor);
    when(capabilityHealth.probe(
            descriptor, "research", ProbeContext.of(caseInstance.getUuid().toString())))
        .thenReturn(new CapabilityStatus.Unavailable("down"));

    final List<AgentCandidate> result =
        factory.buildCandidates(
            caseInstance, def, List.of(worker), capability, executionManager, capabilityHealth);

    assertThat(result).isEmpty();
  }

  @Test
  void workerWithDescriptor_descriptorPassedThrough() {
    final AgentCandidateFactory factory =
        new AgentCandidateFactory(resolverFor(new NoOpVocabularyRegistry()));
    final AgentDescriptor descriptor = mock(AgentDescriptor.class);
    final Worker worker = workerWithCapability("agent-1", "research");
    final CaseDefinition def = definitionFor(worker, descriptor);
    when(capabilityHealth.probe(
            descriptor, "research", ProbeContext.of(caseInstance.getUuid().toString())))
        .thenReturn(new CapabilityStatus.Ready());

    final List<AgentCandidate> result =
        factory.buildCandidates(
            caseInstance, def, List.of(worker), capability, executionManager, capabilityHealth);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).agentDescriptor()).isSameAs(descriptor);
  }

  @Test
  void epistemicallyWeakWorker_includedWithCorrectHealth() {
    final AgentCandidateFactory factory =
        new AgentCandidateFactory(resolverFor(new NoOpVocabularyRegistry()));
    final AgentDescriptor descriptor = mock(AgentDescriptor.class);
    final Worker worker = workerWithCapability("agent-1", "research");
    final CaseDefinition def = definitionFor(worker, descriptor);
    when(capabilityHealth.probe(
            descriptor, "research", ProbeContext.of(caseInstance.getUuid().toString())))
        .thenReturn(new CapabilityStatus.EpistemicallyWeak("domain", 0.3));

    final List<AgentCandidate> result =
        factory.buildCandidates(
            caseInstance, def, List.of(worker), capability, executionManager, capabilityHealth);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).health()).isEqualTo(AgentHealth.EPISTEMICALLY_WEAK);
  }

  @Test
  void nullWorkers_returnsEmpty() {
    final AgentCandidateFactory factory =
        new AgentCandidateFactory(resolverFor(new NoOpVocabularyRegistry()));
    final CaseDefinition def =
        CaseDefinition.builder().namespace("t").name("t").version("1").build();

    final List<AgentCandidate> result =
        factory.buildCandidates(
            caseInstance, def, null, capability, executionManager, capabilityHealth);

    assertThat(result).isEmpty();
  }

  // --- Subsumption matching via AgentDescriptor ---

  @Test
  void workerWithSubsumingDescriptorCapability_isIncludedViaPlugin() {
    final VocabularyRegistry registry =
        subsumptionRegistry(
            VOCAB_URI, "code-review", "security-code-review", new MatchDegree.Plugin(1));
    final AgentCandidateFactory factory = new AgentCandidateFactory(resolverFor(registry));

    final Capability requestedCapability = Capability.of("security-code-review", "{}", "{}");

    final AgentCapability agentCap =
        AgentCapability.builder().name("code-review").capabilityVocabulary(VOCAB_URI).build();
    final AgentDescriptor descriptor =
        AgentDescriptor.builder()
            .agentId("agent-1-id")
            .name("agent-1")
            .slot("reviewer")
            .tenancyId("t1")
            .capabilities(List.of(agentCap))
            .build();

    final Worker worker = workerWithCapability("agent-1", "code-review");
    final CaseDefinition def = definitionFor(worker, descriptor);
    when(executionManager.getActiveWorkCount("agent-1")).thenReturn(0);

    final List<AgentCandidate> result =
        factory.buildCandidates(
            caseInstance,
            def,
            List.of(worker),
            requestedCapability,
            executionManager,
            capabilityHealth);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).workerId()).isEqualTo("agent-1");
    assertThat(result.get(0).matchDegree()).isInstanceOf(MatchDegree.Plugin.class);
    assertThat(((MatchDegree.Plugin) result.get(0).matchDegree()).depth()).isEqualTo(1);
  }

  @Test
  void subsumptionMatchedWorker_excludedByHealthProbe() {
    final VocabularyRegistry registry =
        subsumptionRegistry(
            VOCAB_URI, "code-review", "security-code-review", new MatchDegree.Plugin(1));
    final AgentCandidateFactory factory = new AgentCandidateFactory(resolverFor(registry));

    final Capability requestedCapability = Capability.of("security-code-review", "{}", "{}");

    final AgentCapability agentCap =
        AgentCapability.builder().name("code-review").capabilityVocabulary(VOCAB_URI).build();
    final AgentDescriptor descriptor =
        AgentDescriptor.builder()
            .agentId("agent-1-id")
            .name("agent-1")
            .slot("reviewer")
            .tenancyId("t1")
            .capabilities(List.of(agentCap))
            .build();

    final Worker worker = workerWithCapability("agent-1", "code-review");
    final CaseDefinition def = definitionFor(worker, descriptor);
    when(executionManager.getActiveWorkCount("agent-1")).thenReturn(0);
    when(capabilityHealth.probe(
            descriptor, "security-code-review", ProbeContext.of(caseInstance.getUuid().toString())))
        .thenReturn(new CapabilityStatus.Unavailable("agent down"));

    final List<AgentCandidate> result =
        factory.buildCandidates(
            caseInstance,
            def,
            List.of(worker),
            requestedCapability,
            executionManager,
            capabilityHealth);

    assertThat(result).isEmpty();
  }

  @Test
  void workerWithSubsumingDescriptorCapability_isIncludedViaSpecialization() {
    final VocabularyRegistry registry =
        subsumptionRegistry(
            VOCAB_URI, "security-code-review", "code-review", new MatchDegree.Specialization(1));
    final AgentCandidateFactory factory = new AgentCandidateFactory(resolverFor(registry));

    final Capability requestedCapability = Capability.of("code-review", "{}", "{}");

    final AgentCapability agentCap =
        AgentCapability.builder()
            .name("security-code-review")
            .capabilityVocabulary(VOCAB_URI)
            .build();
    final AgentDescriptor descriptor =
        AgentDescriptor.builder()
            .agentId("agent-2-id")
            .name("agent-2")
            .slot("reviewer")
            .tenancyId("t1")
            .capabilities(List.of(agentCap))
            .build();

    final Worker worker = workerWithCapability("agent-2", "security-code-review");
    final CaseDefinition def = definitionFor(worker, descriptor);
    when(executionManager.getActiveWorkCount("agent-2")).thenReturn(0);

    final List<AgentCandidate> result =
        factory.buildCandidates(
            caseInstance,
            def,
            List.of(worker),
            requestedCapability,
            executionManager,
            capabilityHealth);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).workerId()).isEqualTo("agent-2");
    assertThat(result.get(0).matchDegree()).isInstanceOf(MatchDegree.Specialization.class);
  }

  @Test
  void workerWithUngroundedDescriptorCapability_excludedWhenNoExactMatch() {
    final AgentCandidateFactory factory =
        new AgentCandidateFactory(resolverFor(new NoOpVocabularyRegistry()));

    final Capability requestedCapability = Capability.of("security-code-review", "{}", "{}");

    final AgentCapability agentCap = AgentCapability.builder().name("code-review").build();
    final AgentDescriptor descriptor =
        AgentDescriptor.builder()
            .agentId("agent-1-id")
            .name("agent-1")
            .slot("reviewer")
            .tenancyId("t1")
            .capabilities(List.of(agentCap))
            .build();

    final Worker worker = workerWithCapability("agent-1", "code-review");
    final CaseDefinition def = definitionFor(worker, descriptor);

    final List<AgentCandidate> result =
        factory.buildCandidates(
            caseInstance,
            def,
            List.of(worker),
            requestedCapability,
            executionManager,
            capabilityHealth);

    assertThat(result).isEmpty();
  }

  @Test
  void workerWithoutDescriptor_noSubsumptionFallback() {
    final VocabularyRegistry registry =
        subsumptionRegistry(
            VOCAB_URI, "code-review", "security-code-review", new MatchDegree.Plugin(1));
    final AgentCandidateFactory factory = new AgentCandidateFactory(resolverFor(registry));

    final Capability requestedCapability = Capability.of("security-code-review", "{}", "{}");

    final Worker worker = workerWithCapability("agent-1", "code-review");
    final CaseDefinition def = definitionFor(worker);

    final List<AgentCandidate> result =
        factory.buildCandidates(
            caseInstance,
            def,
            List.of(worker),
            requestedCapability,
            executionManager,
            capabilityHealth);

    assertThat(result).isEmpty();
  }

  @Test
  void noOpVocabularyRegistry_exactMatchOnly() {
    final AgentCandidateFactory factory =
        new AgentCandidateFactory(resolverFor(new NoOpVocabularyRegistry()));

    final AgentCapability agentCap =
        AgentCapability.builder().name("code-review").capabilityVocabulary(VOCAB_URI).build();
    final AgentDescriptor descriptor =
        AgentDescriptor.builder()
            .agentId("agent-1-id")
            .name("agent-1")
            .slot("reviewer")
            .tenancyId("t1")
            .capabilities(List.of(agentCap))
            .build();

    final Worker worker = workerWithCapability("agent-1", "code-review");
    final CaseDefinition def = definitionFor(worker, descriptor);

    final Capability requestedCapability = Capability.of("security-code-review", "{}", "{}");

    final List<AgentCandidate> result =
        factory.buildCandidates(
            caseInstance,
            def,
            List.of(worker),
            requestedCapability,
            executionManager,
            capabilityHealth);

    assertThat(result).isEmpty();
  }

  @Test
  void behavioralViolationWorker_includedWithCorrectHealthAndViolations() {
    final AgentCandidateFactory factory =
        new AgentCandidateFactory(resolverFor(new NoOpVocabularyRegistry()));
    final AgentDescriptor descriptor = mock(AgentDescriptor.class);
    final Worker worker = workerWithCapability("agent-1", "research");
    final CaseDefinition def = definitionFor(worker, descriptor);
    when(capabilityHealth.probe(any(), any(), any()))
        .thenReturn(
            new CapabilityStatus.BehavioralViolation(
                Map.of("LATENCY", 3, "ATTESTATION_RATE", 1),
                CapabilityStatus.BehavioralViolation.ViolationKind.PER_DIMENSION));

    final List<AgentCandidate> result =
        factory.buildCandidates(
            caseInstance, def, List.of(worker), capability, executionManager, capabilityHealth);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).health()).isEqualTo(AgentHealth.BEHAVIORAL_VIOLATION);
    assertThat(result.get(0).violations())
        .containsEntry("LATENCY", 3)
        .containsEntry("ATTESTATION_RATE", 1);
  }

  @Test
  void excludedWorker_isExcluded() {
    final AgentCandidateFactory factory =
        new AgentCandidateFactory(resolverFor(new NoOpVocabularyRegistry()));
    final AgentDescriptor descriptor = mock(AgentDescriptor.class);
    final Worker worker = workerWithCapability("agent-1", "research");
    final CaseDefinition def = definitionFor(worker, descriptor);
    when(capabilityHealth.probe(any(), any(), any()))
        .thenReturn(
            new CapabilityStatus.Excluded("research", CapabilityStatus.ExclusionSource.LEARNED, 5));

    final List<AgentCandidate> result =
        factory.buildCandidates(
            caseInstance, def, List.of(worker), capability, executionManager, capabilityHealth);

    assertThat(result).isEmpty();
  }

  // --- Helpers ---

  private Worker workerWithCapability(final String name, final String capabilityName) {
    return Worker.builder()
        .name(name)
        .capabilityName(capabilityName)
        .function(
            new WorkerFunction.Sync<>(
                Map.class, Map.class, (input, scope) -> WorkerResult.of(Map.of())))
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

  private static VocabularyRegistry subsumptionRegistry(
      final String vocabUri,
      final String declaredValue,
      final String requestedValue,
      final MatchDegree matchDegree) {
    return new NoOpVocabularyRegistry() {
      @Override
      public MatchDegree match(final String uri, final String declared, final String requested) {
        if (vocabUri.equals(uri)
            && declaredValue.equals(declared)
            && requestedValue.equals(requested)) {
          return matchDegree;
        }
        return super.match(uri, declared, requested);
      }
    };
  }

  /** Creates a StrategyResolver that returns a SubsumptionMatchStrategy with the given registry. */
  private static StrategyResolver resolverFor(final VocabularyRegistry vocabRegistry) {
    final SubsumptionMatchStrategy matchStrategy = new SubsumptionMatchStrategy(vocabRegistry);
    return new StrategyResolver() {
      @Override
      @SuppressWarnings("unchecked")
      public <T extends NamedStrategy> T resolve(Class<T> type, String id) {
        if (type == CandidateMatchingStrategy.class) return (T) matchStrategy;
        throw new IllegalStateException("No strategy for " + type);
      }

      @Override
      public <T extends NamedStrategy> Optional<T> find(Class<T> type, String id) {
        return Optional.empty();
      }

      @Override
      @SuppressWarnings("unchecked")
      public <T extends NamedStrategy> T defaultStrategy(Class<T> type) {
        if (type == CandidateMatchingStrategy.class) return (T) matchStrategy;
        throw new IllegalStateException("No default for " + type);
      }

      @Override
      public <T extends NamedStrategy> List<T> available(Class<T> type) {
        return List.of();
      }
    };
  }
}
