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
package io.casehub.workadapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.engine.common.internal.event.ActionGateScheduleEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.work.api.WorkItemCreateRequest;
import io.casehub.work.runtime.service.WorkItemService;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import org.jboss.logging.Logger;

/**
 * Creates a WorkItem when an action gate fires.
 *
 * <p>Consumes {@link ActionGateScheduleEvent} on {@link EventBusAddresses#ACTION_GATE_SCHEDULE}.
 * Creates a WorkItem with:
 *
 * <ul>
 *   <li>callerRef: {@code "case:{caseId}/gate:{gateId}"} — routes back to {@link
 *       ActionGateCompletionApplier}
 *   <li>title: {@link io.casehub.api.spi.RiskDecision.GateRequired#reason()}
 *   <li>candidateGroups: from the gate decision (CSV)
 *   <li>expiresAt: from {@code expiresIn} (if set)
 *   <li>payload: full PlannedAction as JSON (approver sees what the agent proposed)
 * </ul>
 *
 * <p>No PlanItem, no BlackboardRegistry — gate WorkItems are not backed by CMMN plan items. Refs
 * engine#402.
 */
@ApplicationScoped
public class ActionGateWorkItemHandler {

  private static final Logger LOG = Logger.getLogger(ActionGateWorkItemHandler.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject WorkItemService workItemService;

  @ConsumeEvent(value = EventBusAddresses.ACTION_GATE_SCHEDULE, blocking = true)
  @Transactional
  public void onActionGateSchedule(final ActionGateScheduleEvent event) {
    final String callerRef = GateCallerRef.encode(event.caseId(), event.gateId());
    final Instant expiresAt =
        event.gateRequired().expiresIn() != null
            ? Instant.now().plus(event.gateRequired().expiresIn())
            : null;

    final WorkItemCreateRequest request =
        WorkItemCreateRequest.builder()
            .title(event.gateRequired().reason())
            .candidateGroups(candidateGroupsCsv(event.gateRequired().candidateGroups()))
            .createdBy("casehub-engine")
            .payload(buildPayload(event))
            .expiresAt(expiresAt)
            .callerRef(callerRef)
            .scope(event.gateRequired().scope())
            .build();

    workItemService.create(request);
    LOG.infof(
        "Gate WorkItem created: caseId=%s gateId=%d callerRef=%s expiresAt=%s",
        event.caseId(), event.gateId(), callerRef, expiresAt);
  }

  private static String candidateGroupsCsv(final java.util.List<String> groups) {
    if (groups == null || groups.isEmpty()) return null;
    return String.join(",", groups);
  }

  private static String buildPayload(final ActionGateScheduleEvent event) {
    final ObjectNode root = MAPPER.createObjectNode();
    root.put("description", event.plannedAction().description());
    root.put("actionType", event.plannedAction().actionType());
    root.put("reversible", event.gateRequired().reversible());
    root.set("context", MAPPER.valueToTree(event.plannedAction().parameters()));
    try {
      return MAPPER.writeValueAsString(root);
    } catch (final JsonProcessingException e) {
      LOG.warnf(e, "Failed to serialize gate payload for gateId=%d — using null", event.gateId());
      return null;
    }
  }
}
