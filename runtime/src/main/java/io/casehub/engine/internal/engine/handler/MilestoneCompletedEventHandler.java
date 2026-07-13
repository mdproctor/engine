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

import static io.casehub.api.model.event.CaseHubEventType.MILESTONE_COMPLETED;
import static io.casehub.engine.common.internal.event.EventBusAddresses.CONTEXT_CHANGED;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.context.CaseContext;
import io.casehub.api.context.ContextLayer;
import io.casehub.api.model.Milestone;
import io.casehub.api.model.MilestoneLifecycleStatus;
import io.casehub.api.model.SlaStatus;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.MilestoneCompletedEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.scheduler.JobIdentifier;
import io.casehub.engine.common.spi.ReactiveEventLogRepository;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.engine.common.spi.scheduler.JobScheduler;
import io.casehub.ledger.api.spi.LedgerTraceIdProvider;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Handles {@link MilestoneCompletedEvent}: records to EventLog, updates CaseContext, cancels SLA
 * timeout job.
 */
@ApplicationScoped
public class MilestoneCompletedEventHandler {

  private static final Logger LOG = Logger.getLogger(MilestoneCompletedEventHandler.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Inject ReactiveEventLogRepository reactiveEventLogRepository;
  @Inject EventBus eventBus;
  @Inject JobScheduler scheduler;
  @Inject Event<CaseLifecycleEvent> lifecycleEvents;
  @Inject LedgerTraceIdProvider traceIdProvider;

  @ConsumeEvent(value = EventBusAddresses.MILESTONE_COMPLETED)
  public Uni<Void> onMilestoneCompleted(MilestoneCompletedEvent event) {
    CaseInstance caseInstance = event.caseInstance();
    Milestone milestone = event.milestone();
    Instant completedAt = event.completedAt();
    SlaStatus slaStatusAtCompletion = event.slaStatusAtCompletion();

    return recordEventLog(event)
        .chain(() -> updateCaseContext(caseInstance, milestone, completedAt, slaStatusAtCompletion))
        .chain(() -> cancelSlaTimeoutJob(caseInstance, milestone))
        .chain(
            () -> {
              String traceId = traceIdProvider.currentTraceId().orElse(null);
              return Uni.createFrom()
                  .completionStage(
                      () ->
                          lifecycleEvents.fireAsync(
                              CaseLifecycleEvent.of(
                                  caseInstance,
                                  "CompleteMilestone",
                                  "MilestoneCompleted",
                                  null,
                                  "System",
                                  traceId)))
                  .onFailure()
                  .recoverWithItem(
                      t -> {
                        LOG.warnf(
                            t,
                            "CaseLifecycleEvent observer failed for caseId=%s event=MilestoneCompleted",
                            caseInstance.getUuid());
                        return null;
                      })
                  .replaceWithVoid();
            })
        .onFailure()
        .invoke(
            t ->
                LOG.errorf(
                    t,
                    "Failed to process MILESTONE_COMPLETED for caseId=%s milestone=%s",
                    caseInstance.getUuid(),
                    milestone.getName()));
  }

  private Uni<Void> recordEventLog(MilestoneCompletedEvent event) {
    CaseInstance caseInstance = event.caseInstance();
    Milestone milestone = event.milestone();

    EventLog eventLog = new EventLog();
    eventLog.setCaseId(caseInstance.getUuid());
    eventLog.setEventType(MILESTONE_COMPLETED);
    eventLog.setStreamType(EventStreamType.CASE);
    eventLog.setTimestamp(event.completedAt());

    var payload =
        OBJECT_MAPPER
            .createObjectNode()
            .put("milestoneName", milestone.getName())
            .put("lifecycleStatus", MilestoneLifecycleStatus.COMPLETED.name())
            .put("slaStatus", event.slaStatusAtCompletion().name())
            .put("completedAt", event.completedAt().toString());

    eventLog.setPayload(payload);

    LOG.infof(
        "Recording MILESTONE_COMPLETED for case=%s milestone=%s slaStatus=%s",
        caseInstance.getUuid(), milestone.getName(), event.slaStatusAtCompletion());

    return reactiveEventLogRepository.append(eventLog, caseInstance.tenancyId);
  }

  private Uni<Void> updateCaseContext(
      CaseInstance caseInstance,
      Milestone milestone,
      Instant completedAt,
      SlaStatus slaStatusAtCompletion) {
    CaseContext context = caseInstance.getCaseContext();

    Map<String, Object> milestoneState = new HashMap<>();
    milestoneState.put("lifecycleStatus", MilestoneLifecycleStatus.COMPLETED.name());
    milestoneState.put("slaStatus", slaStatusAtCompletion.name());
    milestoneState.put("completedAt", completedAt.toString());

    context.setPath("milestones." + milestone.getName(), milestoneState);

    LOG.debugf(
        "Updated CaseContext for case=%s milestone=%s: lifecycleStatus=COMPLETED, completedAt=%s",
        caseInstance.getUuid(), milestone.getName(), completedAt);

    // Publish CONTEXT_CHANGED event to notify other components
    eventBus.publish(
        CONTEXT_CHANGED,
        new CaseContextChangedEvent(
            caseInstance, caseInstance.getCaseContext().snapshot(), ContextLayer.WORKING));

    return Uni.createFrom().voidItem();
  }

  private Uni<Void> cancelSlaTimeoutJob(CaseInstance caseInstance, Milestone milestone) {
    JobIdentifier jobId =
        JobIdentifier.of("milestone-" + milestone.getName(), "case-" + caseInstance.getUuid());

    return scheduler
        .cancel(jobId)
        .invoke(
            deleted -> {
              if (deleted) {
                LOG.infof(
                    "Cancelled SLA timeout job for case=%s milestone=%s",
                    caseInstance.getUuid(), milestone.getName());
              } else {
                LOG.debugf(
                    "SLA timeout job not found for case=%s milestone=%s (already fired or never scheduled)",
                    caseInstance.getUuid(), milestone.getName());
              }
            })
        .replaceWithVoid();
  }
}
