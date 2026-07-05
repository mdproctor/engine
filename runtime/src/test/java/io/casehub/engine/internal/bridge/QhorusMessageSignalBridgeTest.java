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
package io.casehub.engine.internal.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.CaseChannel;
import io.casehub.api.model.CaseDefinition;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkflowExecutionCompleted;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.ReactiveCrossTenantCaseInstanceRepository;
import io.casehub.engine.common.spi.ReactiveCrossTenantEventLogRepository;
import io.casehub.qhorus.api.gateway.MessageReceivedEvent;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerOutcome;
import io.casehub.worker.api.WorkerResult;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QhorusMessageSignalBridgeTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private CaseHubRuntime runtime;
  private ReactiveCrossTenantEventLogRepository eventLogRepository;
  private ReactiveCrossTenantCaseInstanceRepository caseInstanceRepository;
  private CaseDefinitionRegistry caseDefinitionRegistry;
  private EventBus eventBus;
  private QhorusMessageSignalBridge bridge;

  @BeforeEach
  void setUp() {
    runtime = mock(CaseHubRuntime.class);
    eventLogRepository = mock(ReactiveCrossTenantEventLogRepository.class);
    caseInstanceRepository = mock(ReactiveCrossTenantCaseInstanceRepository.class);
    caseDefinitionRegistry = mock(CaseDefinitionRegistry.class);
    eventBus = mock(EventBus.class);

    bridge = new QhorusMessageSignalBridge(runtime);
    bridge.eventLogRepository = eventLogRepository;
    bridge.caseInstanceRepository = caseInstanceRepository;
    bridge.caseDefinitionRegistry = caseDefinitionRegistry;
    bridge.eventBus = eventBus;
  }

  // ---- DONE/RESPONSE still go through signal path ----

  @Test
  void responseOnCaseChannel_signalsEngine() {
    UUID caseId = UUID.randomUUID();
    MessageReceivedEvent event =
        event(
            caseChannelName(caseId, "work"),
            MessageType.RESPONSE,
            "sender-1",
            "corr-123",
            "message content");

    bridge.onMessage(event);

    verify(runtime)
        .signal(
            eq(caseId), eq(QhorusMessageSignalBridge.SIGNAL_PATH), any(Map.class), any(), any());
  }

  @Test
  void doneOnCaseChannel_signalsEngine() {
    UUID caseId = UUID.randomUUID();
    MessageReceivedEvent event =
        event(
            caseChannelName(caseId, "work"),
            MessageType.DONE,
            "sender-1",
            "corr-123",
            "done content");

    bridge.onMessage(event);

    verify(runtime)
        .signal(
            eq(caseId), eq(QhorusMessageSignalBridge.SIGNAL_PATH), any(Map.class), any(), any());
  }

  // ---- DECLINE/FAILURE with valid engine correlationId → failure cascade ----

  @Test
  void declineWithEngineCorrelation_publishesDeclinedOutcome() {
    UUID caseId = UUID.randomUUID();
    long eventLogId = 42L;
    String workerName = "security-reviewer";
    String bindingName = "security-review";
    CaseInstance instance = stubCaseInstance(caseId);
    stubEventLog(eventLogId, workerName, bindingName, "hash-1");
    stubCaseInstance(caseId, instance);
    stubWorkerInDefinition(instance, workerName);

    bridge.onMessage(
        event(
            caseChannelName(caseId, "work"),
            MessageType.DECLINE,
            "agent-1",
            String.valueOf(eventLogId),
            "not qualified for this review"));

    ArgumentCaptor<WorkflowExecutionCompleted> captor =
        ArgumentCaptor.forClass(WorkflowExecutionCompleted.class);
    verify(eventBus).publish(eq(EventBusAddresses.WORKER_EXECUTION_FINISHED), captor.capture());
    WorkflowExecutionCompleted completed = captor.getValue();

    assertThat(completed.outcome()).isInstanceOf(WorkerOutcome.Declined.class);
    assertThat(((WorkerOutcome.Declined) completed.outcome()).reason())
        .isEqualTo("not qualified for this review");
    assertThat(completed.worker().name()).isEqualTo(workerName);
    assertThat(completed.bindingName()).isEqualTo(bindingName);
    assertThat(completed.caseInstance()).isSameAs(instance);
    verifyNoInteractions(runtime);
  }

  @Test
  void failureWithEngineCorrelation_publishesFailedOutcome() {
    UUID caseId = UUID.randomUUID();
    long eventLogId = 99L;
    String workerName = "code-analyst";
    String bindingName = "analyse-code";
    CaseInstance instance = stubCaseInstance(caseId);
    stubEventLog(eventLogId, workerName, bindingName, "hash-2");
    stubCaseInstance(caseId, instance);
    stubWorkerInDefinition(instance, workerName);

    bridge.onMessage(
        event(
            caseChannelName(caseId, "work"),
            MessageType.FAILURE,
            "agent-2",
            String.valueOf(eventLogId),
            "tool execution error"));

    ArgumentCaptor<WorkflowExecutionCompleted> captor =
        ArgumentCaptor.forClass(WorkflowExecutionCompleted.class);
    verify(eventBus).publish(eq(EventBusAddresses.WORKER_EXECUTION_FINISHED), captor.capture());
    WorkflowExecutionCompleted completed = captor.getValue();

    assertThat(completed.outcome()).isInstanceOf(WorkerOutcome.Failed.class);
    assertThat(((WorkerOutcome.Failed) completed.outcome()).reason())
        .isEqualTo("tool execution error");
    assertThat(completed.idempotency()).isEqualTo("hash-2");
    verifyNoInteractions(runtime);
  }

  // ---- Edge cases: fall through to signal path ----

  @Test
  void declineWithNonNumericCorrelation_fallsThroughToSignal() {
    UUID caseId = UUID.randomUUID();
    bridge.onMessage(
        event(
            caseChannelName(caseId, "work"),
            MessageType.DECLINE,
            "human-1",
            "non-engine-correlation",
            "decline reason"));

    verify(runtime)
        .signal(
            eq(caseId), eq(QhorusMessageSignalBridge.SIGNAL_PATH), any(Map.class), any(), any());
    verifyNoInteractions(eventBus);
  }

  @Test
  void declineWithEventLogNotFound_fallsThroughToSignal() {
    UUID caseId = UUID.randomUUID();
    when(eventLogRepository.findById(999L)).thenReturn(Uni.createFrom().nullItem());

    bridge.onMessage(
        event(
            caseChannelName(caseId, "work"),
            MessageType.DECLINE,
            "agent-1",
            "999",
            "decline reason"));

    verify(runtime)
        .signal(
            eq(caseId), eq(QhorusMessageSignalBridge.SIGNAL_PATH), any(Map.class), any(), any());
    verifyNoInteractions(eventBus);
  }

  // ---- Edge case: case already terminal ----

  @Test
  void declineWithCaseNotFound_skipsCompletely() {
    UUID caseId = UUID.randomUUID();
    long eventLogId = 42L;
    stubEventLog(eventLogId, "worker-1", "binding-1", "hash-1");
    when(caseInstanceRepository.findByUuid(caseId)).thenReturn(Uni.createFrom().nullItem());

    bridge.onMessage(
        event(
            caseChannelName(caseId, "work"),
            MessageType.DECLINE,
            "agent-1",
            String.valueOf(eventLogId),
            "decline reason"));

    verifyNoInteractions(runtime);
    verifyNoInteractions(eventBus);
  }

  // ---- Edge case: worker not in definition → constructs minimal worker ----

  @Test
  void declineWithWorkerNotInDefinition_constructsMinimalWorker() {
    UUID caseId = UUID.randomUUID();
    long eventLogId = 42L;
    String workerName = "removed-worker";
    CaseInstance instance = stubCaseInstance(caseId);
    stubEventLog(eventLogId, workerName, "binding-1", "hash-1");
    stubCaseInstance(caseId, instance);
    when(caseDefinitionRegistry.getCaseDefinition(any())).thenReturn(null);

    bridge.onMessage(
        event(
            caseChannelName(caseId, "work"),
            MessageType.DECLINE,
            "agent-1",
            String.valueOf(eventLogId),
            "declined"));

    ArgumentCaptor<WorkflowExecutionCompleted> captor =
        ArgumentCaptor.forClass(WorkflowExecutionCompleted.class);
    verify(eventBus).publish(eq(EventBusAddresses.WORKER_EXECUTION_FINISHED), captor.capture());
    assertThat(captor.getValue().worker().name()).isEqualTo(workerName);
  }

  // ---- Non-commitment types still ignored ----

  @Test
  void commandOnCaseChannel_isIgnored() {
    bridge.onMessage(
        event(
            caseChannelName(UUID.randomUUID(), "work"),
            MessageType.COMMAND,
            "sender-1",
            null,
            "command content"));
    verifyNoInteractions(runtime);
    verifyNoInteractions(eventBus);
  }

  @Test
  void eventOnCaseChannel_isIgnored() {
    bridge.onMessage(
        new MessageReceivedEvent(
            caseChannelName(UUID.randomUUID(), "observe"),
            UUID.randomUUID(),
            "test-tenancy",
            MessageType.EVENT,
            "sender-1",
            null,
            Instant.now(),
            null));
    verifyNoInteractions(runtime);
    verifyNoInteractions(eventBus);
  }

  // ---- Non-case channels ignored ----

  @Test
  void responseOnNonCaseChannel_isIgnored() {
    bridge.onMessage(
        event("general-channel", MessageType.RESPONSE, "sender-1", "corr-123", "content"));
    verifyNoInteractions(runtime);
    verifyNoInteractions(eventBus);
  }

  // ---- Signal payload shape (DONE/RESPONSE path) ----

  @SuppressWarnings("unchecked")
  @Test
  void signalPayload_containsExpectedFields() {
    UUID caseId = UUID.randomUUID();
    UUID channelId = UUID.randomUUID();
    String channelName = caseChannelName(caseId, "oversight");
    MessageReceivedEvent event =
        new MessageReceivedEvent(
            channelName,
            channelId,
            "test-tenancy",
            MessageType.RESPONSE,
            "human-operator",
            "corr-xyz",
            Instant.now(),
            "approved");

    bridge.onMessage(event);

    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(runtime)
        .signal(
            eq(caseId), eq(QhorusMessageSignalBridge.SIGNAL_PATH), captor.capture(), any(), any());
    Map<String, Object> payload = (Map<String, Object>) captor.getValue();

    assertThat(payload).containsEntry("messageType", "RESPONSE");
    assertThat(payload).containsEntry("content", "approved");
    assertThat(payload).containsEntry("senderId", "human-operator");
    assertThat(payload).containsEntry("channelId", channelId.toString());
    assertThat(payload).containsEntry("channelName", channelName);
    assertThat(payload).containsEntry("correlationId", "corr-xyz");
  }

  @Test
  void caseChannel_channelNameFactory_producesCorrectFormat() {
    UUID caseId = UUID.randomUUID();
    String name = CaseChannel.channelName(caseId, "work");
    assertThat(name).isEqualTo("case-" + caseId + "/work");
    assertThat(name).startsWith(CaseChannel.CASE_CHANNEL_PREFIX);
  }

  // ---- Helpers ----

  private static String caseChannelName(UUID caseId, String purpose) {
    return "case-" + caseId + "/" + purpose;
  }

  private static MessageReceivedEvent event(
      String channelName, MessageType type, String senderId, String correlationId, String content) {
    return new MessageReceivedEvent(
        channelName,
        UUID.randomUUID(),
        "test-tenancy",
        type,
        senderId,
        correlationId,
        Instant.now(),
        type == MessageType.EVENT ? null : content);
  }

  private void stubEventLog(
      long eventLogId, String workerName, String bindingName, String inputDataHash) {
    ObjectNode metadata = MAPPER.createObjectNode();
    metadata.put("workerName", workerName);
    metadata.put("bindingName", bindingName);
    metadata.put("inputDataHash", inputDataHash);
    EventLog log = new EventLog();
    log.setMetadata(metadata);
    when(eventLogRepository.findById(eventLogId)).thenReturn(Uni.createFrom().item(log));
  }

  private CaseInstance stubCaseInstance(UUID caseId) {
    CaseInstance instance = mock(CaseInstance.class);
    when(instance.getUuid()).thenReturn(caseId);
    CaseMetaModel meta = mock(CaseMetaModel.class);
    when(instance.getCaseMetaModel()).thenReturn(meta);
    return instance;
  }

  private void stubCaseInstance(UUID caseId, CaseInstance instance) {
    when(caseInstanceRepository.findByUuid(caseId)).thenReturn(Uni.createFrom().item(instance));
  }

  private void stubWorkerInDefinition(CaseInstance instance, String workerName) {
    Worker worker =
        Worker.builder()
            .name(workerName)
            .capabilityNames(java.util.Set.of())
            .function(new WorkerFunction.Sync(input -> WorkerResult.of(Map.of())))
            .build();
    CaseDefinition def = mock(CaseDefinition.class);
    when(def.getWorkers()).thenReturn(List.of(worker));
    when(caseDefinitionRegistry.getCaseDefinition(instance.getCaseMetaModel())).thenReturn(def);
  }
}
