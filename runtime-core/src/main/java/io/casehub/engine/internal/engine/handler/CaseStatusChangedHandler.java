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
package io.casehub.engine.internal.engine.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.context.CaseContext;
import io.casehub.api.context.ContextLayer;
import io.casehub.api.context.MutableCaseContext;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.api.spi.CaseChannelProvider;
import io.casehub.api.spi.CaseOutcomeEvent;
import io.casehub.api.spi.CaseOutcomeObserver;
import io.casehub.api.spi.event.CaseCompletedEvent;
import io.casehub.api.spi.event.CaseFaultedEvent;
import io.casehub.api.spi.event.EventDispatcher;
import io.casehub.engine.common.internal.event.ActionGateCancelledEvent;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.CaseStatusChanged;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseTerminatedException;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.engine.common.spi.recovery.CompoundLockRegistry;
import io.casehub.engine.internal.acl.WorkerGrantOrchestrator;
import io.casehub.engine.internal.engine.CaseCompletionTracker;
import io.casehub.engine.internal.recovery.CaseRecoveryStateRegistry;
import io.casehub.engine.internal.scheduler.SchedulerService;
import io.casehub.ledger.api.spi.LedgerTraceIdProvider;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jboss.logging.Logger;

public class CaseStatusChangedHandler {

