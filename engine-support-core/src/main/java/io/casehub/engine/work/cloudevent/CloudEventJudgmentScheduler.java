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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.model.JudgmentTarget;
import io.casehub.api.model.TaskStatus;
import io.casehub.api.spi.RiskDecision;
import io.casehub.api.spi.routing.RetrievedExperience;
import io.casehub.engine.common.internal.model.PlanItemSaveRequest;
import io.casehub.engine.common.internal.model.TargetType;
import io.casehub.engine.common.spi.CallerRefParser;
import io.casehub.engine.common.spi.JudgmentPayload;
import io.casehub.engine.common.spi.JudgmentRequest;
import io.casehub.engine.common.spi.JudgmentScheduleRequest;
import io.casehub.engine.common.spi.JudgmentScheduler;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.engine.planning.plan.PlanItem;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import io.casehub.work.api.WorkCloudEventTypes;
import io.casehub.worker.api.PlannedAction;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Unified CloudEvent scheduler for both judgment bindings and action gates. Dispatches on {@link
 * JudgmentPayload} type: {@link JudgmentPayload.BindingPayload} emits a judgment CloudEvent, {@link
 * JudgmentPayload.GatePayload} emits a gate approval CloudEvent.
 *
 * <p>Refs engine#1010, engine#994.
 */
@ApplicationScoped
public class CloudEventJudgmentScheduler implements JudgmentScheduler {

