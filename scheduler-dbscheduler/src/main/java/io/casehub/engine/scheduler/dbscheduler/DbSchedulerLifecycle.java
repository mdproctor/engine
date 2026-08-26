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
package io.casehub.engine.scheduler.dbscheduler;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import io.casehub.engine.common.internal.executor.MilestoneSLAOrchestrator;
import io.casehub.engine.common.internal.executor.ScheduledTriggerOrchestrator;
import io.casehub.engine.common.internal.executor.WorkerExecutionOrchestrator;
import io.casehub.engine.common.internal.executor.WorkerTaskData;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.h2.jdbcx.JdbcDataSource;
import org.jboss.logging.Logger;

@ApplicationScoped
public class DbSchedulerLifecycle implements io.casehub.engine.common.spi.Resettable {

  private static final Logger LOG = Logger.getLogger(DbSchedulerLifecycle.class);

  static final String TASK_WORKER_EXECUTION = "worker-execution";
  static final String TASK_SCHEDULED_TRIGGER = "scheduled-trigger";
  static final String TASK_CONDITIONAL_TRIGGER = "conditional-trigger";
  static final String TASK_MILESTONE_SLA = "milestone-sla-timeout";
  static final String TASK_SIGNAL_TRIGGER = "signal-trigger";

  private static final String H2_SCHEMA =
      """
      CREATE TABLE IF NOT EXISTS scheduled_tasks (
        task_name       VARCHAR(100) NOT NULL,
        task_instance   VARCHAR(500) NOT NULL,
        task_data       BLOB,
        execution_time  TIMESTAMP NOT NULL,
        picked          BOOLEAN NOT NULL DEFAULT FALSE,
        picked_by       VARCHAR(50),
        last_success    TIMESTAMP,
        last_failure    TIMESTAMP,
        consecutive_failures INT,
        last_heartbeat  TIMESTAMP,
        version         BIGINT NOT NULL DEFAULT 0,
        PRIMARY KEY (task_name, task_instance)
      )
      """;

  @Inject WorkerExecutionOrchestrator workerOrchestrator;
  @Inject ScheduledTriggerOrchestrator triggerOrchestrator;
  @Inject MilestoneSLAOrchestrator milestoneOrchestrator;
  @Inject DbSchedulerRetryService retryService;
  @Inject DbSchedulerWorkerExecutionManager executionManager;

  @ConfigProperty(name = "casehub.scheduler.dbscheduler.threads", defaultValue = "4")
  int threads;

  @ConfigProperty(name = "casehub.scheduler.dbscheduler.polling-interval-ms", defaultValue = "500")
  long pollingIntervalMs;

  private Scheduler scheduler;
  private DataSource dataSource;
  private OneTimeTask<ScheduledJobData> workerExecutionTask;
  private OneTimeTask<ScheduledJobData> scheduledTriggerTask;
  private OneTimeTask<ScheduledJobData> conditionalTriggerTask;
  private OneTimeTask<ScheduledJobData> milestoneSlaTask;
  private OneTimeTask<ScheduledJobData> signalTriggerTask;

  void onStart(@Observes @jakarta.annotation.Priority(10) StartupEvent event) {
    dataSource = createDataSource();
    createSchema(dataSource);
    createTasks();

    List<Task<?>> allTasks =
        List.of(
            workerExecutionTask,
            scheduledTriggerTask,
            conditionalTriggerTask,
            milestoneSlaTask,
            signalTriggerTask);

    scheduler =
        Scheduler.create(dataSource, allTasks)
            .threads(threads)
            .pollingInterval(Duration.ofMillis(pollingIntervalMs))
            .build();

    scheduler.start();
    LOG.infof("db-scheduler started: threads=%d, pollingInterval=%dms", threads, pollingIntervalMs);
  }

  void onStop(@Observes ShutdownEvent event) {
    if (scheduler != null) {
      scheduler.stop();
      LOG.info("db-scheduler stopped");
    }
  }

  Scheduler getScheduler() {
    return scheduler;
  }

  DataSource getDataSource() {
    return dataSource;
  }

  @SuppressWarnings("unchecked")
  <T> OneTimeTask<T> findTask(String taskName) {
    return switch (taskName) {
      case TASK_WORKER_EXECUTION -> (OneTimeTask<T>) workerExecutionTask;
      case TASK_SCHEDULED_TRIGGER -> (OneTimeTask<T>) scheduledTriggerTask;
      case TASK_CONDITIONAL_TRIGGER -> (OneTimeTask<T>) conditionalTriggerTask;
      case TASK_MILESTONE_SLA -> (OneTimeTask<T>) milestoneSlaTask;
      case TASK_SIGNAL_TRIGGER -> (OneTimeTask<T>) signalTriggerTask;
      default -> throw new IllegalArgumentException("Unknown task: " + taskName);
    };
  }

