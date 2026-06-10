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
package io.casehub.resilience.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.BackoffStrategy;
import io.casehub.api.model.Binding;
import io.casehub.api.model.Capability;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.ExecutionPolicy;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.RetryPolicy;
import io.casehub.api.model.Worker;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.resilience.deadletter.DeadLetterEntry;
import io.casehub.resilience.deadletter.DeadLetterQuery;
import io.casehub.resilience.deadletter.DeadLetterQueue;
import io.casehub.resilience.deadletter.DeadLetterStatus;
import io.casehub.resilience.poison.PoisonPillDetector;
import io.casehub.resilience.poison.PoisonPillWorkerExecutionGuard;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Cross-cutting integration tests that verify the DLQ and quarantine subsystems work correctly
 * together as a unified resilience layer.
 *
 * <p>Individual subsystem tests live in their own packages. These tests focus on the interaction
 * between {@link DeadLetterQueue}, {@link PoisonPillDetector}, and {@link
 * PoisonPillWorkerExecutionGuard} — ensuring the system remains consistent when both are active.
 */
@QuarkusTest
class ResilienceIntegrationTest {

  @Inject DeadLetterQueue deadLetterQueue;
  @Inject PoisonPillDetector detector;
  @Inject PoisonPillWorkerExecutionGuard guard;
  @Inject CaseInstanceCache caseInstanceCache;
  @Inject CombinedFailureCaseHub combinedFailureCase;

  private static final String WORKER_ID = "combined-failure-worker";

  @BeforeEach
  void reset() {
    deadLetterQueue.clear();
    caseInstanceCache.clear();
    detector.release(WORKER_ID);
  }

  // ---- DLQ + quarantine coexist after exhausted retries ---------------------

