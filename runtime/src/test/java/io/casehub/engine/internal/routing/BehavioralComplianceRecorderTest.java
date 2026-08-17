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
import io.casehub.api.model.TaskStatus;
import io.casehub.eidos.api.AgentCapability;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.BehavioralExpectations;
import io.casehub.eidos.api.BehavioralSignal;
import io.casehub.eidos.api.BehavioralSignalStore;
import io.casehub.eidos.api.ComplianceDimension;
import io.casehub.eidos.api.VocabularyRegistry;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.internal.model.PlanItemRecord;
import io.casehub.engine.common.internal.model.PlanItemType;
import io.casehub.engine.common.internal.model.TargetType;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.worker.api.PlannedAction;
import io.casehub.worker.api.WorkerOutcome;
import jakarta.enterprise.inject.Instance;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class BehavioralComplianceRecorderTest {

  private BehavioralSignalStore signalStore;

  @SuppressWarnings("unchecked")
  private Instance<BehavioralSignalStore> storeInstance = mock(Instance.class);

  private CaseDefinitionRegistry registry;
  private PlanItemStore planItemStore;

  @SuppressWarnings("unchecked")
  private Instance<PlanItemStore> planItemStoreInstance = mock(Instance.class);

  private VocabularyRegistry vocabularyRegistry;
  private BehavioralComplianceRecorder recorder;
  private CaseInstance caseInstance;
  private CaseDefinition definition;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    signalStore = mock(BehavioralSignalStore.class);
    storeInstance = mock(Instance.class);
    when(storeInstance.isResolvable()).thenReturn(true);
    when(storeInstance.get()).thenReturn(signalStore);

    registry = mock(CaseDefinitionRegistry.class);

    planItemStore = mock(PlanItemStore.class);
    planItemStoreInstance = mock(Instance.class);
    when(planItemStoreInstance.isResolvable()).thenReturn(true);
    when(planItemStoreInstance.get()).thenReturn(planItemStore);

    vocabularyRegistry = mock(VocabularyRegistry.class);

    recorder =
        new BehavioralComplianceRecorder(
            storeInstance, registry, planItemStoreInstance, vocabularyRegistry);

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
    var silentRecorder =
        new BehavioralComplianceRecorder(
            absent, registry, planItemStoreInstance, vocabularyRegistry);

    silentRecorder.record(caseInstance, "worker-1", "analysis", WorkerOutcome.success(), 5000L);
    // no exception, no interactions with signalStore
  }

  @Test
  void delegationCompliant_recordsCompliantSignal() {
    AgentDescriptor delegating =
        AgentDescriptor.builder()
            .agentId("agent-1")
            .name("Test Agent")
            .slot("test")
            .tenancyId("tenant-1")
            .capabilities(
                java.util.List.of(
                    AgentCapability.builder().name("analysis").latencyHintP50Ms(1000L).build()))
            .disposition(AgentDisposition.builder().delegation(true).build())
            .build();
    when(definition.agentDescriptorFor("worker-1")).thenReturn(Optional.of(delegating));
    when(definition.getDecompositionStrategy()).thenReturn("llm");

    UUID caseUuid = UUID.randomUUID();
    when(caseInstance.getUuid()).thenReturn(caseUuid);

    PlanItemRecord withParent =
        new PlanItemRecord(
            caseUuid,
            "pi-1",
            "child-binding",
            TaskStatus.COMPLETED,
            Instant.now(),
            null,
            TargetType.CAPABILITY,
            null,
            "tenant-1",
            "child task",
            "worker-2",
            "Worker 2",
            PlanItemType.PRIMITIVE,
            null,
            null,
            null,
            false,
            "compound-1",
            null,
            null,
            null);
    when(planItemStore.findByCaseId(caseUuid, "tenant-1"))
        .thenReturn(java.util.List.of(withParent));

    recorder.record(caseInstance, "worker-1", "analysis", WorkerOutcome.success(), null);

    verify(signalStore)
        .record(
            eq("agent-1"),
            eq("tenant-1"),
            eq("analysis"),
            eq(ComplianceDimension.DELEGATION),
            eq(BehavioralSignal.COMPLIANT));
  }

  @Test
  void delegationViolated_recordsViolatedSignal() {
    AgentDescriptor delegating =
        AgentDescriptor.builder()
            .agentId("agent-1")
            .name("Test Agent")
            .slot("test")
            .tenancyId("tenant-1")
            .capabilities(
                java.util.List.of(
                    AgentCapability.builder().name("analysis").latencyHintP50Ms(1000L).build()))
            .disposition(AgentDisposition.builder().delegation(true).build())
            .build();
    when(definition.agentDescriptorFor("worker-1")).thenReturn(Optional.of(delegating));
    when(definition.getDecompositionStrategy()).thenReturn("llm");

    UUID caseUuid = UUID.randomUUID();
    when(caseInstance.getUuid()).thenReturn(caseUuid);

    PlanItemRecord leafOnly =
        PlanItemRecord.primitive(
            caseUuid,
            "pi-1",
            "leaf-binding",
            TaskStatus.COMPLETED,
            Instant.now(),
            TargetType.CAPABILITY,
            null,
            "tenant-1",
            "leaf task",
            "worker-1",
            "Worker 1");
    when(planItemStore.findByCaseId(caseUuid, "tenant-1")).thenReturn(java.util.List.of(leafOnly));

    recorder.record(caseInstance, "worker-1", "analysis", WorkerOutcome.success(), null);

    verify(signalStore)
        .record(
            eq("agent-1"),
            eq("tenant-1"),
            eq("analysis"),
            eq(ComplianceDimension.DELEGATION),
            eq(BehavioralSignal.VIOLATED));
  }

  @Test
  void delegationNotExpected_skips() {
    UUID caseUuid = UUID.randomUUID();
    when(caseInstance.getUuid()).thenReturn(caseUuid);
    when(definition.getDecompositionStrategy()).thenReturn("llm");

    recorder.record(caseInstance, "worker-1", "analysis", WorkerOutcome.success(), null);

    verify(signalStore, never())
        .record(anyString(), anyString(), anyString(), eq(ComplianceDimension.DELEGATION), any());
  }

  @Test
  void noDecompositionInfrastructure_skipsDelegation() {
    AgentDescriptor delegating =
        AgentDescriptor.builder()
            .agentId("agent-1")
            .name("Test Agent")
            .slot("test")
            .tenancyId("tenant-1")
            .capabilities(java.util.List.of(AgentCapability.builder().name("analysis").build()))
            .disposition(AgentDisposition.builder().delegation(true).build())
            .build();
    when(definition.agentDescriptorFor("worker-1")).thenReturn(Optional.of(delegating));
    when(definition.getDecompositionStrategy()).thenReturn(null);
    when(definition.getBindings()).thenReturn(java.util.List.of());

    UUID caseUuid = UUID.randomUUID();
    when(caseInstance.getUuid()).thenReturn(caseUuid);

    recorder.record(caseInstance, "worker-1", "analysis", WorkerOutcome.success(), null);

    verify(signalStore, never())
        .record(anyString(), anyString(), anyString(), eq(ComplianceDimension.DELEGATION), any());
  }

  @SuppressWarnings("unchecked")
  @Test
  void planItemStoreUnavailable_skipsDelegation() {
    Instance<PlanItemStore> absentStore = mock(Instance.class);
    when(absentStore.isResolvable()).thenReturn(false);

    var recorderNoPlanItems =
        new BehavioralComplianceRecorder(storeInstance, registry, absentStore, vocabularyRegistry);

    AgentDescriptor delegating =
        AgentDescriptor.builder()
            .agentId("agent-1")
            .name("Test Agent")
            .slot("test")
            .tenancyId("tenant-1")
            .capabilities(java.util.List.of(AgentCapability.builder().name("analysis").build()))
            .disposition(AgentDisposition.builder().delegation(true).build())
            .build();
    when(definition.agentDescriptorFor("worker-1")).thenReturn(Optional.of(delegating));
    when(definition.getDecompositionStrategy()).thenReturn("llm");

    UUID caseUuid = UUID.randomUUID();
    when(caseInstance.getUuid()).thenReturn(caseUuid);

    recorderNoPlanItems.record(caseInstance, "worker-1", "analysis", WorkerOutcome.success(), null);

    verify(signalStore, never())
        .record(anyString(), anyString(), anyString(), eq(ComplianceDimension.DELEGATION), any());
  }

  @Test
  void escalationCompliant_plannedAction() {
    UUID caseUuid = UUID.randomUUID();
    when(caseInstance.getUuid()).thenReturn(caseUuid);

    try (MockedStatic<BehavioralExpectations> expectations =
        org.mockito.Mockito.mockStatic(BehavioralExpectations.class)) {
      expectations.when(() -> BehavioralExpectations.delegationExpected(any())).thenReturn(false);
      expectations
          .when(
              () ->
                  BehavioralExpectations.escalationExpected(
                      any(AgentDescriptor.class), any(VocabularyRegistry.class)))
          .thenReturn(true);
      expectations.when(() -> BehavioralExpectations.latencyBound(any())).thenCallRealMethod();

      recorder.record(
          caseInstance,
          "worker-1",
          "analysis",
          WorkerOutcome.success(PlannedAction.of("File report", "report.file", java.util.Map.of())),
          null);
    }

    verify(signalStore)
        .record(
            eq("agent-1"),
            eq("tenant-1"),
            eq("analysis"),
            eq(ComplianceDimension.ESCALATION),
            eq(BehavioralSignal.COMPLIANT));
  }

  @Test
  void escalationCompliant_declined() {
    UUID caseUuid = UUID.randomUUID();
    when(caseInstance.getUuid()).thenReturn(caseUuid);

    try (MockedStatic<BehavioralExpectations> expectations =
        org.mockito.Mockito.mockStatic(BehavioralExpectations.class)) {
      expectations.when(() -> BehavioralExpectations.delegationExpected(any())).thenReturn(false);
      expectations
          .when(
              () ->
                  BehavioralExpectations.escalationExpected(
                      any(AgentDescriptor.class), any(VocabularyRegistry.class)))
          .thenReturn(true);
      expectations.when(() -> BehavioralExpectations.latencyBound(any())).thenCallRealMethod();

      recorder.record(
          caseInstance, "worker-1", "analysis", new WorkerOutcome.Declined<>("not my area"), null);
    }

    verify(signalStore)
        .record(
            eq("agent-1"),
            eq("tenant-1"),
            eq("analysis"),
            eq(ComplianceDimension.ESCALATION),
            eq(BehavioralSignal.COMPLIANT));
  }

  @Test
  void escalationViolated_autonomousSuccess() {
    UUID caseUuid = UUID.randomUUID();
    when(caseInstance.getUuid()).thenReturn(caseUuid);

    try (MockedStatic<BehavioralExpectations> expectations =
        org.mockito.Mockito.mockStatic(BehavioralExpectations.class)) {
      expectations.when(() -> BehavioralExpectations.delegationExpected(any())).thenReturn(false);
      expectations
          .when(
              () ->
                  BehavioralExpectations.escalationExpected(
                      any(AgentDescriptor.class), any(VocabularyRegistry.class)))
          .thenReturn(true);
      expectations.when(() -> BehavioralExpectations.latencyBound(any())).thenCallRealMethod();

      recorder.record(caseInstance, "worker-1", "analysis", WorkerOutcome.success(), null);
    }

    verify(signalStore)
        .record(
            eq("agent-1"),
            eq("tenant-1"),
            eq("analysis"),
            eq(ComplianceDimension.ESCALATION),
            eq(BehavioralSignal.VIOLATED));
  }

  @Test
  void escalationNotExpected_skips() {
    UUID caseUuid = UUID.randomUUID();
    when(caseInstance.getUuid()).thenReturn(caseUuid);

    recorder.record(caseInstance, "worker-1", "analysis", WorkerOutcome.success(), null);

    verify(signalStore, never())
        .record(anyString(), anyString(), anyString(), eq(ComplianceDimension.ESCALATION), any());
  }

  @Test
  void failedOutcome_noEscalationObservation() {
    UUID caseUuid = UUID.randomUUID();
    when(caseInstance.getUuid()).thenReturn(caseUuid);

    try (MockedStatic<BehavioralExpectations> expectations =
        org.mockito.Mockito.mockStatic(BehavioralExpectations.class)) {
      expectations.when(() -> BehavioralExpectations.delegationExpected(any())).thenReturn(false);
      expectations
          .when(
              () ->
                  BehavioralExpectations.escalationExpected(
                      any(AgentDescriptor.class), any(VocabularyRegistry.class)))
          .thenReturn(true);
      expectations.when(() -> BehavioralExpectations.latencyBound(any())).thenCallRealMethod();

      recorder.record(
          caseInstance, "worker-1", "analysis", new WorkerOutcome.Failed<>("error"), null);
    }

    verify(signalStore, never())
        .record(anyString(), anyString(), anyString(), eq(ComplianceDimension.ESCALATION), any());
  }

  @Test
  void expiredOutcome_noEscalationObservation() {
    UUID caseUuid = UUID.randomUUID();
    when(caseInstance.getUuid()).thenReturn(caseUuid);

    try (MockedStatic<BehavioralExpectations> expectations =
        org.mockito.Mockito.mockStatic(BehavioralExpectations.class)) {
      expectations.when(() -> BehavioralExpectations.delegationExpected(any())).thenReturn(false);
      expectations
          .when(
              () ->
                  BehavioralExpectations.escalationExpected(
                      any(AgentDescriptor.class), any(VocabularyRegistry.class)))
          .thenReturn(true);
      expectations.when(() -> BehavioralExpectations.latencyBound(any())).thenCallRealMethod();

      recorder.record(
          caseInstance, "worker-1", "analysis", new WorkerOutcome.Expired<>("timeout"), null);
    }

    verify(signalStore, never())
        .record(anyString(), anyString(), anyString(), eq(ComplianceDimension.ESCALATION), any());
  }

  @Test
  void declined_crossDimensionInteraction() {
    UUID caseUuid = UUID.randomUUID();
    when(caseInstance.getUuid()).thenReturn(caseUuid);

    try (MockedStatic<BehavioralExpectations> expectations =
        org.mockito.Mockito.mockStatic(BehavioralExpectations.class)) {
      expectations.when(() -> BehavioralExpectations.delegationExpected(any())).thenReturn(false);
      expectations
          .when(
              () ->
                  BehavioralExpectations.escalationExpected(
                      any(AgentDescriptor.class), any(VocabularyRegistry.class)))
          .thenReturn(true);
      expectations.when(() -> BehavioralExpectations.latencyBound(any())).thenCallRealMethod();

      recorder.record(
          caseInstance, "worker-1", "analysis", new WorkerOutcome.Declined<>("not my area"), null);
    }

    verify(signalStore)
        .record(
            eq("agent-1"),
            eq("tenant-1"),
            eq("analysis"),
            eq(ComplianceDimension.ATTESTATION_RATE),
            eq(BehavioralSignal.VIOLATED));
    verify(signalStore)
        .record(
            eq("agent-1"),
            eq("tenant-1"),
            eq("analysis"),
            eq(ComplianceDimension.ESCALATION),
            eq(BehavioralSignal.COMPLIANT));
  }
}
