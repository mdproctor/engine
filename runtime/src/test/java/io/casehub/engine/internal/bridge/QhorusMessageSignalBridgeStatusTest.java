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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.CaseChannel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.ReactiveCrossTenantCaseInstanceRepository;
import io.casehub.engine.common.spi.ReactiveCrossTenantEventLogRepository;
import io.casehub.qhorus.api.gateway.MessageReceivedEvent;
import io.casehub.qhorus.api.message.MessageType;
import io.vertx.mutiny.core.eventbus.EventBus;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QhorusMessageSignalBridgeStatusTest {

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

  @Test
  void statusMessage_signalsCaseWithStatusReport() {
    UUID caseId = UUID.randomUUID();
    UUID channelId = UUID.randomUUID();
    String channelName = CaseChannel.channelName(caseId, "work");
    Instant occurredAt = Instant.now();

    MessageReceivedEvent event =
        new MessageReceivedEvent(
            null,
            channelName,
            channelId,
            "test-tenancy",
            MessageType.STATUS,
            "agent-123",
            "corr-456",
            occurredAt,
            "Processing document 3 of 10",
            null);

    bridge.onMessage(event);

    // Verify signal was called with statusReport key
    ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
    verify(runtime)
        .signal(
            eq(caseId),
            keyCaptor.capture(),
            valueCaptor.capture(),
            eq(channelId.toString()),
            eq("corr-456"));

    // Verify signal key is "statusReport"
    assertThat(keyCaptor.getValue()).isEqualTo("statusReport");

    // Verify signal payload contains from, content, timestamp
    @SuppressWarnings("unchecked")
    Map<String, Object> payload = (Map<String, Object>) valueCaptor.getValue();
    assertThat(payload).containsEntry("from", "agent-123");
    assertThat(payload).containsEntry("content", "Processing document 3 of 10");
    assertThat(payload).containsEntry("timestamp", occurredAt);

    // Verify no EventLog/EventBus interactions (STATUS is not commitment-resolving)
    verifyNoInteractions(eventLogRepository);
    verifyNoInteractions(caseInstanceRepository);
    verifyNoInteractions(eventBus);
  }

  @Test
  void statusOnNonCaseChannel_isIgnored() {
    MessageReceivedEvent event =
        new MessageReceivedEvent(
            null,
            "general-channel",
            UUID.randomUUID(),
            "test-tenancy",
            MessageType.STATUS,
            "sender-1",
            "corr-123",
            Instant.now(),
            "status content",
            null);

    bridge.onMessage(event);

    verifyNoInteractions(runtime);
    verifyNoInteractions(eventBus);
  }
}
