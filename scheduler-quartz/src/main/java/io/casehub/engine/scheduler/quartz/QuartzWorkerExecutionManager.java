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

import static org.quartz.CronScheduleBuilder.cronSchedule;
import static org.quartz.JobBuilder.newJob;
import static org.quartz.TriggerBuilder.newTrigger;

import io.casehub.api.model.Binding;
import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.ExtensionTarget;
import io.casehub.api.model.HumanTaskTarget;
import io.casehub.api.model.ScheduleTrigger;
import io.casehub.api.model.SubCaseTarget;
import io.casehub.engine.common.internal.executor.WorkerFunctionHandler;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.utils.WorkerExecutionKeys;
import io.casehub.engine.common.qualifier.CrossTenant;
import io.casehub.engine.common.spi.CrossTenantEventLogRepository;
import io.casehub.engine.common.spi.scheduler.WorkerBackend;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.impl.matchers.GroupMatcher;

@WorkerBackend
@Priority(0)
@ApplicationScoped
public class QuartzWorkerExecutionManager implements WorkerExecutionManager {

  @Inject QuartzWorkerExecutionJobListener workflowExecutionJobListener;

  @Inject QuartzWorkerSchedulerService workerExecutionScheduler;

  @Inject @CrossTenant CrossTenantEventLogRepository eventLogRepository;

  private static final Logger LOG = Logger.getLogger(WorkerExecutionManager.class);

  private final Scheduler scheduler;

  @Inject
  public QuartzWorkerExecutionManager(Scheduler scheduler) {
    this.scheduler = scheduler;
  }

  @Inject Instance<WorkerFunctionHandler> functionHandlers;

  @Override
  public boolean supports(String capabilityName, String tenancyId) {
    return true;
  }

  @Override
  public boolean canExecute(WorkerFunction function) {
    for (WorkerFunctionHandler handler : functionHandlers) {
      if (handler.supports(function)) return true;
    }
    return false;
  }

  void onStart(@Observes @Priority(20) StartupEvent ev) throws SchedulerException {
    scheduler.getListenerManager().addJobListener(workflowExecutionJobListener);
  }

  @Override
  public void submit(
      Long eventLogId,
      CaseInstance instance,
      Worker worker,
      Capability capability,
      Map<String, Object> inputData) {

    String idempotency =
        WorkerExecutionKeys.inputDataHash(
            instance.getUuid(), worker.name(), capability.name(), inputData);
    String group = instance.getUuid().toString();

    EventLog eventLog = eventLogRepository.findById(eventLogId);
    if (eventLog == null) {
      throw new RuntimeException("EventLog not found: id=" + eventLogId);
    }

    scheduleQuartzJob(eventLogId, instance, worker, idempotency, group, instance.tenancyId);
  }

  @Override
  public boolean supportsRecovery() {
    return true;
  }

  @Override
  public void schedulePersistedEvent(EventLog scheduledEventLog) {
    String caseId = scheduledEventLog.getCaseId().toString();
    String workerId = scheduledEventLog.getWorkerId();
    String idempotency = scheduledEventLog.getMetadata().get("inputDataHash").asText();
    scheduleQuartzJob(
        scheduledEventLog.id, caseId, workerId, idempotency, caseId, scheduledEventLog.tenancyId);
  }

  @Override
  public int getActiveWorkCount(String workerId) {
    try {
      List<String> groups = scheduler.getJobGroupNames();
      int count = 0;
      for (String group : groups) {
        Set<JobKey> keys = scheduler.getJobKeys(GroupMatcher.groupEquals(group));
        for (JobKey key : keys) {
          JobDetail detail = scheduler.getJobDetail(key);
          if (detail != null) {
            Object workerIdValue = detail.getJobDataMap().get("workerId");
            if (workerId.equals(workerIdValue)) {
              count++;
            }
          }
        }
      }
      return count;
    } catch (SchedulerException e) {
      LOG.warnf(
          "Failed to count active jobs for worker '%s' — returning 0: %s",
          workerId, e.getMessage());
      return 0;
    }
  }

