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

import static io.casehub.api.model.event.CaseHubEventType.MILESTONE_REACHED;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.Milestone;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.event.CaseLifecycleEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.MilestoneReachedEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.ledger.api.spi.LedgerTraceIdProvider;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.time.Instant;

/** Records a MILESTONE_REACHED event. */
@ApplicationScoped
public class MilestoneReachedEventHandler {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Inject EventLogRepository eventLogRepository;

  @Inject Event<CaseLifecycleEvent> lifecycleEvents;

  @Inject LedgerTraceIdProvider traceIdProvider;

  @ConsumeEvent(value = EventBusAddresses.MILESTONE_REACHED)
  public Uni<Void> onMilestoneReachedEventHandler(MilestoneReachedEvent event) {
    final String traceId = traceIdProvider.currentTraceId().orElse(null);
    final CaseInstance caseInstance = event.caseInstance();
    final Milestone milestone = event.milestone();

    EventLog eventLog = new EventLog();
    eventLog.setCaseId(caseInstance.getUuid());
    eventLog.setEventType(MILESTONE_REACHED);
    eventLog.setStreamType(EventStreamType.CASE);
    eventLog.setTimestamp(Instant.now());
    eventLog.setMetadata(
        OBJECT_MAPPER
            .createObjectNode()
            .put("name", milestone.getName())
            .put("description", milestone.getDescription()));

    return eventLogRepository
        .append(eventLog)
        .invoke(
            () ->
                lifecycleEvents.fireAsync(
                    new CaseLifecycleEvent(
                        caseInstance.getUuid(),
                        "ReachMilestone",
                        "MilestoneReached",
                        caseInstance.getState().name(),
                        null,
                        "System",
                        traceId)));
  }
}
