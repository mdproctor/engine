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
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.qhorus.api.message.MessageType;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Handles agent routing escalation events. When all trust-eligible candidates for a capability are
 * borderline, this handler posts a QUERY to the case's oversight channel so a human supervisor can
 * make the routing decision.
 *
 * <p>PlanItem state during escalation: {@link
 * io.casehub.engine.planning.handler.PlanItemEscalationHandler} marks the PlanItem ESCALATED on the
 * same event bus fan-out. The response-handling loop (human COMMAND response → re-trigger routing)
 * is tracked in engine#383.
 *
 * <p>If no oversight channel is open (e.g. in deployments using the no-op channel provider), the
 * escalation is logged with a {@code [METRIC:escalation.no-oversight-channel]} prefix for log-based
 * alerting. This is expected behavior in dev/test environments.
 */
@ApplicationScoped
public class AgentRoutingEscalationHandler {

  private static final Logger LOG = Logger.getLogger(AgentRoutingEscalationHandler.class);

  private final CaseChannelProvider channelProvider;

  @Inject
  public AgentRoutingEscalationHandler(final CaseChannelProvider channelProvider) {
    this.channelProvider = channelProvider;
  }

  @ConsumeEvent(value = EventBusAddresses.AGENT_ROUTING_ESCALATION, blocking = true)
  public void handle(final AgentRoutingEscalationEvent event) {
    // Metric log fires unconditionally — before channel search
    // Fires even when no oversight channel is open (that scenario is the most critical to alert on)
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

  private void postQuery(final CaseChannel channel, final AgentRoutingEscalationEvent event) {
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
