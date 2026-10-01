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
package io.casehub.engine.internal.recovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.context.ContextLayer;
import io.casehub.api.context.MutableCaseContext;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.OutcomeType;
import io.casehub.api.model.RecoveryLevel;
import io.casehub.api.model.RecoveryOverride;
import io.casehub.api.model.RecoveryPolicy;
import io.casehub.api.model.TaskStatus;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.api.spi.recovery.ErrorClassificationContext;
import io.casehub.api.spi.recovery.ErrorClassifier;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.GoalDecomposer;
import io.casehub.engine.common.spi.PlanAdaptationEvaluator;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.common.spi.recovery.BindingRecoveryState;
import io.casehub.engine.common.spi.recovery.CaseRecoveryState;
import io.casehub.engine.common.spi.recovery.CompoundLockRegistry;
import io.casehub.engine.common.spi.recovery.PlanVersionStore;
import io.casehub.engine.common.spi.recovery.RecoveryContext;
import io.casehub.engine.common.spi.recovery.RecoveryCoordinator;
import io.casehub.engine.plan.execution.PlanVersion;
import io.casehub.engine.plan.snapshot.PlanVersionDelta;
import io.casehub.engine.plan.snapshot.PlanVersionTrigger;
import io.casehub.worker.api.WorkerOutcome;
import io.vertx.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

@ApplicationScoped
public class DefaultRecoveryCoordinator implements RecoveryCoordinator {

