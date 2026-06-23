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
package io.casehub.resilience.deadletter;

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
import io.casehub.platform.api.governance.BackoffStrategy;
import io.casehub.platform.api.governance.ExecutionPolicy;
import io.casehub.platform.api.governance.RetryPolicy;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test: a real case is started with a worker that always fails. After maxAttempts, the
 * engine emits WORKER_RETRIES_EXHAUSTED, the case transitions to FAULTED, and the DLQ receives an
 * entry with the correct metadata.
 */
@QuarkusTest
class DeadLetterQueueEndToEndTest {

  @Inject AlwaysFailingCaseHub alwaysFailingCase;
  @Inject FastFailingCaseHub fastFailingCase;
  @Inject CaseInstanceCache caseInstanceCache;
  @Inject DeadLetterQueue deadLetterQueue;
  @Inject DeadLetterReplayService replayService;

  @BeforeEach
  void clearQueue() {
    deadLetterQueue.clear();
  }

  /** Happy path: exhausted retries → FAULTED case + DLQ entry. */
  @Test
  void workerExhaustingRetries_faultsCase_andCreatesDlqEntry() {
    AtomicReference<UUID> caseIdRef = new AtomicReference<>();
    AtomicReference<Throwable> err = new AtomicReference<>();

    alwaysFailingCase
        .startCase(Map.of("status", "start"))
        .thenAccept(caseIdRef::set)
        .exceptionally(
            ex -> {
              err.set(ex);
              return null;
            });

    // Wait for case to be created
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              if (err.get() != null) throw new AssertionError(err.get());
              assertThat(caseIdRef.get()).isNotNull();
            });

    UUID caseId = caseIdRef.get();

    // Wait for case to reach FAULTED (all retries exhausted)
    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseId);
              assertThat(instance).isNotNull();
              assertThat(instance.getState()).isEqualTo(CaseStatus.FAULTED);
            });

    // DLQ must have exactly one entry for this case
    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              List<DeadLetterEntry> entries = deadLetterQueue.query(DeadLetterQuery.all());
              assertThat(entries).hasSize(1);

              DeadLetterEntry entry = entries.get(0);
              assertThat(entry.caseId()).isEqualTo(caseId);
              assertThat(entry.workerId()).isEqualTo("always-failing-worker");
              assertThat(entry.status()).isEqualTo(DeadLetterStatus.PENDING_REVIEW);
              assertThat(entry.idempotencyHash()).isNotBlank();
            });
  }

  /** DLQ entry can be discarded after inspection. */
  @Test
  void dlqEntry_canBeDiscarded() {
    fastFailingCase.startCase(Map.of("status", "start")).toCompletableFuture().join();

    // Wait for DLQ entry
    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(deadLetterQueue.size()).isGreaterThan(0));

    String id = deadLetterQueue.query(DeadLetterQuery.all()).get(0).deadLetterId();
    deadLetterQueue.discard(id);

    List<DeadLetterEntry> pending =
        deadLetterQueue.query(DeadLetterQuery.withStatus(DeadLetterStatus.PENDING_REVIEW));
    List<DeadLetterEntry> discarded =
        deadLetterQueue.query(DeadLetterQuery.withStatus(DeadLetterStatus.DISCARDED));

    assertThat(pending).isEmpty();
    assertThat(discarded).hasSize(1);
  }

  /** Replay on a FAULTED case must be rejected gracefully — returns empty, entry stays PENDING. */
  @Test
  void failedWorker_dlqEntry_replayOnFaultedCase_returnsEmpty() {
    UUID caseId =
        alwaysFailingCase.startCase(Map.of("status", "start")).toCompletableFuture().join();

    await()
        .atMost(30, TimeUnit.SECONDS)
        .until(
            () ->
                !deadLetterQueue
                    .query(DeadLetterQuery.withStatus(DeadLetterStatus.PENDING_REVIEW))
                    .isEmpty());

    DeadLetterEntry entry =
        deadLetterQueue.query(DeadLetterQuery.withStatus(DeadLetterStatus.PENDING_REVIEW)).get(0);
    assertThat(entry.caseId()).isEqualTo(caseId);
    assertThat(entry.replayAttempts()).isZero();

    // Case is FAULTED — replay must be rejected gracefully
    Optional<DeadLetterEntry> result = replayService.replay(entry.deadLetterId());
    assertThat(result).isEmpty();
    assertThat(entry.status()).isEqualTo(DeadLetterStatus.PENDING_REVIEW);
  }

  // ---- CaseHub bean: always fails, 2 retries (fast) -------------------------

  @ApplicationScoped
  public static class AlwaysFailingCaseHub extends CaseHub {

    private final Capability capability =
        Capability.builder()
            .name("doWork")
            .inputSchema("{ status: .status }")
            .outputSchema("{ status: .status }")
            .build();

    private final Goal goal =
        Goal.builder().name("done").condition(".status == \"done\"").kind(GoalKind.SUCCESS).build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-dlq-e2e")
          .name("Always Failing Case")
          .version("1.0.0")
          .capabilities(capability)
          .workers(
              Worker.builder()
                  .name("always-failing-worker")
                  .capabilities(capability)
                  .function(
                      new WorkerFunction.Sync(
                          input -> {
                            throw new RuntimeException("Intentional failure for DLQ E2E test");
                          }))
                  .executionPolicy(
                      new ExecutionPolicy(5000, new RetryPolicy(2, 100, BackoffStrategy.FIXED)))
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

  @ApplicationScoped
  public static class FastFailingCaseHub extends CaseHub {

    private final Capability capability =
        Capability.builder()
            .name("fastFail")
            .inputSchema("{ status: .status }")
            .outputSchema("{ status: .status }")
            .build();

    private final Goal goal =
        Goal.builder()
            .name("fastDone")
            .condition(".status == \"done\"")
            .kind(GoalKind.SUCCESS)
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-dlq-e2e-fast")
          .name("Fast Failing Case")
          .version("1.0.0")
          .capabilities(capability)
          .workers(
              Worker.builder()
                  .name("fast-failing-worker")
                  .capabilities(capability)
                  .function(
                      new WorkerFunction.Sync(
                          input -> {
                            throw new RuntimeException("Fast failure");
                          }))
                  .executionPolicy(
                      new ExecutionPolicy(5000, new RetryPolicy(1, 50, BackoffStrategy.FIXED)))
                  .build())
          .bindings(
              Binding.builder()
                  .name("fast-trigger")
                  .capability(capability)
                  .on(new ContextChangeTrigger(".status == \"start\""))
                  .build())
          .goals(goal)
          .completion(GoalExpression.allOf(goal))
          .build();
    }
  }
}
