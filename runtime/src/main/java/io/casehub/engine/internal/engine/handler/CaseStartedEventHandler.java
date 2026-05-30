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

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.api.spi.CaseChannelProvider;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.CaseStartedEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.EventLogRepository;
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

/** Records a {@code CASE_STARTED} event and notifies listeners that the context has changed. */
@ApplicationScoped
public class CaseStartedEventHandler {

  private static final Logger LOG = Logger.getLogger(CaseStartedEventHandler.class);

  @Inject EventBus eventBus;

  @Inject EventLogRepository eventLogRepository;

  @Inject SchedulerService schedulerService;

  @Inject Event<CaseLifecycleEvent> lifecycleEvents;

  @Inject CaseChannelProvider caseChannelProvider;

  @Inject LedgerTraceIdProvider traceIdProvider;

  @ConsumeEvent(value = EventBusAddresses.CASE_STARTED, blocking = true)
  public Uni<Void> onCaseStarted(CaseStartedEvent event) {
    final String traceId = traceIdProvider.currentTraceId().orElse(null);
    final CaseInstance instance = event.instance();
    final JsonNode contextSnapshot = instance.getCaseContext().asJsonNode();

    EventLog eventLog = new EventLog();
    eventLog.setCaseId(instance.getUuid());
    eventLog.setEventType(CaseHubEventType.CASE_STARTED);
    eventLog.setStreamType(EventStreamType.CASE);
    eventLog.setTimestamp(Instant.now());
    eventLog.setPayload(contextSnapshot);

    caseChannelProvider.openChannel(instance.getUuid(), "coordination");

    return eventLogRepository
        .append(eventLog)
        .chain(() -> schedulerService.registerScheduledTriggers(instance))
        .invoke(
            () ->
                eventBus.publish(
                    EventBusAddresses.CONTEXT_CHANGED,
                    new CaseContextChangedEvent(instance, contextSnapshot)))
        .chain(
            () ->
                Uni.createFrom()
                    .completionStage(
                        () ->
                            lifecycleEvents.fireAsync(
                                new CaseLifecycleEvent(
                                    instance.getUuid(),
                                    "StartCase",
                                    "CaseStarted",
                                    instance.getState().name(),
                                    null,
                                    "System",
                                    traceId)))
                    .onFailure()
                    .recoverWithItem(
                        t -> {
                          LOG.warnf(
                              t,
                              "CaseLifecycleEvent observer failed for caseId=%s event=CaseStarted",
                              instance.getUuid());
                          return null;
                        })
                    .replaceWithVoid());
  }
}
