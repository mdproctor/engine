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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.ExtensionTarget;
import io.casehub.api.model.HumanTaskTarget;
import io.casehub.api.model.SubCaseTarget;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.blackboard.plan.PlanItem;
import io.casehub.blackboard.registry.BlackboardRegistry;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.jq.ValidationResult;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.work.runtime.model.WorkItem;
import io.casehub.work.runtime.model.WorkItemStatus;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Applies a terminal WorkItemStatus to a PlanItem and fires CONTEXT_CHANGED.
 *
 * <p>Shared between WorkItemLifecycleAdapter (normal flow) and HumanTaskRecoveryService (startup
 * catch-up). Declares @Transactional — REQUIRED semantics means the transaction propagates from
 * callers that already have one, and a new one is opened when called without.
 */
@ApplicationScoped
class PlanItemCompletionApplier {

  private static final Logger LOG = Logger.getLogger(PlanItemCompletionApplier.class);
  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  @Inject BlackboardRegistry registry;
  @Inject CaseInstanceRepository caseInstanceRepository;
  @Inject EventBus eventBus;
  @Inject JQEvaluator jqEvaluator;

  /**
   * Applies the terminal WorkItemStatus to the PlanItem, runs outputMapping if configured, loads
   * the CaseInstance, and publishes CONTEXT_CHANGED.
   *
   * <p>If the PlanItem is already terminal (idempotency), logs DEBUG and returns without throwing.
   *
   * @param caseId the case containing the PlanItem
   * @param planItemId the PlanItem to transition
   * @param status the terminal WorkItemStatus to apply
   * @param workItem the source WorkItem (for outputMapping resolution JSON); may be null
   */
  @Transactional
  public void apply(UUID caseId, String planItemId, WorkItemStatus status, WorkItem workItem) {
    PlanItem item = registry.get(caseId).flatMap(plan -> plan.getPlanItem(planItemId)).orElse(null);

    if (item == null) {
      LOG.warnf("PlanItem %s not found in case %s — completion not applied", planItemId, caseId);
      return;
    }

    if (!applyStatus(item, status)) {
      return; // already terminal or invalid transition — idempotent skip
    }

    CaseInstance instance = caseInstanceRepository.findByUuid(caseId).await().atMost(TIMEOUT);
    if (instance == null) {
      LOG.warnf("CaseInstance not found for caseId=%s — CONTEXT_CHANGED not fired", caseId);
      return;
    }

    applyOutputMapping(item, workItem, instance);
    eventBus.publish(
        EventBusAddresses.CONTEXT_CHANGED,
        new CaseContextChangedEvent(instance, instance.getCaseContext().asJsonNode()));
  }

  private boolean applyStatus(PlanItem item, WorkItemStatus status) {
    try {
      switch (status) {
        case COMPLETED -> item.markCompleted();
        case REJECTED -> item.markRejected();
        case EXPIRED -> item.markFaulted();
        case CANCELLED -> item.markCancelled();
        default -> {
          return false;
        }
      }
      return true;
    } catch (IllegalStateException e) {
      LOG.debugf(
          "PlanItem %s already terminal (status=%s) — skipping for WorkItemStatus %s",
          item.getPlanItemId(), item.getStatus(), status);
      return false;
    }
  }

  private void applyOutputMapping(PlanItem item, WorkItem workItem, CaseInstance instance) {
    if (instance.getCaseContext() == null) return;
    if (item.getTarget() == null) return;
    HumanTaskTarget ht =
        switch (item.getTarget()) {
          case HumanTaskTarget humanTaskTarget -> humanTaskTarget;
          case CapabilityTarget ignored -> null;
          case SubCaseTarget ignored -> null;
          case ExtensionTarget ignored -> null;
        };
    if (ht == null) return;
    if (ht.outputMapping() == null) return;
    if (workItem == null || workItem.resolution == null) return;

    if (!(ht.outputMapping() instanceof JQExpressionEvaluator jq)) {
      LOG.warnf(
          "Unsupported outputMapping evaluator type '%s' for PlanItem %s — skipping",
          ht.outputMapping().getClass().getName(), item.getPlanItemId());
      return;
    }

    try {
      JsonNode resolutionNode = MAPPER.readTree(workItem.resolution);
      ValidationResult vr = jqEvaluator.eval(jq.expression(), resolutionNode);
      if (!vr.ok() || vr.output() == null || vr.output().isEmpty()) {
        LOG.warnf(
            "outputMapping jq expression returned no result for PlanItem %s: %s",
            item.getPlanItemId(), vr.error());
        return;
      }
      List<JsonNode> output = vr.output();
      Map<String, Object> updates = MAPPER.convertValue(output.get(0), MAP_TYPE);
      instance.getCaseContext().setAll(updates);
    } catch (Exception e) {
      LOG.warnf(
          e,
          "outputMapping failed for PlanItem %s — CONTEXT_CHANGED fires without output update",
          item.getPlanItemId());
    }
  }
}
