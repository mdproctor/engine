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

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.CaseChannel;
import io.casehub.api.model.CaseDefinition;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkflowExecutionCompleted;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.qualifier.CrossTenant;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.ReactiveCrossTenantCaseInstanceRepository;
import io.casehub.engine.common.spi.ReactiveCrossTenantEventLogRepository;
import io.casehub.qhorus.api.gateway.MessageReceivedEvent;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerOutcome;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
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
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private final CaseHubRuntime runtime;

  @Inject @CrossTenant ReactiveCrossTenantEventLogRepository eventLogRepository;
  @Inject @CrossTenant ReactiveCrossTenantCaseInstanceRepository caseInstanceRepository;
  @Inject CaseDefinitionRegistry caseDefinitionRegistry;
  @Inject EventBus eventBus;

  @Inject
  public QhorusMessageSignalBridge(CaseHubRuntime runtime) {
    this.runtime = runtime;
  }

  public void onMessage(@ObservesAsync MessageReceivedEvent event) {
    if (!isCommitmentResolving(event.messageType())) return;

    UUID caseId = extractCaseId(event.channelName());
    if (caseId == null) return;

    if (isFailureOutcome(event.messageType()) && handleWorkerOutcome(caseId, event)) {
      return;
    }

    LOG.debugf(
        "Signalling case %s from channel '%s' (type=%s sender=%s)",
        caseId, event.channelName(), event.messageType(), event.senderId());

    runtime.signal(
        caseId,
        SIGNAL_PATH,
        buildPayload(event),
        event.channelId().toString(),
        event.correlationId());
  }

  private boolean handleWorkerOutcome(UUID caseId, MessageReceivedEvent event) {
    Long eventLogId = parseEventLogId(event.correlationId());
    if (eventLogId == null) return false;

    EventLog eventLog = eventLogRepository.findById(eventLogId).await().atMost(TIMEOUT);
    if (eventLog == null) return false;

    JsonNode metadata = eventLog.getMetadata();
    if (metadata == null || !metadata.has("workerName")) {
      LOG.errorf(
          "EventLog %d has no workerName in metadata — cannot route to failure cascade",
          eventLogId);
      return false;
    }

    CaseInstance caseInstance = caseInstanceRepository.findByUuid(caseId).await().atMost(TIMEOUT);
    if (caseInstance == null) {
      LOG.infof(
          "CaseInstance %s not found — case already terminal, skipping failure cascade", caseId);
      return true;
    }

    String workerName = metadata.get("workerName").asText();
    String bindingName = metadata.has("bindingName") ? metadata.get("bindingName").asText() : null;
    String idempotency =
        metadata.has("inputDataHash") ? metadata.get("inputDataHash").asText() : null;

    Worker worker = resolveWorker(caseInstance, workerName);

    WorkerOutcome outcome =
        event.messageType() == MessageType.DECLINE
            ? new WorkerOutcome.Declined(event.content())
            : new WorkerOutcome.Failed(event.content());

    LOG.infof(
        "Qhorus %s → WorkerOutcome for case %s worker '%s' binding '%s'",
        event.messageType(), caseId, workerName, bindingName);

    eventBus.publish(
        EventBusAddresses.WORKER_EXECUTION_FINISHED,
        new WorkflowExecutionCompleted(
            caseInstance, worker, idempotency, Map.of(), bindingName, outcome));

    return true;
  }

  private Worker resolveWorker(CaseInstance caseInstance, String workerName) {
    CaseDefinition def = caseDefinitionRegistry.getCaseDefinition(caseInstance.getCaseMetaModel());
    if (def != null && def.getWorkers() != null) {
      for (Worker w : def.getWorkers()) {
        if (w.name().equals(workerName)) return w;
      }
    }
    return Worker.builder().name(workerName).capabilityNames(Set.of()).noFunction().build();
  }

  private static Long parseEventLogId(String correlationId) {
    if (correlationId == null) return null;
    try {
      return Long.parseLong(correlationId);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static boolean isFailureOutcome(MessageType type) {
    return type == MessageType.DECLINE || type == MessageType.FAILURE;
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
