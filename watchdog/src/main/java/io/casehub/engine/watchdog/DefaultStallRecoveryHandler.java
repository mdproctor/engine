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
package io.casehub.engine.watchdog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.context.ContextLayer;
import io.casehub.api.context.MutableCaseContext;
import io.casehub.api.context.WritableLayer;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.JudgmentTarget;
import io.casehub.api.model.StallRecoveryAction;
import io.casehub.api.model.StallRecoveryContext;
import io.casehub.api.model.StallRecoveryPolicy;
import io.casehub.api.model.TaskStatus;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.api.spi.recovery.StallClassificationContext;
import io.casehub.api.spi.recovery.StallClassifier;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.OutcomeDisposition;
import io.casehub.engine.common.internal.event.WorkerOutcomeResolvedEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.JudgmentPayload;
import io.casehub.engine.common.spi.JudgmentRequest;
import io.casehub.engine.common.spi.JudgmentScheduler;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.common.spi.recovery.StallRecoveryHandler;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

@ApplicationScoped
public class DefaultStallRecoveryHandler implements StallRecoveryHandler {

  private static final Logger LOG = Logger.getLogger(DefaultStallRecoveryHandler.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject CaseInstanceCache caseInstanceCache;
  @Inject CaseDefinitionRegistry definitionRegistry;
  @Inject EventLogRepository eventLogRepository;
  @Inject EventBus eventBus;
  @Inject Instance<PlanItemStore> planItemStore;
  @Inject Instance<JudgmentScheduler> judgmentScheduler;
  @Inject Instance<StallClassifier> stallClassifiers;

  @Override
  public boolean handleStall(StallRecoveryContext context) {
    CaseInstance instance = caseInstanceCache.get(context.caseId());
    if (instance == null || instance.getState().isTerminal()) return false;

    CaseDefinition definition = definitionRegistry.getCaseDefinition(instance.getCaseMetaModel());
    if (definition == null) return false;

    StallRecoveryPolicy policy = definition.getStallRecoveryPolicy();
    if (policy == null || !policy.enabled()) return false;

    StallClassifier classifier = resolveClassifier(policy.classifierId());
    StallRecoveryAction action =
        classifier.classify(new StallClassificationContext(context, definition, policy));

    boolean acted =
        switch (action) {
          case RETRY -> executeRetry(context, instance);
          case REROUTE -> executeReroute(context, instance);
          case CANCEL -> executeCancel(context, instance);
          case EXPIRE -> executeExpire(context, instance);
          case ESCALATE -> executeEscalate(context, instance);
          case NOTIFY -> executeNotify(context);
          case IGNORE -> false;
        };

    return acted;
  }

  boolean executeRetry(StallRecoveryContext context, CaseInstance instance) {
    MutableCaseContext mutableCtx = asMutableContext(instance);
    if (mutableCtx == null) return false;

    WritableLayer working = mutableCtx.writableLayer(ContextLayer.WORKING);
    Instant lastRetry = getLastRetryAt(working, context.caseId());
    if (lastRetry != null && lastRetry.plusSeconds(5).isAfter(Instant.now())) {
      LOG.debugf("RETRY debounced for case %s — last retry at %s", context.caseId(), lastRetry);
      return false;
    }
    working.setPath("_stallRecovery.lastRetryAt", Instant.now().toString());

    eventBus.publish(
        EventBusAddresses.CONTEXT_CHANGED,
        new CaseContextChangedEvent(instance, mutableCtx.snapshot(), ContextLayer.WORKING));
    writeAuditLog(context, StallRecoveryAction.RETRY, CaseHubEventType.STALL_RECOVERY_INITIATED);
    LOG.infof("RETRY: published CONTEXT_CHANGED for case %s", context.caseId());
    return true;
  }

  boolean executeReroute(StallRecoveryContext context, CaseInstance instance) {
    if (context.resolvedBindingName() == null) {
      LOG.warnf("REROUTE requires binding — falling back to NOTIFY for case %s", context.caseId());
      return executeNotify(context);
    }

    MutableCaseContext mutableCtx = asMutableContext(instance);
    if (mutableCtx == null) return false;

    WritableLayer working = mutableCtx.writableLayer(ContextLayer.WORKING);

    String agentId =
        context.affectedAgentIds().isEmpty() ? null : context.affectedAgentIds().getFirst();
    if (agentId == null) return false;

    String diagPath = "_diagnostics." + context.resolvedBindingName() + ".excludedAgents";
    Object existingRaw = working.getPath(diagPath);
    List<String> excludedAgents =
        existingRaw instanceof List<?> list
            ? new ArrayList<>(list.stream().map(Object::toString).toList())
            : new ArrayList<>();

    if (excludedAgents.contains(agentId)) {
      LOG.debugf(
          "REROUTE skipped — agent %s already excluded for binding %s",
          agentId, context.resolvedBindingName());
      return false;
    }
    excludedAgents.add(agentId);
    working.setPath(diagPath, excludedAgents);

    eventBus.publish(
        EventBusAddresses.CONTEXT_CHANGED,
        new CaseContextChangedEvent(instance, mutableCtx.snapshot(), ContextLayer.WORKING));
    LOG.infof(
        "REROUTE: excluded agent %s from binding %s, case %s",
        agentId, context.resolvedBindingName(), context.caseId());
    writeAuditLog(context, StallRecoveryAction.REROUTE, CaseHubEventType.STALL_RECOVERY_INITIATED);
    return true;
  }

  boolean executeCancel(StallRecoveryContext context, CaseInstance instance) {
    if (context.resolvedPlanItemId() == null) {
      LOG.warnf(
          "CANCEL requires planItemId — falling back to NOTIFY for case %s", context.caseId());
      return executeNotify(context);
    }
    if (!planItemStore.isResolvable()) return false;

    PlanItemStore store = planItemStore.get();
    var record =
        store.findByCaseId(context.caseId(), context.tenancyId()).stream()
            .filter(r -> r.planItemId().equals(context.resolvedPlanItemId()))
            .findFirst()
            .orElse(null);
    if (record == null || record.status() != TaskStatus.RUNNING) {
      LOG.debugf("CANCEL skipped — PlanItem %s not RUNNING", context.resolvedPlanItemId());
      return false;
    }

    store.updateStatus(context.resolvedPlanItemId(), TaskStatus.CANCELLED, context.tenancyId());
    eventBus.publish(
        EventBusAddresses.WORKER_OUTCOME_RESOLVED,
        new WorkerOutcomeResolvedEvent(
            instance,
            record.executorName(),
            context.resolvedBindingName(),
            null,
            OutcomeDisposition.FAULT));
    LOG.infof(
        "CANCEL: cancelled PlanItem %s for case %s",
        context.resolvedPlanItemId(), context.caseId());
    writeAuditLog(context, StallRecoveryAction.CANCEL, CaseHubEventType.STALL_RECOVERY_INITIATED);
    return true;
  }

  boolean executeExpire(StallRecoveryContext context, CaseInstance instance) {
    if (context.resolvedBindingName() == null || context.resolvedPlanItemId() == null) {
      LOG.warnf(
          "EXPIRE requires binding and planItemId — falling back to NOTIFY for case %s",
          context.caseId());
      return executeNotify(context);
    }
    if (!planItemStore.isResolvable()) return false;

    PlanItemStore store = planItemStore.get();
    var record =
        store.findByCaseId(context.caseId(), context.tenancyId()).stream()
            .filter(r -> r.planItemId().equals(context.resolvedPlanItemId()))
            .findFirst()
            .orElse(null);
    if (record == null || record.status() != TaskStatus.RUNNING) {
      LOG.debugf("EXPIRE skipped — PlanItem %s not RUNNING", context.resolvedPlanItemId());
      return false;
    }

    eventBus.publish(
        EventBusAddresses.WORKER_OUTCOME_RESOLVED,
        new WorkerOutcomeResolvedEvent(
            instance,
            record.executorName(),
            context.resolvedBindingName(),
            null,
            OutcomeDisposition.EXHAUSTED));
    LOG.infof(
        "EXPIRE: published WORKER_OUTCOME_RESOLVED(EXHAUSTED) for binding %s, case %s",
        context.resolvedBindingName(), context.caseId());
    writeAuditLog(context, StallRecoveryAction.EXPIRE, CaseHubEventType.STALL_RECOVERY_INITIATED);
    return true;
  }

  boolean executeEscalate(StallRecoveryContext context, CaseInstance instance) {
    if (!judgmentScheduler.isResolvable()) {
      LOG.warnf(
          "ESCALATE: JudgmentScheduler not available — falling back to NOTIFY for case %s",
          context.caseId());
      return executeNotify(context);
    }

    String bindingName =
        context.resolvedBindingName() != null ? context.resolvedBindingName() : "stall-recovery";

    JudgmentTarget target =
        JudgmentTarget.builder()
            .prompt(
                "Stall recovery escalation: "
                    + context.conditionType()
                    + " — "
                    + context.alertSummary())
            .build();
    Map<String, Object> inputData =
        Map.of(
            "conditionType", context.conditionType().name(),
            "alertSummary", context.alertSummary() != null ? context.alertSummary() : "",
            "affectedAgentIds", context.affectedAgentIds(),
            "firedAt", context.firedAt().toString());

    JudgmentPayload.BindingPayload payload =
        new JudgmentPayload.BindingPayload(target, inputData, null, null);
    JudgmentRequest request =
        new JudgmentRequest(context.caseId(), context.tenancyId(), bindingName, payload);

    judgmentScheduler.get().schedule(request);
    writeAuditLog(context, StallRecoveryAction.ESCALATE, CaseHubEventType.STALL_RECOVERY_INITIATED);
    LOG.infof(
        "ESCALATE: scheduled judgment for binding %s, case %s", bindingName, context.caseId());
    return true;
  }

  boolean executeNotify(StallRecoveryContext context) {
    writeAuditLog(context, StallRecoveryAction.NOTIFY, CaseHubEventType.STALL_DETECTED);
    LOG.infof(
        "NOTIFY: stall detected for case %s, condition %s",
        context.caseId(), context.conditionType());
    return true;
  }

  private void writeAuditLog(
      StallRecoveryContext context, StallRecoveryAction action, CaseHubEventType eventType) {
    ObjectNode metadata = MAPPER.createObjectNode();
    metadata.put("conditionType", context.conditionType().name());
    metadata.put("action", action.name());
    metadata.put("caseId", context.caseId().toString());
    if (context.resolvedBindingName() != null) {
      metadata.put("bindingName", context.resolvedBindingName());
    }
    if (!context.affectedAgentIds().isEmpty()) {
      metadata.set("affectedAgentIds", MAPPER.valueToTree(context.affectedAgentIds()));
    }
    if (context.alertSummary() != null) {
      metadata.put("alertSummary", context.alertSummary());
    }

    EventLog log = new EventLog();
    log.setCaseId(context.caseId());
    log.setEventType(eventType);
    log.setStreamType(EventStreamType.SYSTEM);
    log.setTimestamp(Instant.now());
    log.setMetadata(metadata);

    eventLogRepository.append(log, context.tenancyId());
  }

  private StallClassifier resolveClassifier(String classifierId) {
    for (StallClassifier classifier : stallClassifiers) {
      if (classifier.id().equals(classifierId)) return classifier;
    }
    for (StallClassifier classifier : stallClassifiers) {
      return classifier;
    }
    return ctx ->
        ctx.policy()
            .conditionActions()
            .getOrDefault(ctx.recoveryContext().conditionType(), ctx.policy().defaultAction());
  }

  private MutableCaseContext asMutableContext(CaseInstance instance) {
    if (instance.getCaseContext() instanceof MutableCaseContext mutable) {
      return mutable;
    }
    LOG.warnf("CaseContext for case %s is not mutable", instance.getUuid());
    return null;
  }

  @SuppressWarnings("unchecked")
  private Instant getLastRetryAt(WritableLayer working, java.util.UUID caseId) {
    Object raw = working.get("_stallRecovery");
    if (raw instanceof Map<?, ?> map) {
      Object ts = map.get("lastRetryAt");
      if (ts instanceof String s) {
        try {
          return Instant.parse(s);
        } catch (Exception e) {
          return null;
        }
      }
    }
    return null;
  }
}
