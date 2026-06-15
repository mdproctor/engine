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

import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.CaseChannel;
import io.casehub.qhorus.api.gateway.MessageReceivedEvent;
import io.casehub.qhorus.api.message.MessageType;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QhorusMessageSignalBridgeTest {

  private CaseHubRuntime runtime;
  private QhorusMessageSignalBridge bridge;

  @BeforeEach
  void setUp() {
    runtime = mock(CaseHubRuntime.class);
    bridge = new QhorusMessageSignalBridge(runtime);
  }

  // ---- Commitment-resolving types that should signal the engine ----

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

  @Test
  void declineOnCaseChannel_signalsEngine() {
    UUID caseId = UUID.randomUUID();
    MessageReceivedEvent event =
        event(
            caseChannelName(caseId, "oversight"),
            MessageType.DECLINE,
            "human-1",
            "corr-456",
            "decline reason");

    bridge.onMessage(event);

    verify(runtime)
        .signal(
            eq(caseId), eq(QhorusMessageSignalBridge.SIGNAL_PATH), any(Map.class), any(), any());
  }

  @Test
  void failureOnCaseChannel_signalsEngine() {
    UUID caseId = UUID.randomUUID();
    MessageReceivedEvent event =
        event(
            caseChannelName(caseId, "work"),
            MessageType.FAILURE,
            "agent-1",
            "corr-789",
            "failure reason");

    bridge.onMessage(event);

    verify(runtime)
        .signal(
            eq(caseId), eq(QhorusMessageSignalBridge.SIGNAL_PATH), any(Map.class), any(), any());
  }

  // ---- Non-commitment types that should NOT signal ----

  @Test
  void commandOnCaseChannel_isIgnored() {
    UUID caseId = UUID.randomUUID();
    MessageReceivedEvent event =
        event(
            caseChannelName(caseId, "work"),
            MessageType.COMMAND,
            "sender-1",
            null,
            "command content");

    bridge.onMessage(event);

    verifyNoInteractions(runtime);
  }

  @Test
  void queryOnCaseChannel_isIgnored() {
    UUID caseId = UUID.randomUUID();
    MessageReceivedEvent event =
        event(
            caseChannelName(caseId, "observe"),
            MessageType.QUERY,
            "sender-1",
            null,
            "query content");

    bridge.onMessage(event);

    verifyNoInteractions(runtime);
  }

  @Test
  void statusOnCaseChannel_isIgnored() {
    UUID caseId = UUID.randomUUID();
    MessageReceivedEvent event =
        event(
            caseChannelName(caseId, "observe"),
            MessageType.STATUS,
            "sender-1",
            null,
            "status content");

    bridge.onMessage(event);

    verifyNoInteractions(runtime);
  }

  @Test
  void handoffOnCaseChannel_isIgnored() {
    UUID caseId = UUID.randomUUID();
    MessageReceivedEvent event =
        event(
            caseChannelName(caseId, "work"),
            MessageType.HANDOFF,
            "sender-1",
            "corr-123",
            "handoff content");

    bridge.onMessage(event);

    verifyNoInteractions(runtime);
  }

  @Test
  void eventOnCaseChannel_isIgnored() {
    // EVENT has null content per PP-20260508-90428f — telemetry only, not actionable outcome.
    UUID caseId = UUID.randomUUID();
    MessageReceivedEvent event =
        new MessageReceivedEvent(
            caseChannelName(caseId, "observe"),
            UUID.randomUUID(),
            "test-tenancy",
            MessageType.EVENT,
            "sender-1",
            null,
            null);

    bridge.onMessage(event);

    verifyNoInteractions(runtime);
  }

  // ---- Non-case channels ignored ----

  @Test
  void responseOnNonCaseChannel_isIgnored() {
    MessageReceivedEvent event =
        event("general-channel", MessageType.RESPONSE, "sender-1", "corr-123", "content");

    bridge.onMessage(event);

    verifyNoInteractions(runtime);
  }

  @Test
  void responseOnChannelWithInvalidUuid_isIgnored() {
    MessageReceivedEvent event =
        event("case-not-a-uuid/work", MessageType.RESPONSE, "sender-1", "corr-123", "content");

    bridge.onMessage(event);

    verifyNoInteractions(runtime);
  }

  @Test
  void nullChannelName_isIgnored() {
    MessageReceivedEvent event =
        event(null, MessageType.RESPONSE, "sender-1", "corr-123", "content");

    bridge.onMessage(event);

    verifyNoInteractions(runtime);
  }

  // ---- Signal payload shape ----

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

  @SuppressWarnings("unchecked")
  @Test
  void nullCorrelationId_notIncludedInPayload() {
    UUID caseId = UUID.randomUUID();
    UUID channelId = UUID.randomUUID();
    MessageReceivedEvent event =
        new MessageReceivedEvent(
            caseChannelName(caseId, "work"),
            channelId,
            "test-tenancy",
            MessageType.DONE,
            "agent-1",
            null,
            "done");
    bridge = new QhorusMessageSignalBridge(runtime);

    bridge.onMessage(event);

    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(runtime)
        .signal(
            eq(caseId), eq(QhorusMessageSignalBridge.SIGNAL_PATH), captor.capture(), any(), any());
    Map<String, Object> payload = (Map<String, Object>) captor.getValue();

    assertThat(payload).doesNotContainKey("correlationId");
  }

  // ---- Channel naming constant ----

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
        type == MessageType.EVENT ? null : content);
  }
}
