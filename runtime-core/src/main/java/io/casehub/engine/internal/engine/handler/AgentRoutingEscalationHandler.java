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

import io.casehub.api.model.CaseChannel;
import io.casehub.api.spi.CaseChannelProvider;
import io.casehub.api.spi.routing.EscalationReason;
import io.casehub.engine.common.internal.event.AgentRoutingEscalationEvent;
import io.casehub.qhorus.api.message.MessageType;
import java.util.List;
import org.jboss.logging.Logger;

public class AgentRoutingEscalationHandler {

  private static final Logger LOG = Logger.getLogger(AgentRoutingEscalationHandler.class);

  private final CaseChannelProvider channelProvider;

  public AgentRoutingEscalationHandler(CaseChannelProvider channelProvider) {
    this.channelProvider = channelProvider;
  }

  public void handle(AgentRoutingEscalationEvent event) {
    if (event.reason() == EscalationReason.NO_QUALIFIED_AGENT) {
      LOG.warnf(
          "[METRIC:escalation.no-qualified-agent] caseId=%s capability=%s binding=%s"
              + " — bootstrap guard fired; no trust-qualified agent available.",
          event.caseId(), event.capabilityName(), event.bindingName());
    }

    final String oversightName = CaseChannel.oversightChannelName(event.caseId());
    final List<CaseChannel> channels = channelProvider.listChannels(event.caseId());

    channels.stream()
        .filter(c -> oversightName.equals(c.name()))
        .findFirst()
        .ifPresentOrElse(
            channel -> postQuery(channel, event),
            () ->
                LOG.warnf(
                    "[METRIC:escalation.no-oversight-channel] caseId=%s capability=%s binding=%s"
                        + " — escalation absorbed; no oversight channel open."
                        + " PlanItem remains ESCALATED. engine#383 tracks response handling.",
                    event.caseId(), event.capabilityName(), event.bindingName()));
  }

  private void postQuery(CaseChannel channel, AgentRoutingEscalationEvent event) {
    final String message =
        switch (event.reason()) {
          case BORDERLINE_STALEMATE ->
              String.format(
                  "All agent candidates for capability '%s' (binding: '%s') are borderline."
                      + " Human oversight required: please select an agent or approve the next"
                      + " best available agent.",
                  event.capabilityName(), event.bindingName());
          case NO_QUALIFIED_AGENT ->
              String.format(
                  "No trust-qualified agent is available for capability '%s' (binding: '%s')."
                      + " Routing policy requires an agent with established trust history."
                      + " Human routing required.",
                  event.capabilityName(), event.bindingName());
        };

    channelProvider.postToChannel(
        channel, "casehub-engine", message, MessageType.QUERY, null, null, null);

    LOG.infof(
        "Agent routing escalation: QUERY posted to oversight channel '%s' for"
            + " caseId=%s capability=%s reason=%s",
        channel.name(), event.caseId(), event.capabilityName(), event.reason());
  }
}