  String taskNameForJobType(io.casehub.engine.common.internal.scheduler.JobType jobType) {
    return switch (jobType) {
      case WORKER_EXECUTION -> TASK_WORKER_EXECUTION;
      case SCHEDULED_TRIGGER_UNCONDITIONAL -> TASK_SCHEDULED_TRIGGER;
      case SCHEDULED_TRIGGER_CONDITIONAL -> TASK_CONDITIONAL_TRIGGER;
      case MILESTONE_SLA_TIMEOUT -> TASK_MILESTONE_SLA;
      case SIGNAL_TRIGGER -> TASK_SIGNAL_TRIGGER;
    };
  }

  @Override
  public void reset() {
    if (dataSource == null) {
      return;
    }
    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute("DELETE FROM scheduled_tasks");
    } catch (SQLException e) {
      throw new RuntimeException("Failed to clear db-scheduler tasks", e);
    }
  }

  private void createTasks() {
    workerExecutionTask =
        Tasks.oneTime(TASK_WORKER_EXECUTION, ScheduledJobData.class)
            .execute(
                (inst, ctx) -> {
                  WorkerTaskData taskData = inst.getData().toWorkerTaskData();
                  executionManager.trackStart(taskData.workerId(), taskData.caseId());
                  try {
                    workerOrchestrator.execute(taskData, retryService);
                  } finally {
                    executionManager.trackComplete(taskData.workerId(), taskData.caseId());
                  }
                });

    scheduledTriggerTask =
        Tasks.oneTime(TASK_SCHEDULED_TRIGGER, ScheduledJobData.class)
            .execute(
                (inst, ctx) -> {
                  triggerOrchestrator.executeUnconditionalTrigger(
                      inst.getData().toScheduledTriggerData());
                  rescheduleIfCron(inst);
                });

    conditionalTriggerTask =
        Tasks.oneTime(TASK_CONDITIONAL_TRIGGER, ScheduledJobData.class)
            .execute(
                (inst, ctx) -> {
                  triggerOrchestrator.executeConditionalTrigger(
                      inst.getData().toScheduledTriggerData());
                  rescheduleIfCron(inst);
                });

    milestoneSlaTask =
        Tasks.oneTime(TASK_MILESTONE_SLA, ScheduledJobData.class)
            .execute(
                (inst, ctx) -> {
                  milestoneOrchestrator.execute(inst.getData().toMilestoneSLAData());
                });

    signalTriggerTask =
        Tasks.oneTime(TASK_SIGNAL_TRIGGER, ScheduledJobData.class)
            .execute(
                (inst, ctx) -> {
                  triggerOrchestrator.executeSignalTrigger(inst.getData().toScheduledSignalData());
                  rescheduleIfCron(inst);
                });
  }

  private void rescheduleIfCron(TaskInstance<ScheduledJobData> inst) {
    String cron = inst.getData().cronExpression();
    if (cron == null) {
      return;
    }
    Optional<Instant> next = CronUtils.nextExecution(cron);
    if (next.isPresent()) {
      OneTimeTask<ScheduledJobData> task = findTask(inst.getTaskName());
      // Use a fresh instance ID — the current row is still in the table (picked=true)
      // and will be deleted by db-scheduler's completion handler after we return.
      String baseId =
          inst.getId().contains("#")
              ? inst.getId().substring(0, inst.getId().lastIndexOf('#'))
              : inst.getId();
      String newId = baseId + "#" + next.get().toEpochMilli();
      scheduler.schedule(task.instance(newId, inst.getData()), next.get());
      LOG.debugf("Rescheduled cron task %s:%s at %s", inst.getTaskName(), newId, next.get());
    }
  }

  private static DataSource createDataSource() {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:dbscheduler;DB_CLOSE_DELAY=-1");
    ds.setUser("sa");
    ds.setPassword("");
    return ds;
  }

  private static void createSchema(DataSource ds) {
    try (Connection conn = ds.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute(H2_SCHEMA);
    } catch (SQLException e) {
      throw new RuntimeException("Failed to create db-scheduler schema", e);
    }
  }
}
