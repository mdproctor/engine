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

import static org.quartz.JobBuilder.newJob;
import static org.quartz.TriggerBuilder.newTrigger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.ObjectAlreadyExistsException;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;

@ApplicationScoped
class QuartzWorkerSchedulerService implements io.casehub.engine.common.spi.Resettable {

  @Inject Scheduler quartz;

  void scheduleRetry(JobDetail job, Trigger trigger) {
    try {
      scheduleRetryTrigger(job, trigger);
    } catch (SchedulerException e) {
      throw new RuntimeException("Quartz retry scheduling failed for jobKey=" + job.getKey(), e);
    }
  }

  void scheduleJob(
      Long eventLogId,
      String caseHubInstanceUuid,
      String workerId,
      String idempotency,
      String group,
      String tenancyId) {
    JobKey jobKey = new JobKey(idempotency, group);
    JobDetail job =
        newJob(QuartzWorkerExecutionJob.class)
            .withIdentity(jobKey)
            .storeDurably(false)
            .usingJobData("inputDataHash", idempotency)
            .usingJobData("caseHubInstanceUuid", caseHubInstanceUuid)
            .usingJobData("workerId", workerId)
            .usingJobData("eventLogId", String.valueOf(eventLogId))
            .usingJobData("tenancyId", tenancyId != null ? tenancyId : "")
            .build();

    Trigger trigger =
        newTrigger().withIdentity(idempotency, group).startNow().forJob(jobKey).build();

    scheduleRetry(job, trigger);
  }

  private void scheduleRetryTrigger(JobDetail job, Trigger trigger) throws SchedulerException {
    if (quartz.rescheduleJob(trigger.getKey(), trigger) != null) {
      return;
    }

    try {
      quartz.scheduleJob(job, trigger);
    } catch (ObjectAlreadyExistsException e) {
      scheduleTriggerForExistingJob(job, trigger, e);
    }
  }

  private void scheduleTriggerForExistingJob(
      JobDetail job, Trigger trigger, ObjectAlreadyExistsException original)
      throws SchedulerException {
    try {
      quartz.scheduleJob(trigger);
    } catch (ObjectAlreadyExistsException e) {
      if (quartz.rescheduleJob(trigger.getKey(), trigger) != null) {
        return;
      }
      throw e;
    } catch (SchedulerException e) {
      if (!quartz.checkExists(job.getKey())) {
        quartz.scheduleJob(job, trigger);
        return;
      }
      original.addSuppressed(e);
      throw original;
    }
  }

  @Override
  public void reset() {
    try {
      quartz.clear();
    } catch (SchedulerException e) {
      throw new RuntimeException("Failed to clear Quartz scheduler", e);
    }
  }
}
