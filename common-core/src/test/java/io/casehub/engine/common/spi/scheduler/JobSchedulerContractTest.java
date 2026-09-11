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
package io.casehub.engine.common.spi.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.engine.common.internal.scheduler.JobIdentifier;
import io.casehub.engine.common.internal.scheduler.JobType;
import io.casehub.engine.common.internal.scheduler.ScheduleStrategy;
import io.casehub.engine.common.internal.scheduler.ScheduledJobRequest;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public abstract class JobSchedulerContractTest {

  protected JobScheduler scheduler;

  protected abstract JobScheduler createJobScheduler();

  protected abstract void destroyJobScheduler();

  @BeforeEach
  void setUp() {
    scheduler = createJobScheduler();
  }

  @AfterEach
  void tearDown() {
    destroyJobScheduler();
  }

  @Test
  void schedule_delay_taskExists() {
    JobIdentifier jobId = JobIdentifier.of("trigger-1", "case-abc");
    scheduler.schedule(
        ScheduledJobRequest.builder()
            .jobId(jobId)
            .schedule(new ScheduleStrategy.DelaySchedule(60_000))
            .jobType(JobType.SCHEDULED_TRIGGER_UNCONDITIONAL)
            .data(Map.of("caseId", "abc", "bindingName", "b1"))
            .build());

    assertThat(scheduler.exists(jobId)).isTrue();
  }

  @Test
  void schedule_fixedAt_taskExists() {
    JobIdentifier jobId = JobIdentifier.of("sla-1", "case-def");
    long futureMs = System.currentTimeMillis() + 60_000;
    scheduler.schedule(
        ScheduledJobRequest.builder()
            .jobId(jobId)
            .schedule(new ScheduleStrategy.FixedAtSchedule(futureMs))
            .jobType(JobType.MILESTONE_SLA_TIMEOUT)
            .data(Map.of("caseId", "def", "milestoneName", "m1"))
            .build());

    assertThat(scheduler.exists(jobId)).isTrue();
  }

  @Test
  void schedule_cron_taskExists() {
    JobIdentifier jobId = JobIdentifier.of("cron-1", "case-ghi");
    scheduler.schedule(
        ScheduledJobRequest.builder()
            .jobId(jobId)
            .schedule(new ScheduleStrategy.CronSchedule("0 0 * * * ?"))
            .jobType(JobType.SCHEDULED_TRIGGER_UNCONDITIONAL)
            .data(Map.of("caseId", "ghi", "bindingName", "b1"))
            .build());

    assertThat(scheduler.exists(jobId)).isTrue();
  }

  @Test
  void cancel_existingTask_returnsTrue() {
    JobIdentifier jobId = JobIdentifier.of("to-cancel", "case-jkl");
    scheduler.schedule(
        ScheduledJobRequest.builder()
            .jobId(jobId)
            .schedule(new ScheduleStrategy.DelaySchedule(60_000))
            .jobType(JobType.SCHEDULED_TRIGGER_UNCONDITIONAL)
            .data(Map.of("caseId", "jkl", "bindingName", "b1"))
            .build());
    assertThat(scheduler.exists(jobId)).isTrue();

    boolean cancelled = scheduler.cancel(jobId);

    assertThat(cancelled).isTrue();
    assertThat(scheduler.exists(jobId)).isFalse();
  }

  @Test
  void cancel_nonExistentTask_returnsFalse() {
    assertThat(scheduler.cancel(JobIdentifier.of("missing", "group"))).isFalse();
  }

  @Test
  void cancelGroup_cancelsAllInGroup() {
    String group = "case-mno";
    for (int i = 0; i < 3; i++) {
      scheduler.schedule(
          ScheduledJobRequest.builder()
              .jobId(JobIdentifier.of("trigger-" + i, group))
              .schedule(new ScheduleStrategy.DelaySchedule(60_000))
              .jobType(JobType.SCHEDULED_TRIGGER_UNCONDITIONAL)
              .data(Map.of("caseId", "mno", "bindingName", "b" + i))
              .build());
    }
    for (int i = 0; i < 3; i++) {
      assertThat(scheduler.exists(JobIdentifier.of("trigger-" + i, group))).isTrue();
    }

    int cancelled = scheduler.cancelGroup(group);

    assertThat(cancelled).isEqualTo(3);
    for (int i = 0; i < 3; i++) {
      assertThat(scheduler.exists(JobIdentifier.of("trigger-" + i, group))).isFalse();
    }
  }

  @Test
  void exists_nonExistentTask_returnsFalse() {
    assertThat(scheduler.exists(JobIdentifier.of("nope", "nowhere"))).isFalse();
  }

  @Test
  void schedule_replacesExisting() {
    JobIdentifier jobId = JobIdentifier.of("replace-me", "case-pqr");
    scheduler.schedule(
        ScheduledJobRequest.builder()
            .jobId(jobId)
            .schedule(new ScheduleStrategy.DelaySchedule(60_000))
            .jobType(JobType.SCHEDULED_TRIGGER_UNCONDITIONAL)
            .data(Map.of("caseId", "pqr", "bindingName", "b1"))
            .build());
    assertThat(scheduler.exists(jobId)).isTrue();

    scheduler.schedule(
        ScheduledJobRequest.builder()
            .jobId(jobId)
            .schedule(new ScheduleStrategy.DelaySchedule(120_000))
            .jobType(JobType.SCHEDULED_TRIGGER_UNCONDITIONAL)
            .data(Map.of("caseId", "pqr", "bindingName", "b1"))
            .build());

    assertThat(scheduler.exists(jobId)).isTrue();
  }

  @Test
  void schedule_viaBuilder_taskExists() {
    JobIdentifier jobId = JobIdentifier.of("builder-1", "case-stu");
    scheduler.schedule(
        ScheduledJobRequest.builder()
            .jobId(jobId)
            .schedule(new ScheduleStrategy.DelaySchedule(60_000))
            .jobType(JobType.MILESTONE_SLA_TIMEOUT)
            .data(Map.of("caseId", "stu", "milestoneName", "m1")));

    assertThat(scheduler.exists(jobId)).isTrue();
  }

  @Test
  void cancelGroup_emptyGroup_returnsZero() {
    int cancelled = scheduler.cancelGroup("nonexistent-group");
    assertThat(cancelled).isEqualTo(0);
  }

  @Test
  void schedule_afterCancel_taskExists() {
    JobIdentifier jobId = JobIdentifier.of("reuse-1", "case-vwx");
    scheduler.schedule(
        ScheduledJobRequest.builder()
            .jobId(jobId)
            .schedule(new ScheduleStrategy.DelaySchedule(60_000))
            .jobType(JobType.SCHEDULED_TRIGGER_UNCONDITIONAL)
            .data(Map.of("caseId", "vwx", "bindingName", "b1"))
            .build());
    scheduler.cancel(jobId);
    assertThat(scheduler.exists(jobId)).isFalse();

    scheduler.schedule(
        ScheduledJobRequest.builder()
            .jobId(jobId)
            .schedule(new ScheduleStrategy.DelaySchedule(60_000))
            .jobType(JobType.SCHEDULED_TRIGGER_UNCONDITIONAL)
            .data(Map.of("caseId", "vwx", "bindingName", "b1"))
            .build());

    assertThat(scheduler.exists(jobId)).isTrue();
  }
}
