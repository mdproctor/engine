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

import static io.casehub.api.model.event.CaseHubEventType.MILESTONE_ACTIVATED;
import static io.casehub.engine.common.internal.event.EventBusAddresses.CONTEXT_CHANGED;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.context.CaseContext;
import io.casehub.api.context.ContextPanel;
import io.casehub.api.model.Milestone;
import io.casehub.api.model.MilestoneLifecycleStatus;
import io.casehub.api.model.SlaStatus;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.MilestoneActivatedEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.scheduler.JobIdentifier;
import io.casehub.engine.common.internal.scheduler.ScheduleStrategy.FixedAtSchedule;
import io.casehub.engine.common.internal.scheduler.ScheduledJobRequest;
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
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Handles {@link MilestoneActivatedEvent}: records to EventLog, updates CaseContext, schedules SLA
 * timeout job.
 */
@ApplicationScoped
public class MilestoneActivatedEventHandler {

  private static final Logger LOG = Logger.getLogger(MilestoneActivatedEventHandler.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Inject ReactiveEventLogRepository reactiveEventLogRepository;
  @Inject EventBus eventBus;
  @Inject JobScheduler scheduler;
  @Inject Event<CaseLifecycleEvent> lifecycleEvents;
  @Inject LedgerTraceIdProvider traceIdProvider;

  @ConsumeEvent(value = EventBusAddresses.MILESTONE_ACTIVATED)
  public Uni<Void> onMilestoneActivated(MilestoneActivatedEvent event) {
    CaseInstance caseInstance = event.caseInstance();
    Milestone milestone = event.milestone();
    Instant activatedAt = event.activatedAt();
    Instant slaDeadline = event.slaDeadline();

    return recordEventLog(event)
        .chain(() -> updateCaseContext(caseInstance, milestone, activatedAt, slaDeadline))
        .chain(() -> scheduleSlaTimeoutJob(caseInstance, milestone, slaDeadline))
        .chain(
            () -> {
              String traceId = traceIdProvider.currentTraceId().orElse(null);
              return Uni.createFrom()
                  .completionStage(
                      () ->
                          lifecycleEvents.fireAsync(
                              new CaseLifecycleEvent(
                                  caseInstance.getUuid(),
                                  caseInstance.tenancyId,
                                  "ActivateMilestone",
                                  "MilestoneActivated",
                                  caseInstance.getState().name(),
                                  null,
                                  "System",
                                  traceId)))
                  .onFailure()
                  .recoverWithItem(
                      t -> {
                        LOG.warnf(
                            t,
                            "CaseLifecycleEvent observer failed for caseId=%s event=MilestoneActivated",
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
                    "Failed to process MILESTONE_ACTIVATED for caseId=%s milestone=%s",
                    caseInstance.getUuid(),
                    milestone.getName()));
  }

  private Uni<Void> recordEventLog(MilestoneActivatedEvent event) {
    CaseInstance caseInstance = event.caseInstance();
    Milestone milestone = event.milestone();

    EventLog eventLog = new EventLog();
    eventLog.setCaseId(caseInstance.getUuid());
    eventLog.setEventType(MILESTONE_ACTIVATED);
    eventLog.setStreamType(EventStreamType.CASE);
    eventLog.setTimestamp(event.activatedAt());

    var payload =
        OBJECT_MAPPER
            .createObjectNode()
            .put("milestoneName", milestone.getName())
            .put("lifecycleStatus", MilestoneLifecycleStatus.ACTIVE.name())
            .put("slaStatus", SlaStatus.ON_TRACK.name())
            .put("activatedAt", event.activatedAt().toString());

    if (event.slaDeadline() != null) {
      payload.put("slaDeadline", event.slaDeadline().toString());
    }

    eventLog.setPayload(payload);

    LOG.infof(
        "Recording MILESTONE_ACTIVATED for case=%s milestone=%s",
        caseInstance.getUuid(), milestone.getName());

    return reactiveEventLogRepository.append(eventLog, caseInstance.tenancyId);
  }

  private Uni<Void> updateCaseContext(
      CaseInstance caseInstance, Milestone milestone, Instant activatedAt, Instant slaDeadline) {
    CaseContext context = caseInstance.getCaseContext();
    String lifecyclePath = "milestones." + milestone.getName() + ".lifecycleStatus";
    String currentLifecycleStatus = context.getPathAsString(lifecyclePath);
    if (isTerminalLifecycleStatus(currentLifecycleStatus)) {
      LOG.debugf(
          "Skipping stale MILESTONE_ACTIVATED for case=%s milestone=%s because current status is %s",
          caseInstance.getUuid(), milestone.getName(), currentLifecycleStatus);
      return Uni.createFrom().voidItem();
    }

    Map<String, Object> milestoneState = new HashMap<>();
    milestoneState.put("lifecycleStatus", MilestoneLifecycleStatus.ACTIVE.name());
    milestoneState.put("slaStatus", SlaStatus.ON_TRACK.name());
    milestoneState.put("activatedAt", activatedAt.toString());
    milestoneState.put("slaDeadline", slaDeadline != null ? slaDeadline.toString() : null);
    milestoneState.put("completedAt", null);

    context.setPath("milestones." + milestone.getName(), milestoneState);

    LOG.infof(
        "Updated CaseContext for case=%s milestone=%s: %s",
        caseInstance.getUuid(), milestone.getName(), milestoneState);

    // Publish CONTEXT_CHANGED event to notify other components (I3)
    eventBus.publish(
        CONTEXT_CHANGED,
        new CaseContextChangedEvent(
            caseInstance, caseInstance.getCaseContext().snapshot(), ContextPanel.WORKING));

    return Uni.createFrom().voidItem();
  }

  private Uni<Void> scheduleSlaTimeoutJob(
      CaseInstance caseInstance, Milestone milestone, Instant slaDeadline) {
    String lifecyclePath = "milestones." + milestone.getName() + ".lifecycleStatus";
    String currentLifecycleStatus = caseInstance.getCaseContext().getPathAsString(lifecyclePath);
    if (!MilestoneLifecycleStatus.ACTIVE.name().equals(currentLifecycleStatus)) {
      LOG.debugf(
          "Skipping SLA timeout job for case=%s milestone=%s because current status is %s",
          caseInstance.getUuid(), milestone.getName(), currentLifecycleStatus);
      return Uni.createFrom().voidItem();
    }

    if (slaDeadline == null) {
      LOG.debugf("No SLA configured for milestone=%s, skipping timeout job", milestone.getName());
      return Uni.createFrom().voidItem();
    }

    Duration delay = Duration.between(Instant.now(), slaDeadline);
    if (delay.isNegative() || delay.isZero()) {
      LOG.warnf(
          "SLA deadline already passed for milestone=%s, immediate violation", milestone.getName());
      // TODO: could immediately fire SLA violation here
      return Uni.createFrom().voidItem();
    }

    JobIdentifier jobId =
        JobIdentifier.of("milestone-" + milestone.getName(), "case-" + caseInstance.getUuid());

    Map<String, Object> jobData = new HashMap<>();
    jobData.put("caseId", caseInstance.getUuid().toString());
    jobData.put("milestoneName", milestone.getName());

    return scheduler
        .schedule(
            ScheduledJobRequest.builder()
                .jobId(jobId)
                .schedule(new FixedAtSchedule(slaDeadline.toEpochMilli()))
                .data(jobData))
        .invoke(
            () ->
                LOG.infof(
                    "Scheduled SLA timeout job for milestone=%s at %s",
                    milestone.getName(), slaDeadline));
  }

  private boolean isTerminalLifecycleStatus(String lifecycleStatus) {
    return MilestoneLifecycleStatus.COMPLETED.name().equals(lifecycleStatus)
        || MilestoneLifecycleStatus.FAILED.name().equals(lifecycleStatus)
        || MilestoneLifecycleStatus.CANCELLED.name().equals(lifecycleStatus);
  }
}
