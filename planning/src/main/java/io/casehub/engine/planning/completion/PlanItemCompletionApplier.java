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
package io.casehub.engine.planning.completion;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.context.ContextBridge;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ConflictResolver;
import io.casehub.api.model.HumanTaskTarget;
import io.casehub.api.model.TaskStatus;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.engine.common.internal.context.BridgeResolver;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.jq.ValidationResult;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CrossTenantCaseInstanceRepository;
import io.casehub.engine.common.spi.event.PlanItemObsoleteEvent;
import io.casehub.engine.common.spi.event.PlanItemStateChangedEvent;
import io.casehub.engine.planning.plan.PlanItem;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

@ApplicationScoped
public class PlanItemCompletionApplier {

  private static final Logger LOG = Logger.getLogger(PlanItemCompletionApplier.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  @Inject BlackboardRegistry registry;
  @Inject CrossTenantCaseInstanceRepository caseInstanceRepository;
  @Inject EventBus eventBus;
  @Inject JQEvaluator jqEvaluator;
  @Inject BridgeResolver bridgeResolver;
  @Inject CaseDefinitionRegistry caseDefinitionRegistry;
  @Inject Event<PlanItemStateChangedEvent> planItemStateChangedEvents;
  @Inject Event<PlanItemObsoleteEvent> planItemObsoleteEvents;

  public void apply(
      UUID caseId,
      String planItemId,
      TaskStatus status,
      @Nullable String resolution,
      @Nullable String resolutionTypeName) {

    PlanItem item = registry.get(caseId).flatMap(plan -> plan.getPlanItem(planItemId)).orElse(null);
    if (item == null) {
      LOG.warnf("PlanItem %s not found in case %s — completion not applied", planItemId, caseId);
      return;
    }

    CaseInstance instance = caseInstanceRepository.findByUuid(caseId);

    if (instance != null
        && status != TaskStatus.FAULTED
        && resolutionTypeName != null
        && resolution != null) {
      try {
        ContextBridge<?> bridge = bridgeResolver.resolveByTypeNameStrict(resolutionTypeName);
        bridge.deserialise(MAPPER.readTree(resolution));
      } catch (Exception e) {
        LOG.warnf(
            e,
            "Resolution validation failed for PlanItem %s caseId=%s — resolution does not match resolutionType %s",
            planItemId,
            caseId,
            resolutionTypeName);
        if (instance.getCaseContext() != null) {
          instance
              .getCaseContext()
              .set(
                  "workItemValidationFailed",
                  Map.of(
                      "planItemId",
                      planItemId,
                      "bindingName",
                      item.getBindingName(),
                      "resolutionTypeName",
                      resolutionTypeName,
                      "error",
                      e.getMessage() != null ? e.getMessage() : e.getClass().getName()));
          eventBus.publish(
              "casehub.context.changed",
              new CaseContextChangedEvent(
                  instance, instance.getCaseContext().snapshot(), "working"));
        }
        return;
      }
    }

    TaskStatus previousStatus = item.getStatus();
    if (!applyStatus(item, status)) {
      return;
    }

    if (instance == null) {
      LOG.warnf("CaseInstance not found for caseId=%s — CONTEXT_CHANGED not fired", caseId);
      return;
    }

    applyOutputMapping(item, resolution, instance);

    String bindingName = item.getBindingName();
    if (status == TaskStatus.REJECTED) {
      planItemStateChangedEvents.fireAsync(
          new PlanItemStateChangedEvent(
              caseId,
              planItemId,
              bindingName,
              previousStatus,
              TaskStatus.REJECTED,
              instance.tenancyId));
    }
    if (status == TaskStatus.FAULTED) {
      planItemStateChangedEvents.fireAsync(
          new PlanItemStateChangedEvent(
              caseId,
              planItemId,
              bindingName,
              previousStatus,
              TaskStatus.FAULTED,
              instance.tenancyId));
    }
    if (status == TaskStatus.OBSOLETE) {
      planItemObsoleteEvents.fireAsync(
          new PlanItemObsoleteEvent(caseId, planItemId, bindingName, instance.tenancyId));
    }

    eventBus.publish(
        "casehub.context.changed",
        new CaseContextChangedEvent(instance, instance.getCaseContext().snapshot(), "working"));
  }

  public void applySuspend(UUID caseId, String planItemId) {
    registry
        .get(caseId)
        .flatMap(plan -> plan.getPlanItem(planItemId))
        .ifPresent(
            item -> {
              try {
                item.markSuspended();
                LOG.infof("PlanItem %s suspended: caseId=%s", planItemId, caseId);
              } catch (IllegalStateException e) {
                LOG.debugf(
                    "Cannot suspend PlanItem %s (status=%s): %s",
                    planItemId, item.getStatus(), e.getMessage());
              }
            });
  }

  public void applyResume(UUID caseId, String planItemId) {
    registry
        .get(caseId)
        .flatMap(plan -> plan.getPlanItem(planItemId))
        .ifPresent(
            item -> {
              if (item.getStatus() == TaskStatus.SUSPENDED) {
                item.markResumed();
                LOG.infof("PlanItem %s resumed: caseId=%s", planItemId, caseId);
              }
            });
  }

  private boolean applyStatus(PlanItem item, TaskStatus status) {
    try {
      switch (status) {
        case COMPLETED -> item.markCompleted();
        case REJECTED -> item.markRejected();
        case FAULTED -> item.markFaulted();
        case OBSOLETE -> item.markObsolete();
        case CANCELLED -> item.markCancelled();
        default -> {
          LOG.warnf(
              "Unhandled TaskStatus %s for PlanItem %s — no transition applied", status, item.id());
          return false;
        }
      }
      return true;
    } catch (IllegalStateException e) {
      LOG.debugf(
          "PlanItem %s already terminal (status=%s) — skipping for TaskStatus %s",
          item.id(), item.getStatus(), status);
      return false;
    }
  }

  private void applyOutputMapping(
      PlanItem item, @Nullable String resolution, CaseInstance instance) {
    if (instance.getCaseContext() == null || item.getTarget() == null || resolution == null) {
      return;
    }
    if (!(item.getTarget() instanceof HumanTaskTarget ht)) {
      return;
    }
    if (ht.outputMapping() == null) {
      return;
    }
    ExpressionEvaluator evaluator = ht.outputMapping();
    if (!(evaluator instanceof JQExpressionEvaluator jq)) {
      LOG.warnf(
          "Unsupported outputMapping evaluator type '%s' for PlanItem %s — skipping",
          evaluator.getClass().getName(), item.id());
      return;
    }
    try {
      JsonNode resolutionNode = MAPPER.readTree(resolution);
      ValidationResult vr = jqEvaluator.eval(jq.expression(), resolutionNode);
      if (!vr.ok() || vr.output() == null || vr.output().isEmpty()) {
        LOG.warnf(
            "outputMapping jq expression returned no result for PlanItem %s: %s",
            item.id(), vr.error());
        return;
      }
      List<JsonNode> output = vr.output();
      Map<String, Object> updates = MAPPER.convertValue(output.get(0), MAP_TYPE);
      String strategy = resolveStrategy(item.getBindingName(), instance);
      for (Map.Entry<String, Object> entry : updates.entrySet()) {
        Object existing = instance.getCaseContext().get(entry.getKey());
        Object resolved =
            ConflictResolver.resolve(strategy, entry.getKey(), existing, entry.getValue());
        instance.getCaseContext().set(entry.getKey(), resolved);
      }
    } catch (Exception e) {
      LOG.warnf(
          e,
          "outputMapping failed for PlanItem %s — CONTEXT_CHANGED fires without output update",
          item.id());
    }
  }

  private String resolveStrategy(String bindingName, CaseInstance instance) {
    if (bindingName == null || instance.getCaseMetaModel() == null) {
      return null;
    }
    CaseDefinition def = caseDefinitionRegistry.getCaseDefinition(instance.getCaseMetaModel());
    if (def == null || def.getBindings() == null) {
      return null;
    }
    return def.getBindings().stream()
        .filter(b -> b.getName().equals(bindingName))
        .map(Binding::getConflictResolverStrategy)
        .findFirst()
        .orElse(null);
  }
}
