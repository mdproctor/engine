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
package io.casehub.engine.internal.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link QuiescenceTracker} counter logic — no CDI, no Quarkus.
 *
 * <p>Refs casehubio/engine#610.
 */
class QuiescenceTrackerTest {

  private QuiescenceTracker tracker;
  private UUID caseId;

  @BeforeEach
  void setUp() {
    tracker = new QuiescenceTracker();
    caseId = UUID.randomUUID();
  }

  @Test
  void resolvesImmediately_whenAlreadyDrained() {
    CompletableFuture<Void> future = tracker.register(caseId);
    tracker.onEvaluationDrained(caseId);

    assertThat(future).isCompleted();
  }

  @Test
  void doesNotResolve_withoutDrain() {
    CompletableFuture<Void> future = tracker.register(caseId);
    tracker.tryResolve(caseId);

    assertThat(future).isNotDone();
  }

  @Test
  void doesNotResolve_whileWorkersActive() {
    tracker.onWorkerDispatched(caseId);
    tracker.onEvaluationDrained(caseId);

    CompletableFuture<Void> future = tracker.register(caseId);
    tracker.tryResolve(caseId);

    assertThat(future).isNotDone();
  }

  @Test
  void resolvesAfterWorkerCompletes() {
    tracker.onWorkerDispatched(caseId);
    tracker.onEvaluationDrained(caseId);

    CompletableFuture<Void> future = tracker.register(caseId);
    tracker.onWorkerCompleted(caseId);

    assertThat(future).isCompleted();
  }

  @Test
  void doesNotResolve_whileContextChangePending() {
    tracker.onContextChangePublished(caseId);
    tracker.onEvaluationDrained(caseId);

    CompletableFuture<Void> future = tracker.register(caseId);
    tracker.tryResolve(caseId);

    assertThat(future).isNotDone();
  }

  @Test
  void resolvesAfterContextChangeConsumed() {
    tracker.onContextChangePublished(caseId);
    tracker.onEvaluationDrained(caseId);

    CompletableFuture<Void> future = tracker.register(caseId);
    tracker.onEvaluationStarting(caseId);
    tracker.onContextChangeConsumed(caseId);
    tracker.onEvaluationDrained(caseId);

    assertThat(future).isCompleted();
  }

  @Test
  void consumeClampedAtZero_seedCCsDoNotGoNegative() {
    CompletableFuture<Void> future = tracker.register(caseId);

    // Simulate seed CC consumed without a matching publish
    tracker.onEvaluationStarting(caseId);
    tracker.onContextChangeConsumed(caseId);
    tracker.onEvaluationStarting(caseId);
    tracker.onContextChangeConsumed(caseId);
    tracker.onEvaluationDrained(caseId);

    // Should resolve because pendingCC stayed at 0 (clamped), not -2
    assertThat(future).isCompleted();
  }

  @Test
  void consumeClampedAtZero_doesNotConsumeTrackedPublish() {
    CompletableFuture<Void> future = tracker.register(caseId);

    // Seed CC consumed (clamped)
    tracker.onEvaluationStarting(caseId);
    tracker.onContextChangeConsumed(caseId);

    // Tracked CC publish from worker completion
    tracker.onContextChangePublished(caseId);
    tracker.onEvaluationDrained(caseId);

    // pendingCC = 1 (publish +1, seed consume was clamped at 0)
    assertThat(future).isNotDone();

    // Consume the tracked CC
    tracker.onEvaluationStarting(caseId);
    tracker.onContextChangeConsumed(caseId);
    tracker.onEvaluationDrained(caseId);

    assertThat(future).isCompleted();
  }

  @Test
  void twoStepCascade_simulatedCounterSequence() {
    CompletableFuture<Void> future = tracker.register(caseId);

    // Seed CC consumed (clamped at 0)
    tracker.onEvaluationStarting(caseId);
    tracker.onContextChangeConsumed(caseId);

    // Step1 dispatched
    tracker.onWorkerDispatched(caseId);
    tracker.onEvaluationDrained(caseId);

    // Step1 completes — publishes CC, then completes
    tracker.onContextChangePublished(caseId);
    tracker.onWorkerCompleted(caseId);
    assertThat(future).isNotDone();

    // CC consumed, step2 dispatched
    tracker.onEvaluationStarting(caseId);
    tracker.onContextChangeConsumed(caseId);
    tracker.onWorkerDispatched(caseId);
    tracker.onEvaluationDrained(caseId);

    // Step2 completes — publishes CC, then completes
    tracker.onContextChangePublished(caseId);
    tracker.onWorkerCompleted(caseId);
    assertThat(future).isNotDone();

    // Final CC consumed, no more work
    tracker.onEvaluationStarting(caseId);
    tracker.onContextChangeConsumed(caseId);
    tracker.onEvaluationDrained(caseId);

    assertThat(future).isCompleted();
  }

  @Test
  void remove_cancelsFuture() {
    CompletableFuture<Void> future = tracker.register(caseId);
    tracker.remove(caseId);

    assertThat(future).isCancelled();
    assertThat(tracker.isTracking(caseId)).isFalse();
  }

  @Test
  void registerTwice_returnsSameFuture() {
    CompletableFuture<Void> f1 = tracker.register(caseId);
    CompletableFuture<Void> f2 = tracker.register(caseId);

    assertThat(f1).isSameAs(f2);
  }

  @Test
  void eventsBeforeRegister_statePreserved() {
    // Events arrive before anyone calls register()
    tracker.onWorkerDispatched(caseId);
    tracker.onContextChangePublished(caseId);

    // Now register
    CompletableFuture<Void> future = tracker.register(caseId);
    tracker.tryResolve(caseId);
    assertThat(future).isNotDone();

    // Complete the work
    tracker.onEvaluationStarting(caseId);
    tracker.onContextChangeConsumed(caseId);
    tracker.onWorkerCompleted(caseId);
    tracker.onEvaluationDrained(caseId);

    assertThat(future).isCompleted();
  }

