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
import io.casehub.api.context.ContextPanel;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.api.spi.CaseChannelProvider;
import io.casehub.api.spi.CaseOutcomeEvent;
import io.casehub.api.spi.CaseOutcomeObserver;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.CaseStatusChanged;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseTerminatedException;
import io.casehub.engine.common.spi.ReactiveCaseInstanceRepository;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.engine.internal.engine.CaseCompletionTracker;
import io.casehub.engine.internal.scheduler.SchedulerService;
import io.casehub.ledger.api.spi.LedgerTraceIdProvider;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Persists a case status change event and atomically updates the instance state. Publishes a
 * downstream event (CASE_COMPLETED or CASE_FAULTED) after the write commits.
 */
@ApplicationScoped
public class CaseStatusChangedHandler {

  private static final Logger LOG = Logger.getLogger(CaseStatusChangedHandler.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  @Inject EventBus eventBus;

  @Inject ReactiveCaseInstanceRepository reactiveCaseInstanceRepository;

  @Inject SchedulerService schedulerService;

  @Inject Event<CaseLifecycleEvent> lifecycleEvents;

  @Inject CaseChannelProvider caseChannelProvider;

  @Inject LedgerTraceIdProvider traceIdProvider;

  @Inject Instance<CaseOutcomeObserver> outcomeObservers;

  @Inject CaseCompletionTracker caseCompletionTracker;

  @ConsumeEvent(value = EventBusAddresses.CASE_STATUS_CHANGED, blocking = true)
  public Uni<Void> onCaseStatusChangedHandler(CaseStatusChanged event) {
    final String traceId = traceIdProvider.currentTraceId().orElse(null);
    final CaseInstance caseInstance = event.instance();
    final CaseStatus newState = CaseStatus.valueOf(event.newStatus());
    final String oldStatus = event.oldStatus();

    LOG.infof(
        "Case status changed: caseId=%s, %s -> %s",
        caseInstance.getUuid(), oldStatus, event.newStatus());

    caseInstance.setState(newState);

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
      metadataNode.put("goalKind", event.satisfiedGoalKind().value());
    }
    eventLog.setMetadata(metadataNode);

    return reactiveCaseInstanceRepository
        .updateStateAndAppendEvent(caseInstance, eventLog, caseInstance.tenancyId)
        .chain(
            () -> {
              if (isTerminalState(newState)) {
                CaseContext contextSnapshot = caseInstance.getCaseContext().snapshot();
                if (newState == CaseStatus.COMPLETED) {
                  caseCompletionTracker.complete(caseInstance.getUuid(), contextSnapshot);
                } else {
                  caseCompletionTracker.completeExceptionally(
                      caseInstance.getUuid(),
                      new CaseTerminatedException(caseInstance.getUuid(), newState));
                }
                caseChannelProvider
                    .listChannels(caseInstance.getUuid())
                    .forEach(caseChannelProvider::closeChannel);
                // Cancel pending gate WorkItem if case terminates while gate is pending
                if (caseInstance.getPendingActionGate() != null) {
                  eventBus.publish(
                      EventBusAddresses.ACTION_GATE_CANCELLED,
                      new io.casehub.engine.common.internal.event.ActionGateCancelledEvent(
                          caseInstance.getUuid(), caseInstance.getPendingActionGate().gateId()));
                }
                return schedulerService.cancelAllTriggers(caseInstance.getUuid());
              }
              return Uni.createFrom().voidItem();
            })
        .invoke(
            () -> {
              // Notify outcome observers on terminal state — CBR Retain step. Refs engine#477.
              // Called before event bus publishes so observer failures don't block downstream
              // events.
              if (isTerminalState(newState)) {
                fireOutcomeObservers(
                    caseInstance, newState, event.satisfiedGoalName(), event.satisfiedGoalKind());
              }
              // Fire-and-forget: downstream event bus consumers (CASE_COMPLETED, CASE_FAULTED,
              // CONTEXT_CHANGED) do not need to complete before this handler returns.
              // Wrapped in try-catch: codec may not be registered in unit test contexts
              // where the handler is called directly without the full event bus setup.
              String eventBusAddress = resolveStateAsString(newState);
              if (eventBusAddress != null) {
                try {
                  eventBus.publish(eventBusAddress, caseInstance);
                } catch (Exception e) {
                  LOG.warnf(
                      e,
                      "Event bus publish failed for %s caseId=%s — non-fatal",
                      eventBusAddress,
                      caseInstance.getUuid());
                }
              }
              // On resume (SUSPENDED → RUNNING), re-evaluate the context so eligible workers fire.
              if (newState == CaseStatus.RUNNING) {
                eventBus.publish(
                    EventBusAddresses.CONTEXT_CHANGED,
                    new CaseContextChangedEvent(
                        caseInstance, caseInstance.getCaseContext().snapshot(), null));
              }
            })
        .chain(
            () -> {
              // Await CDI event delivery so @ObservesAsync observers run before this handler's
              // Uni completes. Failure is logged and recovered — observer errors must not fail
              // case completion (engine#393).
              return Uni.createFrom()
                  .completionStage(
                      () ->
                          lifecycleEvents.fireAsync(
                              new CaseLifecycleEvent(
                                  caseInstance.getUuid(),
                                  caseInstance.tenancyId,
                                  resolveCommandType(newState),
                                  resolveEventType(newState),
                                  newState.name(),
                                  null,
                                  "System",
                                  traceId)))
                  .onFailure()
                  .recoverWithItem(
                      t -> {
                        LOG.warnf(
                            t,
                            "CaseLifecycleEvent observer failed for caseId=%s event=%s",
                            caseInstance.getUuid(),
                            resolveEventType(newState));
                        return null;
                      })
                  .replaceWithVoid();
            });
  }

  private void fireOutcomeObservers(
      CaseInstance caseInstance,
      CaseStatus newState,
      String goalName,
      io.casehub.api.model.GoalKind goalKind) {
    final String caseType =
        caseInstance.getCaseMetaModel() != null
            ? caseInstance.getCaseMetaModel().getName()
            : "unknown";
    final Map<String, Object> snapshot;
    try {
      snapshot =
          OBJECT_MAPPER.convertValue(
              caseInstance.getCaseContext().panel(ContextPanel.WORKING).asJsonNode(), MAP_TYPE);
    } catch (Exception e) {
      LOG.warnf(
          e,
          "Failed to convert case context snapshot for CaseOutcomeEvent caseId=%s",
          caseInstance.getUuid());
      return;
    }
    final Map<String, Object> outcomeMetadata =
        goalName != null ? Map.of("goalName", goalName, "goalKind", goalKind.value()) : Map.of();
    final CaseOutcomeEvent outcomeEvent =
        new CaseOutcomeEvent(
            caseType,
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

  private boolean isTerminalState(CaseStatus state) {
    return state == CaseStatus.COMPLETED
        || state == CaseStatus.FAULTED
        || state == CaseStatus.CANCELLED;
  }

  private CaseHubEventType resolveState(CaseStatus state) {
    return switch (state) {
      case COMPLETED -> CaseHubEventType.CASE_COMPLETED;
      case FAULTED -> CaseHubEventType.CASE_FAULTED;
      case CANCELLED -> CaseHubEventType.CASE_CANCELLED;
      default -> CaseHubEventType.CASE_STATUS_CHANGED;
    };
  }

  private String resolveStateAsString(CaseStatus state) {
    return switch (state) {
      case COMPLETED -> EventBusAddresses.CASE_COMPLETED;
      case FAULTED -> EventBusAddresses.CASE_FAULTED;
      default -> null;
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
