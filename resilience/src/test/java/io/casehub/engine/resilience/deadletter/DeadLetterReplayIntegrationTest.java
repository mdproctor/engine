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
package io.casehub.engine.resilience.deadletter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.api.context.ContextLayer;
import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.runtime.recovery.CaseRecoveryService;
import io.casehub.platform.api.governance.BackoffStrategy;
import io.casehub.platform.api.governance.ExecutionPolicy;
import io.casehub.platform.api.governance.RetryPolicy;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration test for the full DLQ replay path: worker failure → DLQ entry → replay → re-execution
 * → worker output applied to case context.
 */
@QuarkusTest
class DeadLetterReplayIntegrationTest {

  @Inject FailThenSucceedCaseHub failThenSucceedCase;
  @Inject CaseInstanceCache caseInstanceCache;
  @Inject DeadLetterQueue deadLetterQueue;
  @Inject DeadLetterReplayService replayService;
  @Inject CaseRecoveryService caseRecoveryService;

  @BeforeEach
  void setup() {
    deadLetterQueue.clear();
    FailThenSucceedCaseHub.shouldFail.set(true);
  }

  @Test
  void workerFailure_dlqReplay_succeedsAfterRecovery() {
    UUID caseId = failThenSucceedCase.startCase(Map.of("status", "start"));

    // Wait for case to FAULT after retries exhaust
    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              CaseInstance instance = caseInstanceCache.get(caseId);
              assertThat(instance).isNotNull();
              assertThat(instance.getState()).isEqualTo(CaseStatus.FAULTED);
            });

    // Verify DLQ entry was created
    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              List<DeadLetterEntry> entries = deadLetterQueue.query(DeadLetterQuery.all());
              assertThat(entries).isNotEmpty();
              assertThat(entries.get(0).caseId()).isEqualTo(caseId);
              assertThat(entries.get(0).workerId()).isEqualTo("fail-then-succeed-worker");
            });

    DeadLetterEntry entry =
        deadLetterQueue.query(DeadLetterQuery.withStatus(DeadLetterStatus.PENDING_REVIEW)).get(0);

    // Admin recovery: unfault the case, fix the worker
    FailThenSucceedCaseHub.shouldFail.set(false);
    Optional<CaseInstance> recovered = caseRecoveryService.unfault(caseId);
    assertThat(recovered).isPresent();
    assertThat(recovered.get().getState()).isEqualTo(CaseStatus.RUNNING);

    // Trigger DLQ replay
    Optional<DeadLetterEntry> result = replayService.replay(entry.deadLetterId());
    assertThat(result).isPresent();
    assertThat(result.get().status()).isEqualTo(DeadLetterStatus.REPLAYED);
    assertThat(result.get().replayAttempts()).isEqualTo(1);

    // Wait for the replayed worker to execute and apply output to context
    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              CaseInstance updated = caseInstanceCache.get(caseId);
              var working = updated.getCaseContext().layer(ContextLayer.WORKING).asJsonNode();
              assertThat(working.has("result")).isTrue();
              assertThat(working.get("result").asText()).isEqualTo("success");
            });
  }

  @Test
  void replay_onFaultedCase_returnsEmpty() {
    UUID caseId = failThenSucceedCase.startCase(Map.of("status", "start"));

    await()
        .atMost(30, TimeUnit.SECONDS)
        .until(
            () ->
                !deadLetterQueue
                    .query(DeadLetterQuery.withStatus(DeadLetterStatus.PENDING_REVIEW))
                    .isEmpty());

    DeadLetterEntry entry =
        deadLetterQueue.query(DeadLetterQuery.withStatus(DeadLetterStatus.PENDING_REVIEW)).get(0);

    // Case is FAULTED — replay must be rejected
    Optional<DeadLetterEntry> result = replayService.replay(entry.deadLetterId());
    assertThat(result).isEmpty();
    assertThat(entry.status()).isEqualTo(DeadLetterStatus.PENDING_REVIEW);
  }

  @Test
  void unfault_onNonFaultedCase_returnsEmpty() {
    FailThenSucceedCaseHub.shouldFail.set(false);
    UUID caseId = failThenSucceedCase.startCase(Map.of("status", "start"));

    // Wait for worker to succeed — case completes
    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              CaseInstance instance = caseInstanceCache.get(caseId);
              assertThat(instance).isNotNull();
              assertThat(instance.getState()).isNotEqualTo(CaseStatus.FAULTED);
            });

    Optional<CaseInstance> result = caseRecoveryService.unfault(caseId);
    assertThat(result).isEmpty();
  }

  @Test
  void unfault_unknownCase_returnsEmpty() {
    Optional<CaseInstance> result = caseRecoveryService.unfault(UUID.randomUUID());
    assertThat(result).isEmpty();
  }

  // ---- CaseHub bean: fails initially, succeeds after flag flip ----

  @ApplicationScoped
  public static class FailThenSucceedCaseHub extends CaseHub {

    static final AtomicBoolean shouldFail = new AtomicBoolean(true);

    private final Capability capability =
        Capability.builder()
            .name("doWork")
            .inputSchema("{ status: .status }")
            .outputSchema("{ result: .result }")
            .build();

    private final Goal goal =
        Goal.builder()
            .name("done")
            .condition(".result == \"success\"")
            .kind(GoalKind.SUCCESS)
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-dlq-replay")
          .name("Fail Then Succeed Case")
          .version("1.0.0")
          .capabilities(capability)
          .workers(
              Worker.builder()
                  .name("fail-then-succeed-worker")
                  .capabilityName("doWork")
                  .function(
                      new WorkerFunction.Sync<>(
                          Map.class,
                          Map.class,
                          (input, scope) -> {
                            if (shouldFail.get()) {
                              throw new RuntimeException("Intentional failure for DLQ replay test");
                            }
                            return WorkerResult.of(Map.of("result", "success"));
                          }))
                  .executionPolicy(
                      new ExecutionPolicy(5000, new RetryPolicy(1, 50, BackoffStrategy.FIXED)))
                  .build())
          .bindings(
              Binding.builder()
                  .name("trigger")
                  .capability(capability)
                  .on(new ContextChangeTrigger(".status == \"start\""))
                  .build())
          .goals(goal)
          .completion(GoalExpression.allOf(goal))
          .build();
    }
  }
}
