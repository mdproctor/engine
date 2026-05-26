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

import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.CaseChannel;
import io.casehub.qhorus.api.gateway.MessageReceivedEvent;
import io.casehub.qhorus.api.message.MessageType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Bridges Qhorus {@link MessageReceivedEvent}s on case channels into CaseHub signals.
 *
 * <p>Observes all messages dispatched via Qhorus's {@code InProcessMessageBus}. Filters to
 * commitment-resolving types (RESPONSE, DONE, DECLINE, FAILURE) on channels that follow the {@code
 * "case-{caseId}/{purpose}"} naming convention. For matching messages, calls {@link
 * CaseHubRuntime#signal} with path {@value #SIGNAL_PATH} to update the case context and trigger
 * binding re-evaluation — including for WAITING cases when the blackboard is active.
 *
 * <p>This bean is a dead observer in deployments without {@code casehub-qhorus-runtime} on the
 * classpath — {@code InProcessMessageBus} is never instantiated, so no events fire. Zero cost.
 *
 * <p>Case definitions that want to react to human channel messages bind on {@code
 * contextChange(".channelMessage")}. The signal value is a map containing: {@code messageType},
 * {@code content}, {@code senderId}, {@code channelId}, {@code channelName}, and optionally {@code
 * correlationId}.
 */
@ApplicationScoped
public class QhorusMessageSignalBridge {

  /** Context path written by this bridge for channel messages. */
  public static final String SIGNAL_PATH = "channelMessage";

  private static final Logger LOG = Logger.getLogger(QhorusMessageSignalBridge.class);

  private final CaseHubRuntime runtime;

  @Inject
  public QhorusMessageSignalBridge(CaseHubRuntime runtime) {
    this.runtime = runtime;
  }

  public void onMessage(@ObservesAsync MessageReceivedEvent event) {
    if (!isCommitmentResolving(event.messageType())) return;

    UUID caseId = extractCaseId(event.channelName());
    if (caseId == null) return;

    LOG.debugf(
        "Signalling case %s from channel '%s' (type=%s sender=%s)",
        caseId, event.channelName(), event.messageType(), event.senderId());

    runtime.signal(caseId, SIGNAL_PATH, buildPayload(event));
  }

  private static boolean isCommitmentResolving(MessageType type) {
    return type == MessageType.RESPONSE
        || type == MessageType.DONE
        || type == MessageType.DECLINE
        || type == MessageType.FAILURE;
  }

  private static UUID extractCaseId(String channelName) {
    if (channelName == null || !channelName.startsWith(CaseChannel.CASE_CHANNEL_PREFIX))
      return null;
    int slash = channelName.indexOf('/', CaseChannel.CASE_CHANNEL_PREFIX.length());
    String uuidStr =
        slash > 0
            ? channelName.substring(CaseChannel.CASE_CHANNEL_PREFIX.length(), slash)
            : channelName.substring(CaseChannel.CASE_CHANNEL_PREFIX.length());
    try {
      return UUID.fromString(uuidStr);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static Map<String, Object> buildPayload(MessageReceivedEvent event) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("messageType", event.messageType().name());
    payload.put("content", event.content());
    payload.put("senderId", event.senderId());
    payload.put("channelId", event.channelId().toString());
    payload.put("channelName", event.channelName());
    if (event.correlationId() != null) {
      payload.put("correlationId", event.correlationId());
    }
    return payload;
  }
}