  @Override
  public List<UUID> getActiveCaseIds(String workerId) {
    try {
      List<String> groups = scheduler.getJobGroupNames();
      List<UUID> caseIds = new ArrayList<>();
      for (String group : groups) {
        Set<JobKey> keys = scheduler.getJobKeys(GroupMatcher.groupEquals(group));
        for (JobKey key : keys) {
          JobDetail detail = scheduler.getJobDetail(key);
          if (detail != null) {
            Object wId = detail.getJobDataMap().get("workerId");
            Object caseUuid = detail.getJobDataMap().get("caseHubInstanceUuid");
            if (workerId.equals(wId) && caseUuid instanceof String s) {
              try {
                caseIds.add(UUID.fromString(s));
              } catch (IllegalArgumentException ignored) {
                // malformed UUID in job data — skip
              }
            }
          }
        }
      }
      return Collections.unmodifiableList(caseIds);
    } catch (SchedulerException e) {
      LOG.warnf(
          "Failed to collect active case IDs for worker '%s' — returning empty: %s",
          workerId, e.getMessage());
      return List.of();
    }
  }

  private void scheduleQuartzJob(
      Long eventLogId,
      CaseInstance instance,
      Worker worker,
      String idempotency,
      String group,
      String tenancyId) {
    scheduleQuartzJob(eventLogId, instance, worker.name(), idempotency, group, tenancyId);
  }

  private void scheduleQuartzJob(
      Long eventLogId,
      CaseInstance instance,
      String name,
      String idempotency,
      String group,
      String tenancyId) {
    scheduleQuartzJob(
        eventLogId, instance.getUuid().toString(), name, idempotency, group, tenancyId);
  }

  private void scheduleQuartzJob(
      Long eventLogId,
      String caseHubInstanceUuid,
      String workerId,
      String idempotency,
      String group,
      String tenancyId) {
    workerExecutionScheduler.scheduleJob(
        eventLogId, caseHubInstanceUuid, workerId, idempotency, group, tenancyId);
  }

  /**
   * Schedule an unconditional trigger ({@link ScheduledTriggerJob}).
   *
   * <p><b>Planned API — currently unwired.</b> This method has zero callers in the engine. It is
   * staged for the binding/trigger scheduling feature. When wired, callers will inject {@code
   * QuartzWorkerExecutionManager} via {@link WorkerBackend @WorkerBackend} qualifier.
   *
   * @param caseId case UUID
   * @param binding binding configuration
   * @param trigger schedule trigger (cron or delay)
   * @param worker worker to execute
   * @return Uni that completes when scheduled
   */
  public void scheduleScheduledTrigger(
      UUID caseId, Binding binding, ScheduleTrigger trigger, Worker worker) {
    try {
      JobKey jobKey = createTriggerJobKey(caseId, binding.getName());
      JobDetail job = createScheduledTriggerJob(jobKey, caseId, binding, worker);
      Trigger quartzTrigger = createQuartzTrigger(jobKey, trigger);

      scheduler.scheduleJob(job, quartzTrigger);
      LOG.infof(
          "Scheduled unconditional trigger: case=%s, binding=%s, trigger=%s",
          caseId, binding.getName(), trigger);
    } catch (SchedulerException e) {
      throw new RuntimeException("Failed to schedule scheduled trigger", e);
    }
  }

  /**
   * Schedule a conditional trigger ({@link ConditionalScheduledTriggerJob}).
   *
   * <p><b>Planned API — currently unwired.</b> See {@link #scheduleScheduledTrigger} for status.
   *
   * @param caseId case UUID
   * @param binding binding configuration
   * @param trigger schedule trigger (cron or delay)
   * @param worker worker to execute
   * @return Uni that completes when scheduled
   */
  public void scheduleConditionalTrigger(
      UUID caseId, Binding binding, ScheduleTrigger trigger, Worker worker) {
    try {
      JobKey jobKey = createTriggerJobKey(caseId, binding.getName());
      JobDetail job = createConditionalScheduledTriggerJob(jobKey, caseId, binding, worker);
      Trigger quartzTrigger = createQuartzTrigger(jobKey, trigger);

      scheduler.scheduleJob(job, quartzTrigger);
      LOG.infof(
          "Scheduled conditional trigger: case=%s, binding=%s, trigger=%s",
          caseId, binding.getName(), trigger);
    } catch (SchedulerException e) {
      throw new RuntimeException("Failed to schedule conditional trigger", e);
    }
  }

