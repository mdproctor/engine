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
import io.casehub.api.model.Capability;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.ScheduleTrigger;
import io.casehub.api.model.Worker;
import io.casehub.api.model.WorkerResult;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ScheduleTriggerSimpleTest {

  @Inject ScheduleTriggerTestBean delayBean;
  @Inject CronTriggerTestBean cronBean;
  @Inject ConditionalTriggerTestBean conditionalBean;
  @Inject ConditionalTriggerNotMetTestBean conditionalNotMetBean;
  @Inject ConditionalTriggerBecomesReadyTestBean conditionalBecomesReadyBean;
  @Inject ConditionalTriggerBecomesBlockedTestBean conditionalBecomesBlockedBean;
  @Inject MultipleTriggerTestBean multipleTriggersBean;
  @Inject CancellationTestBean cancellationBean;
  @Inject CaseInstanceCache caseInstanceCache;

  @BeforeEach
  void setUp() {
    ScheduleTriggerTestBean.executionCount.set(0);
    CronTriggerTestBean.executionCount.set(0);
    ConditionalTriggerTestBean.executionCount.set(0);
    ConditionalTriggerNotMetTestBean.executionCount.set(0);
    ConditionalTriggerBecomesReadyTestBean.executionCount.set(0);
    ConditionalTriggerBecomesBlockedTestBean.executionCount.set(0);
    MultipleTriggerTestBean.worker1Count.set(0);
    MultipleTriggerTestBean.worker2Count.set(0);
    CancellationTestBean.executionCount.set(0);
  }

  @Test
  void delayTriggerExecutesWorkerAfterDuration() {
    delayBean.startCase(Map.of("started", true));

    // Should NOT execute immediately
    assertThat(ScheduleTriggerTestBean.executionCount.get()).isEqualTo(0);

    // Wait for trigger (2s delay + buffer)
    await()
        .atMost(10, TimeUnit.SECONDS)
        .pollInterval(100, TimeUnit.MILLISECONDS)
        .untilAsserted(() -> assertThat(ScheduleTriggerTestBean.executionCount.get()).isEqualTo(1));
  }

  @Test
  void cronTriggerExecutesWorker() {
    cronBean.startCase(Map.of("started", true));

    // Should NOT execute immediately
    assertThat(CronTriggerTestBean.executionCount.get()).isEqualTo(0);

    // Wait for first execution (cron runs every 2 seconds: "*/2 * * * * ?")
    // Note: worker deduplication prevents repeated execution of the same worker
    // Testing periodic execution would require different workers or idempotency keys
    await()
        .atMost(5, TimeUnit.SECONDS)
        .pollInterval(100, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () -> assertThat(CronTriggerTestBean.executionCount.get()).isGreaterThanOrEqualTo(1));
  }

  @Test
  void conditionalTriggerExecutesWhenConditionMet() {
    conditionalBean.startCase(Map.of("status", "ready"));

    // Should NOT execute immediately
    assertThat(ConditionalTriggerTestBean.executionCount.get()).isEqualTo(0);

    // Wait for trigger (condition .status == "ready" should be true)
    await()
        .atMost(10, TimeUnit.SECONDS)
        .pollInterval(100, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () -> assertThat(ConditionalTriggerTestBean.executionCount.get()).isEqualTo(1));
  }

  @Test
  void conditionalTriggerDoesNotExecuteWhenConditionNotMet() {
    conditionalNotMetBean.startCase(Map.of("status", "not-ready"));

    // Should NOT execute immediately
    assertThat(ConditionalTriggerNotMetTestBean.executionCount.get()).isEqualTo(0);

    // Wait for trigger - should NOT execute because condition is not met
    // Condition checks .status == "ready", but we set it to "not-ready"
    try {
      await()
          .atMost(5, TimeUnit.SECONDS)
          .pollInterval(100, TimeUnit.MILLISECONDS)
          .untilAsserted(
              () ->
                  assertThat(ConditionalTriggerNotMetTestBean.executionCount.get())
                      .isGreaterThan(0));

      // If we reach here, test should fail
      throw new AssertionError("Worker should not have executed when condition is not met");
    } catch (org.awaitility.core.ConditionTimeoutException e) {
      // Expected - condition was not met, so worker never executed
      assertThat(ConditionalTriggerNotMetTestBean.executionCount.get()).isEqualTo(0);
    }
  }

  @Test
  void conditionalTriggerEvaluatesLatestContextWhenConditionBecomesMetBeforeFire() {
    java.util.UUID caseId =
        conditionalBecomesReadyBean
            .startCase(Map.of("status", "not-ready"))
            .toCompletableFuture()
            .join();

    assertThat(ConditionalTriggerBecomesReadyTestBean.executionCount.get()).isEqualTo(0);

    conditionalBecomesReadyBean.signal(caseId, "status", "ready");

    await()
        .atMost(10, TimeUnit.SECONDS)
        .pollInterval(100, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () ->
                assertThat(ConditionalTriggerBecomesReadyTestBean.executionCount.get())
                    .isEqualTo(1));
  }

  @Test
  void conditionalTriggerEvaluatesLatestContextWhenConditionBecomesFalseBeforeFire() {
    java.util.UUID caseId =
        conditionalBecomesBlockedBean
            .startCase(Map.of("status", "ready"))
            .toCompletableFuture()
            .join();

    assertThat(ConditionalTriggerBecomesBlockedTestBean.executionCount.get()).isEqualTo(0);

    conditionalBecomesBlockedBean.signal(caseId, "status", "blocked");

    try {
      await()
          .atMost(5, TimeUnit.SECONDS)
          .pollInterval(100, TimeUnit.MILLISECONDS)
          .untilAsserted(
              () ->
                  assertThat(ConditionalTriggerBecomesBlockedTestBean.executionCount.get())
                      .isGreaterThan(0));

      throw new AssertionError("Worker should not have executed after condition became false");
    } catch (org.awaitility.core.ConditionTimeoutException e) {
      assertThat(ConditionalTriggerBecomesBlockedTestBean.executionCount.get()).isEqualTo(0);
    }
  }

  @Test
  void multipleTriggersExecuteIndependently() {
    multipleTriggersBean.startCase(Map.of("started", true));

    // Should NOT execute immediately
    assertThat(MultipleTriggerTestBean.worker1Count.get()).isEqualTo(0);
    assertThat(MultipleTriggerTestBean.worker2Count.get()).isEqualTo(0);

    // Wait for both triggers (2s and 3s delays)
    await()
        .atMost(10, TimeUnit.SECONDS)
        .pollInterval(100, TimeUnit.MILLISECONDS)
        .untilAsserted(() -> assertThat(MultipleTriggerTestBean.worker1Count.get()).isEqualTo(1));

    await()
        .atMost(10, TimeUnit.SECONDS)
        .pollInterval(100, TimeUnit.MILLISECONDS)
        .untilAsserted(() -> assertThat(MultipleTriggerTestBean.worker2Count.get()).isEqualTo(1));
  }

  @Test
  void triggersAreCancelledWhenCaseCompletes() {
    java.util.UUID caseId =
        cancellationBean.startCase(Map.of("started", true)).toCompletableFuture().join();

    // Should NOT execute immediately
    assertThat(CancellationTestBean.executionCount.get()).isEqualTo(0);

    // Complete the case before trigger fires (trigger has 5s delay)
    CaseInstance instance = caseInstanceCache.get(caseId);
    instance.setState(CaseStatus.COMPLETED);

    // Wait to ensure trigger does NOT fire
    try {
      await()
          .atMost(7, TimeUnit.SECONDS)
          .pollInterval(100, TimeUnit.MILLISECONDS)
          .untilAsserted(
              () -> assertThat(CancellationTestBean.executionCount.get()).isGreaterThan(0));

      // If we reach here, test should fail
      throw new AssertionError("Worker should not have executed after case completed");
    } catch (org.awaitility.core.ConditionTimeoutException e) {
      // Expected - trigger should be cancelled or lazy-cancelled
      assertThat(CancellationTestBean.executionCount.get()).isEqualTo(0);
    }
  }

  @ApplicationScoped
  static class ScheduleTriggerTestBean extends CaseHub {
    static final AtomicInteger executionCount = new AtomicInteger(0);

    @Override
    public CaseDefinition getDefinition() {
      Capability cap =
          Capability.builder()
              .name("doWork")
              .inputSchema("{ }")
              .outputSchema("{ workDone: .workDone }")
              .build();

      Worker worker =
          Worker.builder()
              .name("worker")
              .capabilities(cap)
              .function(
                  ctx -> {
                    executionCount.incrementAndGet();
                    return WorkerResult.of(Map.of("workDone", true));
                  })
              .build();

      Binding binding =
          Binding.builder()
              .name("delayed-work")
              .capability(cap)
              .on(ScheduleTrigger.delay(Duration.ofSeconds(2)))
              .build();

      return CaseDefinition.builder()
          .name("schedule-test-delay")
          .namespace("test")
          .version("1.0")
          .capabilities(cap)
          .workers(worker)
          .bindings(binding)
          .build();
    }
  }

  @ApplicationScoped
  static class CronTriggerTestBean extends CaseHub {
    static final AtomicInteger executionCount = new AtomicInteger(0);

    @Override
    public CaseDefinition getDefinition() {
      Capability cap =
          Capability.builder()
              .name("doWork")
              .inputSchema("{ }")
              .outputSchema("{ workDone: .workDone }")
              .build();

      Worker worker =
          Worker.builder()
              .name("worker")
              .capabilities(cap)
              .function(
                  ctx -> {
                    executionCount.incrementAndGet();
                    return WorkerResult.of(Map.of("workDone", true));
                  })
              .build();

      Binding binding =
          Binding.builder()
              .name("cron-work")
              .capability(cap)
              .on(ScheduleTrigger.cron("*/2 * * * * ?")) // Every 2 seconds
              .build();

      return CaseDefinition.builder()
          .name("schedule-test-cron")
          .namespace("test")
          .version("1.0")
          .capabilities(cap)
          .workers(worker)
          .bindings(binding)
          .build();
    }
  }

  @ApplicationScoped
  static class ConditionalTriggerTestBean extends CaseHub {
    static final AtomicInteger executionCount = new AtomicInteger(0);

    @Override
    public CaseDefinition getDefinition() {
      Capability cap =
          Capability.builder()
              .name("doWork")
              .inputSchema("{ }")
              .outputSchema("{ workDone: .workDone }")
              .build();

      Worker worker =
          Worker.builder()
              .name("worker")
              .capabilities(cap)
              .function(
                  ctx -> {
                    executionCount.incrementAndGet();
                    return WorkerResult.of(Map.of("workDone", true));
                  })
              .build();

      Binding binding =
          Binding.builder()
              .name("conditional-work")
              .capability(cap)
              .on(ScheduleTrigger.delay(Duration.ofSeconds(2)))
              .when(new JQExpressionEvaluator(".working.status == \"ready\""))
              .build();

      return CaseDefinition.builder()
          .name("schedule-test-conditional")
          .namespace("test")
          .version("1.0")
          .capabilities(cap)
          .workers(worker)
          .bindings(binding)
          .build();
    }
  }

  @ApplicationScoped
  static class ConditionalTriggerNotMetTestBean extends CaseHub {
    static final AtomicInteger executionCount = new AtomicInteger(0);

    @Override
    public CaseDefinition getDefinition() {
      Capability cap =
          Capability.builder()
              .name("doWork")
              .inputSchema("{ }")
              .outputSchema("{ workDone: .workDone }")
              .build();

      Worker worker =
          Worker.builder()
              .name("worker")
              .capabilities(cap)
              .function(
                  ctx -> {
                    executionCount.incrementAndGet();
                    return WorkerResult.of(Map.of("workDone", true));
                  })
              .build();

      Binding binding =
          Binding.builder()
              .name("conditional-not-met-work")
              .capability(cap)
              .on(ScheduleTrigger.delay(Duration.ofSeconds(2)))
              .when(new JQExpressionEvaluator(".working.status == \"ready\""))
              .build();

      return CaseDefinition.builder()
          .name("schedule-test-conditional-not-met")
          .namespace("test")
          .version("1.0")
          .capabilities(cap)
          .workers(worker)
          .bindings(binding)
          .build();
    }
  }

  @ApplicationScoped
  static class ConditionalTriggerBecomesReadyTestBean extends CaseHub {
    static final AtomicInteger executionCount = new AtomicInteger(0);

    @Override
    public CaseDefinition getDefinition() {
      Capability cap =
          Capability.builder()
              .name("doWork")
              .inputSchema("{ }")
              .outputSchema("{ workDone: .workDone }")
              .build();

      Worker worker =
          Worker.builder()
              .name("worker")
              .capabilities(cap)
              .function(
                  ctx -> {
                    executionCount.incrementAndGet();
                    return WorkerResult.of(Map.of("workDone", true));
                  })
              .build();

      Binding binding =
          Binding.builder()
              .name("conditional-becomes-ready-work")
              .capability(cap)
              .on(ScheduleTrigger.delay(Duration.ofSeconds(2)))
              .when(new JQExpressionEvaluator(".working.status == \"ready\""))
              .build();

      return CaseDefinition.builder()
          .name("schedule-test-conditional-becomes-ready")
          .namespace("test")
          .version("1.0")
          .capabilities(cap)
          .workers(worker)
          .bindings(binding)
          .build();
    }
  }

  @ApplicationScoped
  static class ConditionalTriggerBecomesBlockedTestBean extends CaseHub {
    static final AtomicInteger executionCount = new AtomicInteger(0);

    @Override
    public CaseDefinition getDefinition() {
      Capability cap =
          Capability.builder()
              .name("doWork")
              .inputSchema("{ }")
              .outputSchema("{ workDone: .workDone }")
              .build();

      Worker worker =
          Worker.builder()
              .name("worker")
              .capabilities(cap)
              .function(
                  ctx -> {
                    executionCount.incrementAndGet();
                    return WorkerResult.of(Map.of("workDone", true));
                  })
              .build();

      Binding binding =
          Binding.builder()
              .name("conditional-becomes-blocked-work")
              .capability(cap)
              .on(ScheduleTrigger.delay(Duration.ofSeconds(2)))
              .when(new JQExpressionEvaluator(".working.status == \"ready\""))
              .build();

      return CaseDefinition.builder()
          .name("schedule-test-conditional-becomes-blocked")
          .namespace("test")
          .version("1.0")
          .capabilities(cap)
          .workers(worker)
          .bindings(binding)
          .build();
    }
  }

  @ApplicationScoped
  static class MultipleTriggerTestBean extends CaseHub {
    static final AtomicInteger worker1Count = new AtomicInteger(0);
    static final AtomicInteger worker2Count = new AtomicInteger(0);

    @Override
    public CaseDefinition getDefinition() {
      Capability cap1 =
          Capability.builder()
              .name("doWork1")
              .inputSchema("{ }")
              .outputSchema("{ work1Done: .work1Done }")
              .build();

      Capability cap2 =
          Capability.builder()
              .name("doWork2")
              .inputSchema("{ }")
              .outputSchema("{ work2Done: .work2Done }")
              .build();

      Worker worker1 =
          Worker.builder()
              .name("worker1")
              .capabilities(cap1)
              .function(
                  ctx -> {
                    worker1Count.incrementAndGet();
                    return WorkerResult.of(Map.of("work1Done", true));
                  })
              .build();

      Worker worker2 =
          Worker.builder()
              .name("worker2")
              .capabilities(cap2)
              .function(
                  ctx -> {
                    worker2Count.incrementAndGet();
                    return WorkerResult.of(Map.of("work2Done", true));
                  })
              .build();

      Binding binding1 =
          Binding.builder()
              .name("trigger1")
              .capability(cap1)
              .on(ScheduleTrigger.delay(Duration.ofSeconds(2)))
              .build();

      Binding binding2 =
          Binding.builder()
              .name("trigger2")
              .capability(cap2)
              .on(ScheduleTrigger.delay(Duration.ofSeconds(3)))
              .build();

      return CaseDefinition.builder()
          .name("schedule-test-multiple")
          .namespace("test")
          .version("1.0")
          .capabilities(cap1, cap2)
          .workers(worker1, worker2)
          .bindings(binding1, binding2)
          .build();
    }
  }

  @ApplicationScoped
  static class CancellationTestBean extends CaseHub {
    static final AtomicInteger executionCount = new AtomicInteger(0);

    @Override
    public CaseDefinition getDefinition() {
      Capability cap =
          Capability.builder()
              .name("doWork")
              .inputSchema("{ }")
              .outputSchema("{ workDone: .workDone }")
              .build();

      Worker worker =
          Worker.builder()
              .name("worker")
              .capabilities(cap)
              .function(
                  ctx -> {
                    executionCount.incrementAndGet();
                    return WorkerResult.of(Map.of("workDone", true));
                  })
              .build();

      Binding binding =
          Binding.builder()
              .name("delayed-work")
              .capability(cap)
              .on(ScheduleTrigger.delay(Duration.ofSeconds(5)))
              .build();

      Goal goal =
          Goal.builder()
              .name("complete")
              .kind(GoalKind.SUCCESS)
              .condition(new JQExpressionEvaluator(".working.workDone == true"))
              .build();

      return CaseDefinition.builder()
          .name("schedule-test-cancellation")
          .namespace("test")
          .version("1.0")
          .capabilities(cap)
          .workers(worker)
          .bindings(binding)
          .goals(goal)
          .build();
    }
  }
}
