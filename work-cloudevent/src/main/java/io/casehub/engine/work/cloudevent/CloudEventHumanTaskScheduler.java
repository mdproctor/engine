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
import io.casehub.api.model.CallerConfig;
import io.casehub.api.model.JudgmentTarget;
import io.casehub.api.model.TaskStatus;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.api.spi.routing.RetrievedExperience;
import io.casehub.engine.common.internal.model.PlanItemSaveRequest;
import io.casehub.engine.common.internal.model.TargetType;
import io.casehub.engine.common.spi.CallerRefParser;
import io.casehub.engine.common.spi.JudgmentPayload;
import io.casehub.engine.common.spi.JudgmentRequest;
import io.casehub.engine.common.spi.JudgmentScheduler;
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
public class CloudEventHumanTaskScheduler implements JudgmentScheduler {

  private static final Logger LOG = Logger.getLogger(CloudEventHumanTaskScheduler.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject BlackboardRegistry registry;
  @Inject PlanItemStore planItemStore;
  @Inject Event<CloudEvent> cloudEventEmitter;

  @Override
  public void schedule(JudgmentRequest request) {
    if (!(request.payload() instanceof JudgmentPayload.BindingPayload payload)) {
      LOG.warnf(
          "CloudEventHumanTaskScheduler only handles BindingPayload — got %s",
          request.payload().getClass().getSimpleName());
      return;
    }
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
    JudgmentTarget target = request.target();
    CallerConfig.Human human = target.callerConfig() instanceof CallerConfig.Human h ? h : null;

    ObjectNode data = buildDataPayload(request, payload, target, human, callerRef);

    CloudEventBuilder ceBuilder =
        CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withType(WorkCloudEventTypes.CREATE)
            .withSource(URI.create("/engine/cases/" + request.caseId()))
            .withDataContentType("application/json")
            .withData(data.toString().getBytes())
            .withExtension(WorkCloudEventTypes.EXT_TENANCY_ID, request.tenancyId());

    if (human != null && human.templateRef() != null) {
      ceBuilder.withExtension(WorkCloudEventTypes.EXT_TEMPLATE_ID, human.templateRef());
    }

    CloudEvent cloudEvent = ceBuilder.build();
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

    boolean templateMode = human != null && human.templateRef() != null;
    LOG.infof(
        "CloudEvent emitted for judgment binding callerRef=%s type=%s",
        callerRef, templateMode ? "template" : "inline");
  }

  private ObjectNode buildDataPayload(
      JudgmentRequest request,
      JudgmentPayload.BindingPayload payload,
      JudgmentTarget target,
      CallerConfig.Human human,
      String callerRef) {
    ObjectNode data = MAPPER.createObjectNode();

    data.put("callerRef", callerRef);

    String humanTitle = human != null ? human.title() : null;
    String title = payload.resolvedTitle() != null ? payload.resolvedTitle() : humanTitle;
    if (title != null) {
      data.put("title", title);
    }

    String humanScope = human != null ? human.scope() : null;
    String scope = payload.resolvedScope() != null ? payload.resolvedScope() : humanScope;
    if (scope != null) {
      data.put("scope", scope);
    }

    if (human != null && human.templateRef() != null) {
      data.put("templateId", human.templateRef());
    }

    String candidateGroups = toCsv(payload.resolvedCandidateGroups());
    if (candidateGroups != null) {
      data.put("candidateGroups", candidateGroups);
    }

    String candidateUsers = toCsv(payload.resolvedCandidateUsers());
    if (candidateUsers != null) {
      data.put("candidateUsers", candidateUsers);
    }

    String inputJson = serializeToJson(payload.inputData());
    if (inputJson != null) {
      data.put("payload", inputJson);
    }

    Instant effectiveDeadline =
        earliestOf(payload.expiresAtDeadline(), payload.caseBudgetDeadline());
    if (effectiveDeadline == null && target.expiresIn() != null) {
      effectiveDeadline = Instant.now().plus(target.expiresIn());
    }
    if (effectiveDeadline != null) {
      data.put("expiresAt", effectiveDeadline.toString());
    }

    if (human != null && human.claimDeadlineHours() != null) {
      data.put("claimDeadlineBusinessHours", human.claimDeadlineHours());
    }

    if (payload.payloadTypeName() != null) {
      data.put("payloadTypeName", payload.payloadTypeName());
    }

    if (payload.resolutionTypeName() != null) {
      data.put("resolutionTypeName", payload.resolutionTypeName());
    }

    String scores = serializeToJson(payload.candidateScores());
    if (scores != null) {
      data.put("candidateScores", scores);
    }

    String experiences = serializeExperiences(payload.experiences());
    if (experiences != null) {
      data.put("routingExperiences", experiences);
    }

    Set<String> outcomes = human != null ? human.outcomes() : null;
    if (outcomes != null && !outcomes.isEmpty()) {
      ArrayNode outcomesArray = data.putArray("permittedOutcomes");
      for (String outcome : outcomes) {
        outcomesArray.addObject().put("name", outcome);
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

  private static String extractOutputMappingExpression(JudgmentTarget target) {
    if (target == null || target.outputMapping() == null) return null;
    ExpressionEvaluator evaluator = target.outputMapping();
    if (evaluator instanceof JQExpressionEvaluator jq) {
      return jq.expression();
    }
    return null;
  }
}
