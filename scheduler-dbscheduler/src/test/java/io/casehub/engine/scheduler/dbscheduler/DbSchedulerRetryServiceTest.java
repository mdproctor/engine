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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.spi.event.EventDispatcher;
import io.casehub.engine.common.internal.event.WorkerRetriesExhaustedEvent;
import io.casehub.engine.common.internal.executor.RetryOrchestrator;
import io.casehub.engine.common.internal.executor.WorkerTaskData;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.recovery.RecoveryCoordinator;
import io.casehub.engine.common.spi.recovery.WorkerExecutionRecoveryService;
import io.casehub.platform.api.governance.BackoffStrategy;
import io.casehub.platform.api.governance.ExecutionPolicy;
import io.casehub.platform.api.governance.RetryPolicy;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DbSchedulerRetryServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock EventLogRepository eventLogRepository;
  @Mock WorkerExecutionRecoveryService recoveryService;
  @Mock CaseDefinitionRegistry caseDefinitionRegistry;
  @Mock EventDispatcher eventDispatcher;
  @Mock RecoveryCoordinator recoveryCoordinator;
  @Mock Scheduler scheduler;

  private DbSchedulerRetryService retryService;
  private final UUID caseId = UUID.randomUUID();
  private final String workerId = "test-worker";
  private final String inputDataHash = "hash-123";
  private final String tenancyId = "tenant-1";

  private OneTimeTask<ScheduledJobData> workerExecutionTask;

  @BeforeEach
  void setUp() {
    RetryOrchestrator retryOrchestrator =
        new RetryOrchestrator(
            eventLogRepository,
            recoveryService,
            caseDefinitionRegistry,
            eventDispatcher,
            recoveryCoordinator);

    workerExecutionTask =
        Tasks.oneTime(DbSchedulerLifecycle.TASK_WORKER_EXECUTION, ScheduledJobData.class)
            .execute((inst, ctx) -> {});

    DbSchedulerLifecycle lifecycle =
        new DbSchedulerLifecycle() {
          @Override
          Scheduler getScheduler() {
            return scheduler;
          }

          @SuppressWarnings("unchecked")
          @Override
          <T> OneTimeTask<T> findTask(String taskName) {
            return (OneTimeTask<T>) workerExecutionTask;
          }
        };

    retryService = new DbSchedulerRetryService();
    retryService.retryOrchestrator = retryOrchestrator;
    retryService.lifecycle = lifecycle;
  }

  @Test
  void handleFailure_underMaxAttempts_reschedulesViaDbScheduler() {
    WorkerTaskData taskData =
        new WorkerTaskData(42L, inputDataHash, caseId, workerId, tenancyId, null, null);

    CaseInstance instance = caseInstanceWithWorker(3, 1000, BackoffStrategy.FIXED);
    stubPersistAndRecovery(instance);
    when(eventLogRepository.findByCaseAndWorkerAndType(
            eq(caseId), eq(workerId), any(), eq(tenancyId)))
        .thenReturn(List.of());

    retryService.handleFailure(taskData, new RuntimeException("boom"), "boom");

    ArgumentCaptor<TaskInstance<ScheduledJobData>> instanceCaptor = taskInstanceCaptor();
    ArgumentCaptor<Instant> timeCaptor = ArgumentCaptor.forClass(Instant.class);
    verify(scheduler).schedule(instanceCaptor.capture(), timeCaptor.capture());

    assertThat(timeCaptor.getValue()).isAfter(Instant.now());
    assertThat(instanceCaptor.getValue().getData().jobType())
        .isEqualTo(io.casehub.engine.common.internal.scheduler.JobType.WORKER_EXECUTION);
  }

  @Test
  void handleFailure_atMaxAttempts_publishesExhaustion() {
    WorkerTaskData taskData =
        new WorkerTaskData(42L, inputDataHash, caseId, workerId, tenancyId, null, null);

    CaseInstance instance = caseInstanceWithWorker(2, 1000, BackoffStrategy.FIXED);
    stubPersistAndRecovery(instance);
    when(eventLogRepository.findByCaseAndWorkerAndType(
            eq(caseId), eq(workerId), any(), eq(tenancyId)))
        .thenReturn(List.of(failureLog(), failureLog()));

    retryService.handleFailure(taskData, new RuntimeException("boom"), "boom");

    ArgumentCaptor<WorkerRetriesExhaustedEvent> captor =
        ArgumentCaptor.forClass(WorkerRetriesExhaustedEvent.class);
    verify(eventDispatcher).dispatch(captor.capture());
    assertThat(captor.getValue().caseId()).isEqualTo(caseId);
    verify(scheduler, never()).schedule(any(TaskInstance.class), any(Instant.class));
  }

  @Test
  void handleFailure_persistsFailureEventLog() {
    WorkerTaskData taskData =
        new WorkerTaskData(42L, inputDataHash, caseId, workerId, tenancyId, null, null);

    CaseInstance instance = caseInstanceWithWorker(3, 1000, BackoffStrategy.FIXED);
    ArgumentCaptor<EventLog> logCaptor = ArgumentCaptor.forClass(EventLog.class);
    doNothing().when(eventLogRepository).append(logCaptor.capture(), eq(tenancyId));
    when(recoveryService.loadOrRestoreCaseInstance(caseId)).thenReturn(instance);
    when(eventLogRepository.findByCaseAndWorkerAndType(
            eq(caseId), eq(workerId), any(), eq(tenancyId)))
        .thenReturn(List.of());

    retryService.handleFailure(taskData, new RuntimeException("broke"), "broke");

    EventLog captured = logCaptor.getValue();
    assertThat(captured.getCaseId()).isEqualTo(caseId);
    assertThat(captured.getWorkerId()).isEqualTo(workerId);
    assertThat(captured.getMetadata().get("errorMessage").asText()).isEqualTo("broke");
  }

  @SuppressWarnings("unchecked")
  private ArgumentCaptor<TaskInstance<ScheduledJobData>> taskInstanceCaptor() {
    return ArgumentCaptor.forClass(TaskInstance.class);
  }

  private void stubPersistAndRecovery(CaseInstance instance) {
    when(recoveryService.loadOrRestoreCaseInstance(caseId)).thenReturn(instance);
  }

  private EventLog failureLog() {
    EventLog log = new EventLog();
    ObjectNode meta = MAPPER.createObjectNode().put("inputDataHash", inputDataHash);
    log.setMetadata(meta);
    return log;
  }

  private CaseInstance caseInstanceWithWorker(
      int maxAttempts, int delayMs, BackoffStrategy strategy) {
    RetryPolicy retryPolicy = new RetryPolicy(maxAttempts, delayMs, strategy);
    ExecutionPolicy executionPolicy = new ExecutionPolicy(null, retryPolicy);
    Worker worker =
        Worker.builder()
            .name(workerId)
            .capabilityName("test-cap")
            .function(
                new WorkerFunction.Sync<>(
                    Map.class, Map.class, (input, scope) -> WorkerResult.of(Map.of())))
            .executionPolicy(executionPolicy)
            .build();
    CaseDefinition definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("test-def")
            .version("1.0")
            .workers(worker)
            .build();

    CaseInstance instance = new CaseInstance();
    instance.setUuid(caseId);
    CaseMetaModel metaModel = new CaseMetaModel();
    metaModel.setName("test-def");
    metaModel.setNamespace("test");
    metaModel.setVersion("1.0");
    instance.setCaseMetaModel(metaModel);

    when(caseDefinitionRegistry.getCaseDefinition(metaModel)).thenReturn(definition);

    return instance;
  }
}
