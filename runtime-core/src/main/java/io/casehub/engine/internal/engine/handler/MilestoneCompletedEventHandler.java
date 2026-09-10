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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.context.CaseContext;
import io.casehub.api.context.ContextLayer;
import io.casehub.api.model.Milestone;
import io.casehub.api.model.MilestoneLifecycleStatus;
import io.casehub.api.model.SlaStatus;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.api.spi.event.EventDispatcher;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.MilestoneCompletedEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.scheduler.JobIdentifier;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.engine.common.spi.scheduler.JobScheduler;
import io.casehub.ledger.api.spi.LedgerTraceIdProvider;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.jboss.logging.Logger;

public class MilestoneCompletedEventHandler {

  private static final Logger LOG = Logger.getLogger(MilestoneCompletedEventHandler.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final EventLogRepository eventLogRepository;
  private final EventDispatcher eventDispatcher;
  private final JobScheduler scheduler;
  private final Consumer<CaseLifecycleEvent> lifecycleEventConsumer;
  private final LedgerTraceIdProvider traceIdProvider;

  public MilestoneCompletedEventHandler(
      EventLogRepository eventLogRepository,
      EventDispatcher eventDispatcher,
      JobScheduler scheduler,
      Consumer<CaseLifecycleEvent> lifecycleEventConsumer,
      LedgerTraceIdProvider traceIdProvider) {
    this.eventLogRepository = eventLogRepository;
    this.eventDispatcher = eventDispatcher;
    this.scheduler = scheduler;
    this.lifecycleEventConsumer = lifecycleEventConsumer;
    this.traceIdProvider = traceIdProvider;
  }

  public void handle(MilestoneCompletedEvent event) {
    try {
      CaseInstance caseInstance = event.caseInstance();
      Milestone milestone = event.milestone();
      Instant completedAt = event.completedAt();
      SlaStatus slaStatusAtCompletion = event.slaStatusAtCompletion();

      recordEventLog(event);
      updateCaseContext(caseInstance, milestone, completedAt, slaStatusAtCompletion);
      cancelSlaTimeoutJob(caseInstance, milestone);

      String traceId = traceIdProvider.currentTraceId().orElse(null);
      lifecycleEventConsumer.accept(
          CaseLifecycleEvent.of(
              caseInstance, "CompleteMilestone", "MilestoneCompleted", null, "System", traceId));
    } catch (Exception e) {
      LOG.errorf(
          e,
          "Failed to process MILESTONE_COMPLETED for caseId=%s milestone=%s",
          event.caseInstance().getUuid(),
          event.milestone().getName());
    }
  }

  private void recordEventLog(MilestoneCompletedEvent event) {
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

    eventLogRepository.append(eventLog, caseInstance.tenancyId);
  }

  private void updateCaseContext(
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

    eventDispatcher.dispatch(
        new CaseContextChangedEvent(
            caseInstance, caseInstance.getCaseContext().snapshot(), ContextLayer.WORKING));
  }

  private void cancelSlaTimeoutJob(CaseInstance caseInstance, Milestone milestone) {
    JobIdentifier jobId =
        JobIdentifier.of("milestone-" + milestone.getName(), "case-" + caseInstance.getUuid());

    boolean deleted = scheduler.cancel(jobId);
    if (deleted) {
      LOG.infof(
          "Cancelled SLA timeout job for case=%s milestone=%s",
          caseInstance.getUuid(), milestone.getName());
    } else {
      LOG.debugf(
          "SLA timeout job not found for case=%s milestone=%s (already fired or never scheduled)",
          caseInstance.getUuid(), milestone.getName());
    }
  }
}
