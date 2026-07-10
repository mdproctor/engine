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
package io.casehub.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.event.WorkerScheduleEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.utils.ReactiveUtils;
import io.casehub.engine.common.internal.utils.WorkerExecutionKeys;
import io.casehub.engine.common.spi.ReactiveEventLogRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.common.spi.recovery.WorkerExecutionRecoveryService;
import io.casehub.engine.internal.engine.handler.WorkerScheduleEventHandler;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class WorkerScheduleDedupTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final Duration SPI_TIMEOUT = Duration.ofSeconds(10);

  @Inject DedupCaseHubBean bean;

  @Inject WorkerScheduleEventHandler handler;

  @Inject WorkerExecutionRecoveryService recoveryService;

  @Inject CaseInstanceCache caseInstanceCache;

  @Inject ReactiveEventLogRepository reactiveEventLogRepository;

  @Inject Vertx vertx;

  @Test
  void shouldSkipWhenExecutionAlreadyCompleted() {
    DedupCaseHubBean.runCount.set(0);

    UUID caseId =
        bean.startCase(Map.of("documentId", "doc-completed", "status", "queued"))
            .toCompletableFuture()
            .join();

    CaseInstance instance = caseInstanceCache.get(caseId);
    assertNotNull(instance);

    String executionIdempotency =
        WorkerExecutionKeys.inputDataHash(
            caseId,
            "dedup-worker",
            "dedupCapability",
            Map.of("documentId", "doc-completed", "status", "queued"));
    persistEvent(
        eventLog(
            caseId,
            CaseHubEventType.WORKER_EXECUTION_COMPLETED,
            "dedup-worker",
            executionIdempotency,
            Map.of("status", "processed")));

    ReactiveUtils.runOnSafeVertxContext(
            vertx,
            () ->
                handler.onWorkerScheduleEventHandler(
                    new WorkerScheduleEvent(instance, bean.worker(), bean.capability())))
        .await()
        .atMost(Duration.ofSeconds(10));

    Awaitility.await()
        .during(2, TimeUnit.SECONDS)
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertEquals(0, DedupCaseHubBean.runCount.get());
              assertEquals(
                  0,
                  countEvents(
                      caseId,
                      CaseHubEventType.WORKER_SCHEDULED,
                      "dedup-worker",
                      executionIdempotency));
            });
  }

  @Test
  void shouldSkipWhenMatchingScheduledEventAlreadyExists() {
    DedupCaseHubBean.runCount.set(0);

    UUID caseId =
        bean.startCase(Map.of("documentId", "doc-resubmit", "status", "queued"))
            .toCompletableFuture()
            .join();

    CaseInstance instance = caseInstanceCache.get(caseId);
    assertNotNull(instance);

    String executionIdempotency =
        WorkerExecutionKeys.inputDataHash(
            caseId,
            "dedup-worker",
            "dedupCapability",
            Map.of("documentId", "doc-resubmit", "status", "queued"));
    persistEvent(
        eventLog(
            caseId,
            CaseHubEventType.WORKER_SCHEDULED,
            "dedup-worker",
            executionIdempotency,
            Map.of("documentId", "doc-resubmit", "status", "queued")));

    ReactiveUtils.runOnSafeVertxContext(
            vertx,
            () ->
                handler.onWorkerScheduleEventHandler(
                    new WorkerScheduleEvent(instance, bean.worker(), bean.capability())))
        .await()
        .atMost(Duration.ofSeconds(10));

    Awaitility.await()
        .during(2, TimeUnit.SECONDS)
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertEquals(0, DedupCaseHubBean.runCount.get());
              assertEquals(
                  1,
                  countEvents(
                      caseId,
                      CaseHubEventType.WORKER_SCHEDULED,
                      "dedup-worker",
                      executionIdempotency));
            });
  }

  /**
   * Crash-recovery scenario: a WORKER_SCHEDULED row exists in the DB (written before the crash) but
   * the Quartz RAM-store is empty (cleared on restart). The handler SKIPs re-scheduling (covered by
   * shouldSkipWhenMatchingScheduledEventAlreadyExists), while recoverPendingScheduledWorkers()
   * replays the orphaned job exactly once without creating a duplicate WORKER_SCHEDULED row.
   */
  @Test
  void shouldRecoverOrphanedScheduledWorkerOnRestart() {
    DedupCaseHubBean.runCount.set(0);

    UUID caseId =
        bean.startCase(Map.of("documentId", "doc-recovery", "status", "queued"))
            .toCompletableFuture()
            .join();

    assertNotNull(caseInstanceCache.get(caseId));

    String executionIdempotency =
        WorkerExecutionKeys.inputDataHash(
            caseId,
            "dedup-worker",
            "dedupCapability",
            Map.of("documentId", "doc-recovery", "status", "queued"));

    // Simulate the state left after a crash: WORKER_SCHEDULED persisted, Quartz job lost.
    persistEvent(
        eventLog(
            caseId,
            CaseHubEventType.WORKER_SCHEDULED,
            "dedup-worker",
            executionIdempotency,
            Map.of("documentId", "doc-recovery", "status", "queued")));

    // Recovery service reschedules the orphaned job without creating a new EventLog row.
    ReactiveUtils.runOnSafeVertxContext(
            vertx, () -> recoveryService.recoverPendingScheduledWorkers())
        .await()
        .atMost(Duration.ofSeconds(10));

    Awaitility.await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertEquals(1, DedupCaseHubBean.runCount.get(), "worker must run exactly once");
              assertEquals(
                  1,
                  countEvents(
                      caseId,
                      CaseHubEventType.WORKER_SCHEDULED,
                      "dedup-worker",
                      executionIdempotency),
                  "no duplicate WORKER_SCHEDULED row must be created");
              assertEquals(
                  "processed",
                  bean.query(caseId, "status", String.class).toCompletableFuture().join());
            });
  }

  private void persistEvent(EventLog eventLog) {
    reactiveEventLogRepository
        .append(eventLog, TenancyConstants.DEFAULT_TENANT_ID)
        .await()
        .atMost(SPI_TIMEOUT);
  }

  private long countEvents(
      UUID caseId, CaseHubEventType eventType, String workerId, String inputDataHash) {
    List<EventLog> eventLogs =
        reactiveEventLogRepository
            .findByCaseAndWorkerAndType(
                caseId, workerId, eventType, TenancyConstants.DEFAULT_TENANT_ID)
            .await()
            .atMost(SPI_TIMEOUT);

    return eventLogs.stream()
        .filter(
            eventLog -> {
              JsonNode metadata = eventLog.getMetadata();
              JsonNode existingHash = metadata == null ? null : metadata.get("inputDataHash");
              return existingHash != null && inputDataHash.equals(existingHash.asText());
            })
        .count();
  }

  private EventLog eventLog(
      UUID caseId,
      CaseHubEventType eventType,
      String workerId,
      String inputDataHash,
      Map<String, Object> payload) {
    EventLog eventLog = new EventLog();
    eventLog.setCaseId(caseId);
    eventLog.setEventType(eventType);
    eventLog.setStreamType(EventStreamType.CASE);
    eventLog.setTimestamp(Instant.now());
    eventLog.setWorkerId(workerId);
    eventLog.setMetadata(
        OBJECT_MAPPER.valueToTree(
            Map.of(
                "workerName", workerId,
                "capabilityName", "dedupCapability",
                "inputDataHash", inputDataHash)));
    eventLog.setPayload(OBJECT_MAPPER.valueToTree(payload));
    return eventLog;
  }

  @ApplicationScoped
  public static class DedupCaseHubBean extends CaseHub {

    static final AtomicInteger runCount = new AtomicInteger();

    private final Capability capability =
        Capability.builder()
            .name("dedupCapability")
            .inputSchema("{ documentId: .documentId, status: .status }")
            .outputSchema("{ status: .status, processedDocument: .processedDocument }")
            .build();

    private final Worker worker =
        Worker.builder()
            .name("dedup-worker")
            .capabilityName("dedupCapability")
            .function(
                new WorkerFunction.Sync<>(
                    Map.class,
                    input -> {
                      runCount.incrementAndGet();
                      return WorkerResult.of(
                          Map.of(
                              "status",
                              "processed",
                              "processedDocument",
                              Map.of("id", input.get("documentId"))));
                    }))
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-worker-schedule-dedup")
          .name("Worker Schedule Dedup Test")
          .version("1.0.0")
          .capabilities(capability)
          .workers(worker)
          .build();
    }

    Capability capability() {
      return capability;
    }

    Worker worker() {
      return worker;
    }
  }
}