  private static final Logger LOG = Logger.getLogger(CaseStatusChangedHandler.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final EventDispatcher eventDispatcher;
  private final CaseInstanceRepository caseInstanceRepository;
  private final SchedulerService schedulerService;
  private final Consumer<CaseLifecycleEvent> lifecycleEventConsumer;
  private final CaseChannelProvider caseChannelProvider;
  private final LedgerTraceIdProvider traceIdProvider;
  private final List<CaseOutcomeObserver> outcomeObservers;
  private final CaseCompletionTracker caseCompletionTracker;
  private final io.casehub.engine.common.internal.worker.scope.ScopedWorkerRegistry
      scopedWorkerRegistry;
  private final ContextOutputApplier contextOutputApplier;
  private final WorkerGrantOrchestrator workerGrantOrchestrator;
  private final io.casehub.engine.common.internal.channel.DataChannelRegistry dataChannelRegistry;
  private final CaseRecoveryStateRegistry recoveryStateRegistry;
  private final CompoundLockRegistry compoundLockRegistry;

  public CaseStatusChangedHandler(
      EventDispatcher eventDispatcher,
      CaseInstanceRepository caseInstanceRepository,
      SchedulerService schedulerService,
      Consumer<CaseLifecycleEvent> lifecycleEventConsumer,
      CaseChannelProvider caseChannelProvider,
      LedgerTraceIdProvider traceIdProvider,
      List<CaseOutcomeObserver> outcomeObservers,
      CaseCompletionTracker caseCompletionTracker,
      io.casehub.engine.common.internal.worker.scope.ScopedWorkerRegistry scopedWorkerRegistry,
      ContextOutputApplier contextOutputApplier,
      WorkerGrantOrchestrator workerGrantOrchestrator,
      io.casehub.engine.common.internal.channel.DataChannelRegistry dataChannelRegistry,
      CaseRecoveryStateRegistry recoveryStateRegistry,
      CompoundLockRegistry compoundLockRegistry) {
    this.eventDispatcher = eventDispatcher;
    this.caseInstanceRepository = caseInstanceRepository;
    this.schedulerService = schedulerService;
    this.lifecycleEventConsumer = lifecycleEventConsumer;
    this.caseChannelProvider = caseChannelProvider;
    this.traceIdProvider = traceIdProvider;
    this.outcomeObservers = outcomeObservers;
    this.caseCompletionTracker = caseCompletionTracker;
    this.scopedWorkerRegistry = scopedWorkerRegistry;
    this.contextOutputApplier = contextOutputApplier;
    this.workerGrantOrchestrator = workerGrantOrchestrator;
    this.dataChannelRegistry = dataChannelRegistry;
    this.recoveryStateRegistry = recoveryStateRegistry;
    this.compoundLockRegistry = compoundLockRegistry;
  }

  public void handle(CaseStatusChanged event) {
    final String traceId = traceIdProvider.currentTraceId().orElse(null);
    final CaseInstance caseInstance = event.instance();
    final CaseStatus newState = CaseStatus.valueOf(event.newStatus());
    final String oldStatus = event.oldStatus();

    if (newState.isTerminal()) {
      if (!caseInstance.trySetTerminalState(newState)) {
        LOG.infof(
            "Ignoring duplicate terminal transition for caseId=%s — already %s, rejecting %s",
            caseInstance.getUuid(), caseInstance.getState(), newState);
        return;
      }
    } else {
      caseInstance.setState(newState);
    }

    LOG.infof(
        "Case status changed: caseId=%s, %s -> %s",
        caseInstance.getUuid(), oldStatus, event.newStatus());

    EventLog eventLog = new EventLog();
    eventLog.setCaseId(caseInstance.getUuid());
    eventLog.setEventType(resolveState(newState));
    eventLog.setStreamType(EventStreamType.CASE);
    eventLog.setTimestamp(Instant.now());
    final ObjectNode metadataNode =
        OBJECT_MAPPER
            .createObjectNode()
            .put("oldStatus", oldStatus)
            .put("newStatus", event.newStatus());
    if (event.satisfiedGoalName() != null) {
      metadataNode.put("goalName", event.satisfiedGoalName());
      metadataNode.put("goalKind", event.satisfiedGoalKind());
    }
    eventLog.setMetadata(metadataNode);

    caseInstanceRepository.updateStateAndAppendEvent(
        caseInstance, eventLog, caseInstance.tenancyId);

    if (newState.isTerminal()) {
      CaseContext contextSnapshot = caseInstance.getCaseContext().snapshot();
      if (newState == CaseStatus.COMPLETED) {
        caseCompletionTracker.complete(caseInstance.getUuid(), contextSnapshot);
      } else {
        caseCompletionTracker.completeExceptionally(
            caseInstance.getUuid(), new CaseTerminatedException(caseInstance.getUuid(), newState));
      }
      caseChannelProvider
          .listChannels(caseInstance.getUuid())
          .forEach(caseChannelProvider::closeChannel);
      workerGrantOrchestrator.revokeForCase(caseInstance.getUuid());
      if (caseInstance.getPendingActionGate() != null) {
        eventDispatcher.dispatch(
            new ActionGateCancelledEvent(
                caseInstance.getUuid(),
                caseInstance.tenancyId,
                caseInstance.getPendingActionGate().gateId()));
      }
      schedulerService.cancelAllTriggers(caseInstance.getUuid());
      scopedWorkerRegistry.terminateByCase(caseInstance.getUuid());
      dataChannelRegistry.closeByCase(caseInstance.getUuid());
      contextOutputApplier.evict(caseInstance.getUuid());
      recoveryStateRegistry.evict(caseInstance.getUuid());
      compoundLockRegistry.cleanForCase(caseInstance.getUuid());
      if (caseInstance.getCaseContext() instanceof MutableCaseContext mctx) {
        mctx.close();
      }
    }
    if (newState.isTerminal()) {
      fireOutcomeObservers(
          caseInstance, newState, event.satisfiedGoalName(), event.satisfiedGoalKind());
    }

    if (newState == CaseStatus.COMPLETED) {
      eventDispatcher.dispatch(new CaseCompletedEvent(caseInstance.getUuid().toString()));
    } else if (newState == CaseStatus.FAULTED) {
      eventDispatcher.dispatch(new CaseFaultedEvent(caseInstance.getUuid().toString()));
    }

    if (newState == CaseStatus.RUNNING) {
      eventDispatcher.dispatch(
          new CaseContextChangedEvent(
              caseInstance, caseInstance.getCaseContext().snapshot(), null));
    }

    lifecycleEventConsumer.accept(
        CaseLifecycleEvent.of(
            caseInstance,
            resolveCommandType(newState),
            resolveEventType(newState),
            null,
            "System",
            traceId,
            event.satisfiedGoalName(),
            event.satisfiedGoalKind()));
  }

  private void fireOutcomeObservers(
      CaseInstance caseInstance, CaseStatus newState, String goalName, String goalKind) {
    final String caseType =
        caseInstance.getCaseMetaModel() != null
            ? caseInstance.getCaseMetaModel().getName()
            : "unknown";
    final Map<String, Object> snapshot;
    try {
      snapshot =
          OBJECT_MAPPER.convertValue(
              caseInstance.getCaseContext().layer(ContextLayer.WORKING).asJsonNode(), MAP_TYPE);
    } catch (Exception e) {
      LOG.warnf(
          e,
          "Failed to convert case context snapshot for CaseOutcomeEvent caseId=%s",
          caseInstance.getUuid());
      return;
    }
    final Map<String, Object> outcomeMetadata =
        goalName != null ? Map.of("goalName", goalName, "goalKind", goalKind) : Map.of();
    final CaseOutcomeEvent outcomeEvent =
        new CaseOutcomeEvent(
            caseType,
            caseInstance.tenancyId,
            caseInstance.getUuid(),
            snapshot,
            newState.name(),
            Instant.now(),
            outcomeMetadata);

    for (CaseOutcomeObserver observer : outcomeObservers) {
      try {
        observer.onOutcome(outcomeEvent);
      } catch (Exception e) {
        LOG.warnf(
            e,
            "CaseOutcomeObserver %s failed for caseId=%s — continuing",
            observer.getClass().getSimpleName(),
            caseInstance.getUuid());
      }
    }
  }

  private CaseHubEventType resolveState(CaseStatus state) {
    return switch (state) {
      case COMPLETED -> CaseHubEventType.CASE_COMPLETED;
      case FAULTED -> CaseHubEventType.CASE_FAULTED;
      case CANCELLED -> CaseHubEventType.CASE_CANCELLED;
      default -> CaseHubEventType.CASE_STATUS_CHANGED;
    };
  }

  private String resolveCommandType(CaseStatus state) {
    return switch (state) {
      case COMPLETED -> "CompleteCase";
      case FAULTED -> "FaultCase";
      case CANCELLED -> "CancelCase";
      case SUSPENDED -> "SuspendCase";
      case WAITING -> "SubmitWork";
      case RUNNING -> "ResumeCase";
      case STARTING -> "InitCase";
      default -> "TransitionCase";
    };
  }

  private String resolveEventType(CaseStatus state) {
    return switch (state) {
      case COMPLETED -> "CaseCompleted";
      case FAULTED -> "CaseFaulted";
      case CANCELLED -> "CaseCancelled";
      case SUSPENDED -> "CaseSuspended";
      case WAITING -> "WorkSubmitted";
      case RUNNING -> "CaseResumed";
      case STARTING -> "CaseInitializing";
      default -> "CaseStatusChanged";
    };
  }
}
