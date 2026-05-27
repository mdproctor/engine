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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.api.spi.CaseChannelProvider;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.CaseStatusChanged;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.engine.internal.scheduler.SchedulerService;
import io.casehub.ledger.api.spi.LedgerTraceIdProvider;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.time.Instant;
import org.jboss.logging.Logger;

/**
 * Persists a case status change event and atomically updates the instance state. Publishes a
 * downstream event (CASE_COMPLETED or CASE_FAULTED) after the write commits.
 */
@ApplicationScoped
public class CaseStatusChangedHandler {

  private static final Logger LOG = Logger.getLogger(CaseStatusChangedHandler.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Inject EventBus eventBus;

  @Inject CaseInstanceRepository caseInstanceRepository;

  @Inject SchedulerService schedulerService;

  @Inject Event<CaseLifecycleEvent> lifecycleEvents;

  @Inject CaseChannelProvider caseChannelProvider;

  @Inject LedgerTraceIdProvider traceIdProvider;

  @ConsumeEvent(value = EventBusAddresses.CASE_STATUS_CHANGED)
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
    eventLog.setMetadata(
        OBJECT_MAPPER
            .createObjectNode()
            .put("oldStatus", oldStatus)
            .put("newStatus", event.newStatus()));

    return caseInstanceRepository
        .updateStateAndAppendEvent(caseInstance, eventLog)
        .chain(
            () -> {
              if (isTerminalState(newState)) {
                caseChannelProvider
                    .listChannels(caseInstance.getUuid())
                    .forEach(caseChannelProvider::closeChannel);
                return schedulerService.cancelAllTriggers(caseInstance.getUuid());
              }
              return Uni.createFrom().voidItem();
            })
        .invoke(
            () -> {
              String eventBusAddress = resolveStateAsString(newState);
              if (eventBusAddress != null) {
                eventBus.publish(eventBusAddress, caseInstance);
              }
              // On resume (SUSPENDED → RUNNING), re-evaluate the context so eligible workers fire.
              if (newState == CaseStatus.RUNNING) {
                eventBus.publish(
                    EventBusAddresses.CONTEXT_CHANGED,
                    new CaseContextChangedEvent(
                        caseInstance, caseInstance.getCaseContext().asJsonNode()));
              }
              lifecycleEvents.fireAsync(
                  new CaseLifecycleEvent(
                      caseInstance.getUuid(),
                      resolveCommandType(newState),
                      resolveEventType(newState),
                      newState.name(),
                      null,
                      "System",
                      traceId));
            });
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
      default -> "CaseStatusChanged";
    };
  }
}
