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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.eidos.api.AgentCapability;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.BehavioralSignal;
import io.casehub.eidos.api.BehavioralSignalStore;
import io.casehub.eidos.api.ComplianceDimension;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerOutcome;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * CDI integration test verifying BehavioralComplianceRecorder wiring with its new dependencies
 * (Instance&lt;PlanItemStore&gt;, VocabularyRegistry) in a real Quarkus container. Tests delegation
 * compliance through the full CDI injection path. Escalation compliance requires vocabulary
 * infrastructure — covered by unit tests with mockStatic in BehavioralComplianceRecorderTest.
 */
@QuarkusTest
@TestProfile(BehavioralComplianceIntegrationTest.MemoryProfile.class)
class BehavioralComplianceIntegrationTest {

  @Inject BehavioralComplianceRecorder recorder;
  @Inject RecordingSignalStore signalStore;

  @BeforeEach
  void setUp() {
    signalStore.clear();
  }

  @Test
  void recorderInjected() {
    assertNotNull(recorder, "BehavioralComplianceRecorder should be CDI-injected");
  }

  @Test
  void delegationViolated_noCompoundChildren() {
    CaseInstance instance = buildCaseInstance();

    recorder.record(instance, "delegator", "process", WorkerOutcome.success(), null);

    List<RecordingSignalStore.Signal> delegationSignals =
        signalStore.getSignals().stream()
            .filter(s -> ComplianceDimension.DELEGATION.equals(s.dimension()))
            .toList();

    assertEquals(1, delegationSignals.size(), "Should record one delegation signal");
    assertEquals(
        BehavioralSignal.VIOLATED,
        delegationSignals.getFirst().signal(),
        "No compound children in PlanItemStore → VIOLATED");
  }

  @Test
  void attestationRecorded_alongside_delegation() {
    CaseInstance instance = buildCaseInstance();

    recorder.record(instance, "delegator", "process", WorkerOutcome.success(), null);

    List<RecordingSignalStore.Signal> attestationSignals =
        signalStore.getSignals().stream()
            .filter(s -> ComplianceDimension.ATTESTATION_RATE.equals(s.dimension()))
            .toList();

    assertEquals(1, attestationSignals.size());
    assertEquals(BehavioralSignal.COMPLIANT, attestationSignals.getFirst().signal());
  }

  @Test
  void noDelegationDescriptor_skipsDelegation() {
    CaseInstance instance = buildCaseInstance();

    recorder.record(instance, "non-delegator", "process", WorkerOutcome.success(), null);

    boolean hasDelegationSignal =
        signalStore.getSignals().stream()
            .anyMatch(s -> ComplianceDimension.DELEGATION.equals(s.dimension()));

    assertFalse(
        hasDelegationSignal, "Worker without delegation disposition → no delegation signal");
  }

  @Test
  void unknownWorker_recordsNothing() {
    CaseInstance instance = buildCaseInstance();

    recorder.record(instance, "unknown-worker", "process", WorkerOutcome.success(), null);

    assertTrue(signalStore.getSignals().isEmpty(), "Unknown worker has no descriptor → no signals");
  }

  private CaseInstance buildCaseInstance() {
    CaseMetaModel meta = new CaseMetaModel();
    meta.setNamespace("compliance-integ");
    meta.setName("Compliance Integration");
    meta.setVersion("1.0.0");
    CaseInstance instance = new CaseInstance();
    instance.setCaseMetaModel(meta);
    instance.setUuid(UUID.randomUUID());
    instance.tenancyId = "test-tenant";
    return instance;
  }

  // ── Recording BehavioralSignalStore ──

  @Alternative
  @Priority(1)
  @ApplicationScoped
  public static class RecordingSignalStore implements BehavioralSignalStore {

    final CopyOnWriteArrayList<Signal> signals = new CopyOnWriteArrayList<>();

    record Signal(
        String agentId,
        String tenancyId,
        String capability,
        String dimension,
        BehavioralSignal signal) {}

    @Override
    public void record(
        String agentId,
        String tenancyId,
        String capability,
        String dimension,
        BehavioralSignal signal) {
      signals.add(new Signal(agentId, tenancyId, capability, dimension, signal));
    }

    @Override
    public void clear(
        String agentId, String tenancyId, String capability, BehavioralSignal signal) {
      signals.removeIf(
          s ->
              s.agentId().equals(agentId)
                  && s.tenancyId().equals(tenancyId)
                  && s.capability().equals(capability)
                  && s.signal() == signal);
    }

    @Override
    public Map<String, Integer> learned(
        String agentId, String tenancyId, String capability, BehavioralSignal signal) {
      return Map.of();
    }

    @Override
    public int count(
        String agentId,
        String tenancyId,
        String capability,
        String dimension,
        BehavioralSignal signal) {
      return (int)
          signals.stream()
              .filter(
                  s ->
                      s.agentId().equals(agentId)
                          && s.tenancyId().equals(tenancyId)
                          && s.capability().equals(capability)
                          && s.dimension().equals(dimension)
                          && s.signal() == signal)
              .count();
    }

    List<Signal> getSignals() {
      return List.copyOf(signals);
    }

    void clear() {
      signals.clear();
    }
  }

  // ── CaseHub with delegation descriptor ──

  @ApplicationScoped
  public static class ComplianceIntegrationCaseHub extends CaseHub {
    @Override
    public CaseDefinition getDefinition() {
      Capability processCapability =
          Capability.builder()
              .name("process")
              .inputSchema(".")
              .outputSchema(".")
              .description("Process task")
              .build();

      AgentDescriptor delegatorDescriptor =
          AgentDescriptor.builder()
              .agentId("delegator-agent")
              .name("Delegator Agent")
              .slot("test")
              .tenancyId("test-tenant")
              .capabilities(List.of(AgentCapability.builder().name("process").build()))
              .disposition(AgentDisposition.builder().delegation(true).build())
              .build();

      AgentDescriptor nonDelegatorDescriptor =
          AgentDescriptor.builder()
              .agentId("non-delegator-agent")
              .name("Non-Delegator Agent")
              .slot("test")
              .tenancyId("test-tenant")
              .capabilities(List.of(AgentCapability.builder().name("process").build()))
              .build();

      return CaseDefinition.builder()
          .namespace("compliance-integ")
          .name("Compliance Integration")
          .version("1.0.0")
          .title("Behavioral Compliance CDI Integration Test")
          .capabilities(processCapability)
          .workers(
              Worker.builder()
                  .name("delegator")
                  .capabilityName("process")
                  .function(input -> WorkerResult.of(Map.of("done", true)))
                  .build(),
              Worker.builder()
                  .name("non-delegator")
                  .capabilityName("process")
                  .function(input -> WorkerResult.of(Map.of("done", true)))
                  .build())
          .bindings(
              Binding.builder()
                  .name("trigger-process")
                  .capability(processCapability)
                  .on(new ContextChangeTrigger(".status == \"ready\""))
                  .build())
          .goals(
              Goal.builder().name("done").condition(".done == true").kind(GoalKind.SUCCESS).build())
          .completion(
              GoalExpression.allOf(
                  Goal.builder()
                      .name("done")
                      .condition(".done == true")
                      .kind(GoalKind.SUCCESS)
                      .build()))
          .decompositionStrategy("llm")
          .agentDescriptor("delegator", delegatorDescriptor)
          .agentDescriptor("non-delegator", nonDelegatorDescriptor)
          .build();
    }
  }

  public static class MemoryProfile implements QuarkusTestProfile {
    @Override
    public String getConfigProfile() {
      return "memory";
    }
  }
}
