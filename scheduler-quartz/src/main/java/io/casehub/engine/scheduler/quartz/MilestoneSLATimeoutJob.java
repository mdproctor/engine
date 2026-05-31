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
package io.casehub.engine.scheduler.quartz;

import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.MilestoneLifecycleStatus;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.MilestoneSLAViolatedEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CrossTenantCaseInstanceRepository;
import io.casehub.engine.common.spi.CrossTenantEventLogRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.UUID;
import org.jboss.logging.Logger;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

/**
 * Quartz job that fires when a milestone's SLA deadline expires.
 *
 * <p>Checks if milestone is still ACTIVE (not completed) and publishes {@link
 * MilestoneSLAViolatedEvent} if so.
 *
 * <p>Idempotency: queries EventLog before publishing to avoid duplicate SLA violation events.
 */
@ApplicationScoped
public class MilestoneSLATimeoutJob implements Job {

  private static final Logger LOG = Logger.getLogger(MilestoneSLATimeoutJob.class);

  private static final EnumSet<CaseHubEventType> MILESTONE_LIFECYCLE_EVENTS =
      EnumSet.of(
          CaseHubEventType.MILESTONE_ACTIVATED,
          CaseHubEventType.MILESTONE_COMPLETED,
          CaseHubEventType.MILESTONE_SLA_VIOLATED);

  @Inject EventBus eventBus;

  @Inject CaseInstanceCache caseInstanceCache;

  @Inject CrossTenantCaseInstanceRepository caseInstanceRepository;

  @Inject CrossTenantEventLogRepository eventLogRepository;

  @Override
  public void execute(JobExecutionContext context) throws JobExecutionException {
    String caseIdStr = context.getMergedJobDataMap().getString("caseId");
    String milestoneName = context.getMergedJobDataMap().getString("milestoneName");

    UUID caseId = UUID.fromString(caseIdStr);

    LOG.infof("SLA timeout fired for case=%s milestone=%s", caseId, milestoneName);

    // Load case instance
    CaseInstance caseInstance = caseInstanceCache.get(caseId);
    if (caseInstance == null) {
      caseInstance = caseInstanceRepository.findByUuid(caseId).await().indefinitely();
    }

    if (caseInstance == null) {
      LOG.warnf("Case not found: %s, skipping SLA violation", caseId);
      return;
    }

    // Check if case is terminal
    if (caseInstance.getState() == CaseStatus.COMPLETED
        || caseInstance.getState() == CaseStatus.CANCELLED) {
      LOG.debugf("Case %s already terminal, skipping SLA violation", caseId);
      return;
    }

    // Query milestone state from EventLog
    MilestoneLifecycleStatus currentStatus =
        getCurrentLifecycleStatus(eventLogRepository, caseId, milestoneName);

    if (currentStatus == MilestoneLifecycleStatus.ACTIVE) {
      // Still active — fire SLA violation
      LOG.warnf("Milestone %s SLA VIOLATED for case %s", milestoneName, caseId);

      MilestoneSLAViolatedEvent event =
          new MilestoneSLAViolatedEvent(caseInstance, milestoneName, Instant.now());

      eventBus.publish(EventBusAddresses.MILESTONE_SLA_VIOLATED, event);
    } else {
      LOG.debugf("Milestone %s already %s, skipping SLA violation", milestoneName, currentStatus);
    }
  }

  private MilestoneLifecycleStatus getCurrentLifecycleStatus(
      CrossTenantEventLogRepository eventLogRepository, UUID caseId, String milestoneName) {
    EventLog lastEvent =
        eventLogRepository
            .findByCaseAndTypes(caseId, MILESTONE_LIFECYCLE_EVENTS)
            .await()
            .indefinitely()
            .stream()
            .filter(e -> milestoneName.equals(e.getPayload().get("milestoneName").asText()))
            .max(Comparator.comparing(EventLog::getSeq))
            .orElse(null);

    if (lastEvent == null) {
      return MilestoneLifecycleStatus.PENDING;
    }

    return switch (lastEvent.getEventType()) {
      case MILESTONE_ACTIVATED -> MilestoneLifecycleStatus.ACTIVE;
      case MILESTONE_COMPLETED -> MilestoneLifecycleStatus.COMPLETED;
      default -> MilestoneLifecycleStatus.PENDING;
    };
  }
}
