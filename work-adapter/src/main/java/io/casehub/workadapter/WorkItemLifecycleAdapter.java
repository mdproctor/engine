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
import io.casehub.blackboard.plan.CasePlanModel;
import io.casehub.blackboard.plan.PlanItem;
import io.casehub.blackboard.registry.BlackboardRegistry;
import io.casehub.engine.internal.event.CaseContextChangedEvent;
import io.casehub.engine.internal.event.EventBusAddresses;
import io.casehub.engine.internal.model.CaseInstance;
import io.casehub.engine.spi.CaseInstanceRepository;
import io.casehub.platform.expression.JQEvaluator;
import io.casehub.platform.expression.ValidationResult;
import io.casehub.work.runtime.event.WorkItemLifecycleEvent;
import io.casehub.work.runtime.model.WorkItem;
import io.casehub.work.runtime.model.WorkItemStatus;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Translates terminal quarkus-work {@link WorkItemLifecycleEvent}s into CaseHub PlanItem
 * transitions and fires {@code CONTEXT_CHANGED} to trigger engine re-evaluation.
 *
 * <p>Choreography path: the engine's binding evaluator picks up the next step automatically once
 * the PlanItem status changes and the context-changed signal arrives. Refs casehubio/work#136.
 *
 * <p>Only processes events whose {@code callerRef} matches the CaseHub format {@code
 * case:{caseId}/pi:{planItemId}} — other WorkItems are ignored.
 */
@ApplicationScoped
public class WorkItemLifecycleAdapter {

  private static final Logger LOG = Logger.getLogger(WorkItemLifecycleAdapter.class);
  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  @Inject BlackboardRegistry registry;

  @Inject CaseInstanceRepository caseInstanceRepository;

  @Inject EventBus eventBus;

  @Inject JQEvaluator jqEvaluator;

  public void onWorkItemLifecycle(@ObservesAsync WorkItemLifecycleEvent event) {
    WorkItemStatus status = event.status();
    if (status != WorkItemStatus.COMPLETED
        && status != WorkItemStatus.REJECTED
        && status != WorkItemStatus.CANCELLED
        && status != WorkItemStatus.EXPIRED
        && status != WorkItemStatus.ESCALATED) return;

    if (!(event.source() instanceof WorkItem workItem)) return;

    CallerRef ref = CallerRef.parse(workItem.callerRef);
    if (ref == null) return;

    CasePlanModel plan = registry.get(ref.caseId()).orElse(null);
    if (plan == null) {
      LOG.debugf(
          "No CasePlanModel for caseId=%s — case may have completed or not use blackboard",
          ref.caseId());
      return;
    }

    PlanItem item = plan.getPlanItem(ref.planItemId()).orElse(null);
    if (item == null) {
      LOG.warnf("PlanItem %s not found in case %s", ref.planItemId(), ref.caseId());
      return;
    }

    if (!applyStatus(item, status)) return;

    // Use Uni.await() directly — safe from a CDI managed executor thread (non-Vert.x).
    // runOnSafeVertxContext was removed because it reliably times out in complex Quarkus
    // deployments with many event-bus subscribers (engine#316).
    CaseInstance instance = caseInstanceRepository.findByUuid(ref.caseId()).await().atMost(TIMEOUT);

    if (instance == null) {
      LOG.warnf("CaseInstance not found for caseId=%s — cannot fire CONTEXT_CHANGED", ref.caseId());
      return;
    }

    applyOutputMapping(item, workItem, instance);
    eventBus.publish(
        EventBusAddresses.CONTEXT_CHANGED,
        new CaseContextChangedEvent(instance, instance.getCaseContext().asJsonNode()));
  }

  private void applyOutputMapping(PlanItem item, WorkItem workItem, CaseInstance instance) {
    if (instance.getCaseContext() == null) {
      LOG.warnf(
          "CaseInstance %s has no CaseContext — outputMapping skipped for PlanItem %s",
          instance.getUuid(), item.getPlanItemId());
      return;
    }
    HumanTaskTarget ht =
        switch (item.getTarget()) {
          case HumanTaskTarget humanTaskTarget -> humanTaskTarget;
          case CapabilityTarget ignored -> null;
          case SubCaseTarget ignored -> null;
          case ExtensionTarget ignored -> null;
        };
    if (ht == null) return;
    if (ht.outputMapping() == null) return;
    if (workItem.resolution == null) return;

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

  /**
   * Applies the terminal WorkItemStatus to the PlanItem. Returns false if the transition is invalid
   * (e.g. item already terminal), in which case no CONTEXT_CHANGED should be fired.
   */
  private boolean applyStatus(PlanItem item, WorkItemStatus status) {
    try {
      switch (status) {
        case COMPLETED -> item.markCompleted();
        case CANCELLED -> item.markCancelled();
        case REJECTED, EXPIRED, ESCALATED -> item.markFaulted();
        default -> {
          return false;
        }
      }
      return true;
    } catch (IllegalStateException e) {
      LOG.warnf(
          "Cannot transition PlanItem %s (current=%s) for WorkItemStatus %s: %s",
          item.getPlanItemId(), item.getStatus(), status, e.getMessage());
      return false;
    }
  }
}
