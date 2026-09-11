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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.model.HumanTaskTarget;
import io.casehub.api.model.TaskStatus;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.api.spi.routing.RetrievedExperience;
import io.casehub.engine.common.internal.model.PlanItemSaveRequest;
import io.casehub.engine.common.internal.model.TargetType;
import io.casehub.engine.common.spi.CallerRefParser;
import io.casehub.engine.common.spi.HumanTaskScheduleRequest;
import io.casehub.engine.common.spi.HumanTaskScheduler;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.engine.planning.plan.PlanItem;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import io.casehub.work.api.WorkCloudEventTypes;
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

@ApplicationScoped
@Deprecated(forRemoval = true)
@SuppressWarnings("removal")
public class CloudEventHumanTaskScheduler implements HumanTaskScheduler {

  private static final Logger LOG = Logger.getLogger(CloudEventHumanTaskScheduler.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject BlackboardRegistry registry;
  @Inject PlanItemStore planItemStore;
  @Inject Event<CloudEvent> cloudEventEmitter;

  @Override
  public void schedule(HumanTaskScheduleRequest request) {
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

    String callerRef = CallerRefParser.encodePlanItem(request.caseId(), item.id());
    HumanTaskTarget target = request.target();

    ObjectNode data = buildDataPayload(request, target, callerRef);

    CloudEventBuilder builder =
        CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withType(WorkCloudEventTypes.CREATE)
            .withSource(URI.create("/engine/cases/" + request.caseId()))
            .withDataContentType("application/json")
            .withData(data.toString().getBytes())
            .withExtension(WorkCloudEventTypes.EXT_TENANCY_ID, request.tenancyId());

    if (target.isTemplateMode() && target.templateRef() != null) {
      builder.withExtension(WorkCloudEventTypes.EXT_TEMPLATE_ID, target.templateRef());
    }

    CloudEvent cloudEvent = builder.build();
    cloudEventEmitter.fireAsync(cloudEvent);

    planItemStore.save(
        PlanItemSaveRequest.primitive(
            request.caseId(),
            item.id(),
            item.getBindingName(),
            TaskStatus.DELEGATED,
            item.getCreatedAt(),
            TargetType.HUMAN_TASK,
            extractOutputMappingExpression(target),
            request.tenancyId(),
            null,
            null,
            null),
        request.tenancyId());
    item.markDelegated();

    LOG.infof(
        "CloudEvent emitted for HumanTask binding callerRef=%s type=%s",
        callerRef, target.isTemplateMode() ? "template" : "inline");
  }

  private ObjectNode buildDataPayload(
      HumanTaskScheduleRequest request, HumanTaskTarget target, String callerRef) {
    ObjectNode data = MAPPER.createObjectNode();

    data.put("callerRef", callerRef);

    String title = request.resolvedTitle() != null ? request.resolvedTitle() : target.title();
    if (title != null) {
      data.put("title", title);
    }

    String scope = request.resolvedScope() != null ? request.resolvedScope() : target.scope();
    if (scope != null) {
      data.put("scope", scope);
    }

    if (target.isTemplateMode() && target.templateRef() != null) {
      data.put("templateId", target.templateRef());
    }

    String candidateGroups = toCsv(request.resolvedCandidateGroups());
    if (candidateGroups != null) {
      data.put("candidateGroups", candidateGroups);
    }

    String candidateUsers = toCsv(request.resolvedCandidateUsers());
    if (candidateUsers != null) {
      data.put("candidateUsers", candidateUsers);
    }

    String payload = serializeToJson(request.inputData());
    if (payload != null) {
      data.put("payload", payload);
    }

    Instant effectiveDeadline =
        earliestOf(request.expiresAtDeadline(), request.caseBudgetDeadline());
    if (effectiveDeadline == null && target.expiresIn() != null) {
      effectiveDeadline = Instant.now().plus(target.expiresIn());
    }
    if (effectiveDeadline != null) {
      data.put("expiresAt", effectiveDeadline.toString());
    }

    if (target.claimDeadlineHours() != null) {
      data.put("claimDeadlineBusinessHours", target.claimDeadlineHours());
    }

    if (request.payloadTypeName() != null) {
      data.put("payloadTypeName", request.payloadTypeName());
    }

    if (request.resolutionTypeName() != null) {
      data.put("resolutionTypeName", request.resolutionTypeName());
    }

    String scores = serializeToJson(request.candidateScores());
    if (scores != null) {
      data.put("candidateScores", scores);
    }

    String experiences = serializeExperiences(request.experiences());
    if (experiences != null) {
      data.put("routingExperiences", experiences);
    }

    if (target.outcomes() != null && !target.outcomes().isEmpty()) {
      ArrayNode outcomes = data.putArray("permittedOutcomes");
      for (String outcome : target.outcomes()) {
        outcomes.addObject().put("name", outcome);
      }
    }

    return data;
  }

  private static Instant earliestOf(Instant a, Instant b) {
    if (a == null) return b;
    if (b == null) return a;
    return a.isBefore(b) ? a : b;
  }

  private static String toCsv(Set<String> values) {
    if (values == null || values.isEmpty()) return null;
    return String.join(",", values);
  }

  private String serializeToJson(Object value) {
    if (value == null) return null;
    if (value instanceof Map<?, ?> m && m.isEmpty()) return null;
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      LOG.warnf(e, "Failed to serialize to JSON — using null");
      return null;
    }
  }

  private String serializeExperiences(List<RetrievedExperience> experiences) {
    if (experiences == null || experiences.isEmpty()) return null;
    try {
      return MAPPER.writeValueAsString(experiences);
    } catch (JsonProcessingException e) {
      LOG.warnf(e, "Failed to serialize routing experiences — using null");
      return null;
    }
  }

  private static String extractOutputMappingExpression(HumanTaskTarget target) {
    if (target == null || target.outputMapping() == null) return null;
    ExpressionEvaluator evaluator = target.outputMapping();
    if (evaluator instanceof JQExpressionEvaluator jq) {
      return jq.expression();
    }
    return null;
  }
}
