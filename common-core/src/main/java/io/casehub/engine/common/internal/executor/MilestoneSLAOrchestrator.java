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
package io.casehub.engine.common.internal.executor;

import io.casehub.api.model.MilestoneLifecycleStatus;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.MilestoneSLAViolatedEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.qualifier.CrossTenant;
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

/**
 * Scheduler-agnostic orchestrator for milestone SLA timeout handling. Extracts all domain logic
 * from MilestoneSLATimeoutJob into a reusable bean that any scheduler backend can delegate to.
 */
@ApplicationScoped
public class MilestoneSLAOrchestrator {

  private static final Logger LOG = Logger.getLogger(MilestoneSLAOrchestrator.class);

  private static final EnumSet<CaseHubEventType> MILESTONE_LIFECYCLE_EVENTS =
      EnumSet.of(
          CaseHubEventType.MILESTONE_ACTIVATED,
          CaseHubEventType.MILESTONE_COMPLETED,
          CaseHubEventType.MILESTONE_SLA_VIOLATED);

  private final CaseInstanceCache caseInstanceCache;
  private final CrossTenantCaseInstanceRepository caseInstanceRepository;
  private final CrossTenantEventLogRepository eventLogRepository;
  private final EventBus eventBus;

  @Inject
  public MilestoneSLAOrchestrator(
      CaseInstanceCache caseInstanceCache,
      @CrossTenant CrossTenantCaseInstanceRepository caseInstanceRepository,
      @CrossTenant CrossTenantEventLogRepository eventLogRepository,
      EventBus eventBus) {
    this.caseInstanceCache = caseInstanceCache;
    this.caseInstanceRepository = caseInstanceRepository;
    this.eventLogRepository = eventLogRepository;
    this.eventBus = eventBus;
  }

  public void execute(MilestoneSLAData data) {
    UUID caseId = data.caseId();
    String milestoneName = data.milestoneName();

    LOG.infof("SLA timeout fired for case=%s milestone=%s", caseId, milestoneName);

    CaseInstance caseInstance = caseInstanceCache.get(caseId);
    if (caseInstance == null) {
      caseInstance = caseInstanceRepository.findByUuid(caseId);
    }

    if (caseInstance == null) {
      LOG.warnf("Case not found: %s, skipping SLA violation", caseId);
      return;
    }

    if (caseInstance.getState().isTerminal()) {
      LOG.debugf("Case %s already terminal, skipping SLA violation", caseId);
      return;
    }

    MilestoneLifecycleStatus currentStatus = getCurrentLifecycleStatus(caseId, milestoneName);

    if (currentStatus == MilestoneLifecycleStatus.ACTIVE) {
      LOG.warnf("Milestone %s SLA VIOLATED for case %s", milestoneName, caseId);
      eventBus.publish(
          EventBusAddresses.MILESTONE_SLA_VIOLATED,
          new MilestoneSLAViolatedEvent(caseInstance, milestoneName, Instant.now()));
    } else {
      LOG.debugf("Milestone %s already %s, skipping SLA violation", milestoneName, currentStatus);
    }
  }

  private MilestoneLifecycleStatus getCurrentLifecycleStatus(UUID caseId, String milestoneName) {
    EventLog lastEvent =
        eventLogRepository.findByCaseAndTypes(caseId, MILESTONE_LIFECYCLE_EVENTS).stream()
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
