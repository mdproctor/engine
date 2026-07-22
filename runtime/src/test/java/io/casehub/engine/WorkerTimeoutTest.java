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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.OutcomeAction;
import io.casehub.api.model.OutcomePolicy;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.platform.api.governance.ExecutionPolicy;
import io.casehub.platform.api.governance.RetryPolicy;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class WorkerTimeoutTest {

  @Inject FastWorkerTestBean fastWorkerBean;
  @Inject SlowWorkerDefaultTimeoutTestBean slowWorkerDefaultTimeoutBean;
  @Inject SlowWorkerCustomTimeoutTestBean slowWorkerCustomTimeoutBean;
  @Inject FastWorkerWithShortTimeoutTestBean fastWorkerWithShortTimeoutBean;

  @Inject CaseInstanceCache caseInstanceCache;

  @BeforeEach
  void setUp() {
    FastWorkerTestBean.executionCount.set(0);
    SlowWorkerDefaultTimeoutTestBean.executionCount.set(0);
    SlowWorkerCustomTimeoutTestBean.executionCount.set(0);
    FastWorkerWithShortTimeoutTestBean.executionCount.set(0);
  }

  @Test
  void fastWorkerShouldCompleteSuccessfully() {
    UUID caseId = fastWorkerBean.startCase(Map.of("input", "data")).toCompletableFuture().join();

    // Worker should execute quickly and complete
    await()
        .atMost(5, TimeUnit.SECONDS)
        .pollInterval(100, TimeUnit.MILLISECONDS)
        .untilAsserted(() -> assertThat(FastWorkerTestBean.executionCount.get()).isEqualTo(1));

    // Wait for result to be written to context
    await()
        .atMost(3, TimeUnit.SECONDS)
        .pollInterval(100, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () -> {
              CaseInstance instance = caseInstanceCache.get(caseId);
              assertThat(instance.getCaseContext().get("result")).isEqualTo("fast-completed");
            });
  }

  @Test
  void slowWorkerShouldTimeoutWithDefaultTimeout() {
    UUID caseId =
        slowWorkerDefaultTimeoutBean
            .startCase(Map.of("input", "data"))
            .toCompletableFuture()
            .join();

    // Worker starts executing
    await()
        .atMost(3, TimeUnit.SECONDS)
        .pollInterval(100, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () -> assertThat(SlowWorkerDefaultTimeoutTestBean.executionCount.get()).isEqualTo(1));

    // Case should transition to FAULTED via OutcomePolicy.onExpired=FAULT
    // Default timeout is 2000ms in tests, worker sleeps 10s, so timeout happens ~2s after start
    await()
        .atMost(6, TimeUnit.SECONDS)
        .pollInterval(200, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () -> {
              CaseInstance instance = caseInstanceCache.get(caseId);
              assertThat(instance.getState()).isEqualTo(CaseStatus.FAULTED);
            });
  }

  @Test
  void slowWorkerShouldCompleteWithCustomLongerTimeout() {
    UUID caseId =
        slowWorkerCustomTimeoutBean.startCase(Map.of("input", "data")).toCompletableFuture().join();

    // Worker should complete successfully with 10 second timeout (worker sleeps 3 seconds)
    await()
        .atMost(15, TimeUnit.SECONDS)
        .pollInterval(100, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () -> assertThat(SlowWorkerCustomTimeoutTestBean.executionCount.get()).isEqualTo(1));

    // Wait for worker to complete and result to be written to context
    await()
        .atMost(10, TimeUnit.SECONDS)
        .pollInterval(100, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () -> {
              CaseInstance instance = caseInstanceCache.get(caseId);
              assertThat(instance.getCaseContext().get("result")).isEqualTo("slow-completed");
              assertThat(instance.getState()).isEqualTo(CaseStatus.RUNNING);
            });
  }

  @Test
  void fastWorkerShouldTimeoutWithVeryShortTimeout() {
    UUID caseId =
        fastWorkerWithShortTimeoutBean
            .startCase(Map.of("input", "data"))
            .toCompletableFuture()
            .join();

    // Worker starts executing
    await()
        .atMost(3, TimeUnit.SECONDS)
        .pollInterval(100, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () -> assertThat(FastWorkerWithShortTimeoutTestBean.executionCount.get()).isEqualTo(1));

    // Case should fault via OutcomePolicy.onExpired=FAULT (100ms timeout, worker sleeps 500ms)
    await()
        .atMost(10, TimeUnit.SECONDS)
        .pollInterval(200, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () -> {
              CaseInstance instance = caseInstanceCache.get(caseId);
              assertThat(instance.getState()).isEqualTo(CaseStatus.FAULTED);
            });
  }

  @ApplicationScoped
  static class FastWorkerTestBean extends CaseHub {
    static final AtomicInteger executionCount = new AtomicInteger(0);

    @Override
    public CaseDefinition getDefinition() {
      Capability cap =
          Capability.builder()
              .name("fastWork")
              .inputSchema("{ }")
              .outputSchema("{ result: .result }")
              .build();

      Worker worker =
          Worker.builder()
              .name("fast-worker")
              .capabilityName("fastWork")
              .executionPolicy(new ExecutionPolicy()) // Uses default timeout
              .function(
                  new WorkerFunction.Sync<>(
                      Map.class,
                      Map.class,
                      (ctx, scope) -> {
                        executionCount.incrementAndGet();
                        // Completes immediately
                        return WorkerResult.of(Map.of("result", "fast-completed"));
                      }))
              .build();

      return CaseDefinition.builder()
          .name("fast-worker-test")
          .namespace("test")
          .version("1.0")
          .capabilities(cap)
          .workers(worker)
          .bindings(
              Binding.builder()
                  .name("trigger-fast-worker")
                  .capability(cap)
                  .on(new ContextChangeTrigger(".input != null"))
                  .build())
          .build();
    }
  }

  @ApplicationScoped
  static class SlowWorkerDefaultTimeoutTestBean extends CaseHub {
    static final AtomicInteger executionCount = new AtomicInteger(0);

    @Override
    public CaseDefinition getDefinition() {
      Capability cap =
          Capability.builder()
              .name("slowWork")
              .inputSchema("{ }")
              .outputSchema("{ result: .result }")
              .build();

      // No retry to make test faster
      ExecutionPolicy noRetryPolicy = ExecutionPolicy.noRetry();

      Worker worker =
          Worker.builder()
              .name("slow-worker-default")
              .capabilityName("slowWork")
              .executionPolicy(noRetryPolicy)
              .function(
                  new WorkerFunction.Sync<>(
                      Map.class,
                      Map.class,
                      (ctx, scope) -> {
                        executionCount.incrementAndGet();
                        try {
                          // Sleep longer than default timeout (2000ms in tests)
                          Thread.sleep(10000); // 10 seconds
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                        return WorkerResult.of(Map.of("result", "should-not-complete"));
                      }))
              .build();

      return CaseDefinition.builder()
          .name("slow-worker-default-timeout-test")
          .namespace("test")
          .version("1.0")
          .capabilities(cap)
          .workers(worker)
          .bindings(
              Binding.builder()
                  .name("trigger-slow-worker-default")
                  .capability(cap)
                  .outcomePolicy(
                      new OutcomePolicy(
                          OutcomeAction.REROUTE, OutcomeAction.REROUTE, OutcomeAction.FAULT, 1))
                  .on(new ContextChangeTrigger(".input != null"))
                  .build())
          .build();
    }
  }

  @ApplicationScoped
  static class SlowWorkerCustomTimeoutTestBean extends CaseHub {
    static final AtomicInteger executionCount = new AtomicInteger(0);

    @Override
    public CaseDefinition getDefinition() {
      Capability cap =
          Capability.builder()
              .name("slowWorkCustom")
              .inputSchema("{ }")
              .outputSchema("{ result: .result }")
              .build();

      Worker worker =
          Worker.builder()
              .name("slow-worker-custom")
              .capabilityName("slowWorkCustom")
              .executionPolicy(
                  new ExecutionPolicy(
                      10000, // 10 second custom timeout
                      new RetryPolicy()))
              .function(
                  new WorkerFunction.Sync<>(
                      Map.class,
                      Map.class,
                      (ctx, scope) -> {
                        executionCount.incrementAndGet();
                        try {
                          // Sleep 3 seconds - within custom timeout
                          Thread.sleep(3000);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                        return WorkerResult.of(Map.of("result", "slow-completed"));
                      }))
              .build();

      return CaseDefinition.builder()
          .name("slow-worker-custom-timeout-test")
          .namespace("test")
          .version("1.0")
          .capabilities(cap)
          .workers(worker)
          .bindings(
              Binding.builder()
                  .name("trigger-slow-worker-custom")
                  .capability(cap)
                  .on(new ContextChangeTrigger(".input != null"))
                  .build())
          .build();
    }
  }

  @ApplicationScoped
  static class FastWorkerWithShortTimeoutTestBean extends CaseHub {
    static final AtomicInteger executionCount = new AtomicInteger(0);

    @Override
    public CaseDefinition getDefinition() {
      Capability cap =
          Capability.builder()
              .name("fastWorkShortTimeout")
              .inputSchema("{ }")
              .outputSchema("{ result: .result }")
              .build();

      // No retry to make test faster
      ExecutionPolicy noRetryPolicy = ExecutionPolicy.noRetry();

      Worker worker =
          Worker.builder()
              .name("fast-worker-short-timeout")
              .capabilityName("fastWorkShortTimeout")
              .executionPolicy(
                  new ExecutionPolicy(
                      100, // Very short timeout (100ms)
                      new RetryPolicy(1, 0, null)))
              .function(
                  new WorkerFunction.Sync<>(
                      Map.class,
                      Map.class,
                      (ctx, scope) -> {
                        executionCount.incrementAndGet();
                        try {
                          // Sleep 500ms - exceeds short timeout
                          Thread.sleep(500);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                        return WorkerResult.of(Map.of("result", "should-not-complete"));
                      }))
              .build();

      return CaseDefinition.builder()
          .name("fast-worker-short-timeout-test")
          .namespace("test")
          .version("1.0")
          .capabilities(cap)
          .workers(worker)
          .bindings(
              Binding.builder()
                  .name("trigger-fast-worker-short")
                  .capability(cap)
                  .outcomePolicy(
                      new OutcomePolicy(
                          OutcomeAction.REROUTE, OutcomeAction.REROUTE, OutcomeAction.FAULT, 1))
                  .on(new ContextChangeTrigger(".input != null"))
                  .build())
          .build();
    }
  }
}