  /**
   * Cancel a specific scheduled trigger.
   *
   * <p><b>Planned API — currently unwired.</b> See {@link #scheduleScheduledTrigger} for status.
   *
   * @param caseId case UUID
   * @param bindingName binding name
   * @return Uni with true if trigger was deleted
   */
  public boolean cancelScheduledTrigger(UUID caseId, String bindingName) {
    try {
      JobKey jobKey = createTriggerJobKey(caseId, bindingName);
      boolean deleted = scheduler.deleteJob(jobKey);
      if (deleted) {
        LOG.infof("Cancelled scheduled trigger: case=%s, binding=%s", caseId, bindingName);
      } else {
        LOG.debugf("No trigger found to cancel: case=%s, binding=%s", caseId, bindingName);
      }
      return deleted;
    } catch (SchedulerException e) {
      throw new RuntimeException("Failed to cancel scheduled trigger", e);
    }
  }

  /**
   * Cancel all scheduled triggers for a case.
   *
   * <p><b>Planned API — currently unwired.</b> See {@link #scheduleScheduledTrigger} for status.
   *
   * @param caseId case UUID
   * @return Uni with count of cancelled triggers
   */
  public int cancelAllScheduledTriggers(UUID caseId) {
    try {
      String groupName = "case-" + caseId;
      Set<JobKey> jobKeys = scheduler.getJobKeys(GroupMatcher.groupEquals(groupName));
      int count = 0;
      for (JobKey jobKey : jobKeys) {
        if (scheduler.deleteJob(jobKey)) {
          count++;
        }
      }
      if (count > 0) {
        LOG.infof("Cancelled %d scheduled triggers for case %s", count, caseId);
      } else {
        LOG.debugf("No scheduled triggers to cancel for case %s", caseId);
      }
      return count;
    } catch (SchedulerException e) {
      throw new RuntimeException("Failed to cancel all scheduled triggers", e);
    }
  }

  private JobKey createTriggerJobKey(UUID caseId, String bindingName) {
    String jobName = "binding-" + bindingName;
    String groupName = "case-" + caseId;
    return new JobKey(jobName, groupName);
  }

  private JobDetail createScheduledTriggerJob(
      JobKey jobKey, UUID caseId, Binding binding, Worker worker) {
    Map<String, Object> jobData = createTriggerJobData(caseId, binding, worker);
    return newJob(ScheduledTriggerJob.class)
        .withIdentity(jobKey)
        .usingJobData("caseId", jobData.get("caseId").toString())
        .usingJobData("bindingName", jobData.get("bindingName").toString())
        .usingJobData("capabilityName", jobData.get("capabilityName").toString())
        .usingJobData("workerName", jobData.get("workerName").toString())
        .build();
  }

  private JobDetail createConditionalScheduledTriggerJob(
      JobKey jobKey, UUID caseId, Binding binding, Worker worker) {
    Map<String, Object> jobData = createTriggerJobData(caseId, binding, worker);
    return newJob(ConditionalScheduledTriggerJob.class)
        .withIdentity(jobKey)
        .usingJobData("caseId", jobData.get("caseId").toString())
        .usingJobData("bindingName", jobData.get("bindingName").toString())
        .usingJobData("capabilityName", jobData.get("capabilityName").toString())
        .usingJobData("workerName", jobData.get("workerName").toString())
        .build();
  }

  private Map<String, Object> createTriggerJobData(UUID caseId, Binding binding, Worker worker) {
    Map<String, Object> data = new HashMap<>();
    data.put("caseId", caseId.toString());
    data.put("bindingName", binding.getName());
    switch (binding.target()) {
      case CapabilityTarget ct -> data.put("capabilityName", ct.capability().name());
      case SubCaseTarget ignored ->
          throw new IllegalStateException(
              "Schedule-triggered binding '" + binding.getName() + "' must target a Capability");
      case HumanTaskTarget ignored ->
          throw new IllegalStateException(
              "Schedule-triggered binding '" + binding.getName() + "' must target a Capability");
      case ExtensionTarget ignored ->
          throw new IllegalStateException(
              "Schedule-triggered binding '" + binding.getName() + "' must target a Capability");
    }
    data.put("workerName", worker.name());
    return data;
  }

  private Trigger createQuartzTrigger(JobKey jobKey, ScheduleTrigger trigger) {
    if (trigger.isCron()) {
      return newTrigger()
          .withIdentity(jobKey.getName(), jobKey.getGroup())
          .withSchedule(cronSchedule(trigger.getCron()))
          .build();
    } else if (trigger.isDelay()) {
      long delayMs = trigger.getDelay().toMillis();
      return newTrigger()
          .withIdentity(jobKey.getName(), jobKey.getGroup())
          .startAt(new Date(System.currentTimeMillis() + delayMs))
          .build();
    } else {
      throw new IllegalArgumentException("ScheduleTrigger must have either cron or delay set");
    }
  }
}
