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
package io.casehub.resilience.poison;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
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

/**
 * End-to-end test: verifies that once a worker is quarantined by {@link PoisonPillDetector}, the
 * engine skips scheduling it and the case transitions to FAULTED without additional execution
 * attempts.
 *
 * <p>The test pre-quarantines the worker directly via {@link PoisonPillDetector} (bypassing the
 * failure count mechanism), then starts a case and verifies the worker is never invoked.
 */
@QuarkusTest
class PoisonPillIntegrationTest {

  @Inject PoisonPillDetector detector;
  @Inject CaseInstanceCache caseInstanceCache;
  @Inject QuarantineSkipCaseHub quarantineSkipCase;

  @BeforeEach
  void releaseAll() {
    detector.release("quarantine-checked-worker");
    QuarantineSkipCaseHub.runCount.set(0);
  }

  /**
   * When the worker is quarantined, the engine must NOT invoke it — the worker function should
   * never run, and the case should fault due to no eligible workers.
   */
  @Test
  void quarantinedWorker_isNeverInvoked_andCaseFaults() {
    // Pre-quarantine: record enough failures to trigger quarantine (threshold=3 in test config)
    detector.recordFailure("quarantine-checked-worker");
    detector.recordFailure("quarantine-checked-worker");
    detector.recordFailure("quarantine-checked-worker");
    assertThat(detector.isQuarantined("quarantine-checked-worker")).isTrue();

    UUID caseId = quarantineSkipCase.startCase(Map.of("status", "run"));

    // Case must reach FAULTED — quarantined worker cannot execute
    await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseId);
              assertThat(instance).isNotNull();
              assertThat(instance.getState()).isEqualTo(CaseStatus.FAULTED);
            });

    // Worker function must have been called zero times
    assertThat(QuarantineSkipCaseHub.runCount.get())
        .as("quarantined worker must not execute")
        .isZero();
  }

  /** After releasing the quarantine, the worker should execute normally and complete the case. */
  @Test
  void afterRelease_workerExecutesNormally() {
    // Pre-quarantine then release
    detector.recordFailure("quarantine-checked-worker");
    detector.recordFailure("quarantine-checked-worker");
    detector.recordFailure("quarantine-checked-worker");
    assertThat(detector.isQuarantined("quarantine-checked-worker")).isTrue();

    detector.release("quarantine-checked-worker");
    assertThat(detector.isQuarantined("quarantine-checked-worker")).isFalse();

    UUID caseId2 = quarantineSkipCase.startCase(Map.of("status", "run"));

    await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseId2);
              assertThat(instance).isNotNull();
              assertThat(instance.getState()).isEqualTo(CaseStatus.COMPLETED);
            });

    assertThat(QuarantineSkipCaseHub.runCount.get())
        .as("released worker should have executed once")
        .isEqualTo(1);
  }

  // ---- CaseHub bean ---------------------------------------------------------

  @ApplicationScoped
  public static class QuarantineSkipCaseHub extends CaseHub {

    static final AtomicInteger runCount = new AtomicInteger(0);

    private final Capability capability =
        Capability.builder()
            .name("checkedWork")
            .inputSchema("{ status: .status }")
            .outputSchema("{ status: .status }")
            .build();

    private final Goal goal =
        Goal.builder()
            .name("done")
            .condition(".status == \"complete\"")
            .kind(GoalKind.SUCCESS)
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-poison-pill-e2e")
          .name("Quarantine Skip Case")
          .version("1.0.0")
          .capabilities(capability)
          .workers(
              Worker.builder()
                  .name("quarantine-checked-worker")
                  .capabilityName("checkedWork")
                  .function(
                      new WorkerFunction.Sync<>(
                          Map.class,
                          Map.class,
                          (input, scope) -> {
                            runCount.incrementAndGet();
                            return WorkerResult.of(Map.of("status", "complete"));
                          }))
                  .executionPolicy(new ExecutionPolicy(5000, new RetryPolicy(1, 100)))
                  .build())
          .bindings(
              Binding.builder()
                  .name("trigger")
                  .capability(capability)
                  .on(new ContextChangeTrigger(".status == \"run\""))
                  .build())
          .goals(goal)
          .completion(GoalExpression.allOf(goal))
          .build();
    }
  }
}
