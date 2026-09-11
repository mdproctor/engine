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
package io.casehub.engine.work.cloudevent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.spi.RiskDecision;
import io.casehub.engine.common.spi.ActionGateScheduleRequest;
import io.casehub.engine.common.spi.ActionGateScheduler;
import io.casehub.engine.common.spi.CallerRefParser;
import io.casehub.work.api.WorkCloudEventTypes;
import io.casehub.worker.api.PlannedAction;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CloudEventActionGateScheduler implements ActionGateScheduler {

  private static final Logger LOG = Logger.getLogger(CloudEventActionGateScheduler.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject Event<CloudEvent> cloudEventEmitter;

  @Override
  public void schedule(ActionGateScheduleRequest request) {
    RiskDecision.GateRequired gate = request.gateRequired();

    if (gate.quorum() != null) {
      LOG.warnf(
          "Quorum gates not yet supported via CloudEvent — skipping gate for caseId=%s gateId=%d",
          request.caseId(), request.gateId());
      return;
    }

    String callerRef = CallerRefParser.encodeGate(request.caseId(), request.gateId());
    PlannedAction action = request.plannedAction();

    ObjectNode data = MAPPER.createObjectNode();
    data.put("callerRef", callerRef);
    data.put("title", gate.reason());

    String candidateGroups = toCsv(request.resolvedCandidateGroups());
    if (candidateGroups != null) {
      data.put("candidateGroups", candidateGroups);
    }

    if (gate.scope() != null) {
      data.put("scope", gate.scope());
    }

    if (request.resolutionTypeName() != null) {
      data.put("resolutionTypeName", request.resolutionTypeName());
    }

    ObjectNode payloadObj = MAPPER.createObjectNode();
    payloadObj.put("description", action.description());
    payloadObj.put("actionType", action.actionType());
    payloadObj.put("reversible", gate.reversible());
    if (action.parameters() != null && !action.parameters().isEmpty()) {
      payloadObj.set("context", MAPPER.valueToTree(action.parameters()));
    }
    data.put("payload", payloadObj.toString());

    if (gate.expiresIn() != null) {
      Instant expiresAt = Instant.now().plus(gate.expiresIn());
      data.put("expiresAt", expiresAt.toString());
    }

    CloudEvent cloudEvent =
        CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withType(WorkCloudEventTypes.CREATE)
            .withSource(
                URI.create("/engine/cases/" + request.caseId() + "/gates/" + request.gateId()))
            .withDataContentType("application/json")
            .withData(data.toString().getBytes())
            .withExtension(WorkCloudEventTypes.EXT_TENANCY_ID, request.tenancyId())
            .build();

    cloudEventEmitter.fireAsync(cloudEvent);

    LOG.infof(
        "CloudEvent emitted for ActionGate callerRef=%s caseId=%s", callerRef, request.caseId());
  }

  private static String toCsv(Set<String> values) {
    if (values == null || values.isEmpty()) return null;
    return String.join(",", values);
  }
}
