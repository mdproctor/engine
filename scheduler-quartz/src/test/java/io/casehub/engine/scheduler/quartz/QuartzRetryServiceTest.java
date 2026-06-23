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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.model.CaseDefinition;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkerRetriesExhaustedEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.recovery.WorkerExecutionRecoveryService;
import io.casehub.platform.api.governance.BackoffStrategy;
import io.casehub.platform.api.governance.ExecutionPolicy;
import io.casehub.platform.api.governance.RetryPolicy;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import io.vertx.mutiny.core.eventbus.EventBus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobDetail;
import org.quartz.Trigger;

@ExtendWith(MockitoExtension.class)
class QuartzRetryServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static Vertx vertx;

  @Mock EventLogRepository eventLogRepository;
  @Mock WorkerExecutionRecoveryService recoveryService;
  @Mock CaseDefinitionRegistry caseDefinitionRegistry;
  @Mock QuartzWorkerSchedulerService schedulerService;
  @Mock EventBus eventBus;

  private QuartzRetryService retryService;
  private final UUID caseId = UUID.randomUUID();
  private final String workerId = "test-worker";
  private final String inputDataHash = "hash-123";
  private final String tenancyId = "tenant-1";
  private final String eventLogId = "42";

  @BeforeAll
  static void startVertx() {
    vertx = Vertx.vertx();
  }

  @AfterAll
  static void stopVertx() throws Exception {
    if (vertx != null) {
      CountDownLatch latch = new CountDownLatch(1);
      vertx.close().onComplete(ar -> latch.countDown());
      latch.await(5, TimeUnit.SECONDS);
    }
  }

  @BeforeEach
  void setUp() {
    retryService =
        new QuartzRetryService(
            eventLogRepository,
            recoveryService,
            caseDefinitionRegistry,
            schedulerService,
            eventBus,
            vertx);
  }

  @Test
  void handleFailure_underMaxAttempts_reschedulesWorker() {
    WorkerRetryContext ctx =
        new WorkerRetryContext(caseId, workerId, inputDataHash, tenancyId, eventLogId, null);

    CaseInstance instance = caseInstanceWithWorker(3, 1000, BackoffStrategy.FIXED);

    stubPersistAndRecovery(instance);
    when(eventLogRepository.findByCaseAndWorkerAndType(
            eq(caseId), eq(workerId), any(), eq(tenancyId)))
        .thenReturn(Uni.createFrom().item(List.of()));
    when(schedulerService.scheduleRetryAsync(any(JobDetail.class), any(Trigger.class)))
        .thenReturn(Uni.createFrom().voidItem());

    retryService.handleFailure(ctx, "test error").await().indefinitely();

    verify(schedulerService).scheduleRetryAsync(any(JobDetail.class), any(Trigger.class));
    verify(eventBus, never()).publish(eq(EventBusAddresses.WORKER_RETRIES_EXHAUSTED), any());
  }

  @Test
  void handleFailure_atMaxAttempts_publishesExhaustion() {
    WorkerRetryContext ctx =
        new WorkerRetryContext(caseId, workerId, inputDataHash, tenancyId, eventLogId, null);

    CaseInstance instance = caseInstanceWithWorker(2, 1000, BackoffStrategy.FIXED);

    stubPersistAndRecovery(instance);
    when(eventLogRepository.findByCaseAndWorkerAndType(
            eq(caseId), eq(workerId), any(), eq(tenancyId)))
        .thenReturn(Uni.createFrom().item(List.of(failureLog(), failureLog())));

    retryService.handleFailure(ctx, "test error").await().indefinitely();

    ArgumentCaptor<WorkerRetriesExhaustedEvent> captor =
        ArgumentCaptor.forClass(WorkerRetriesExhaustedEvent.class);
    verify(eventBus).publish(eq(EventBusAddresses.WORKER_RETRIES_EXHAUSTED), captor.capture());
    assertThat(captor.getValue().caseId()).isEqualTo(caseId);
    assertThat(captor.getValue().workerId()).isEqualTo(workerId);
    verify(schedulerService, never()).scheduleRetryAsync(any(JobDetail.class), any(Trigger.class));
  }

  @Test
  void handleFailure_noRetryPolicyConfigured_usesDefault() {
    WorkerRetryContext ctx =
        new WorkerRetryContext(caseId, workerId, inputDataHash, tenancyId, eventLogId, null);

    CaseInstance instance = caseInstanceWithDefaultPolicy();

    stubPersistAndRecovery(instance);
    when(eventLogRepository.findByCaseAndWorkerAndType(
            eq(caseId), eq(workerId), any(), eq(tenancyId)))
        .thenReturn(Uni.createFrom().item(List.of()));
    when(schedulerService.scheduleRetryAsync(any(JobDetail.class), any(Trigger.class)))
        .thenReturn(Uni.createFrom().voidItem());

    retryService.handleFailure(ctx, "test error").await().indefinitely();

    verify(schedulerService).scheduleRetryAsync(any(JobDetail.class), any(Trigger.class));
  }

  @Test
  void handleFailure_caseDefinitionNotFound_logsErrorWithoutCrash() {
    WorkerRetryContext ctx =
        new WorkerRetryContext(caseId, workerId, inputDataHash, tenancyId, eventLogId, null);

    CaseInstance instance = new CaseInstance();
    instance.setUuid(caseId);
    CaseMetaModel metaModel = new CaseMetaModel();
    metaModel.setName("missing-def");
    metaModel.setNamespace("test");
    metaModel.setVersion("1.0");
    instance.setCaseMetaModel(metaModel);

    stubPersistAndRecovery(instance);
    when(caseDefinitionRegistry.getCaseDefinition(any(CaseMetaModel.class))).thenReturn(null);

    retryService.handleFailure(ctx, "test error").await().indefinitely();

    verify(schedulerService, never()).scheduleRetryAsync(any(JobDetail.class), any(Trigger.class));
    verify(eventBus, never()).publish(eq(EventBusAddresses.WORKER_RETRIES_EXHAUSTED), any());
  }

  @Test
  void handleFailure_persistsFailureEventLog() {
    WorkerRetryContext ctx =
        new WorkerRetryContext(caseId, workerId, inputDataHash, tenancyId, eventLogId, null);

    CaseInstance instance = caseInstanceWithWorker(3, 1000, BackoffStrategy.FIXED);

    ArgumentCaptor<EventLog> logCaptor = ArgumentCaptor.forClass(EventLog.class);
    when(eventLogRepository.append(logCaptor.capture(), eq(tenancyId)))
        .thenReturn(Uni.createFrom().voidItem());
    when(recoveryService.loadOrRestoreCaseInstance(caseId))
        .thenReturn(Uni.createFrom().item(instance));
    when(eventLogRepository.findByCaseAndWorkerAndType(
            eq(caseId), eq(workerId), any(), eq(tenancyId)))
        .thenReturn(Uni.createFrom().item(List.of()));
    when(schedulerService.scheduleRetryAsync(any(JobDetail.class), any(Trigger.class)))
        .thenReturn(Uni.createFrom().voidItem());

    retryService.handleFailure(ctx, "something broke").await().indefinitely();

    EventLog captured = logCaptor.getValue();
    assertThat(captured.getCaseId()).isEqualTo(caseId);
    assertThat(captured.getWorkerId()).isEqualTo(workerId);
    assertThat(captured.getMetadata().get("errorMessage").asText()).isEqualTo("something broke");
    assertThat(captured.getMetadata().get("inputDataHash").asText()).isEqualTo(inputDataHash);
  }

  @Test
  void handleFailure_countsOnlyMatchingInputDataHash() {
    WorkerRetryContext ctx =
        new WorkerRetryContext(caseId, workerId, inputDataHash, tenancyId, eventLogId, null);

    CaseInstance instance = caseInstanceWithWorker(3, 1000, BackoffStrategy.FIXED);

    stubPersistAndRecovery(instance);

    EventLog matching = failureLog();
    EventLog nonMatching = new EventLog();
    ObjectNode otherMeta = MAPPER.createObjectNode().put("inputDataHash", "other-hash");
    nonMatching.setMetadata(otherMeta);

    when(eventLogRepository.findByCaseAndWorkerAndType(
            eq(caseId), eq(workerId), any(), eq(tenancyId)))
        .thenReturn(Uni.createFrom().item(List.of(matching, nonMatching)));
    when(schedulerService.scheduleRetryAsync(any(JobDetail.class), any(Trigger.class)))
        .thenReturn(Uni.createFrom().voidItem());

    retryService.handleFailure(ctx, "test error").await().indefinitely();

    verify(schedulerService).scheduleRetryAsync(any(JobDetail.class), any(Trigger.class));
  }

  private void stubPersistAndRecovery(CaseInstance instance) {
    when(eventLogRepository.append(any(EventLog.class), eq(tenancyId)))
        .thenReturn(Uni.createFrom().voidItem());
    when(recoveryService.loadOrRestoreCaseInstance(caseId))
        .thenReturn(Uni.createFrom().item(instance));
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
            .capabilities(Capability.of("test-cap", "{}", "{}"))
            .function(new WorkerFunction.Sync(input -> WorkerResult.of(Map.of())))
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

  private CaseInstance caseInstanceWithDefaultPolicy() {
    Worker worker =
        Worker.builder()
            .name(workerId)
            .capabilities(Capability.of("test-cap", "{}", "{}"))
            .function(new WorkerFunction.Sync(input -> WorkerResult.of(Map.of())))
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
