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
package io.casehub.engine.internal.engine.handler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.api.model.CaseChannel;
import io.casehub.api.spi.CaseChannelProvider;
import io.casehub.api.spi.routing.EscalationReason;
import io.casehub.engine.common.internal.event.AgentRoutingEscalationEvent;
import io.casehub.qhorus.api.message.MessageType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentRoutingEscalationHandlerTest {

  private CaseChannelProvider channelProvider;
  private AgentRoutingEscalationHandler handler;

  @BeforeEach
  void setUp() {
    channelProvider = mock(CaseChannelProvider.class);
    handler = new AgentRoutingEscalationHandler(channelProvider);
  }

  @Test
  void oversightChannelFound_postsQuery() {
    final UUID caseId = UUID.randomUUID();
    final String oversightName = CaseChannel.oversightChannelName(caseId);
    final CaseChannel channel = channel(oversightName);
    when(channelProvider.listChannels(caseId)).thenReturn(List.of(channel));

    handler.handle(
        new AgentRoutingEscalationEvent(
            caseId, "research", "research-binding", EscalationReason.BORDERLINE_STALEMATE));

    verify(channelProvider)
        .postToChannel(
            eq(channel),
            eq("casehub-engine"),
            any(String.class),
            eq(MessageType.QUERY),
            eq(null),
            eq(null));
  }

  @Test
  void oversightChannelNotFound_logsWarningAndDoesNotPost() {
    final UUID caseId = UUID.randomUUID();
    final CaseChannel unrelatedChannel = channel(CaseChannel.channelName(caseId, "work"));
    when(channelProvider.listChannels(caseId)).thenReturn(List.of(unrelatedChannel));

    handler.handle(
        new AgentRoutingEscalationEvent(
            caseId, "research", "research-binding", EscalationReason.BORDERLINE_STALEMATE));

    verify(channelProvider, never()).postToChannel(any(), any(), any(), any(), any(), any());
  }

  @Test
  void noChannelsAtAll_logsWarningAndDoesNotPost() {
    final UUID caseId = UUID.randomUUID();
    when(channelProvider.listChannels(caseId)).thenReturn(List.of());

    handler.handle(
        new AgentRoutingEscalationEvent(
            caseId, "research", "research-binding", EscalationReason.BORDERLINE_STALEMATE));

    verify(channelProvider, never()).postToChannel(any(), any(), any(), any(), any(), any());
  }

  @Test
  void noQualifiedAgent_channelFound_postsQueryWithNoQualifiedMessage() {
    final UUID caseId = UUID.randomUUID();
    final String oversightName = CaseChannel.oversightChannelName(caseId);
    final CaseChannel oversight = channel(oversightName);
    when(channelProvider.listChannels(caseId)).thenReturn(List.of(oversight));

    handler.handle(
        new AgentRoutingEscalationEvent(
            caseId, "merge-executor", "merge-binding", EscalationReason.NO_QUALIFIED_AGENT));

    verify(channelProvider)
        .postToChannel(
            eq(oversight),
            eq("casehub-engine"),
            contains("No trust-qualified agent"),
            eq(MessageType.QUERY),
            eq(null),
            eq(null));
  }

  @Test
  void noQualifiedAgent_noChannel_doesNotPostQueryButHandlesGracefully() {
    // Regression test: metric log fires unconditionally before channel search.
    // Even with no channel, handle() completes without error and posts nothing.
    final UUID caseId = UUID.randomUUID();
    when(channelProvider.listChannels(caseId)).thenReturn(List.of());

    handler.handle(
        new AgentRoutingEscalationEvent(
            caseId, "merge-executor", "merge-binding", EscalationReason.NO_QUALIFIED_AGENT));

    verify(channelProvider, never()).postToChannel(any(), any(), any(), any(), any(), any());
  }

  private static CaseChannel channel(final String name) {
    return new CaseChannel("ch-" + name.hashCode(), name, "oversight", "qhorus", null);
  }
}
