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

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.api.model.Capability;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Worker;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.event.WorkerScheduleEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.utils.ReactiveUtils;
import io.casehub.engine.common.internal.utils.WorkerExecutionKeys;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.common.spi.recovery.WorkerExecutionRecoveryService;
import io.casehub.engine.internal.engine.handler.WorkerScheduleEventHandler;
import io.casehub.platform.api.identity.TenancyConstants;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class WorkerIdempotencyTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  @Inject IdempotencyBean bean;

  @Inject TwoWorkerIdempotencyBean twoWorkerBean;

  @Inject WorkerScheduleEventHandler handler;

  @Inject WorkerExecutionRecoveryService recoveryService;

  @Inject CaseInstanceCache caseInstanceCache;

  @Inject EventLogRepository eventLogRepository;

  @Inject Vertx vertx;

  @BeforeEach
  void reset() {
    IdempotencyBean.runCount.set(0);
    IdempotencyBean.runCountById.clear();
    TwoWorkerIdempotencyBean.alphaRunCount.set(0);
    TwoWorkerIdempotencyBean.betaRunCount.set(0);
  }

  // ------------------------------------------------------------------ //

  /**
   * Three explicit WorkerScheduleEvents for the same (caseId, worker, inputHash) must produce
   * exactly one WORKER_SCHEDULED log row and one worker execution.
   */
  @Test
  void multipleScheduleEventsWithSameHashProduceSingleExecution() {
    String taskId = "multi-sched-" + UUID.randomUUID();
    UUID caseId = bean.startCase(Map.of("taskId", taskId)).toCompletableFuture().join();

    CaseInstance instance = caseInstanceCache.get(caseId);
    assertNotNull(instance);
    instance.getCaseContext().set("task", Map.of("op", "COMPUTE"));

    String inputDataHash =
        WorkerExecutionKeys.inputDataHash(
            caseId,
            "idempotency-worker",
            "idempotencyCapability",
            Map.of("task", Map.of("op", "COMPUTE"), "taskId", taskId));

    // Send the same WorkerScheduleEvent three times in rapid succession
    for (int i = 0; i < 3; i++) {
      ReactiveUtils.runOnSafeVertxContext(
              vertx,
              () ->
                  handler.onWorkerScheduleEventHandler(
                      new WorkerScheduleEvent(instance, bean.worker(), bean.capability())))
          .await()
          .atMost(TIMEOUT);
    }

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertEquals(
                  1,
                  IdempotencyBean.runCountById.getOrDefault(taskId, new AtomicInteger()).get(),
                  "Worker must execute exactly once");
              assertEquals(
                  1,
                  countEvents(
                      caseId,
                      CaseHubEventType.WORKER_SCHEDULED,
                      "idempotency-worker",
                      inputDataHash),
                  "Exactly one WORKER_SCHEDULED row must exist");
            });
  }

  /**
   * Worker must run again when the input data changes — a different input hash represents a new,
   * distinct execution unit.
   */
  @Test
  void workerRunsAgainWhenInputChanges() {
    String taskId = "input-change-" + UUID.randomUUID();
    UUID caseId = bean.startCase(Map.of("taskId", taskId)).toCompletableFuture().join();

    // First signal → worker runs with op=INIT
    bean.signal(caseId, "task", Map.of("op", "INIT"));

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    1,
                    IdempotencyBean.runCountById.getOrDefault(taskId, new AtomicInteger()).get()));

    // Different value → new input hash → worker must run again
    bean.signal(caseId, "task", Map.of("op", "UPGRADE"));

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    2,
                    IdempotencyBean.runCountById.getOrDefault(taskId, new AtomicInteger()).get(),
                    "Worker must run a second time for a new distinct input"));
  }

  /**
   * Two independent workers in the same case must each run exactly once. A schedule event for one
   * worker must not affect the idempotency state of the other.
   */
  @Test
  void twoIndependentWorkersInSameCaseRunOnce() {
    UUID caseId =
        twoWorkerBean.startCase(Map.of("orderId", "two-workers")).toCompletableFuture().join();

    twoWorkerBean.signal(caseId, "alpha", Map.of("code", "A1"));

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertEquals(1, TwoWorkerIdempotencyBean.alphaRunCount.get()));

    assertEquals(0, TwoWorkerIdempotencyBean.betaRunCount.get(), "Beta worker must not run yet");

    twoWorkerBean.signal(caseId, "beta", Map.of("code", "B1"));

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertEquals(1, TwoWorkerIdempotencyBean.alphaRunCount.get());
              assertEquals(1, TwoWorkerIdempotencyBean.betaRunCount.get());
            });
  }

  /**
   * After the worker completes, re-signaling the exact same value does not change the context
   * (empty diff) and must not trigger a second worker execution.
   */
  @Test
  void workerNotRetriggeredAfterCompletionOnSameSignalValue() {
    String taskId = "no-retrigger-" + UUID.randomUUID();
    UUID caseId = bean.startCase(Map.of("taskId", taskId)).toCompletableFuture().join();

    Map<String, Object> taskValue = Map.of("op", "PROCESS", "priority", "HIGH");
    bean.signal(caseId, "task", taskValue);

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    1,
                    IdempotencyBean.runCountById.getOrDefault(taskId, new AtomicInteger()).get()));

    // Re-signal the same value — context unchanged → no CONTEXT_CHANGED → no new schedule
    bean.signal(caseId, "task", taskValue);

    Awaitility.await()
        .during(2, TimeUnit.SECONDS)
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    1,
                    IdempotencyBean.runCountById.getOrDefault(taskId, new AtomicInteger()).get(),
                    "Worker must not run again when context does not change"));
  }

  /**
   * Mutating the cached CaseContext immediately after start must not affect the asynchronous
   * CASE_STARTED snapshot. The worker may only be scheduled from the actual start-state, not from a
   * later in-memory mutation.
   */
  @Test
  void cachedContextMutationAfterStartDoesNotRetroactivelyTriggerStartBindings() {
    String taskId = "start-snapshot-" + UUID.randomUUID();
    UUID caseId = bean.startCase(Map.of("taskId", taskId)).toCompletableFuture().join();

    CaseInstance instance = caseInstanceCache.get(caseId);
    assertNotNull(instance);
    instance.getCaseContext().set("task", Map.of("op", "VERIFY"));

    String inputDataHash =
        WorkerExecutionKeys.inputDataHash(
            caseId,
            "idempotency-worker",
            "idempotencyCapability",
            Map.of("task", Map.of("op", "VERIFY"), "taskId", taskId));

    Awaitility.await()
        .during(2, TimeUnit.SECONDS)
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertEquals(
                  0,
                  IdempotencyBean.runCountById.getOrDefault(taskId, new AtomicInteger()).get(),
                  "Worker must not run from a retroactively mutated CASE_STARTED snapshot");
              assertEquals(
                  0,
                  countEvents(
                      caseId,
                      CaseHubEventType.WORKER_SCHEDULED,
                      "idempotency-worker",
                      inputDataHash),
                  "No WORKER_SCHEDULED row must be created from the post-start mutation");
            });
  }

  /**
   * Regardless of how many times the handler is invoked with the same hash, exactly one
   * WORKER_SCHEDULED row must exist in the event log.
   */
  @Test
  void singleWorkerScheduledLogAfterMultipleScheduleAttempts() {
    String taskId = "single-log-" + UUID.randomUUID();
    UUID caseId = bean.startCase(Map.of("taskId", taskId)).toCompletableFuture().join();

    CaseInstance instance = caseInstanceCache.get(caseId);
    assertNotNull(instance);
    instance.getCaseContext().set("task", Map.of("op", "EXPORT"));

    String inputDataHash =
        WorkerExecutionKeys.inputDataHash(
            caseId,
            "idempotency-worker",
            "idempotencyCapability",
            Map.of("task", Map.of("op", "EXPORT"), "taskId", taskId));

    // Five handler invocations for the same input hash
    for (int i = 0; i < 5; i++) {
      ReactiveUtils.runOnSafeVertxContext(
              vertx,
              () ->
                  handler.onWorkerScheduleEventHandler(
                      new WorkerScheduleEvent(instance, bean.worker(), bean.capability())))
          .await()
          .atMost(TIMEOUT);
    }

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    1,
                    countEvents(
                        caseId,
                        CaseHubEventType.WORKER_SCHEDULED,
                        "idempotency-worker",
                        inputDataHash),
                    "Exactly one WORKER_SCHEDULED row must exist regardless of schedule attempt count"));
  }

  /**
   * WorkerScheduleEventHandler must SKIP scheduling when a WORKER_EXECUTION_STARTED row already
   * exists for the same input hash — the worker is already in flight.
   */
  @Test
  void workerSkipsWhenExecutionStartedEventExists() {
    String taskId = "skip-started-" + UUID.randomUUID();
    UUID caseId = bean.startCase(Map.of("taskId", taskId)).toCompletableFuture().join();

    CaseInstance instance = caseInstanceCache.get(caseId);
    assertNotNull(instance);
    instance.getCaseContext().set("task", Map.of("op", "VERIFY"));

    String inputDataHash =
        WorkerExecutionKeys.inputDataHash(
            caseId,
            "idempotency-worker",
            "idempotencyCapability",
            Map.of("task", Map.of("op", "VERIFY"), "taskId", taskId));

    // Pre-insert a WORKER_EXECUTION_STARTED event — simulates an in-flight worker
    persistEvent(
        buildEventLog(
            caseId,
            CaseHubEventType.WORKER_EXECUTION_STARTED,
            "idempotency-worker",
            inputDataHash,
            Map.of("op", "VERIFY")));

    ReactiveUtils.runOnSafeVertxContext(
            vertx,
            () ->
                handler.onWorkerScheduleEventHandler(
                    new WorkerScheduleEvent(instance, bean.worker(), bean.capability())))
        .await()
        .atMost(TIMEOUT);

    Awaitility.await()
        .during(2, TimeUnit.SECONDS)
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertEquals(
                  0,
                  IdempotencyBean.runCountById.getOrDefault(taskId, new AtomicInteger()).get(),
                  "Worker must not run when WORKER_EXECUTION_STARTED exists");
              assertEquals(
                  0,
                  countEvents(
                      caseId,
                      CaseHubEventType.WORKER_SCHEDULED,
                      "idempotency-worker",
                      inputDataHash),
                  "No WORKER_SCHEDULED row must be created when STARTED exists");
            });
  }

  /**
   * A changed signal value produces a different input hash, which is treated as a new distinct
   * execution — the worker must run again for the new hash.
   */
  @Test
  void workerRunsAgainForDifferentInputHash() {
    String taskId = "diff-hash-" + UUID.randomUUID();
    UUID caseId = bean.startCase(Map.of("taskId", taskId)).toCompletableFuture().join();

    bean.signal(caseId, "task", Map.of("op", "STEP_1"));

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    1,
                    IdempotencyBean.runCountById.getOrDefault(taskId, new AtomicInteger()).get()));

    bean.signal(caseId, "task", Map.of("op", "STEP_2"));

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    2,
                    IdempotencyBean.runCountById.getOrDefault(taskId, new AtomicInteger()).get(),
                    "Worker must run again because the input hash changed"));
  }

  /**
   * Concurrent WorkerScheduleEvents for the same (caseId, worker, hash) sent without waiting must
   * produce a single WORKER_SCHEDULED row via the Vert.x lock in WorkerScheduleEventHandler.
   */
  @Test
  void concurrentScheduleEventsProduceSingleLog() {
    String taskId = "concurrent-" + UUID.randomUUID();
    UUID caseId = bean.startCase(Map.of("taskId", taskId)).toCompletableFuture().join();

    CaseInstance instance = caseInstanceCache.get(caseId);
    assertNotNull(instance);
    instance.getCaseContext().set("task", Map.of("op", "PARALLEL"));

    String inputDataHash =
        WorkerExecutionKeys.inputDataHash(
            caseId,
            "idempotency-worker",
            "idempotencyCapability",
            Map.of("task", Map.of("op", "PARALLEL"), "taskId", taskId));

    // Fire four schedule events concurrently (no await between them)
    ReactiveUtils.runOnSafeVertxContext(
            vertx,
            () ->
                handler.onWorkerScheduleEventHandler(
                    new WorkerScheduleEvent(instance, bean.worker(), bean.capability())))
        .subscribe()
        .asCompletionStage();
    ReactiveUtils.runOnSafeVertxContext(
            vertx,
            () ->
                handler.onWorkerScheduleEventHandler(
                    new WorkerScheduleEvent(instance, bean.worker(), bean.capability())))
        .subscribe()
        .asCompletionStage();
    ReactiveUtils.runOnSafeVertxContext(
            vertx,
            () ->
                handler.onWorkerScheduleEventHandler(
                    new WorkerScheduleEvent(instance, bean.worker(), bean.capability())))
        .subscribe()
        .asCompletionStage();
    ReactiveUtils.runOnSafeVertxContext(
            vertx,
            () ->
                handler.onWorkerScheduleEventHandler(
                    new WorkerScheduleEvent(instance, bean.worker(), bean.capability())))
        .await()
        .atMost(TIMEOUT);

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertEquals(
                  1,
                  IdempotencyBean.runCountById.getOrDefault(taskId, new AtomicInteger()).get(),
                  "Worker must execute exactly once despite concurrent schedule events");
              assertEquals(
                  1,
                  countEvents(
                      caseId,
                      CaseHubEventType.WORKER_SCHEDULED,
                      "idempotency-worker",
                      inputDataHash),
                  "Exactly one WORKER_SCHEDULED row must exist");
            });
  }

  /**
   * When a WORKER_SCHEDULED row exists without a matching STARTED/COMPLETED (crash-recovery
   * scenario), recoverPendingScheduledWorkers() must replay the job exactly once without creating a
   * duplicate log row.
   */
  @Test
  void recoveryPlaysOrphanedWorkerExactlyOnce() {
    String taskId = "recovery-idem-" + UUID.randomUUID();
    UUID caseId = bean.startCase(Map.of("taskId", taskId)).toCompletableFuture().join();

    assertNotNull(caseInstanceCache.get(caseId));

    String inputDataHash =
        WorkerExecutionKeys.inputDataHash(
            caseId,
            "idempotency-worker",
            "idempotencyCapability",
            Map.of("task", Map.of("op", "RECOVER"), "taskId", taskId));

    // Pre-insert the WORKER_SCHEDULED row that would have been written before a crash
    persistEvent(
        buildEventLog(
            caseId,
            CaseHubEventType.WORKER_SCHEDULED,
            "idempotency-worker",
            inputDataHash,
            Map.of("task", Map.of("op", "RECOVER"), "taskId", taskId)));

    ReactiveUtils.runOnSafeVertxContext(
            vertx, () -> recoveryService.recoverPendingScheduledWorkers())
        .await()
        .atMost(TIMEOUT);

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertEquals(
                  1,
                  IdempotencyBean.runCountById.getOrDefault(taskId, new AtomicInteger()).get(),
                  "Worker must execute exactly once during recovery");
              assertEquals(
                  1,
                  countEvents(
                      caseId,
                      CaseHubEventType.WORKER_SCHEDULED,
                      "idempotency-worker",
                      inputDataHash),
                  "No duplicate WORKER_SCHEDULED row must be created");
            });
  }

  /**
   * Multiple signals to the same path that all produce the same computed worker input must result
   * in exactly one worker execution. The input hash, not the signal count, determines uniqueness.
   */
  @Test
  void workerRunsOnceForSameInputAcrossMultipleSignals() {
    String taskId = "multi-sig-" + UUID.randomUUID();
    UUID caseId = bean.startCase(Map.of("taskId", taskId)).toCompletableFuture().join();

    // All three signals produce the same context value → same input hash
    Map<String, Object> value = Map.of("op", "STABLE");
    bean.signal(caseId, "task", value);
    bean.signal(caseId, "task", value);
    bean.signal(caseId, "task", value);

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    1,
                    IdempotencyBean.runCountById.getOrDefault(taskId, new AtomicInteger()).get(),
                    "Worker must run exactly once for the same effective input regardless of signal count"));
  }

  // ================================================================== //
  //  Helpers                                                            //
  // ================================================================== //

  private void persistEvent(EventLog eventLog) {
    eventLogRepository
        .append(eventLog, TenancyConstants.DEFAULT_TENANT_ID)
        .subscribe()
        .asCompletionStage()
        .toCompletableFuture()
        .join();
  }

  private long countEvents(
      UUID caseId, CaseHubEventType eventType, String workerId, String inputDataHash) {
    List<EventLog> eventLogs =
        eventLogRepository
            .findByCaseAndWorkerAndType(
                caseId, workerId, eventType, TenancyConstants.DEFAULT_TENANT_ID)
            .subscribe()
            .asCompletionStage()
            .toCompletableFuture()
            .join();

    return eventLogs.stream()
        .filter(
            el -> {
              var metadata = el.getMetadata();
              var hash = metadata == null ? null : metadata.get("inputDataHash");
              return hash != null && inputDataHash.equals(hash.asText());
            })
        .count();
  }

  private EventLog buildEventLog(
      UUID caseId,
      CaseHubEventType eventType,
      String workerId,
      String inputDataHash,
      Map<String, Object> payload) {
    EventLog el = new EventLog();
    el.setCaseId(caseId);
    el.setEventType(eventType);
    el.setStreamType(EventStreamType.CASE);
    el.setTimestamp(Instant.now());
    el.setWorkerId(workerId);
    el.setMetadata(
        OBJECT_MAPPER.valueToTree(
            Map.of(
                "workerName", workerId,
                "capabilityName", "idempotencyCapability",
                "inputDataHash", inputDataHash)));
    el.setPayload(OBJECT_MAPPER.valueToTree(payload));
    return el;
  }

  // ================================================================== //
  //  Beans                                                              //
  // ================================================================== //

  @ApplicationScoped
  public static class IdempotencyBean extends CaseHub {

    static final AtomicInteger runCount = new AtomicInteger();
    static final ConcurrentHashMap<String, AtomicInteger> runCountById = new ConcurrentHashMap<>();

    private final Capability capability =
        Capability.builder()
            .name("idempotencyCapability")
            .inputSchema("{ task: .task, taskId: .taskId }")
            .outputSchema("{ taskResult: .taskResult }")
            .build();

    private final Worker worker =
        Worker.builder()
            .name("idempotency-worker")
            .capabilities(capability)
            .function(
                input -> {
                  runCount.incrementAndGet();
                  String taskId = (String) input.get("taskId");
                  if (taskId != null) {
                    runCountById
                        .computeIfAbsent(taskId, k -> new AtomicInteger())
                        .incrementAndGet();
                  }
                  return Map.of("taskResult", "done");
                })
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-worker-idempotency")
          .name("Worker Idempotency Test")
          .version("1.0.0")
          .capabilities(capability)
          .workers(worker)
          .bindings(
              Binding.builder()
                  .name("on-task")
                  .capability(capability)
                  .on(new ContextChangeTrigger(".task != null"))
                  .build())
          .build();
    }

    Capability capability() {
      return capability;
    }

    Worker worker() {
      return worker;
    }
  }

  @ApplicationScoped
  public static class TwoWorkerIdempotencyBean extends CaseHub {

    static final AtomicInteger alphaRunCount = new AtomicInteger();
    static final AtomicInteger betaRunCount = new AtomicInteger();

    private final Capability alphaCapability =
        Capability.builder()
            .name("alphaCapability")
            .inputSchema("{ alpha: .alpha }")
            .outputSchema("{ alphaResult: .alphaResult }")
            .build();

    private final Capability betaCapability =
        Capability.builder()
            .name("betaCapability")
            .inputSchema("{ beta: .beta }")
            .outputSchema("{ betaResult: .betaResult }")
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-worker-idempotency-two")
          .name("Two Worker Idempotency Test")
          .version("1.0.0")
          .capabilities(alphaCapability, betaCapability)
          .workers(
              Worker.builder()
                  .name("alpha-worker")
                  .capabilities(alphaCapability)
                  .function(
                      input -> {
                        alphaRunCount.incrementAndGet();
                        return Map.of("alphaResult", "done");
                      })
                  .build(),
              Worker.builder()
                  .name("beta-worker")
                  .capabilities(betaCapability)
                  .function(
                      input -> {
                        betaRunCount.incrementAndGet();
                        return Map.of("betaResult", "done");
                      })
                  .build())
          .bindings(
              Binding.builder()
                  .name("on-alpha")
                  .capability(alphaCapability)
                  .on(new ContextChangeTrigger(".alpha != null"))
                  .build(),
              Binding.builder()
                  .name("on-beta")
                  .capability(betaCapability)
                  .on(new ContextChangeTrigger(".beta != null"))
                  .build())
          .build();
    }
  }
}