  private static final Logger LOG = Logger.getLogger(DefaultRecoveryCoordinator.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ErrorClassifier classifier;
  private final CaseRecoveryStateRegistry stateRegistry;
  private final PlanVersionStore planVersionStore;
  private final PlanAdaptationEvaluator adaptationEvaluator;
  private final GoalDecomposer goalDecomposer;
  private final CaseDefinitionRegistry definitionRegistry;
  private final CaseInstanceCache caseInstanceCache;
  private final EventLogRepository eventLogRepository;
  private final EventBus eventBus;
  private final CompoundLockRegistry lockRegistry;

  private final Instance<io.casehub.engine.common.spi.PlanItemStore> planItemStore;

  @Inject
  public DefaultRecoveryCoordinator(
      ErrorClassifier classifier,
      CaseRecoveryStateRegistry stateRegistry,
      PlanVersionStore planVersionStore,
      PlanAdaptationEvaluator adaptationEvaluator,
      GoalDecomposer goalDecomposer,
      CaseDefinitionRegistry definitionRegistry,
      CaseInstanceCache caseInstanceCache,
      EventLogRepository eventLogRepository,
      EventBus eventBus,
      CompoundLockRegistry lockRegistry,
      Instance<io.casehub.engine.common.spi.PlanItemStore> planItemStore) {
    this.classifier = classifier;
    this.stateRegistry = stateRegistry;
    this.planVersionStore = planVersionStore;
    this.adaptationEvaluator = adaptationEvaluator;
    this.goalDecomposer = goalDecomposer;
    this.definitionRegistry = definitionRegistry;
    this.caseInstanceCache = caseInstanceCache;
    this.eventLogRepository = eventLogRepository;
    this.eventBus = eventBus;
    this.lockRegistry = lockRegistry;
    this.planItemStore = planItemStore;
  }

  @Override
  public boolean handleFailure(RecoveryContext context) {
    CaseInstance instance = caseInstanceCache.get(context.caseId());
    if (instance == null) {
      return false;
    }

    CaseDefinition definition = definitionRegistry.getCaseDefinition(instance.getCaseMetaModel());
    if (definition == null) {
      return false;
    }

    RecoveryPolicy policy = definition.getRecoveryPolicy();
    if (policy == null || !policy.enabled()) {
      return false;
    }

    Binding binding = findBinding(definition, context.bindingName());
    RecoveryOverride override = binding != null ? binding.getRecoveryOverride() : null;

    if (override != null && override.skipRecovery()) {
      return false;
    }
    if (override != null
        && !override.skipRecoveryFor().isEmpty()
        && override.skipRecoveryFor().contains(toOutcomeType(context.outcome()))) {
      return false;
    }

    RecoveryLevel level = classifier.classify(buildClassificationContext(context, definition));
    RecoveryLevel maxLevel =
        override != null ? override.effectiveMaxLevel() : RecoveryLevel.FUNDAMENTAL;

    CaseRecoveryState caseState = stateRegistry.getOrCreate(context.caseId());
    BindingRecoveryState bindingState = caseState.getOrCreate(context.bindingName());
    bindingState.setCurrentLevel(level);
    bindingState.incrementRetryCount();
    bindingState.excludeAgent(context.workerName());

    return switch (level) {
      case TRANSIENT -> {
        LOG.infof(
            "Recovery: TRANSIENT classification for case=%s binding=%s — no escalation",
            context.caseId(), context.bindingName());
        yield false;
      }
      case REASONING -> escalateToLevel2(context, instance, definition, maxLevel, caseState);
      case FUNDAMENTAL -> escalateToLevel3(context, instance, definition, maxLevel, caseState);
    };
  }

  private boolean escalateToLevel2(
      RecoveryContext ctx,
      CaseInstance instance,
      CaseDefinition definition,
      RecoveryLevel maxLevel,
      CaseRecoveryState caseState) {
    String compoundId = adaptationEvaluator.findCompoundForBinding(ctx.caseId(), ctx.bindingName());
    if (compoundId == null) {
      LOG.infof(
          "Recovery: binding %s not in a compound — escalating to Level 3", ctx.bindingName());
      if (maxLevel.ordinal() >= RecoveryLevel.FUNDAMENTAL.ordinal()) {
        return escalateToLevel3(ctx, instance, definition, maxLevel, caseState);
      }
      return false;
    }

    var lock = lockRegistry.getLock(ctx.caseId(), compoundId);
    lock.lock();
    try {
      snapshotPlanVersion(
          ctx,
          new PlanVersionTrigger.CompoundAdaptation(
              compoundId,
              ctx.bindingName(),
              RecoveryLevel.REASONING,
              "Level 2 recovery — local patch"));

      adaptationEvaluator.evaluateAdaptation(
          ctx.caseId(), ctx.tenancyId(), ctx.bindingName(), TaskStatus.FAULTED);

      caseState.markCompoundAdapted(compoundId);

      writeEventLog(
          ctx,
          CaseHubEventType.RECOVERY_ESCALATED,
          Map.of(
              "classifiedLevel",
              "REASONING",
              "compoundId",
              compoundId,
              "bindingName",
              ctx.bindingName(),
              "workerName",
              ctx.workerName()));

      LOG.infof(
          "Recovery: Level 2 escalation for case=%s binding=%s compound=%s",
          ctx.caseId(), ctx.bindingName(), compoundId);
      return true;
    } catch (Exception e) {
      LOG.warnf(
          e,
          "Recovery: Level 2 failed for case=%s binding=%s — attempting Level 3",
          ctx.caseId(),
          ctx.bindingName());
      if (maxLevel.ordinal() >= RecoveryLevel.FUNDAMENTAL.ordinal()) {
        return escalateToLevel3(ctx, instance, definition, maxLevel, caseState);
      }
      return false;
    } finally {
      lock.unlock();
    }
  }

  private boolean escalateToLevel3(
      RecoveryContext ctx,
      CaseInstance instance,
      CaseDefinition definition,
      RecoveryLevel maxLevel,
      CaseRecoveryState caseState) {
    if (maxLevel.ordinal() < RecoveryLevel.FUNDAMENTAL.ordinal()) {
      LOG.infof(
          "Recovery: maxLevel prevents Level 3 for case=%s binding=%s",
          ctx.caseId(), ctx.bindingName());
      return false;
    }
    if (caseState.isReplanAttempted()) {
      LOG.infof(
          "Recovery: replan already attempted for case=%s — no further escalation", ctx.caseId());
      return false;
    }

    snapshotPlanVersion(
        ctx,
        new PlanVersionTrigger.CaseReplan(
            RecoveryLevel.FUNDAMENTAL, "Level 3 recovery — full replan", List.of()));

    caseState.markReplanAttempted();

    List<String> obsoletedIds = obsoletePendingPlanItems(ctx.caseId(), ctx.tenancyId());

    if (instance.getCaseContext() instanceof MutableCaseContext mctx) {
      goalDecomposer.decompose(instance, definition, mctx);
    }

    writeEventLog(
        ctx,
        CaseHubEventType.RECOVERY_REPLAN,
        Map.of(
            "classifiedLevel", "FUNDAMENTAL",
            "bindingName", ctx.bindingName(),
            "workerName", ctx.workerName(),
            "obsoletedPlanItems", obsoletedIds.size(),
            "planVersion",
                planVersionStore
                    .getLatest(ctx.caseId(), ctx.tenancyId())
                    .map(PlanVersion::version)
                    .orElse(0)));

    eventBus.publish(
        EventBusAddresses.CONTEXT_CHANGED,
        new CaseContextChangedEvent(
            instance, instance.getCaseContext().snapshot(), ContextLayer.WORKING));

    LOG.infof(
        "Recovery: Level 3 replan for case=%s triggered by binding=%s",
        ctx.caseId(), ctx.bindingName());
    return true;
  }

  private void snapshotPlanVersion(RecoveryContext ctx, PlanVersionTrigger trigger) {
    int nextVersion =
        planVersionStore
            .getLatest(ctx.caseId(), ctx.tenancyId())
            .map(v -> v.version() + 1)
            .orElse(1);
    planVersionStore.store(
        new PlanVersion(
            nextVersion,
            ctx.caseId(),
            Instant.now(),
            trigger,
            null,
            new PlanVersionDelta(List.of(), List.of(), List.of(), Map.of())),
        ctx.tenancyId());
  }

  private void writeEventLog(
      RecoveryContext ctx, CaseHubEventType type, Map<String, Object> metadata) {
    var log = new EventLog();
    log.setCaseId(ctx.caseId());
    log.setWorkerId(ctx.workerName());
    log.setStreamType(EventStreamType.CASE);
    log.setTimestamp(Instant.now());
    log.setEventType(type);
    log.setMetadata(MAPPER.valueToTree(metadata));
    eventLogRepository.append(log, ctx.tenancyId());
  }

  private List<String> obsoletePendingPlanItems(java.util.UUID caseId, String tenancyId) {
    if (!planItemStore.isResolvable()) {
      return List.of();
    }
    var store = planItemStore.get();
    List<String> obsoleted = new java.util.ArrayList<>();
    for (var record : store.findByCaseId(caseId, tenancyId)) {
      if (record.status() == TaskStatus.PENDING) {
        store.updateStatus(record.planItemId(), TaskStatus.OBSOLETE, tenancyId);
        obsoleted.add(record.planItemId());
      }
    }
    if (!obsoleted.isEmpty()) {
      LOG.infof(
          "Recovery: obsoleted %d PENDING PlanItems for case=%s before replan",
          obsoleted.size(), caseId);
    }
    return obsoleted;
  }

  private ErrorClassificationContext buildClassificationContext(
      RecoveryContext ctx, CaseDefinition def) {
    return new ErrorClassificationContext(
        ctx.caseId(),
        ctx.tenancyId(),
        ctx.bindingName(),
        ctx.workerName(),
        ctx.capabilityName(),
        ctx.outcome(),
        ctx.attemptCount(),
        def);
  }

  private Binding findBinding(CaseDefinition def, String bindingName) {
    return def.getBindings().stream()
        .filter(b -> bindingName.equals(b.getName()))
        .findFirst()
        .orElse(null);
  }

  private OutcomeType toOutcomeType(WorkerOutcome<?> outcome) {
    if (outcome == null) {
      return OutcomeType.FAILED;
    }
    return switch (outcome) {
      case WorkerOutcome.Declined<?> d -> OutcomeType.DECLINED;
      case WorkerOutcome.Failed<?> f -> OutcomeType.FAILED;
      case WorkerOutcome.Expired<?> e -> OutcomeType.EXPIRED;
      default -> OutcomeType.FAILED;
    };
  }
}
