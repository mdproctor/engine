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
import io.casehub.api.model.HumanTaskTarget;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.blackboard.plan.CasePlanModel;
import io.casehub.blackboard.plan.PlanItem;
import io.casehub.blackboard.registry.BlackboardRegistry;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.HumanTaskScheduleEvent;
import io.casehub.engine.common.internal.model.PlanItemSaveRequest;
import io.casehub.engine.common.internal.model.PlanItemStatus;
import io.casehub.engine.common.internal.model.TargetType;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.work.runtime.model.WorkItem;
import io.casehub.work.runtime.model.WorkItemCreateRequest;
import io.casehub.work.runtime.model.WorkItemTemplate;
import io.casehub.work.runtime.service.WorkItemService;
import io.casehub.work.runtime.service.WorkItemTemplateService;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Handles outbound human task creation when a {@link HumanTaskTarget} binding is selected.
 *
 * <p>Receives {@link HumanTaskScheduleEvent} from the engine event bus, looks up the {@link
 * PlanItem} in the {@link BlackboardRegistry} by binding name, creates a {@link WorkItem} via
 * {@link WorkItemService} (inline mode) or {@link
 * io.casehub.work.runtime.service.WorkItemTemplateService} (template mode), persists the DELEGATED
 * status to {@link PlanItemStore}, then marks the in-memory PlanItem DELEGATED.
 *
 * <p>All three steps — WorkItem creation, {@code planItemStore.save(...DELEGATED...)}, and {@code
 * item.markDelegated()} — execute in a single {@code @Transactional} boundary. If WorkItem creation
 * fails, the transaction rolls back and {@code markDelegated()} is never called, leaving the
 * PlanItem PENDING. Refs engine#273.
 *
 * <p>The {@code callerRef} encodes {@code case:{caseId}/pi:{planItemId}} so that {@link
 * WorkItemLifecycleAdapter} can route the completion event back to the correct case and plan item.
 * Refs engine#245.
 */
@ApplicationScoped
public class HumanTaskScheduleHandler {