  @Test
  void completedWorkerWithoutDispatch_noOpOnUnknownCase() {
    // onWorkerCompleted on unknown case is a no-op (uses get, not computeIfAbsent)
    tracker.onWorkerCompleted(caseId);
    assertThat(tracker.isTracking(caseId)).isFalse();
  }

  @Test
  void multipleWorkers_resolvesWhenAllComplete() {
    CompletableFuture<Void> future = tracker.register(caseId);

    tracker.onWorkerDispatched(caseId);
    tracker.onWorkerDispatched(caseId);
    tracker.onWorkerDispatched(caseId);
    tracker.onEvaluationDrained(caseId);

    tracker.onWorkerCompleted(caseId);
    assertThat(future).isNotDone();

    tracker.onWorkerCompleted(caseId);
    assertThat(future).isNotDone();

    tracker.onWorkerCompleted(caseId);
    assertThat(future).isCompleted();
  }

  @Test
  void skipCompensation_dispatchThenImmediateComplete() {
    CompletableFuture<Void> future = tracker.register(caseId);

    // Worker dispatched then immediately SKIP-compensated
    tracker.onWorkerDispatched(caseId);
    tracker.onWorkerCompleted(caseId);

    tracker.onEvaluationDrained(caseId);

    assertThat(future).isCompleted();
  }

  @Test
  void doesNotResolve_whileEvaluationInProgress() {
    tracker.onWorkerDispatched(caseId);
    tracker.onEvaluationDrained(caseId);

    CompletableFuture<Void> future = tracker.register(caseId);

    // Worker completes, publishes CC
    tracker.onContextChangePublished(caseId);
    tracker.onWorkerCompleted(caseId);
    assertThat(future).isNotDone();

    // CC received: evaluationStarting set BEFORE consume
    tracker.onEvaluationStarting(caseId);
    tracker.onContextChangeConsumed(caseId);

    // At this point pendingCC=0 and activeWorkers=0, but evaluationInProgress=true
    tracker.tryResolve(caseId);
    assertThat(future).as("must not resolve while evaluation in progress").isNotDone();

    // Evaluation dispatches step2, then drains
    tracker.onWorkerDispatched(caseId);
    tracker.onEvaluationDrained(caseId);
    assertThat(future).isNotDone();

    // step2 completes
    tracker.onContextChangePublished(caseId);
    tracker.onWorkerCompleted(caseId);

    // Final CC consumed, no more work
    tracker.onEvaluationStarting(caseId);
    tracker.onContextChangeConsumed(caseId);
    tracker.onEvaluationDrained(caseId);

    assertThat(future).isCompleted();
  }

  @Test
  void seedCCDrain_doesNotResolvePrematurelyWhenSignalCCPending() {
    // Simulates: startCase publishes seed CC, signal publishes signal CC,
    // awaitQuiescence registers, then seed CC evaluation drains before signal CC is processed.
    tracker.onContextChangePublished(caseId); // startCase CC
    tracker.onContextChangePublished(caseId); // signal CC

    CompletableFuture<Void> future = tracker.register(caseId);
    tracker.tryResolve(caseId);
    assertThat(future).isNotDone();

    // Seed CC evaluation: no workers match (empty context snapshot)
    tracker.onEvaluationStarting(caseId);
    tracker.onContextChangeConsumed(caseId);
    // evaluation finds no matching bindings → no onWorkerDispatched
    tracker.onEvaluationDrained(caseId);

    // Must NOT resolve — signal CC is still pending (pendingCC=1)
    assertThat(future).as("must not resolve while signal CC is pending").isNotDone();

    // Signal CC evaluation: dispatches slow worker
    tracker.onEvaluationStarting(caseId);
    tracker.onContextChangeConsumed(caseId);
    tracker.onWorkerDispatched(caseId);
    tracker.onEvaluationDrained(caseId);

    assertThat(future).as("must not resolve while worker active").isNotDone();

    // Worker completes, publishes CC
    tracker.onContextChangePublished(caseId);
    tracker.onWorkerCompleted(caseId);
    assertThat(future).isNotDone();

    // Final CC consumed, no more work
    tracker.onEvaluationStarting(caseId);
    tracker.onContextChangeConsumed(caseId);
    tracker.onEvaluationDrained(caseId);

    assertThat(future).isCompleted();
  }

  @Test
  void orphanState_cleanedUpWhenNoFutureRegistered() {
    // State created by onContextChangePublished (from startCase) but no awaitQuiescence called
    tracker.onContextChangePublished(caseId);

    // CC consumed and evaluation drains
    tracker.onEvaluationStarting(caseId);
    tracker.onContextChangeConsumed(caseId);
    tracker.onEvaluationDrained(caseId);

    // State should be cleaned up — no future registered
    assertThat(tracker.isTracking(caseId)).isFalse();
  }

  @Test
  void independentCases_doNotInterfere() {
    UUID case1 = UUID.randomUUID();
    UUID case2 = UUID.randomUUID();

    CompletableFuture<Void> f1 = tracker.register(case1);
    CompletableFuture<Void> f2 = tracker.register(case2);

    tracker.onWorkerDispatched(case1);
    tracker.onEvaluationDrained(case1);
    tracker.onEvaluationDrained(case2);

    // case2 has no workers — should resolve
    assertThat(f2).isCompleted();
    // case1 still has active worker
    assertThat(f1).isNotDone();

    tracker.onWorkerCompleted(case1);
    assertThat(f1).isCompleted();
  }
}
