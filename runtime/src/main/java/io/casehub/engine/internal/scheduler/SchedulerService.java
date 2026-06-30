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
package io.casehub.engine.internal.scheduler;

import io.casehub.api.model.Binding;
import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ExtensionTarget;
import io.casehub.api.model.ScheduleTrigger;
import io.casehub.api.model.SubCaseTarget;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.scheduler.JobIdentifier;
import io.casehub.engine.common.internal.scheduler.ScheduleStrategy;
import io.casehub.engine.common.internal.scheduler.ScheduleStrategy.CronSchedule;
import io.casehub.engine.common.internal.scheduler.ScheduleStrategy.DelaySchedule;
import io.casehub.engine.common.internal.scheduler.ScheduledJobRequest;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.scheduler.JobScheduler;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Service for scheduling time-based triggers for Case Hub bindings.
 *
 * <p>This service manages Quartz jobs that fire when scheduled triggers activate. It supports:
 *
 * <ul>
 *   <li><b>Unconditional scheduling</b> - worker executes when trigger fires
 *   <li><b>Conditional scheduling</b> - worker executes only if condition evaluates to true
 *   <li><b>Cancellation</b> - remove all scheduled triggers when a case completes
 * </ul>
 *
 * <p><b>Lifecycle:</b>
 *
 * <ul>
 *   <li>Register triggers when a case is created: {@link #registerScheduledTriggers(CaseInstance)}
 *   <li>Cancel all triggers when a case completes: {@link #cancelAllTriggers(UUID)}
 * </ul>
 *
 * @see ScheduleTrigger
 * @see ScheduledTriggerJob
 * @see ConditionalScheduledTriggerJob
 */
@ApplicationScoped
public class SchedulerService {

  private static final Logger LOG = Logger.getLogger(SchedulerService.class);

  @Inject JobScheduler scheduler;

  @Inject CaseDefinitionRegistry caseDefinitionRegistry;

  SchedulerService() {}

  SchedulerService(JobScheduler scheduler, CaseDefinitionRegistry caseDefinitionRegistry) {
    this.scheduler = scheduler;
    this.caseDefinitionRegistry = caseDefinitionRegistry;
  }

  /**
   * Register all scheduled triggers for a case instance.
   *
   * <p>Scans the case definition for bindings with {@link ScheduleTrigger} and creates Quartz jobs
   * for each. Jobs are grouped by case ID for easy bulk cancellation.
   *
   * @param caseInstance the case instance to register triggers for
   * @return Uni that completes when all triggers are registered
   */
  public Uni<Void> registerScheduledTriggers(CaseInstance caseInstance) {
    CaseDefinition definition =
        caseDefinitionRegistry.getCaseDefinition(caseInstance.getCaseMetaModel());

    if (definition == null) {
      LOG.warnf(
          "CaseDefinition not found for case %s — no scheduled triggers to register",
          caseInstance.getUuid());
      return Uni.createFrom().voidItem();
    }

    List<Binding> bindings = definition.getBindings();
    if (bindings == null || bindings.isEmpty()) {
      return Uni.createFrom().voidItem();
    }

    List<Uni<Void>> scheduleOps = new ArrayList<>();

    for (Binding binding : bindings) {
      if (!(binding.getOn() instanceof ScheduleTrigger trigger)) {
        continue;
      }

      CapabilityTarget ct =
          switch (binding.target()) {
            case CapabilityTarget cap -> cap;
            case SubCaseTarget st -> {
              LOG.warnf(
                  "Schedule binding '%s' has non-capability target — skipping", binding.getName());
              yield null;
            }
            case io.casehub.api.model.HumanTaskTarget ht -> {
              LOG.warnf(
                  "Schedule binding '%s' has non-capability target — skipping", binding.getName());
              yield null;
            }
            case ExtensionTarget et -> {
              LOG.warnf(
                  "Schedule binding '%s' has non-capability target — skipping", binding.getName());
              yield null;
            }
          };
      if (ct == null) {
        continue;
      }
      // Find worker that provides the capability
      Worker worker = findWorkerForCapability(definition, ct.capability());
      if (worker == null) {
        LOG.warnf(
            "No worker found for capability '%s' in binding '%s', skipping",
            ct.capability().name(), binding.getName());
        continue;
      }

      if (binding.getWhen() != null) {
        // Conditional scheduling
        scheduleOps.add(
            scheduleConditionalWorker(caseInstance.getUuid(), binding, trigger, worker));
      } else {
        // Unconditional scheduling
        scheduleOps.add(scheduleWorker(caseInstance.getUuid(), binding, trigger, worker));
      }
    }

    if (scheduleOps.isEmpty()) {
      return Uni.createFrom().voidItem();
    }

    return Uni.combine().all().unis(scheduleOps).discardItems();
  }

  /**
   * Schedule unconditional worker execution when the trigger fires.
   *
   * @param caseId the case ID
   * @param binding the binding configuration
   * @param trigger the schedule trigger
   * @param worker the worker to execute
   * @return Uni that completes when the job is scheduled
   */
  public Uni<Void> scheduleWorker(
      UUID caseId, Binding binding, ScheduleTrigger trigger, Worker worker) {

    JobIdentifier jobId = createJobIdentifier(caseId, binding.getName());
    ScheduleStrategy schedule = toScheduleStrategy(trigger);
    Map<String, Object> jobData = createJobData(caseId, binding, worker);
    jobData.put("triggerType", "unconditional");

    return scheduler
        .schedule(ScheduledJobRequest.builder().jobId(jobId).schedule(schedule).data(jobData))
        .invoke(
            () ->
                LOG.infof(
                    "Scheduled unconditional trigger: case=%s, binding=%s, trigger=%s",
                    caseId, binding.getName(), trigger));
  }

  /**
   * Schedule conditional worker execution. The worker executes only if the condition evaluates to
   * true when the trigger fires.
   *
   * @param caseId the case ID
   * @param binding the binding configuration
   * @param trigger the schedule trigger
   * @param worker the worker to execute if condition is true
   * @return Uni that completes when the job is scheduled
   */
  public Uni<Void> scheduleConditionalWorker(
      UUID caseId, Binding binding, ScheduleTrigger trigger, Worker worker) {

    JobIdentifier jobId = createJobIdentifier(caseId, binding.getName());
    ScheduleStrategy schedule = toScheduleStrategy(trigger);
    Map<String, Object> jobData = createJobData(caseId, binding, worker);
    jobData.put("triggerType", "conditional");

    return scheduler
        .schedule(ScheduledJobRequest.builder().jobId(jobId).schedule(schedule).data(jobData))
        .invoke(
            () ->
                LOG.infof(
                    "Scheduled conditional trigger: case=%s, binding=%s, trigger=%s, condition=%s",
                    caseId, binding.getName(), trigger, binding.getWhen()));
  }

  /**
   * Cancel all scheduled triggers for a case.
   *
   * <p>This should be called when a case completes (COMPLETED, CLOSED, or FAULTED state).
   *
   * @param caseId the case ID
   * @return Uni that completes when all triggers are cancelled
   */
  public Uni<Void> cancelAllTriggers(UUID caseId) {
    String groupName = "case-" + caseId;

    return scheduler
        .cancelGroup(groupName)
        .invoke(
            count -> {
              if (count > 0) {
                LOG.infof("Cancelled %d scheduled triggers for case %s", count, caseId);
              } else {
                LOG.debugf("No scheduled triggers to cancel for case %s", caseId);
              }
            })
        .replaceWithVoid();
  }

  /**
   * Cancel a specific scheduled trigger for a case.
   *
   * @param caseId the case ID
   * @param bindingName the binding name
   * @return Uni that completes when the trigger is cancelled
   */
  public Uni<Void> cancelTrigger(UUID caseId, String bindingName) {
    JobIdentifier jobId = createJobIdentifier(caseId, bindingName);

    return scheduler
        .cancel(jobId)
        .invoke(
            deleted -> {
              if (deleted) {
                LOG.infof("Cancelled scheduled trigger: case=%s, binding=%s", caseId, bindingName);
              } else {
                LOG.debugf("No trigger found to cancel: case=%s, binding=%s", caseId, bindingName);
              }
            })
        .replaceWithVoid();
  }

  private JobIdentifier createJobIdentifier(UUID caseId, String bindingName) {
    return JobIdentifier.of("binding-" + bindingName, "case-" + caseId);
  }

  private ScheduleStrategy toScheduleStrategy(ScheduleTrigger trigger) {
    if (trigger.isCron()) {
      return new CronSchedule(trigger.getCron());
    } else if (trigger.isDelay()) {
      return new DelaySchedule(trigger.getDelay().toMillis());
    } else {
      throw new IllegalArgumentException("ScheduleTrigger must have either cron or delay set");
    }
  }

  private Map<String, Object> createJobData(UUID caseId, Binding binding, Worker worker) {
    Map<String, Object> data = new HashMap<>();
    data.put("caseId", caseId.toString());
    data.put("bindingName", binding.getName());
    switch (binding.target()) {
      case CapabilityTarget ct -> {
        String capabilityName = ct.capability().name();
        data.put("capabilityName", capabilityName);
      }
      case SubCaseTarget ignored ->
          throw new IllegalStateException(
              "createJobData called with non-CapabilityTarget binding '" + binding.getName() + "'");
      case io.casehub.api.model.HumanTaskTarget ignored ->
          throw new IllegalStateException(
              "createJobData called with non-CapabilityTarget binding '" + binding.getName() + "'");
      case ExtensionTarget ignored ->
          throw new IllegalStateException(
              "createJobData called with non-CapabilityTarget binding '" + binding.getName() + "'");
    }
    data.put("workerName", worker.name());
    return data;
  }

  private Worker findWorkerForCapability(CaseDefinition definition, Capability capability) {
    return definition.getWorkers().stream()
        .filter(w -> w.capabilityNames().contains(capability.name()))
        .findFirst()
        .orElse(null);
  }
}