  private static final Logger LOG = Logger.getLogger(HumanTaskScheduleHandler.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject BlackboardRegistry registry;
  @Inject WorkItemService workItemService;
  @Inject WorkItemTemplateService workItemTemplateService;
  @Inject PlanItemStore planItemStore;

  @ConsumeEvent(value = EventBusAddresses.HUMAN_TASK_SCHEDULE, blocking = true)
  @Transactional
  public void onHumanTaskSchedule(HumanTaskScheduleEvent event) {
    CasePlanModel plan = registry.get(event.caseId()).orElse(null);
    if (plan == null) {
      LOG.warnf(
          "No CasePlanModel for caseId=%s — case may not use blackboard or has completed",
          event.caseId());
      return;
    }

    PlanItem item = plan.getPlanItemByBindingName(event.bindingName()).orElse(null);
    if (item == null) {
      LOG.warnf(
          "PlanItem for binding '%s' not found in case %s", event.bindingName(), event.caseId());
      return;
    }

    if (item.getStatus() != PlanItemStatus.PENDING) {
      LOG.warnf(
          "PlanItem for binding '%s' case %s is not PENDING (status=%s) — skipping",
          event.bindingName(), event.caseId(), item.getStatus());
      return;
    }

    if (event.target().isTemplateMode()) {
      handleTemplateMode(item, event);
    } else {
      handleInlineMode(item, event);
    }
  }

  private void handleTemplateMode(PlanItem item, HumanTaskScheduleEvent event) {
    HumanTaskTarget target = event.target();

    // Parse templateRef as UUID
    UUID templateId;
    try {
      templateId = UUID.fromString(target.templateRef());
    } catch (IllegalArgumentException e) {
      LOG.warnf(
          "templateRef '%s' is not a valid UUID for binding '%s' case %s — PlanItem left PENDING",
          target.templateRef(), event.bindingName(), event.caseId());
      return;
    }

    WorkItemTemplate template = workItemTemplateService.findById(templateId).orElse(null);

    if (template == null) {
      LOG.warnf(
          "No template found for ref '%s' binding '%s' case %s — PlanItem left PENDING",
          target.templateRef(), event.bindingName(), event.caseId());
      return;
    }

    String callerRef = CallerRef.encode(event.caseId(), item.getPlanItemId());
    WorkItem workItem =
        workItemTemplateService.instantiate(
            template, target.title(), null, "casehub-engine", callerRef);

    workItem.scope = target.scope();
    if (event.inputData() != null && !event.inputData().isEmpty()) {
      workItem.payload = serializePayload(event.inputData());
    }
    workItem.persist();

    planItemStore.save(
        new PlanItemSaveRequest(
            event.caseId(),
            item.getPlanItemId(),
            item.getBindingName(),
            PlanItemStatus.DELEGATED,
            item.getCreatedAt(),
            TargetType.HUMAN_TASK,
            extractOutputMappingExpression(event.target()),
            event.tenancyId()));
    item.markDelegated();
    LOG.infof("WorkItem created (inline) for binding callerRef=%s", callerRef);
  }

  private void handleInlineMode(PlanItem item, HumanTaskScheduleEvent event) {
    String callerRef = CallerRef.encode(event.caseId(), item.getPlanItemId());
    createInline(event.target(), event.inputData(), callerRef, event.caseBudgetDeadline());
    planItemStore.save(
        new PlanItemSaveRequest(
            event.caseId(),
            item.getPlanItemId(),
            item.getBindingName(),
            PlanItemStatus.DELEGATED,
            item.getCreatedAt(),
            TargetType.HUMAN_TASK,
            extractOutputMappingExpression(event.target()),
            event.tenancyId()));
    item.markDelegated();
  }

  private void createInline(
      HumanTaskTarget target,
      Map<String, Object> inputData,
      String callerRef,
      Instant caseBudgetDeadline) {
    String payload = serializePayload(inputData);
    Instant taskDeadline =
        target.expiresIn() != null ? Instant.now().plus(target.expiresIn()) : null;
    Instant effectiveDeadline = earliestOf(taskDeadline, caseBudgetDeadline);

    WorkItemCreateRequest request =
        WorkItemCreateRequest.builder()
            .title(target.title())
            .candidateGroups(candidateGroupsCsv(target))
            .candidateUsers(candidateUsersCsv(target))
            .createdBy("casehub-engine")
            .payload(payload)
            .expiresAt(effectiveDeadline)
            .callerRef(callerRef)
            .scope(target.scope())
            .build();

    workItemService.create(request);
    LOG.infof(
        "WorkItem created (inline) for binding callerRef=%s title='%s' expiresAt=%s",
        callerRef, target.title(), effectiveDeadline);
  }

  private static Instant earliestOf(Instant a, Instant b) {
    if (a == null) return b;
    if (b == null) return a;
    return a.isBefore(b) ? a : b;
  }

  private String serializePayload(Map<String, Object> inputData) {
    if (inputData == null || inputData.isEmpty()) return null;
    try {
      return MAPPER.writeValueAsString(inputData);
    } catch (JsonProcessingException e) {
      LOG.warnf(e, "Failed to serialize inputData to JSON payload — using null");
      return null;
    }
  }

  private static String candidateGroupsCsv(HumanTaskTarget target) {
    if (target.candidateGroups() == null || target.candidateGroups().isEmpty()) return null;
    return String.join(",", target.candidateGroups());
  }

  private static String candidateUsersCsv(HumanTaskTarget target) {
    if (target.candidateUsers() == null || target.candidateUsers().isEmpty()) return null;
    return String.join(",", target.candidateUsers());
  }

  private static String extractOutputMappingExpression(HumanTaskTarget target) {
    if (target == null || target.outputMapping() == null) return null;
    if (target.outputMapping() instanceof JQExpressionEvaluator jq) return jq.expression();
    return null;
  }
}