  private static final Logger LOG = Logger.getLogger(CloudEventJudgmentScheduler.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject BlackboardRegistry registry;
  @Inject PlanItemStore planItemStore;
  @Inject Event<CloudEvent> cloudEventEmitter;

  @SuppressWarnings("deprecation")
  @Override
  public void schedule(JudgmentScheduleRequest request) {
    schedule(
        new JudgmentRequest(
            request.caseId(),
            request.tenancyId(),
            request.bindingName(),
            new JudgmentPayload.BindingPayload(
                request.target(),
                request.inputData(),
                request.resolutionTypeName(),
                request.expiresAtDeadline(),
                request.caseBudgetDeadline(),
                request.resolvedTitle(),
                request.resolvedScope(),
                request.resolvedCandidateGroups(),
                request.resolvedCandidateUsers(),
                request.payloadTypeName(),
                request.experiences(),
                request.candidateScores())));
  }

  @Override
  public void schedule(JudgmentRequest request) {
    switch (request.payload()) {
      case JudgmentPayload.BindingPayload bp -> scheduleBinding(request, bp);
      case JudgmentPayload.GatePayload gp -> scheduleGate(request, gp);
    }
  }

  private void scheduleBinding(JudgmentRequest request, JudgmentPayload.BindingPayload bp) {
    var plan = registry.get(request.caseId()).orElse(null);
    if (plan == null) {
      LOG.warnf(
          "No CasePlanModel for caseId=%s — case may not use blackboard or has completed",
          request.caseId());
      return;
    }

    PlanItem item = plan.getPlanItemByBindingName(request.bindingName()).orElse(null);
    if (item == null) {
      LOG.warnf(
          "PlanItem for binding '%s' not found in case %s",
          request.bindingName(), request.caseId());
      return;
    }

    if (item.getStatus() != TaskStatus.DISPATCHING) {
      LOG.warnf(
          "PlanItem for binding '%s' case %s is not DISPATCHING (status=%s) — skipping",
          request.bindingName(), request.caseId(), item.getStatus());
      item.revertDispatching();
      return;
    }

    String callerRef = CallerRefParser.encodeJudgment(request.caseId(), request.bindingName());
    JudgmentTarget target = bp.target();

    ObjectNode data = MAPPER.createObjectNode();
    data.put("callerRef", callerRef);

    if (target.prompt() != null) {
      data.put("prompt", target.prompt());
    }

    String title = bp.resolvedTitle() != null ? bp.resolvedTitle() : target.title();
    if (title != null) {
      data.put("title", title);
    }

    String scope = bp.resolvedScope() != null ? bp.resolvedScope() : target.scope();
    if (scope != null) {
      data.put("scope", scope);
    }

    String candidateGroups = toCsv(bp.resolvedCandidateGroups());
    if (candidateGroups != null) {
      data.put("candidateGroups", candidateGroups);
    }

    String candidateUsers = toCsv(bp.resolvedCandidateUsers());
    if (candidateUsers != null) {
      data.put("candidateUsers", candidateUsers);
    }

    String payload = serializeToJson(bp.inputData());
    if (payload != null) {
      data.put("payload", payload);
    }

    Instant effectiveDeadline = earliestOf(bp.expiresAtDeadline(), bp.caseBudgetDeadline());
    if (effectiveDeadline == null && target.expiresIn() != null) {
      effectiveDeadline = Instant.now().plus(target.expiresIn());
    }
    if (effectiveDeadline != null) {
      data.put("expiresAt", effectiveDeadline.toString());
    }

    if (bp.payloadTypeName() != null) {
      data.put("payloadTypeName", bp.payloadTypeName());
    }
    if (bp.resolutionTypeName() != null) {
      data.put("resolutionTypeName", bp.resolutionTypeName());
    }

    if (!target.evidenceRequirements().isEmpty()) {
      data.put("evidenceRequirements", String.join(",", target.evidenceRequirements()));
    }

    String scores = serializeToJson(bp.candidateScores());
    if (scores != null) {
      data.put("candidateScores", scores);
    }
    String experiences = serializeExperiences(bp.experiences());
    if (experiences != null) {
      data.put("routingExperiences", experiences);
    }

    if (target.outcomes() != null && !target.outcomes().isEmpty()) {
      var outcomes = data.putArray("permittedOutcomes");
      for (String outcome : target.outcomes()) {
        outcomes.addObject().put("name", outcome);
      }
    }

    CloudEvent cloudEvent =
        CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withType(WorkCloudEventTypes.CREATE)
            .withSource(URI.create("/engine/cases/" + request.caseId() + "/judgment"))
            .withDataContentType("application/json")
            .withData(data.toString().getBytes())
            .withExtension(WorkCloudEventTypes.EXT_TENANCY_ID, request.tenancyId())
            .build();

    cloudEventEmitter.fireAsync(cloudEvent);

    planItemStore.save(
        PlanItemSaveRequest.primitive(
            request.caseId(),
            item.id(),
            item.getBindingName(),
            TaskStatus.DELEGATED,
            item.getCreatedAt(),
            TargetType.JUDGMENT,
            null,
            request.tenancyId(),
            null,
            null,
            null),
        request.tenancyId());
    item.markDelegated();

    LOG.infof(
        "CloudEvent emitted for Judgment binding callerRef=%s caseId=%s",
        callerRef, request.caseId());
  }

  private void scheduleGate(JudgmentRequest request, JudgmentPayload.GatePayload gp) {
    RiskDecision.GateRequired gate = gp.gateRequired();

    if (gate.quorum() != null) {
      LOG.warnf(
          "Quorum gates not yet supported via CloudEvent — skipping gate for caseId=%s gateId=%d",
          request.caseId(), gp.gateId());
      return;
    }

    String callerRef = CallerRefParser.encodeGate(request.caseId(), gp.gateId());
    PlannedAction action = gp.plannedAction();

    ObjectNode data = MAPPER.createObjectNode();
    data.put("callerRef", callerRef);
    data.put("title", gate.reason());

    String candidateGroups = toCsv(gp.resolvedCandidateGroups());
    if (candidateGroups != null) {
      data.put("candidateGroups", candidateGroups);
    }

    if (gate.scope() != null) {
      data.put("scope", gate.scope());
    }

    if (gp.resolutionTypeName() != null) {
      data.put("resolutionTypeName", gp.resolutionTypeName());
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
            .withSource(URI.create("/engine/cases/" + request.caseId() + "/gates/" + gp.gateId()))
            .withDataContentType("application/json")
            .withData(data.toString().getBytes())
            .withExtension(WorkCloudEventTypes.EXT_TENANCY_ID, request.tenancyId())
            .build();

    cloudEventEmitter.fireAsync(cloudEvent);

    LOG.infof(
        "CloudEvent emitted for ActionGate callerRef=%s caseId=%s", callerRef, request.caseId());
  }

  private static @Nullable Instant earliestOf(@Nullable Instant a, @Nullable Instant b) {
    if (a == null) return b;
    if (b == null) return a;
    return a.isBefore(b) ? a : b;
  }

  private static @Nullable String toCsv(@Nullable Set<String> values) {
    if (values == null || values.isEmpty()) return null;
    return String.join(",", values);
  }

  private @Nullable String serializeToJson(@Nullable Object value) {
    if (value == null) return null;
    if (value instanceof Map<?, ?> m && m.isEmpty()) return null;
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      LOG.warnf(e, "Failed to serialize to JSON — using null");
      return null;
    }
  }

  private @Nullable String serializeExperiences(@Nullable List<RetrievedExperience> experiences) {
    if (experiences == null || experiences.isEmpty()) return null;
    try {
      return MAPPER.writeValueAsString(experiences);
    } catch (JsonProcessingException e) {
      LOG.warnf(e, "Failed to serialize routing experiences — using null");
      return null;
    }
  }
}
