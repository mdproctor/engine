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

import static io.casehub.api.model.event.CaseHubEventType.MILESTONE_SLA_VIOLATED;
import static io.casehub.engine.common.internal.event.EventBusAddresses.CONTEXT_CHANGED;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.context.CaseContext;
import io.casehub.api.context.ContextPanel;
import io.casehub.api.model.SlaStatus;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.MilestoneSLAViolatedEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.ReactiveEventLogRepository;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import org.jboss.logging.Logger;

/**
 * Handles {@link MilestoneSLAViolatedEvent}: records to EventLog, updates CaseContext
 * slaStatus=BREACHED.
 */
@ApplicationScoped
public class MilestoneSLAViolatedEventHandler {

  private static final Logger LOG = Logger.getLogger(MilestoneSLAViolatedEventHandler.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Inject ReactiveEventLogRepository reactiveEventLogRepository;
  @Inject EventBus eventBus;

  @ConsumeEvent(value = EventBusAddresses.MILESTONE_SLA_VIOLATED)
  public Uni<Void> onMilestoneSLAViolated(MilestoneSLAViolatedEvent event) {
    CaseInstance caseInstance = event.caseInstance();
    String milestoneName = event.milestoneName();
    Instant violatedAt = event.violatedAt();

    return recordEventLog(event)
        .chain(() -> updateCaseContext(caseInstance, milestoneName, violatedAt))
        .onFailure()
        .invoke(
            t ->
                LOG.errorf(
                    t,
                    "Failed to process MILESTONE_SLA_VIOLATED for caseId=%s milestone=%s",
                    caseInstance.getUuid(),
                    milestoneName));
  }

  private Uni<Void> recordEventLog(MilestoneSLAViolatedEvent event) {
    CaseInstance caseInstance = event.caseInstance();
    String milestoneName = event.milestoneName();

    EventLog eventLog = new EventLog();
    eventLog.setCaseId(caseInstance.getUuid());
    eventLog.setEventType(MILESTONE_SLA_VIOLATED);
    eventLog.setStreamType(EventStreamType.CASE);
    eventLog.setTimestamp(event.violatedAt());

    var payload =
        OBJECT_MAPPER
            .createObjectNode()
            .put("milestoneName", milestoneName)
            .put("slaStatus", SlaStatus.BREACHED.name())
            .put("violatedAt", event.violatedAt().toString());

    eventLog.setPayload(payload);

    LOG.infof(
        "Recording MILESTONE_SLA_VIOLATED for case=%s milestone=%s violatedAt=%s",
        caseInstance.getUuid(), milestoneName, event.violatedAt());

    return reactiveEventLogRepository.append(eventLog, caseInstance.tenancyId);
  }

  private Uni<Void> updateCaseContext(
      CaseInstance caseInstance, String milestoneName, Instant violatedAt) {
    CaseContext context = caseInstance.getCaseContext();

    // Update only the slaStatus field within the milestone state
    String milestoneKey = "milestones." + milestoneName + ".slaStatus";
    context.setPath(milestoneKey, SlaStatus.BREACHED.name());

    LOG.debugf(
        "Updated CaseContext for case=%s milestone=%s: slaStatus=BREACHED at %s",
        caseInstance.getUuid(), milestoneName, violatedAt);

    // Publish CONTEXT_CHANGED event to notify other components
    eventBus.publish(
        CONTEXT_CHANGED,
        new CaseContextChangedEvent(
            caseInstance, caseInstance.getCaseContext().snapshot(), ContextPanel.WORKING));

    return Uni.createFrom().voidItem();
  }
}
