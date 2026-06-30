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

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.platform.api.governance.ExecutionPolicy;
import io.casehub.platform.api.governance.RetryPolicy;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests that workers which throw exceptions are retried and eventually complete successfully. Each
 * test uses a unique taskId to isolate attempt counters across concurrent or sequential runs.
 */
@QuarkusTest
public class WorkerRetryExtendedTest {

  @Inject FlexibleRetryBean bean;

  @Inject SignalTriggerRetryBean signalBean;

  @Inject TwoWorkerRetryBean twoWorkerBean;

  @Inject CaseInstanceCache caseInstanceCache;

  @Inject EventLogRepository eventLogRepository;

  @BeforeEach
  void reset() {
    FlexibleRetryBean.attempts.clear();
    FlexibleRetryBean.failUntil.clear();
    SignalTriggerRetryBean.attempts.clear();
    SignalTriggerRetryBean.failUntil.clear();
    TwoWorkerRetryBean.alphaAttempts.set(0);
    TwoWorkerRetryBean.betaAttempts.set(0);
    TwoWorkerRetryBean.betaFailUntil.set(0);
  }

  // ------------------------------------------------------------------ //

  /**
   * Worker fails on the first attempt and succeeds on the second. Run count must be exactly 2 and
   * the output must be written to the context.
   */
  @Test
  void workerSucceedsAfterOneFail() {
    String taskId = "one-fail-" + UUID.randomUUID();
    FlexibleRetryBean.failUntil.put(taskId, 1); // fail attempt 1, succeed on attempt 2

    UUID caseId =
        bean.startCase(Map.of("taskId", taskId, "status", "pending")).toCompletableFuture().join();

    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertEquals(
                  2,
                  FlexibleRetryBean.attempts.getOrDefault(taskId, new AtomicInteger()).get(),
                  "Worker must be called exactly twice: one failure + one success");
              assertEquals(
                  "processed",
                  bean.query(caseId, "status", String.class).toCompletableFuture().join());
            });
  }

  /**
   * Worker fails on the first two attempts and succeeds on the third. Total run count must be 3.
   */
  @Test
  void workerSucceedsAfterTwoFails() {
    String taskId = "two-fail-" + UUID.randomUUID();
    FlexibleRetryBean.failUntil.put(taskId, 2); // fail attempts 1–2, succeed on attempt 3

    UUID caseId =
        bean.startCase(Map.of("taskId", taskId, "status", "pending")).toCompletableFuture().join();

    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertEquals(
                  3,
                  FlexibleRetryBean.attempts.getOrDefault(taskId, new AtomicInteger()).get(),
                  "Worker must be called three times: two failures + one success");
              assertEquals(
                  "processed",
                  bean.query(caseId, "status", String.class).toCompletableFuture().join());
            });
  }

  /**
   * Run count must equal the number of configured failures plus exactly one successful attempt — no
   * extra executions must occur after success.
   */
  @Test
  void workerRunCountEqualsFailuresPlusOneSuccess() {
    String taskId = "run-count-" + UUID.randomUUID();
    int expectedFailures = 3;
    FlexibleRetryBean.failUntil.put(taskId, expectedFailures);

    bean.startCase(Map.of("taskId", taskId, "status", "pending")).toCompletableFuture().join();

    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    expectedFailures + 1,
                    FlexibleRetryBean.attempts.getOrDefault(taskId, new AtomicInteger()).get(),
                    "Run count must be exactly failures + 1 (no extra runs after success)"));
  }

  /**
   * After retrying, the output data written by the successful attempt must be correctly stored in
   * the case context — not corrupted or empty due to the preceding failures.
   */
  @Test
  void workerOutputIsCorrectAfterRetry() {
    String taskId = "output-check-" + UUID.randomUUID();
    FlexibleRetryBean.failUntil.put(taskId, 2);

    UUID caseId =
        bean.startCase(Map.of("taskId", taskId, "status", "pending")).toCompletableFuture().join();

    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertEquals(
                  "processed",
                  bean.query(caseId, "status", String.class).toCompletableFuture().join(),
                  "Output field 'status' must be 'processed' after retry success");
              assertNotNull(
                  bean.query(caseId, "taskId", String.class).toCompletableFuture().join(),
                  "Output field 'taskId' must survive through retries");
            });
  }

  /**
   * The case must reach COMPLETED status after the worker eventually succeeds on retry — not remain
   * RUNNING or transition to FAULTED.
   */
  @Test
  void caseCompletesAfterRetrySuccess() {
    String taskId = "complete-" + UUID.randomUUID();
    FlexibleRetryBean.failUntil.put(taskId, 1);

    UUID caseId =
        bean.startCase(Map.of("taskId", taskId, "status", "pending")).toCompletableFuture().join();

    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    CaseStatus.COMPLETED,
                    caseInstanceCache.get(caseId).getState(),
                    "Case must reach COMPLETED after worker succeeds on retry"));
  }

  /**
   * Each failed worker attempt must produce a WORKER_EXECUTION_FAILED entry in the event log. Two
   * failures must produce exactly two such entries.
   */
  @Test
  void workerExecutionFailedEventRecordedForEachFailure() {
    String taskId = "failed-events-" + UUID.randomUUID();
    int failCount = 2;
    FlexibleRetryBean.failUntil.put(taskId, failCount);

    UUID caseId =
        bean.startCase(Map.of("taskId", taskId, "status", "pending")).toCompletableFuture().join();

    // Wait until all attempts complete (failures + success)
    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    failCount + 1,
                    FlexibleRetryBean.attempts.getOrDefault(taskId, new AtomicInteger()).get()));

    // Fold event log assertion into await — the DB write is async and may not
    // have committed by the time the attempt counter reaches failCount+1.
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    failCount,
                    findEvents(caseId, CaseHubEventType.WORKER_EXECUTION_FAILED).size(),
                    "One WORKER_EXECUTION_FAILED entry must exist per failed attempt"));
  }

  /**
   * Worker succeeds on exactly the last allowed attempt (maxAttempts). The case must complete
   * rather than fault, and no extra attempts must occur.
   */
  @Test
  void workerSucceedsOnLastAllowedAttempt() {
    // FlexibleRetryBean uses RetryPolicy(5, 100) → max 5 attempts
    int maxAttempts = 5;
    String taskId = "last-attempt-" + UUID.randomUUID();
    FlexibleRetryBean.failUntil.put(taskId, maxAttempts - 1); // succeed on attempt 5

    UUID caseId =
        bean.startCase(Map.of("taskId", taskId, "status", "pending")).toCompletableFuture().join();

    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertEquals(
                  maxAttempts,
                  FlexibleRetryBean.attempts.getOrDefault(taskId, new AtomicInteger()).get(),
                  "Worker must be attempted exactly maxAttempts times");
              assertEquals(
                  CaseStatus.COMPLETED,
                  caseInstanceCache.get(caseId).getState(),
                  "Case must be COMPLETED, not FAULTED");
            });
  }

  /**
   * Retries must not create additional WORKER_SCHEDULED log rows — retrying a Quartz job reuses the
   * original job; only one WORKER_SCHEDULED entry must exist even after N failures.
   */
  @Test
  void retryDoesNotDuplicateWorkerScheduledLog() {
    String taskId = "no-dup-sched-" + UUID.randomUUID();
    FlexibleRetryBean.failUntil.put(taskId, 2);

    UUID caseId =
        bean.startCase(Map.of("taskId", taskId, "status", "pending")).toCompletableFuture().join();

    // Wait for all retries to complete
    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    3, FlexibleRetryBean.attempts.getOrDefault(taskId, new AtomicInteger()).get()));

    // Fold event log assertion into await — the DB write is async and may not
    // have committed by the time the attempt counter reaches 3.
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    1,
                    findWorkerEvents(caseId, CaseHubEventType.WORKER_SCHEDULED).size(),
                    "Exactly one WORKER_SCHEDULED row must exist regardless of retry count"));
  }

  /** WORKER_EXECUTION_COMPLETED must appear exactly once even after multiple failed attempts. */
  @Test
  void workerExecutionCompletedAppearsExactlyOnce() {
    String taskId = "one-completed-" + UUID.randomUUID();
    FlexibleRetryBean.failUntil.put(taskId, 2);

    UUID caseId =
        bean.startCase(Map.of("taskId", taskId, "status", "pending")).toCompletableFuture().join();

    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    3, FlexibleRetryBean.attempts.getOrDefault(taskId, new AtomicInteger()).get()));

    // Fold event log assertion into await — the DB write is async and may not
    // have committed by the time the attempt counter reaches 3.
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    1,
                    findWorkerEvents(caseId, CaseHubEventType.WORKER_EXECUTION_COMPLETED).size(),
                    "Exactly one WORKER_EXECUTION_COMPLETED entry must exist after retry success"));
  }

  /**
   * Worker triggered by a signal (not initial context) fails on the first attempt and succeeds on
   * retry. The final output must be queryable after the signal-triggered retry completes.
   */
  @Test
  void workerTriggeredBySignalSucceedsAfterRetry() {
    String taskId = "signal-retry-" + UUID.randomUUID();
    SignalTriggerRetryBean.failUntil.put(taskId, 1); // fail once, then succeed

    UUID caseId = signalBean.startCase(Map.of("taskId", taskId)).toCompletableFuture().join();

    // Worker must NOT run before the signal
    Awaitility.await()
        .during(1, TimeUnit.SECONDS)
        .atMost(2, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    0,
                    SignalTriggerRetryBean.attempts
                        .getOrDefault(taskId, new AtomicInteger())
                        .get()));

    // Signal triggers the worker
    signalBean.signal(caseId, "request", Map.of("action", "PROCESS"));

    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertEquals(
                  2,
                  SignalTriggerRetryBean.attempts.getOrDefault(taskId, new AtomicInteger()).get(),
                  "Worker must have been called twice: one failure + one success");
              assertEquals(
                  "processed",
                  signalBean.query(caseId, "status", String.class).toCompletableFuture().join());
            });
  }

  /**
   * When two workers are active in the same case, one of which fails and retries, both must
   * eventually complete. The retrying worker must not prevent the other from completing.
   */
  @Test
  void oneOfTwoWorkersRetriesButBothComplete() {
    // Alpha succeeds immediately; Beta fails once then succeeds
    TwoWorkerRetryBean.betaFailUntil.set(1);

    UUID caseId =
        twoWorkerBean
            .startCase(Map.of("alpha", "ready", "beta", "ready"))
            .toCompletableFuture()
            .join();

    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertEquals(
                  1,
                  TwoWorkerRetryBean.alphaAttempts.get(),
                  "Alpha worker must succeed on first attempt");
              assertEquals(
                  2,
                  TwoWorkerRetryBean.betaAttempts.get(),
                  "Beta worker must have two attempts: one failure + one success");
              assertEquals(
                  "alpha-done",
                  twoWorkerBean
                      .query(caseId, "alphaResult", String.class)
                      .toCompletableFuture()
                      .join());
              assertEquals(
                  "beta-done",
                  twoWorkerBean
                      .query(caseId, "betaResult", String.class)
                      .toCompletableFuture()
                      .join());
            });
  }

  // ================================================================== //
  //  Helpers                                                            //
  // ================================================================== //

  private List<EventLog> findEvents(UUID caseId, CaseHubEventType eventType) {
    return eventLogRepository
        .findByCaseAndTypes(caseId, List.of(eventType), TenancyConstants.DEFAULT_TENANT_ID)
        .subscribe()
        .asCompletionStage()
        .toCompletableFuture()
        .join();
  }

  private List<EventLog> findWorkerEvents(UUID caseId, CaseHubEventType eventType) {
    return eventLogRepository
        .findByCaseAndTypes(caseId, List.of(eventType), TenancyConstants.DEFAULT_TENANT_ID)
        .subscribe()
        .asCompletionStage()
        .toCompletableFuture()
        .join()
        .stream()
        .filter(e -> e.getWorkerId() != null)
        .toList();
  }

  // ================================================================== //
  //  Beans                                                              //
  // ================================================================== //

  /**
   * Configurable-retry worker triggered by initial context. Uses {@code failUntil[taskId]} to
   * control how many times it throws before returning success, allowing each test to configure its
   * own failure count independently via a unique taskId.
   */
  @ApplicationScoped
  public static class FlexibleRetryBean extends CaseHub {

    /** Number of times the worker has been called, keyed by taskId. */
    static final ConcurrentHashMap<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

    /**
     * How many attempts should fail before succeeding, keyed by taskId. Default: 0 (succeed
     * immediately).
     */
    static final ConcurrentHashMap<String, Integer> failUntil = new ConcurrentHashMap<>();

    private final Capability capability =
        Capability.builder()
            .name("flexibleRetryCapability")
            .inputSchema("{ taskId: .taskId, status: .status }")
            .outputSchema("{ status: .status, taskId: .taskId }")
            .build();

    private final Goal doneGoal =
        Goal.builder()
            .name("flexRetryDone")
            .condition(".status == \"processed\"")
            .kind(GoalKind.SUCCESS)
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-worker-retry-ext")
          .name("Flexible Retry Test")
          .version("1.0.0")
          .capabilities(capability)
          .workers(
              Worker.builder()
                  .name("flexible-retry-worker")
                  .capabilityName("flexibleRetryCapability")
                  .function(
                      new WorkerFunction.Sync(
                          input -> {
                            String taskId = (String) input.get("taskId");
                            int attempt =
                                attempts
                                    .computeIfAbsent(taskId, k -> new AtomicInteger())
                                    .incrementAndGet();
                            int maxFail = failUntil.getOrDefault(taskId, 0);
                            if (attempt <= maxFail) {
                              throw new RuntimeException(
                                  "Simulated failure on attempt " + attempt + " of " + maxFail);
                            }
                            return WorkerResult.of(Map.of("status", "processed", "taskId", taskId));
                          }))
                  // 5 max attempts, 100 ms between retries — allows tests up to 4 failures
                  .executionPolicy(new ExecutionPolicy(60000, new RetryPolicy(5, 100)))
                  .build())
          .bindings(
              Binding.builder()
                  .name("on-pending")
                  .capability(capability)
                  .on(new ContextChangeTrigger(".status == \"pending\""))
                  .build())
          .goals(doneGoal)
          .completion(GoalExpression.allOf(doneGoal))
          .build();
    }
  }

  /**
   * Configurable-retry worker triggered by a signal on the {@code request} path. Lets tests verify
   * the full retry lifecycle for signal-initiated workers.
   */
  @ApplicationScoped
  public static class SignalTriggerRetryBean extends CaseHub {

    static final ConcurrentHashMap<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, Integer> failUntil = new ConcurrentHashMap<>();

    private final Capability capability =
        Capability.builder()
            .name("signalRetryCapability")
            .inputSchema("{ request: .request, taskId: .taskId }")
            .outputSchema("{ status: .status }")
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-worker-retry-signal")
          .name("Signal Trigger Retry Test")
          .version("1.0.0")
          .capabilities(capability)
          .workers(
              Worker.builder()
                  .name("signal-retry-worker")
                  .capabilityName("signalRetryCapability")
                  .function(
                      new WorkerFunction.Sync(
                          input -> {
                            String taskId = (String) input.get("taskId");
                            int attempt =
                                attempts
                                    .computeIfAbsent(taskId, k -> new AtomicInteger())
                                    .incrementAndGet();
                            int maxFail = failUntil.getOrDefault(taskId, 0);
                            if (attempt <= maxFail) {
                              throw new RuntimeException(
                                  "Signal-triggered failure on attempt " + attempt);
                            }
                            return WorkerResult.of(Map.of("status", "processed"));
                          }))
                  .executionPolicy(new ExecutionPolicy(60000, new RetryPolicy(5, 100)))
                  .build())
          .bindings(
              Binding.builder()
                  .name("on-request")
                  .capability(capability)
                  .on(new ContextChangeTrigger(".request != null"))
                  .build())
          .build();
    }
  }

  /**
   * Two-worker case: Alpha always succeeds on the first attempt; Beta is configured via {@code
   * betaFailUntil} to fail a given number of times before succeeding. Both are triggered by the
   * initial context.
   */
  @ApplicationScoped
  public static class TwoWorkerRetryBean extends CaseHub {

    static final AtomicInteger alphaAttempts = new AtomicInteger();
    static final AtomicInteger betaAttempts = new AtomicInteger();
    static final AtomicInteger betaFailUntil = new AtomicInteger(0);

    private final Capability alphaCapability =
        Capability.builder()
            .name("alphaRetryCapability")
            .inputSchema("{ alpha: .alpha }")
            .outputSchema("{ alphaResult: .alphaResult }")
            .build();

    private final Capability betaCapability =
        Capability.builder()
            .name("betaRetryCapability")
            .inputSchema("{ beta: .beta }")
            .outputSchema("{ betaResult: .betaResult }")
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-worker-retry-two")
          .name("Two Worker Retry Test")
          .version("1.0.0")
          .capabilities(alphaCapability, betaCapability)
          .workers(
              Worker.builder()
                  .name("alpha-always-success")
                  .capabilityName("alphaRetryCapability")
                  .function(
                      new WorkerFunction.Sync(
                          input -> {
                            alphaAttempts.incrementAndGet();
                            return WorkerResult.of(Map.of("alphaResult", "alpha-done"));
                          }))
                  .executionPolicy(new ExecutionPolicy(60000, new RetryPolicy(3, 100)))
                  .build(),
              Worker.builder()
                  .name("beta-retry-worker")
                  .capabilityName("betaRetryCapability")
                  .function(
                      new WorkerFunction.Sync(
                          input -> {
                            int attempt = betaAttempts.incrementAndGet();
                            if (attempt <= betaFailUntil.get()) {
                              throw new RuntimeException("Beta failure on attempt " + attempt);
                            }
                            return WorkerResult.of(Map.of("betaResult", "beta-done"));
                          }))
                  .executionPolicy(new ExecutionPolicy(60000, new RetryPolicy(5, 100)))
                  .build())
          .bindings(
              Binding.builder()
                  .name("on-alpha-ready")
                  .capability(alphaCapability)
                  .on(new ContextChangeTrigger(".alpha == \"ready\""))
                  .build(),
              Binding.builder()
                  .name("on-beta-ready")
                  .capability(betaCapability)
                  .on(new ContextChangeTrigger(".beta == \"ready\""))
                  .build())
          .build();
    }
  }
}
