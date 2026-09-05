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
package io.casehub.engine.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.engine.common.spi.InboundWorkItemRequest;
import io.casehub.engine.common.spi.InboundWorkItemScheduler;
import io.casehub.qhorus.api.gateway.MessageReceivedEvent;
import io.casehub.qhorus.api.message.MessageType;
import jakarta.enterprise.inject.Instance;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InboundWorkItemBridgeTest {

  @Mock InboundWorkItemScheduler scheduler;
  @Mock InboundWorkItemPolicy policyImpl;
  @Mock Instance<InboundWorkItemPolicy> policy;

  private InboundWorkItemBridge bridge;

  @BeforeEach
  void setUp() {
    bridge = new InboundWorkItemBridge();
    bridge.scheduler = scheduler;
    bridge.policy = policy;
  }

  private void policyActive() {
    when(policy.isUnsatisfied()).thenReturn(false);
    when(policy.get()).thenReturn(policyImpl);
  }

  private static MessageReceivedEvent commandEvent(String tenancyId) {
    return new MessageReceivedEvent(
        null,
        "test-channel",
        UUID.randomUUID(),
        tenancyId,
        MessageType.COMMAND,
        "sender-id",
        null,
        null,
        "corr-id-1",
        Instant.now(),
        "{\"msg\":\"hello\"}",
        null,
        null);
  }

  private static InboundWorkItemRequest minimalRequest(String tenancyId) {
    return InboundWorkItemRequest.builder().title("Test WorkItem").tenancyId(tenancyId).build();
  }

  @Test
  void decide_returnsRequest_schedulerInvoked() {
    policyActive();
    when(policyImpl.decide(any())).thenReturn(Optional.of(minimalRequest("tenant-a")));

    bridge.onMessage(commandEvent("tenant-a"));

    verify(scheduler).schedule(any(InboundWorkItemRequest.class));
  }

  @Test
  void decide_returnsRequest_createdByIsStampedByBridge() {
    policyActive();
    when(policyImpl.decide(any())).thenReturn(Optional.of(minimalRequest("tenant-a")));

    bridge.onMessage(commandEvent("tenant-a"));

    ArgumentCaptor<InboundWorkItemRequest> captor =
        ArgumentCaptor.forClass(InboundWorkItemRequest.class);
    verify(scheduler).schedule(captor.capture());
    assertThat(captor.getValue().createdBy()).isEqualTo("casehub-engine-inbound");
  }

  @Test
  void decide_returnsRequest_tenancyIdFromEvent() {
    policyActive();
    when(policyImpl.decide(any())).thenReturn(Optional.of(minimalRequest("tenant-a")));

    bridge.onMessage(commandEvent("tenant-xyz"));

    ArgumentCaptor<InboundWorkItemRequest> captor =
        ArgumentCaptor.forClass(InboundWorkItemRequest.class);
    verify(scheduler).schedule(captor.capture());
    assertThat(captor.getValue().tenancyId()).isEqualTo("tenant-xyz");
  }

  @Test
  void decide_returnsEmpty_schedulerNotInvoked() {
    policyActive();
    when(policyImpl.decide(any())).thenReturn(Optional.empty());

    bridge.onMessage(commandEvent("tenant-a"));

    verify(scheduler, never()).schedule(any());
  }

  @Test
  void decide_policyReceivesEvent() {
    policyActive();
    when(policyImpl.decide(any())).thenReturn(Optional.empty());

    MessageReceivedEvent event = commandEvent("tenant-b");
    bridge.onMessage(event);

    ArgumentCaptor<MessageReceivedEvent> captor =
        ArgumentCaptor.forClass(MessageReceivedEvent.class);
    verify(policyImpl).decide(captor.capture());
    assertThat(captor.getValue().channelName()).isEqualTo("test-channel");
    assertThat(captor.getValue().messageType()).isEqualTo(MessageType.COMMAND);
    assertThat(captor.getValue().senderId()).isEqualTo("sender-id");
    assertThat(captor.getValue().tenancyId()).isEqualTo("tenant-b");
  }

  @Test
  void decide_throws_schedulerNotInvoked() {
    policyActive();
    when(policyImpl.decide(any())).thenThrow(new RuntimeException("policy exploded"));

    bridge.onMessage(commandEvent("tenant-a"));

    verify(scheduler, never()).schedule(any());
  }

  @Test
  void decide_returnsRequest_fieldsPreserved() {
    policyActive();
    InboundWorkItemRequest request =
        InboundWorkItemRequest.builder()
            .title("Important Task")
            .tenancyId("tenant-a")
            .description("Do this thing")
            .callerRef("case:abc/pi:123")
            .build();
    when(policyImpl.decide(any())).thenReturn(Optional.of(request));

    bridge.onMessage(commandEvent("tenant-a"));

    ArgumentCaptor<InboundWorkItemRequest> captor =
        ArgumentCaptor.forClass(InboundWorkItemRequest.class);
    verify(scheduler).schedule(captor.capture());
    assertThat(captor.getValue().title()).isEqualTo("Important Task");
    assertThat(captor.getValue().description()).isEqualTo("Do this thing");
    assertThat(captor.getValue().callerRef()).isEqualTo("case:abc/pi:123");
  }

  @Test
  void noPolicy_messageIgnored() {
    when(policy.isUnsatisfied()).thenReturn(true);

    bridge.onMessage(commandEvent("tenant-a"));

    verify(scheduler, never()).schedule(any());
  }
}
