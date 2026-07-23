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

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkerScheduleEvent;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.recovery.WorkerExecutionRecoveryService;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import org.jboss.logging.Logger;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

/**
 * Quartz job for unconditional scheduled triggers.
 *
 * <p>When this job fires, it:
 *
 * <ol>
 *   <li>Loads the case instance
 *   <li>Verifies the case is still RUNNING
 *   <li>Publishes a {@link WorkerScheduleEvent} to schedule worker execution
 * </ol>
 *
 * <p>This job is used for time-based triggers without conditions (e.g., "send reminder after 30
 * minutes"). For conditional triggers, use {@link ConditionalScheduledTriggerJob}.
 */
@DisallowConcurrentExecution
@ApplicationScoped
public class ScheduledTriggerJob implements Job {

  private static final Logger LOG = Logger.getLogger(ScheduledTriggerJob.class);

  @Inject CaseDefinitionRegistry caseDefinitionRegistry;

  @Inject EventBus eventBus;

  @Inject WorkerExecutionRecoveryService recoveryService;

  @Override
  public void execute(JobExecutionContext context) throws JobExecutionException {
    JobDataMap data = context.getJobDetail().getJobDataMap();

    String caseIdStr = data.getString("caseId");
    String bindingName = data.getString("bindingName");
    String capabilityName = data.getString("capabilityName");
    String workerName = data.getString("workerName");

    LOG.infof(
        "Executing scheduled trigger: caseId=%s, binding=%s, capability=%s",
        caseIdStr, bindingName, capabilityName);

    UUID caseId;
    try {
      caseId = UUID.fromString(caseIdStr);
    } catch (IllegalArgumentException e) {
      throw new JobExecutionException("Invalid caseId format: " + caseIdStr, e);
    }

    CaseInstance caseInstance;
    try {
      caseInstance = recoveryService.loadOrRestoreCaseInstance(caseId);
    } catch (Exception e) {
      LOG.warnf(e, "Failed to load case instance: %s, skipping scheduled trigger", caseId);
      return;
    }

    if (caseInstance.getState() != CaseStatus.RUNNING) {
      LOG.infof(
          "Case %s is %s (not RUNNING), skipping scheduled trigger",
          caseId, caseInstance.getState());
      return;
    }

    CaseDefinition definition =
        caseDefinitionRegistry.getCaseDefinition(caseInstance.getCaseMetaModel());
    if (definition == null) {
      throw new JobExecutionException(
          "CaseDefinition not found for case: " + caseInstance.getUuid());
    }

    Worker worker = findWorker(definition, workerName);
    if (worker == null) {
      throw new JobExecutionException("Worker not found: " + workerName);
    }

    Capability capability = findCapability(definition, capabilityName);
    if (capability == null) {
      throw new JobExecutionException("Capability not found: " + capabilityName);
    }

    LOG.infof(
        "Publishing WorkerScheduleEvent for case=%s, worker=%s, capability=%s",
        caseId, workerName, capabilityName);

    eventBus.publish(
        EventBusAddresses.WORKER_SCHEDULE,
        new WorkerScheduleEvent(
            caseInstance,
            worker,
            capability,
            null,
            null,
            null,
            io.casehub.api.model.event.ExecutionOrigin.SCHEDULE_TRIGGER,
            List.of()));
  }

  private Worker findWorker(CaseDefinition definition, String workerName) {
    return definition.getWorkers().stream()
        .filter(w -> w.name().equals(workerName))
        .findFirst()
        .orElse(null);
  }

  private Capability findCapability(CaseDefinition definition, String capabilityName) {
    return definition.getCapabilities().stream()
        .filter(c -> c.name().equals(capabilityName))
        .findFirst()
        .orElse(null);
  }
}