  /**
   * End-to-end: a worker exhausts its retries → case reaches FAULTED, DLQ receives an entry, and
   * the worker can then be quarantined by the operator (simulating recording the failure count).
   * Verifies both subsystems hold consistent state simultaneously.
   */
  @Test
  void exhaustedRetries_dlqPopulated_andQuarantineCanBeApplied() {
    AtomicReference<UUID> caseIdRef = new AtomicReference<>();
    AtomicReference<Throwable> err = new AtomicReference<>();

    combinedFailureCase
        .startCase(Map.of("trigger", "go"))
        .thenAccept(caseIdRef::set)
        .exceptionally(
            ex -> {
              err.set(ex);
              return null;
            });

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              if (err.get() != null) throw new AssertionError(err.get());
              assertThat(caseIdRef.get()).isNotNull();
            });

    UUID caseId = caseIdRef.get();

    // Wait for case to reach FAULTED (retries exhausted)
    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseId);
              assertThat(instance).isNotNull();
              assertThat(instance.getState()).isEqualTo(CaseStatus.FAULTED);
            });

    // DLQ must have an entry for this case
    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              List<DeadLetterEntry> entries = deadLetterQueue.query(DeadLetterQuery.all());
              DeadLetterEntry ours =
                  entries.stream()
                      .filter(e -> e.caseId().equals(caseId))
                      .findFirst()
                      .orElseThrow(() -> new AssertionError("No DLQ entry for caseId " + caseId));
              assertThat(ours.workerId()).isEqualTo(WORKER_ID);
              assertThat(ours.status()).isEqualTo(DeadLetterStatus.PENDING_REVIEW);
            });

    // Operator quarantines the worker after inspecting the DLQ
    detector.recordFailure(WORKER_ID);
    detector.recordFailure(WORKER_ID);
    detector.recordFailure(WORKER_ID); // threshold=3 in test config

    // Guard reflects the quarantine
    assertThat(guard.isBlocked(WORKER_ID, caseId)).isTrue();

    // DLQ entry is still present and queryable while worker is quarantined
    List<DeadLetterEntry> pending =
        deadLetterQueue.query(DeadLetterQuery.withStatus(DeadLetterStatus.PENDING_REVIEW));
    assertThat(pending).hasSize(1);
    assertThat(pending.get(0).caseId()).isEqualTo(caseId);
  }

  // ---- DLQ lifecycle while worker is quarantined ----------------------------

  /**
   * An entry in PENDING_REVIEW can be discarded by an operator. After discard, it is no longer
   * surfaced in PENDING_REVIEW queries. Quarantine state is independent of DLQ status.
   */
  @Test
  void dlqEntry_discardedByOperator_whileWorkerIsQuarantined() {
    // Quarantine the worker first (simulating prior failures)
    detector.recordFailure(WORKER_ID);
    detector.recordFailure(WORKER_ID);
    detector.recordFailure(WORKER_ID);
    assertThat(detector.isQuarantined(WORKER_ID)).isTrue();

    // Seed a DLQ entry directly (mirrors what DeadLetterEventHandler would produce)
    UUID caseId = UUID.randomUUID();
    DeadLetterEntry entry = deadLetterQueue.add(caseId, WORKER_ID, "hash-it-001", Map.of());

    // Worker is blocked
    assertThat(guard.isBlocked(WORKER_ID, caseId)).isTrue();

    // Operator discards the DLQ entry after deciding not to replay
    deadLetterQueue.discard(entry.deadLetterId());

    // DLQ reflects the discard
    assertThat(deadLetterQueue.query(DeadLetterQuery.withStatus(DeadLetterStatus.PENDING_REVIEW)))
        .isEmpty();
    assertThat(deadLetterQueue.query(DeadLetterQuery.withStatus(DeadLetterStatus.DISCARDED)))
        .hasSize(1);

    // Quarantine is independent of DLQ — worker remains blocked
    assertThat(guard.isBlocked(WORKER_ID, caseId)).isTrue();
  }

  // ---- Release quarantine, DLQ entry persists -------------------------------

  /**
   * Releasing the quarantine does not affect DLQ entries. A PENDING_REVIEW entry remains available
   * for replay even after the operator clears the worker for re-execution.
   */
  @Test
  void releaseQuarantine_dlqEntryRemainsAvailable() {
    detector.recordFailure(WORKER_ID);
    detector.recordFailure(WORKER_ID);
    detector.recordFailure(WORKER_ID);
    assertThat(detector.isQuarantined(WORKER_ID)).isTrue();

    UUID caseId = UUID.randomUUID();
    deadLetterQueue.add(caseId, WORKER_ID, "hash-it-002", Map.of("key", "val"));

    // Operator deploys a fix and releases the quarantine
    detector.release(WORKER_ID);
    assertThat(guard.isBlocked(WORKER_ID, caseId)).isFalse();

    // DLQ entry is still PENDING_REVIEW — it must be replayed or discarded explicitly
    List<DeadLetterEntry> pending =
        deadLetterQueue.query(DeadLetterQuery.withStatus(DeadLetterStatus.PENDING_REVIEW));
    assertThat(pending).hasSize(1);
    assertThat(pending.get(0).caseId()).isEqualTo(caseId);
    assertThat(pending.get(0).workerId()).isEqualTo(WORKER_ID);
  }

  // ---- CaseHub bean ---------------------------------------------------------

  @ApplicationScoped
  public static class CombinedFailureCaseHub extends CaseHub {

    private final Capability capability =
        Capability.builder()
            .name("combinedFail")
            .inputSchema("{ trigger: .working.trigger }")
            .outputSchema("{ trigger: .trigger }")
            .build();

    private final Goal goal =
        Goal.builder()
            .name("done")
            .condition(".working.trigger == \"done\"")
            .kind(GoalKind.SUCCESS)
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-resilience-it")
          .name("Combined Failure Case")
          .version("1.0.0")
          .capabilities(capability)
          .workers(
              Worker.builder()
                  .name(WORKER_ID)
                  .capabilities(capability)
                  .function(
                      input -> {
                        throw new RuntimeException("Resilience IT — intentional failure");
                      })
                  .executionPolicy(
                      new ExecutionPolicy(5000, new RetryPolicy(2, 50, BackoffStrategy.FIXED)))
                  .build())
          .bindings(
              Binding.builder()
                  .name("combined-trigger")
                  .capability(capability)
                  .on(new ContextChangeTrigger(".working.trigger == \"go\""))
                  .build())
          .goals(goal)
          .completion(GoalExpression.allOf(goal))
          .build();
    }
  }
}
