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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.casehub.api.model.CaseDefinition;
import io.casehub.eidos.api.AgentCapability;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.BehavioralSignal;
import io.casehub.eidos.api.BehavioralSignalStore;
import io.casehub.eidos.api.ComplianceDimension;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.worker.api.WorkerOutcome;
import jakarta.enterprise.inject.Instance;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BehavioralComplianceRecorderTest {

  private BehavioralSignalStore signalStore;
  private CaseDefinitionRegistry registry;
  private BehavioralComplianceRecorder recorder;
  private CaseInstance caseInstance;
  private CaseDefinition definition;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    signalStore = mock(BehavioralSignalStore.class);
    Instance<BehavioralSignalStore> storeInstance = mock(Instance.class);
    when(storeInstance.isResolvable()).thenReturn(true);
    when(storeInstance.get()).thenReturn(signalStore);

    registry = mock(CaseDefinitionRegistry.class);
    recorder = new BehavioralComplianceRecorder(storeInstance, registry);

    caseInstance = mock(CaseInstance.class);
    caseInstance.tenancyId = "tenant-1";
    CaseMetaModel meta = mock(CaseMetaModel.class);
    when(caseInstance.getCaseMetaModel()).thenReturn(meta);

    AgentDescriptor descriptor =
        AgentDescriptor.builder()
            .agentId("agent-1")
            .name("Test Agent")
            .slot("test")
            .tenancyId("tenant-1")
            .capabilities(
                java.util.List.of(
                    AgentCapability.builder().name("analysis").latencyHintP50Ms(1000L).build()))
            .build();

    definition = mock(CaseDefinition.class);
    when(definition.agentDescriptorFor("worker-1")).thenReturn(Optional.of(descriptor));
    when(registry.getCaseDefinition(any())).thenReturn(definition);
  }

  @Test
  void latencyViolated_recordsViolatedSignal() {
    long durationMs = 5000L;

    recorder.record(caseInstance, "worker-1", "analysis", WorkerOutcome.success(), durationMs);

    verify(signalStore)
        .record(
            eq("agent-1"),
            eq("tenant-1"),
            eq("analysis"),
            eq(ComplianceDimension.LATENCY),
            eq(BehavioralSignal.VIOLATED));
  }

  @Test
  void latencyCompliant_recordsCompliantSignal() {
    long durationMs = 500L;

    recorder.record(caseInstance, "worker-1", "analysis", WorkerOutcome.success(), durationMs);

    verify(signalStore)
        .record(
            eq("agent-1"),
            eq("tenant-1"),
            eq("analysis"),
            eq(ComplianceDimension.LATENCY),
            eq(BehavioralSignal.COMPLIANT));
  }

  @Test
  void noLatencyBound_skipsLatencyObservation() {
    AgentDescriptor noBound =
        AgentDescriptor.builder()
            .agentId("agent-1")
            .name("Test Agent")
            .slot("test")
            .tenancyId("tenant-1")
            .capabilities(java.util.List.of(AgentCapability.builder().name("analysis").build()))
            .build();
    when(definition.agentDescriptorFor("worker-1")).thenReturn(Optional.of(noBound));

    recorder.record(caseInstance, "worker-1", "analysis", WorkerOutcome.success(), 5000L);

    verify(signalStore, never())
        .record(anyString(), anyString(), anyString(), eq(ComplianceDimension.LATENCY), any());
  }

  @Test
  void nullDuration_skipsLatencyObservation() {
    recorder.record(caseInstance, "worker-1", "analysis", WorkerOutcome.success(), null);

    verify(signalStore, never())
        .record(anyString(), anyString(), anyString(), eq(ComplianceDimension.LATENCY), any());
  }

  @Test
  void successOutcome_recordsCompliantAttestation() {
    recorder.record(caseInstance, "worker-1", "analysis", WorkerOutcome.success(), null);

    verify(signalStore)
        .record(
            eq("agent-1"),
            eq("tenant-1"),
            eq("analysis"),
            eq(ComplianceDimension.ATTESTATION_RATE),
            eq(BehavioralSignal.COMPLIANT));
  }

  @Test
  void declinedOutcome_recordsViolatedAttestation() {
    recorder.record(
        caseInstance, "worker-1", "analysis", new WorkerOutcome.Declined<>("not interested"), null);

    verify(signalStore)
        .record(
            eq("agent-1"),
            eq("tenant-1"),
            eq("analysis"),
            eq(ComplianceDimension.ATTESTATION_RATE),
            eq(BehavioralSignal.VIOLATED));
  }

  @Test
  void noDescriptor_recordsNothing() {
    when(definition.agentDescriptorFor("worker-1")).thenReturn(Optional.empty());

    recorder.record(caseInstance, "worker-1", "analysis", WorkerOutcome.success(), 5000L);

    verifyNoInteractions(signalStore);
  }

  @SuppressWarnings("unchecked")
  @Test
  void storeUnavailable_noOp() {
    Instance<BehavioralSignalStore> absent = mock(Instance.class);
    when(absent.isResolvable()).thenReturn(false);
    var silentRecorder = new BehavioralComplianceRecorder(absent, registry);

    silentRecorder.record(caseInstance, "worker-1", "analysis", WorkerOutcome.success(), 5000L);
    // no exception, no interactions with signalStore
  }
}
